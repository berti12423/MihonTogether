//! The phone port: `0.0.0.0:47824`, so the Mihon Together app on the same Wi-Fi
//! can send pages to this PC (see "Phone uses the PC translator" in
//! docs/KOHARU_TRANSLATION_PLAN.md).
//!
//! Browsers aren't involved, so there is no CORS and any `Host` is accepted.
//! Pairing with the console code gives the phone a bearer token; `/translate`
//! needs it and shares the queue and engine with 127.0.0.1:47823.

use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr},
    sync::Arc,
};

use axum::{
    Router,
    body::Body,
    extract::{Query, Request, State as AxumState},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};

use crate::{
    api::{self, AppState, TranslateParams, error_response},
    pairing::{PairError, Pairing},
};

pub const PHONE_PORT: u16 = 47824;
/// `/pair` bodies are tiny JSON; anything bigger is refused.
pub const MAX_PAIR_BODY_BYTES: usize = 4 * 1024;
pub const SERVICE_TYPE: &str = "_mihontogether._tcp.local.";

#[derive(Clone)]
pub struct PhoneState {
    pub app: AppState,
    pub pairing: Arc<Pairing>,
}

pub fn router(state: PhoneState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/pair", post(pair).delete(unpair))
        .route(
            "/translate",
            post(translate)
                .route_layer(middleware::from_fn_with_state(state.clone(), require_token)),
        )
        .fallback(not_found)
        .layer(middleware::from_fn(no_browsers))
        .with_state(state)
}

/// The bearer token from `Authorization: Bearer <token>`, if any.
pub fn bearer(headers: &HeaderMap) -> Option<&str> {
    let value = headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    let (scheme, token) = value.trim().split_once(' ')?;
    let token = token.trim();
    (scheme.eq_ignore_ascii_case("bearer") && !token.is_empty()).then_some(token)
}

fn unpaired() -> Response {
    error_response(
        StatusCode::UNAUTHORIZED,
        "unpaired",
        "This phone isn't paired with the translator. Pair it again with the code shown on the PC.",
    )
}

/// Browsers always send `Origin` on cross-site requests; the phone app never does.
/// Refusing it keeps websites on the LAN from guessing pairing codes.
async fn no_browsers(request: Request, next: Next) -> Response {
    if request.headers().contains_key(header::ORIGIN) {
        return error_response(
            StatusCode::FORBIDDEN,
            "forbidden_origin",
            "The phone port is for the Mihon Together app, not for websites.",
        );
    }
    next.run(request).await
}

async fn require_token(
    AxumState(phone): AxumState<PhoneState>,
    request: Request,
    next: Next,
) -> Response {
    match bearer(request.headers()) {
        Some(token) if phone.pairing.authorize(token) => next.run(request).await,
        _ => {
            // Read the upload before answering: replying while the phone is still
            // sending makes the connection reset, and the phone would see "unreachable"
            // instead of "pair again".
            if api::check_declared_length(request.headers()).is_ok() {
                let _ = axum::body::to_bytes(request.into_body(), api::MAX_BODY_BYTES).await;
            }
            unpaired()
        }
    }
}

async fn health(AxumState(phone): AxumState<PhoneState>, headers: HeaderMap) -> Response {
    let paired = bearer(&headers).is_some_and(|token| phone.pairing.is_paired(token));
    api::health_response(&phone.app, Some(paired))
}

async fn translate(
    AxumState(phone): AxumState<PhoneState>,
    query: Query<TranslateParams>,
    headers: HeaderMap,
    body: Body,
) -> Response {
    // Same reason as in `require_token`: take the whole page before any early answer
    // such as 503 not_ready. Oversized bodies are refused by the shared handler.
    let body = match api::check_declared_length(&headers) {
        Ok(()) => match axum::body::to_bytes(body, api::MAX_BODY_BYTES).await {
            Ok(bytes) => Body::from(bytes),
            Err(_) => {
                return error_response(
                    StatusCode::PAYLOAD_TOO_LARGE,
                    "too_large",
                    "Pages can be at most 30 MB.",
                );
            }
        },
        Err(_) => body,
    };
    api::translate(AxumState(phone.app), query, headers, body).await
}

#[derive(Deserialize)]
struct PairRequest {
    code: String,
    device: String,
}

#[derive(Serialize)]
struct PairResponse<'a> {
    token: &'a str,
    name: &'a str,
}

fn json_response(status: StatusCode, body: &impl Serialize) -> Response {
    (
        status,
        [
            (
                header::CONTENT_TYPE,
                HeaderValue::from_static("application/json"),
            ),
            (header::CACHE_CONTROL, HeaderValue::from_static("no-store")),
        ],
        serde_json::to_vec(body).expect("response serializes"),
    )
        .into_response()
}

async fn pair(AxumState(phone): AxumState<PhoneState>, body: Body) -> Response {
    let bytes = match axum::body::to_bytes(body, MAX_PAIR_BODY_BYTES).await {
        Ok(bytes) => bytes,
        Err(_) => {
            return error_response(
                StatusCode::PAYLOAD_TOO_LARGE,
                "too_large",
                "The pairing request is too large.",
            );
        }
    };
    let Ok(request) = serde_json::from_slice::<PairRequest>(&bytes) else {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_request",
            "Send JSON like {\"code\": \"123456\", \"device\": \"My phone\"}.",
        );
    };
    match phone.pairing.pair(&request.code, &request.device) {
        Ok(paired) => json_response(
            StatusCode::OK,
            &PairResponse {
                token: &paired.token,
                name: phone.pairing.pc_name(),
            },
        ),
        Err(PairError::BadCode) => error_response(
            StatusCode::FORBIDDEN,
            "bad_code",
            "Wrong pairing code. Use the code shown in the translator window on the PC.",
        ),
        Err(PairError::Locked { retry_after }) => {
            let mut response = error_response(
                StatusCode::TOO_MANY_REQUESTS,
                "locked",
                "Too many wrong codes. Pairing is paused for a minute.",
            );
            let seconds = retry_after.as_secs_f64().ceil().max(1.0) as u64;
            response.headers_mut().insert(
                header::RETRY_AFTER,
                HeaderValue::from_str(&seconds.to_string()).expect("digits are a valid header"),
            );
            response
        }
        Err(PairError::BadDevice) => error_response(
            StatusCode::BAD_REQUEST,
            "bad_device",
            "The device name must be 1 to 60 characters.",
        ),
        Err(PairError::Storage(message)) => error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            "failed",
            &format!("Could not save the pairing: {message}"),
        ),
    }
}

async fn unpair(AxumState(phone): AxumState<PhoneState>, headers: HeaderMap) -> Response {
    let Some(token) = bearer(&headers) else {
        return unpaired();
    };
    match phone.pairing.unpair(token) {
        Ok(Some(_)) => StatusCode::NO_CONTENT.into_response(),
        Ok(None) => unpaired(),
        Err(error) => error_response(
            StatusCode::INTERNAL_SERVER_ERROR,
            "failed",
            &format!("Could not save the change: {error:?}"),
        ),
    }
}

async fn not_found() -> Response {
    error_response(StatusCode::NOT_FOUND, "not_found", "No such endpoint.")
}

/// The computer's name, for `/pair` and the mDNS `name` TXT record.
pub fn computer_name() -> String {
    ["COMPUTERNAME", "HOSTNAME"]
        .iter()
        .filter_map(|key| std::env::var(key).ok())
        .map(|name| name.trim().to_owned())
        .find(|name| !name.is_empty())
        .unwrap_or_else(|| "This PC".to_owned())
}

/// Adapter names that are virtual, VPN or tunnel adapters (lowercase substrings).
const VIRTUAL_ADAPTERS: [&str; 14] = [
    "virtual",
    "vethernet",
    "hyper-v",
    "vmware",
    "virtualbox",
    "wsl",
    "docker",
    "tailscale",
    "zerotier",
    "hamachi",
    "radmin",
    "wireguard",
    "vpn",
    "bluetooth",
];

/// Whether an interface address is one a phone on the home network could use.
pub fn is_lan_candidate(name: &str, ip: Ipv4Addr, up: bool, point_to_point: bool) -> bool {
    let octets = ip.octets();
    let lower = name.to_ascii_lowercase();
    up && !point_to_point
        && !ip.is_loopback()
        && !ip.is_link_local()
        && !ip.is_unspecified()
        && !ip.is_multicast()
        // 100.64.0.0/10 (carrier-grade NAT) is what Tailscale uses.
        && !(octets[0] == 100 && (64..128).contains(&octets[1]))
        && !VIRTUAL_ADAPTERS.iter().any(|word| lower.contains(word))
}

/// The LAN IPv4 addresses of up interfaces, with their adapter names.
pub fn lan_addresses() -> Vec<(String, Ipv4Addr)> {
    let Ok(interfaces) = if_addrs::get_if_addrs() else {
        return Vec::new();
    };
    let mut found: Vec<(String, Ipv4Addr)> = interfaces
        .into_iter()
        .filter_map(|interface| match interface.addr {
            if_addrs::IfAddr::V4(ref v4)
                if is_lan_candidate(
                    &interface.name,
                    v4.ip,
                    interface.is_oper_up(),
                    interface.is_p2p(),
                ) =>
            {
                Some((interface.name.clone(), v4.ip))
            }
            _ => None,
        })
        .collect();
    found.sort_by_key(|(_, ip)| *ip);
    found.dedup_by_key(|(_, ip)| *ip);
    found
}

/// A DNS-safe host label from the computer name.
pub fn host_label(name: &str) -> String {
    let label: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() {
                c.to_ascii_lowercase()
            } else {
                '-'
            }
        })
        .collect::<String>()
        .trim_matches('-')
        .chars()
        .take(63)
        .collect();
    if label.is_empty() {
        "mihontogether-translator".to_owned()
    } else {
        label
    }
}

/// Advertises `_mihontogether._tcp.local.` on the phone port. Keep the returned
/// daemon alive for as long as the service should be visible.
pub fn advertise(
    name: &str,
    addresses: &[Ipv4Addr],
) -> Result<mdns_sd::ServiceDaemon, mdns_sd::Error> {
    let daemon = mdns_sd::ServiceDaemon::new()?;
    let host = format!("{}.local.", host_label(name));
    let mut properties = HashMap::new();
    properties.insert("api".to_owned(), "1".to_owned());
    properties.insert("name".to_owned(), name.to_owned());
    let ips: Vec<IpAddr> = addresses.iter().copied().map(IpAddr::V4).collect();
    let info = if ips.is_empty() {
        mdns_sd::ServiceInfo::new(SERVICE_TYPE, name, &host, (), PHONE_PORT, properties)?
            .enable_addr_auto()
    } else {
        mdns_sd::ServiceInfo::new(SERVICE_TYPE, name, &host, &ips[..], PHONE_PORT, properties)?
    };
    daemon.register(info)?;
    Ok(daemon)
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use async_trait::async_trait;
    use axum::http::{Method, Request as HttpRequest};
    use tower::ServiceExt;

    use super::*;
    use crate::{
        engine::{EngineError, Page, PageTranslator},
        pairing::{Config, LOCKOUT, testing::FakeClock},
        queue::Queue,
        status::{Models, State, Status},
    };

    /// Fake pipeline: returns the page bytes unchanged.
    struct Echo;

    #[async_trait]
    impl PageTranslator for Echo {
        fn accepts_target(&self, target: &str) -> bool {
            target == "en-US"
        }
        async fn translate(&self, page: Page) -> Result<Vec<u8>, EngineError> {
            Ok(page.bytes.to_vec())
        }
    }

    const PNG: &[u8] = b"\x89PNG\r\n\x1a\n0123456789abcdefghij";

    struct Harness {
        router: Router,
        clock: Arc<FakeClock>,
        pairing: Arc<Pairing>,
        lines: Arc<Mutex<Vec<String>>>,
    }

    fn harness(state: State) -> Harness {
        let status = Arc::new(Status::new(Models::default()));
        status.set_state(state);
        let engine: Arc<dyn PageTranslator> = Arc::new(Echo);
        let queue = Queue::start(engine.clone(), Arc::new(|_| {}));
        let clock = FakeClock::new();
        let lines = Arc::new(Mutex::new(Vec::new()));
        let sink = lines.clone();
        let pairing = Arc::new(Pairing::new(
            "123456".to_owned(),
            "TEST-PC".to_owned(),
            Config::default(),
            None,
            clock.clone(),
            Arc::new(move |line| sink.lock().unwrap().push(line)),
        ));
        let router = router(PhoneState {
            app: AppState {
                status,
                queue,
                engine,
                version: "0.2.0",
                port: api::PORT,
                settings: Arc::new(crate::settings::SettingsStore::load(None, &[])),
                llms: Arc::new(Vec::new()),
                gpu: None,
            },
            pairing: pairing.clone(),
        });
        Harness {
            router,
            clock,
            pairing,
            lines,
        }
    }

    /// Phones address the PC by its LAN IP, so the Host is not loopback.
    fn request(method: Method, uri: &str) -> axum::http::request::Builder {
        HttpRequest::builder()
            .method(method)
            .uri(uri)
            .header(header::HOST, "10.100.102.2:47824")
    }

    async fn send(router: &Router, request: HttpRequest<Body>) -> Response {
        router.clone().oneshot(request).await.unwrap()
    }

    async fn body_json(response: Response) -> serde_json::Value {
        let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    fn pair_request(code: &str, device: &str) -> HttpRequest<Body> {
        request(Method::POST, "/pair")
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(
                serde_json::json!({ "code": code, "device": device }).to_string(),
            ))
            .unwrap()
    }

    async fn pair_ok(h: &Harness) -> String {
        let response = send(&h.router, pair_request("123456", "Galaxy A35")).await;
        assert_eq!(response.status(), StatusCode::OK);
        let json = body_json(response).await;
        assert_eq!(json["name"], "TEST-PC");
        json["token"].as_str().unwrap().to_owned()
    }

    fn translate_request(token: Option<&str>) -> HttpRequest<Body> {
        let mut builder = request(Method::POST, "/translate?target=en-US")
            .header(header::CONTENT_TYPE, "image/png");
        if let Some(token) = token {
            builder = builder.header(header::AUTHORIZATION, format!("Bearer {token}"));
        }
        builder.body(Body::from(PNG)).unwrap()
    }

    #[tokio::test]
    async fn pairing_returns_a_token_and_the_pc_name() {
        let h = harness(State::Ready);
        let token = pair_ok(&h).await;
        assert_eq!(token.len(), 43);
        assert!(h.pairing.is_paired(&token));
        assert_eq!(
            *h.lines.lock().unwrap(),
            vec!["Paired Galaxy A35".to_owned()]
        );
    }

    #[tokio::test]
    async fn wrong_code_is_403_and_five_lock_pairing_with_429() {
        let h = harness(State::Ready);
        for _ in 0..5 {
            let response = send(&h.router, pair_request("999999", "Phone")).await;
            assert_eq!(response.status(), StatusCode::FORBIDDEN);
            assert_eq!(body_json(response).await["error"], "bad_code");
        }
        let locked = send(&h.router, pair_request("123456", "Phone")).await;
        assert_eq!(locked.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(locked.headers()[header::RETRY_AFTER], "60");
        assert_eq!(body_json(locked).await["error"], "locked");

        h.clock.advance(LOCKOUT);
        let response = send(&h.router, pair_request("123456", "Phone")).await;
        assert_eq!(response.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn pair_validates_the_device_and_the_body() {
        let h = harness(State::Ready);
        for device in ["", "    ", &"x".repeat(61)] {
            let response = send(&h.router, pair_request("123456", device)).await;
            assert_eq!(response.status(), StatusCode::BAD_REQUEST);
            assert_eq!(body_json(response).await["error"], "bad_device");
        }
        let junk = send(
            &h.router,
            request(Method::POST, "/pair")
                .body(Body::from("code=123456"))
                .unwrap(),
        )
        .await;
        assert_eq!(junk.status(), StatusCode::BAD_REQUEST);
        assert_eq!(body_json(junk).await["error"], "bad_request");

        let big = format!(
            r#"{{"code":"123456","device":"Phone","pad":"{}"}}"#,
            "x".repeat(MAX_PAIR_BODY_BYTES)
        );
        let response = send(
            &h.router,
            request(Method::POST, "/pair")
                .body(Body::from(big))
                .unwrap(),
        )
        .await;
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_eq!(h.pairing.paired_count(), 0);
    }

    #[tokio::test]
    async fn translate_needs_a_paired_token() {
        let h = harness(State::Ready);
        let missing = send(&h.router, translate_request(None)).await;
        assert_eq!(missing.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(body_json(missing).await["error"], "unpaired");

        let wrong = send(&h.router, translate_request(Some("made-up-token"))).await;
        assert_eq!(wrong.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(body_json(wrong).await["error"], "unpaired");

        let basic = send(
            &h.router,
            request(Method::POST, "/translate")
                .header(header::AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .body(Body::from(PNG))
                .unwrap(),
        )
        .await;
        assert_eq!(basic.status(), StatusCode::UNAUTHORIZED);

        let token = pair_ok(&h).await;
        let ok = send(&h.router, translate_request(Some(&token))).await;
        assert_eq!(ok.status(), StatusCode::OK);
        assert_eq!(ok.headers()[header::CONTENT_TYPE], "image/png");
        assert!(ok.headers().contains_key("x-translate-ms"));
        // No CORS headers on the phone port.
        assert!(
            ok.headers()
                .get(header::ACCESS_CONTROL_ALLOW_ORIGIN)
                .is_none()
        );
        let bytes = axum::body::to_bytes(ok.into_body(), usize::MAX)
            .await
            .unwrap();
        assert_eq!(&bytes[..], PNG);
    }

    #[tokio::test]
    async fn translate_behaves_like_the_local_port_once_paired() {
        let h = harness(State::Loading);
        let token = pair_ok(&h).await;
        let response = send(&h.router, translate_request(Some(&token))).await;
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(body_json(response).await["error"], "not_ready");

        let h = harness(State::Ready);
        let token = pair_ok(&h).await;
        let response = send(
            &h.router,
            request(Method::POST, "/translate")
                .header(header::AUTHORIZATION, format!("bearer {token}"))
                .header(header::CONTENT_TYPE, "text/plain")
                .body(Body::from(PNG))
                .unwrap(),
        )
        .await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        assert_eq!(body_json(response).await["error"], "bad_image");

        let big = vec![0u8; api::MAX_BODY_BYTES + 1];
        let response = send(
            &h.router,
            request(Method::POST, "/translate")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .header(header::CONTENT_TYPE, "image/png")
                .body(Body::from(big))
                .unwrap(),
        )
        .await;
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    }

    #[tokio::test]
    async fn delete_pair_removes_the_phone() {
        let h = harness(State::Ready);
        let token = pair_ok(&h).await;
        let no_token = send(
            &h.router,
            request(Method::DELETE, "/pair")
                .body(Body::empty())
                .unwrap(),
        )
        .await;
        assert_eq!(no_token.status(), StatusCode::UNAUTHORIZED);

        let delete = || {
            request(Method::DELETE, "/pair")
                .header(header::AUTHORIZATION, format!("Bearer {token}"))
                .body(Body::empty())
                .unwrap()
        };
        let removed = send(&h.router, delete()).await;
        assert_eq!(removed.status(), StatusCode::NO_CONTENT);
        assert_eq!(
            *h.lines.lock().unwrap(),
            vec![
                "Paired Galaxy A35".to_owned(),
                "Removed Galaxy A35".to_owned()
            ]
        );
        let after = send(&h.router, translate_request(Some(&token))).await;
        assert_eq!(after.status(), StatusCode::UNAUTHORIZED);
        let again = send(&h.router, delete()).await;
        assert_eq!(again.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn health_reports_paired_with_and_without_a_token() {
        let h = harness(State::Ready);
        let anonymous = send(
            &h.router,
            request(Method::GET, "/health").body(Body::empty()).unwrap(),
        )
        .await;
        assert_eq!(anonymous.status(), StatusCode::OK);
        let json = body_json(anonymous).await;
        assert_eq!(json["name"], "mihon-together-translator");
        assert_eq!(json["api"], 1);
        assert_eq!(json["version"], "0.2.0");
        assert_eq!(json["state"], "ready");
        assert_eq!(json["paired"], false);

        let token = pair_ok(&h).await;
        let with = |value: String| {
            request(Method::GET, "/health")
                .header(header::AUTHORIZATION, value)
                .body(Body::empty())
                .unwrap()
        };
        let json = body_json(send(&h.router, with(format!("Bearer {token}"))).await).await;
        assert_eq!(json["paired"], true);
        let json = body_json(send(&h.router, with("Bearer nope".to_owned())).await).await;
        assert_eq!(json["paired"], false);
    }

    #[tokio::test]
    async fn any_host_is_accepted_but_browsers_are_refused() {
        let h = harness(State::Ready);
        for host in ["192.168.1.20:47824", "my-pc.local:47824", "10.0.2.2:47824"] {
            let response = send(
                &h.router,
                HttpRequest::builder()
                    .uri("/health")
                    .header(header::HOST, host)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await;
            assert_eq!(response.status(), StatusCode::OK, "host {host}");
        }
        let response = send(
            &h.router,
            request(Method::POST, "/pair")
                .header(header::ORIGIN, "https://evil.example")
                .body(Body::from(r#"{"code":"123456","device":"x"}"#))
                .unwrap(),
        )
        .await;
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
        assert_eq!(body_json(response).await["error"], "forbidden_origin");
        assert_eq!(h.pairing.paired_count(), 0);
    }

    #[test]
    fn bearer_parsing() {
        let mut headers = HeaderMap::new();
        assert_eq!(bearer(&headers), None);
        headers.insert(
            header::AUTHORIZATION,
            HeaderValue::from_static("Bearer abc"),
        );
        assert_eq!(bearer(&headers), Some("abc"));
        headers.insert(
            header::AUTHORIZATION,
            HeaderValue::from_static("bearer   abc "),
        );
        assert_eq!(bearer(&headers), Some("abc"));
        headers.insert(header::AUTHORIZATION, HeaderValue::from_static("Bearer "));
        assert_eq!(bearer(&headers), None);
        headers.insert(header::AUTHORIZATION, HeaderValue::from_static("Token abc"));
        assert_eq!(bearer(&headers), None);
    }

    #[test]
    fn lan_candidates_skip_loopback_virtual_and_tailscale() {
        let lan = Ipv4Addr::new(10, 100, 102, 2);
        assert!(is_lan_candidate("Ethernet", lan, true, false));
        assert!(is_lan_candidate(
            "Wi-Fi",
            Ipv4Addr::new(192, 168, 1, 5),
            true,
            false
        ));
        assert!(!is_lan_candidate("Ethernet", lan, false, false));
        assert!(!is_lan_candidate(
            "Loopback Pseudo-Interface 1",
            Ipv4Addr::LOCALHOST,
            true,
            false
        ));
        assert!(!is_lan_candidate(
            "Tailscale",
            Ipv4Addr::new(100, 101, 1, 2),
            true,
            false
        ));
        assert!(!is_lan_candidate(
            "Ethernet 3",
            Ipv4Addr::new(100, 64, 0, 1),
            true,
            false
        ));
        assert!(!is_lan_candidate(
            "Tailscale",
            Ipv4Addr::new(169, 254, 83, 107),
            true,
            false
        ));
        assert!(!is_lan_candidate(
            "vEthernet (WSL)",
            Ipv4Addr::new(172, 20, 0, 1),
            true,
            false
        ));
        assert!(!is_lan_candidate(
            "VirtualBox Host-Only Network",
            Ipv4Addr::new(192, 168, 56, 1),
            true,
            false
        ));
        assert!(!is_lan_candidate("Ethernet", lan, true, true));
    }

    #[test]
    fn host_labels_are_dns_safe() {
        assert_eq!(host_label("DESKTOP-Q55TN2I"), "desktop-q55tn2i");
        assert_eq!(host_label("Berti's PC"), "berti-s-pc");
        assert_eq!(host_label("***"), "mihontogether-translator");
    }
}
