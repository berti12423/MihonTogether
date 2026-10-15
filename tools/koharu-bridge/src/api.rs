//! Translator API v1 (see docs/KOHARU_TRANSLATION_PLAN.md in the repo).

use std::sync::Arc;

use axum::{
    Router,
    body::Body,
    extract::{DefaultBodyLimit, Query, Request, State as AxumState},
    http::{HeaderMap, HeaderValue, Method, StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use bytes::Bytes;
use serde::{Deserialize, Serialize};

use crate::{
    engine::{EngineError, Page, PageFormat, PageTranslator},
    queue::Queue,
    settings::{self, INPAINTING_MODELS, LlmOption, OCR_MODELS, Settings, SettingsStore},
    status::{self, State, Status},
};

pub const PORT: u16 = 47823;
pub const MAX_BODY_BYTES: usize = 30 * 1024 * 1024;
pub const DEFAULT_TARGET: &str = "en-US";

pub const ALLOWED_ORIGINS: [&str; 4] = [
    "https://mihontogether.web.app",
    "https://mihontogether.firebaseapp.com",
    "http://localhost:5050",
    "http://127.0.0.1:5050",
];

/// Exact, case-sensitive match (browsers serialize origins in lowercase).
pub fn origin_allowed(origin: &str) -> bool {
    ALLOWED_ORIGINS.contains(&origin)
}

/// DNS-rebinding guard: only loopback names may address the bridge.
pub fn host_allowed(host: &str, port: u16) -> bool {
    let Some((name, host_port)) = host.rsplit_once(':') else {
        return false;
    };
    host_port == port.to_string()
        && (name == "127.0.0.1" || name.eq_ignore_ascii_case("localhost") || name == "[::1]")
}

#[derive(Clone)]
pub struct AppState {
    pub status: Arc<Status>,
    pub queue: Queue,
    pub engine: Arc<dyn PageTranslator>,
    pub version: &'static str,
    pub port: u16,
    pub settings: Arc<SettingsStore>,
    /// Local translation models this build can use (the website's model list).
    pub llms: Arc<Vec<LlmOption>>,
    /// The NVIDIA GPU, if one was found, so the website can say which models fit.
    pub gpu: Option<crate::gpu::Gpu>,
}

/// Largest `PUT /settings` body.
pub const MAX_SETTINGS_BYTES: usize = 16 * 1024;

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/translate", post(translate))
        .route(
            "/settings",
            get(get_settings)
                .put(put_settings)
                .layer(DefaultBodyLimit::max(MAX_SETTINGS_BYTES)),
        )
        .fallback(not_found)
        .layer(middleware::from_fn_with_state(state.clone(), guard))
        .with_state(state)
}

#[derive(Serialize)]
struct ErrorBody<'a> {
    error: &'a str,
    message: &'a str,
}

pub fn error_response(status: StatusCode, code: &str, message: &str) -> Response {
    let body = serde_json::to_vec(&ErrorBody {
        error: code,
        message,
    })
    .expect("error body serializes");
    (
        status,
        [(header::CONTENT_TYPE, HeaderValue::from_static("application/json"))],
        body,
    )
        .into_response()
}

/// Host and Origin checks, CORS headers and the preflight answer, for every route.
async fn guard(AxumState(app): AxumState<AppState>, request: Request, next: Next) -> Response {
    let headers = request.headers();
    let host_ok = headers
        .get(header::HOST)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|host| host_allowed(host, app.port));
    if !host_ok {
        return error_response(
            StatusCode::FORBIDDEN,
            "forbidden_host",
            "The translator only answers requests addressed to 127.0.0.1 or localhost.",
        );
    }
    let origin = match headers.get(header::ORIGIN) {
        None => None,
        Some(value) => match value.to_str() {
            Ok(origin) if origin_allowed(origin) => Some(value.clone()),
            _ => {
                return error_response(
                    StatusCode::FORBIDDEN,
                    "forbidden_origin",
                    "This website is not allowed to use the translator.",
                );
            }
        },
    };

    let mut response = if request.method() == Method::OPTIONS {
        preflight_response()
    } else {
        next.run(request).await
    };
    apply_cors(response.headers_mut(), origin);
    response
}

fn preflight_response() -> Response {
    let mut response = StatusCode::NO_CONTENT.into_response();
    let headers = response.headers_mut();
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_METHODS,
        HeaderValue::from_static("GET, POST, PUT"),
    );
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_HEADERS,
        HeaderValue::from_static("Content-Type"),
    );
    headers.insert(
        "access-control-allow-private-network",
        HeaderValue::from_static("true"),
    );
    headers.insert(
        header::ACCESS_CONTROL_MAX_AGE,
        HeaderValue::from_static("600"),
    );
    response
}

/// Adds the per-origin CORS headers. `origin` is already checked against the allow-list.
pub fn apply_cors(headers: &mut HeaderMap, origin: Option<HeaderValue>) {
    headers.insert(header::VARY, HeaderValue::from_static("Origin"));
    if let Some(origin) = origin {
        headers.insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        // Lets the page read X-Translate-Ms from a cross-origin response.
        headers.insert(
            header::ACCESS_CONTROL_EXPOSE_HEADERS,
            HeaderValue::from_static("X-Translate-Ms"),
        );
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Health<'a> {
    name: &'static str,
    api: u32,
    version: &'static str,
    state: State,
    progress: Option<status::Progress>,
    error: Option<String>,
    device: String,
    models: &'a status::Models,
    queue: usize,
    last_page_ms: Option<u64>,
    /// Only on the phone port: whether a valid pairing token was sent.
    #[serde(skip_serializing_if = "Option::is_none")]
    paired: Option<bool>,
}

#[derive(Serialize)]
struct Choice {
    id: &'static str,
    name: &'static str,
}

#[derive(Serialize)]
struct SettingsOptions<'a> {
    ocr: Vec<Choice>,
    inpainting: Vec<Choice>,
    llm: &'a [LlmOption],
    #[serde(rename = "maxInstructions")]
    max_instructions: usize,
    #[serde(rename = "maxFonts")]
    max_fonts: usize,
    gpu: Option<&'a crate::gpu::Gpu>,
}

#[derive(Serialize)]
struct SettingsBody<'a> {
    settings: &'a Settings,
    defaults: Settings,
    options: SettingsOptions<'a>,
}

fn settings_response(app: &AppState, current: &Settings) -> Response {
    let choices = |list: &[(&'static str, &'static str)]| {
        list.iter().map(|(id, name)| Choice { id, name }).collect::<Vec<_>>()
    };
    let body = SettingsBody {
        settings: current,
        defaults: Settings::default(),
        options: SettingsOptions {
            ocr: choices(&OCR_MODELS),
            inpainting: choices(&INPAINTING_MODELS),
            llm: &app.llms,
            max_instructions: settings::MAX_INSTRUCTIONS,
            max_fonts: settings::MAX_FONTS,
            gpu: app.gpu.as_ref(),
        },
    };
    (
        [
            (header::CONTENT_TYPE, HeaderValue::from_static("application/json")),
            (header::CACHE_CONTROL, HeaderValue::from_static("no-store")),
        ],
        serde_json::to_vec(&body).expect("settings serialize"),
    )
        .into_response()
}

/// `GET /settings`: the current choices, the defaults and what can be chosen.
async fn get_settings(AxumState(app): AxumState<AppState>) -> Response {
    settings_response(&app, &app.settings.current().0)
}

/// `PUT /settings`: replaces the settings; they apply from the next page.
async fn put_settings(AxumState(app): AxumState<AppState>, headers: HeaderMap, body: Bytes) -> Response {
    let json = headers
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.split(';').next().unwrap_or_default().trim().eq_ignore_ascii_case("application/json"));
    if !json {
        return error_response(StatusCode::BAD_REQUEST, "bad_settings", "Send the settings as JSON.");
    }
    let settings: Settings = match serde_json::from_slice(&body) {
        Ok(settings) => settings,
        Err(error) => {
            return error_response(StatusCode::BAD_REQUEST, "bad_settings", &format!("Invalid settings: {error}"));
        }
    };
    if let Err(message) = settings.validate(&app.llms) {
        return error_response(StatusCode::BAD_REQUEST, "bad_settings", &message);
    }
    if let Err(message) = app.settings.update(settings.clone()) {
        return error_response(StatusCode::INTERNAL_SERVER_ERROR, "failed", &message);
    }
    app.status.set_models(crate::koharu::models(&settings));
    println!(
        "Settings saved from the website: OCR {}, inpainting {}, translation {}{}.",
        settings.ocr,
        settings.inpainting,
        settings.llm,
        if settings.instructions.trim().is_empty() { "" } else { ", with instructions" }
    );
    settings_response(&app, &settings)
}

async fn health(AxumState(app): AxumState<AppState>) -> Response {
    health_response(&app, None)
}

/// The `/health` JSON; `paired` is added on the phone port only.
pub fn health_response(app: &AppState, paired: Option<bool>) -> Response {
    let snapshot = app.status.snapshot();
    let body = Health {
        name: "mihon-together-translator",
        api: 1,
        version: app.version,
        state: snapshot.state,
        progress: snapshot.progress,
        error: snapshot.error,
        device: snapshot.device,
        models: &snapshot.models,
        queue: app.queue.open(),
        last_page_ms: snapshot.last_page_ms,
        paired,
    };
    (
        [
            (header::CONTENT_TYPE, HeaderValue::from_static("application/json")),
            (header::CACHE_CONTROL, HeaderValue::from_static("no-store")),
        ],
        serde_json::to_vec(&body).expect("health serializes"),
    )
        .into_response()
}

#[derive(Deserialize)]
pub struct TranslateParams {
    pub target: Option<String>,
    /// Accepted for forward compatibility; Koharu detects the source language itself.
    #[allow(dead_code)]
    pub source: Option<String>,
}

/// A request body rejection, before anything reaches the queue.
#[derive(Debug, Eq, PartialEq)]
pub enum Rejection {
    TooLarge,
    BadImage(&'static str),
}

/// Rejects a body by its declared length before reading it.
pub fn check_declared_length(headers: &HeaderMap) -> Result<(), Rejection> {
    let declared = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok());
    match declared {
        Some(length) if length > MAX_BODY_BYTES as u64 => Err(Rejection::TooLarge),
        _ => Ok(()),
    }
}

/// Validates the Content-Type and the image bytes. The bytes decide the format;
/// a declared image type must match what the bytes are.
pub fn validate_image(content_type: Option<&str>, bytes: &[u8]) -> Result<PageFormat, Rejection> {
    if bytes.is_empty() {
        return Err(Rejection::BadImage("The request body is empty."));
    }
    if bytes.len() > MAX_BODY_BYTES {
        return Err(Rejection::TooLarge);
    }
    let declared = content_type
        .map(|value| {
            value
                .split(';')
                .next()
                .unwrap_or_default()
                .trim()
                .to_ascii_lowercase()
        })
        .filter(|value| !value.is_empty());
    let declared_format = match declared.as_deref() {
        Some("image/jpeg" | "image/jpg" | "image/pjpeg") => Some(PageFormat::Jpeg),
        Some("image/png") => Some(PageFormat::Png),
        Some("image/webp") => Some(PageFormat::Webp),
        Some("image/gif") => Some(PageFormat::Gif),
        Some("image/avif") => Some(PageFormat::Avif),
        // Untyped uploads are sniffed.
        None | Some("application/octet-stream") => None,
        Some(_) => {
            return Err(Rejection::BadImage(
                "Content-Type must be image/jpeg, image/png, image/webp, image/gif or image/avif.",
            ));
        }
    };
    let sniffed = sniff(bytes).ok_or(Rejection::BadImage(
        "The body is not a JPEG, PNG, WebP, GIF or AVIF image.",
    ))?;
    if let Some(declared) = declared_format {
        if declared != sniffed {
            return Err(Rejection::BadImage(
                "The Content-Type does not match the image bytes.",
            ));
        }
    }
    Ok(sniffed)
}

fn sniff(bytes: &[u8]) -> Option<PageFormat> {
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some(PageFormat::Jpeg)
    } else if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some(PageFormat::Png)
    } else if bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Some(PageFormat::Webp)
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        Some(PageFormat::Gif)
    } else if bytes.len() >= 12
        && &bytes[4..8] == b"ftyp"
        && matches!(&bytes[8..12], b"avif" | b"avis")
    {
        Some(PageFormat::Avif)
    } else {
        None
    }
}

fn rejection_response(rejection: Rejection) -> Response {
    match rejection {
        Rejection::TooLarge => error_response(
            StatusCode::PAYLOAD_TOO_LARGE,
            "too_large",
            "Pages can be at most 30 MB.",
        ),
        Rejection::BadImage(message) => {
            error_response(StatusCode::BAD_REQUEST, "bad_image", message)
        }
    }
}

/// `POST /translate`, shared by both ports (the phone port checks the token first).
pub async fn translate(
    AxumState(app): AxumState<AppState>,
    Query(params): Query<TranslateParams>,
    headers: HeaderMap,
    body: Body,
) -> Response {
    let target = params
        .target
        .filter(|target| !target.trim().is_empty())
        .unwrap_or_else(|| DEFAULT_TARGET.to_owned());
    if !app.engine.accepts_target(&target) {
        return error_response(
            StatusCode::BAD_REQUEST,
            "bad_target",
            "Unknown target language. Use a language tag such as en-US.",
        );
    }
    if app.status.state() != State::Ready {
        return error_response(
            StatusCode::SERVICE_UNAVAILABLE,
            "not_ready",
            "The translator is still starting, downloading or loading models.",
        );
    }
    if let Err(rejection) = check_declared_length(&headers) {
        return rejection_response(rejection);
    }
    let bytes = match axum::body::to_bytes(body, MAX_BODY_BYTES).await {
        Ok(bytes) => bytes,
        // Over the limit, or the client went away mid-upload (it won't see this).
        Err(_) => return rejection_response(Rejection::TooLarge),
    };
    let content_type = headers
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok());
    let format = match validate_image(content_type, &bytes) {
        Ok(format) => format,
        Err(rejection) => return rejection_response(rejection),
    };

    match app
        .queue
        .submit(Page {
            bytes,
            format,
            target,
        })
        .await
    {
        Ok(done) => {
            let ms = done.elapsed.as_millis().to_string();
            (
                [
                    (header::CONTENT_TYPE, HeaderValue::from_static("image/png")),
                    (header::CACHE_CONTROL, HeaderValue::from_static("no-store")),
                    (
                        header::HeaderName::from_static("x-translate-ms"),
                        HeaderValue::from_str(&ms).expect("digits are a valid header"),
                    ),
                ],
                done.png,
            )
                .into_response()
        }
        Err(EngineError::BadImage(message)) => {
            error_response(StatusCode::BAD_REQUEST, "bad_image", &message)
        }
        Err(EngineError::Failed(message)) => {
            error_response(StatusCode::INTERNAL_SERVER_ERROR, "failed", &message)
        }
    }
}

async fn not_found() -> Response {
    error_response(StatusCode::NOT_FOUND, "not_found", "No such endpoint.")
}

#[cfg(test)]
mod tests {
    use async_trait::async_trait;
    use axum::http::Request as HttpRequest;
    use tower::ServiceExt;

    use super::*;
    use crate::status::Models;

    struct Echo;

    #[async_trait]
    impl PageTranslator for Echo {
        fn accepts_target(&self, target: &str) -> bool {
            target == "en-US" || target == "de-DE"
        }
        async fn translate(&self, page: Page) -> Result<Vec<u8>, EngineError> {
            if page.bytes.len() < 20 {
                return Err(EngineError::BadImage("truncated".to_owned()));
            }
            Ok(page.bytes.to_vec())
        }
    }

    fn app(state: State) -> Router {
        let status = Arc::new(Status::new(Models::default()));
        status.set_state(state);
        let engine: Arc<dyn PageTranslator> = Arc::new(Echo);
        let queue = Queue::start(engine.clone(), Arc::new(|_| {}));
        router(AppState {
            status,
            queue,
            engine,
            version: "0.1.0",
            port: PORT,
            settings: Arc::new(SettingsStore::load(None, &test_llms())),
            llms: Arc::new(test_llms()),
            gpu: Some(crate::gpu::Gpu { name: "Test GPU".to_owned(), vram_mb: 8192 }),
        })
    }

    pub(crate) fn test_llms() -> Vec<LlmOption> {
        ["gemma4-12b-it", "qwen3.5-9b"]
            .map(|id| LlmOption {
                id: id.to_owned(),
                name: id.to_owned(),
                vision: true,
                reasoning: true,
                size_mb: Some(5000),
                projector_mb: Some(500),
            })
            .to_vec()
    }

    fn put_settings_request(json: &str) -> axum::http::Request<Body> {
        request(Method::PUT, "/settings")
            .header(header::ORIGIN, "https://mihontogether.web.app")
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(json.to_owned()))
            .unwrap()
    }

    #[tokio::test]
    async fn settings_round_trip_and_update_health_models() {
        let app = app(State::Ready);
        let response = app
            .clone()
            .oneshot(request(Method::GET, "/settings").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_json(response).await;
        assert_eq!(body["settings"]["llm"], "gemma4-12b-it");
        assert_eq!(body["options"]["llm"].as_array().unwrap().len(), 2);
        assert_eq!(body["options"]["ocr"][1]["id"], "manga-ocr");
        assert_eq!(body["options"]["gpu"]["vramMb"], 8192);
        assert_eq!(body["options"]["llm"][0]["sizeMb"], 5000);

        let response = app
            .clone()
            .oneshot(put_settings_request(r#"{"llm":"qwen3.5-9b","ocr":"manga-ocr","instructions":"Keep honorifics."}"#))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()[header::ACCESS_CONTROL_ALLOW_ORIGIN], "https://mihontogether.web.app");
        let body = body_json(response).await;
        assert_eq!(body["settings"]["llm"], "qwen3.5-9b");
        assert_eq!(body["settings"]["inpainting"], "lama");

        let response = app
            .oneshot(request(Method::GET, "/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        let health = body_json(response).await;
        assert_eq!(health["models"]["llm"], "qwen3.5-9b");
        assert_eq!(health["models"]["ocr"], "manga-ocr");
        assert_eq!(health["models"]["settings"].as_str().unwrap().len(), 8);
    }

    #[tokio::test]
    async fn bad_settings_are_refused_and_change_nothing() {
        let app = app(State::Ready);
        for json in [
            r#"{"llm":"not-a-model"}"#,
            r#"{"temperature":9}"#,
            r#"{"unknownField":true}"#,
            "not json",
        ] {
            let response = app.clone().oneshot(put_settings_request(json)).await.unwrap();
            assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{json}");
            let body = body_json(response).await;
            assert_eq!(body["error"], "bad_settings");
        }
        let response = app
            .clone()
            .oneshot(
                request(Method::PUT, "/settings")
                    .header(header::CONTENT_TYPE, "text/plain")
                    .body(Body::from("{}"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        let response = app
            .oneshot(request(Method::GET, "/settings").body(Body::empty()).unwrap())
            .await
            .unwrap();
        let body = body_json(response).await;
        assert_eq!(body["settings"]["llm"], "gemma4-12b-it");
    }

    #[tokio::test]
    async fn other_websites_cannot_change_settings() {
        let response = app(State::Ready)
            .oneshot(
                request(Method::PUT, "/settings")
                    .header(header::ORIGIN, "https://evil.example")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(r#"{"llm":"qwen3.5-9b"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
    }

    #[tokio::test]
    async fn the_preflight_allows_put() {
        let response = app(State::Ready)
            .oneshot(
                request(Method::OPTIONS, "/settings")
                    .header(header::ORIGIN, "https://mihontogether.web.app")
                    .header("access-control-request-method", "PUT")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NO_CONTENT);
        assert!(response.headers()[header::ACCESS_CONTROL_ALLOW_METHODS].to_str().unwrap().contains("PUT"));
    }

    const PNG: &[u8] = b"\x89PNG\r\n\x1a\n0123456789abcdefghij";

    fn request(method: Method, uri: &str) -> axum::http::request::Builder {
        HttpRequest::builder()
            .method(method)
            .uri(uri)
            .header(header::HOST, "127.0.0.1:47823")
    }

    async fn body_json(response: Response) -> serde_json::Value {
        let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    #[test]
    fn origin_allow_list_is_exact() {
        assert!(origin_allowed("https://mihontogether.web.app"));
        assert!(origin_allowed("https://mihontogether.firebaseapp.com"));
        assert!(origin_allowed("http://localhost:5050"));
        assert!(origin_allowed("http://127.0.0.1:5050"));
        assert!(!origin_allowed("https://evil.example"));
        assert!(!origin_allowed("http://mihontogether.web.app"));
        assert!(!origin_allowed("https://mihontogether.web.app.evil.example"));
        assert!(!origin_allowed("https://mihontogether.web.app/"));
        assert!(!origin_allowed("http://localhost:5051"));
        assert!(!origin_allowed("null"));
        assert!(!origin_allowed(""));
    }

    #[test]
    fn host_must_be_loopback_on_our_port() {
        assert!(host_allowed("127.0.0.1:47823", PORT));
        assert!(host_allowed("localhost:47823", PORT));
        assert!(host_allowed("[::1]:47823", PORT));
        assert!(!host_allowed("127.0.0.1", PORT));
        assert!(!host_allowed("evil.example:47823", PORT));
        assert!(!host_allowed("127.0.0.1:80", PORT));
    }

    #[tokio::test]
    async fn health_without_origin_has_no_cors_origin() {
        let response = app(State::Loading)
            .oneshot(request(Method::GET, "/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert!(
            response
                .headers()
                .get(header::ACCESS_CONTROL_ALLOW_ORIGIN)
                .is_none()
        );
        let json = body_json(response).await;
        assert_eq!(json["name"], "mihon-together-translator");
        assert_eq!(json["api"], 1);
        assert_eq!(json["state"], "loading");
        assert_eq!(json["queue"], 0);
        assert!(json["progress"].is_null());
        assert!(json["lastPageMs"].is_null());
        assert!(json["models"]["llm"].is_string());
        // `paired` belongs to the phone port only.
        assert!(json.get("paired").is_none());
    }

    #[tokio::test]
    async fn allowed_origin_gets_cors_headers() {
        let response = app(State::Ready)
            .oneshot(
                request(Method::GET, "/health")
                    .header(header::ORIGIN, "https://mihontogether.web.app")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let headers = response.headers();
        assert_eq!(
            headers[header::ACCESS_CONTROL_ALLOW_ORIGIN],
            "https://mihontogether.web.app"
        );
        assert_eq!(headers[header::VARY], "Origin");
    }

    #[tokio::test]
    async fn other_origins_get_403() {
        for method in [Method::GET, Method::POST, Method::OPTIONS] {
            let response = app(State::Ready)
                .oneshot(
                    request(method, "/translate")
                        .header(header::ORIGIN, "https://evil.example")
                        .body(Body::from(PNG))
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::FORBIDDEN);
            assert!(
                response
                    .headers()
                    .get(header::ACCESS_CONTROL_ALLOW_ORIGIN)
                    .is_none()
            );
            assert_eq!(body_json(response).await["error"], "forbidden_origin");
        }
    }

    #[tokio::test]
    async fn foreign_host_header_gets_403() {
        let response = app(State::Ready)
            .oneshot(
                HttpRequest::builder()
                    .uri("/health")
                    .header(header::HOST, "rebound.evil.example:47823")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
        assert_eq!(body_json(response).await["error"], "forbidden_host");
    }

    #[tokio::test]
    async fn preflight_answers_private_network_access() {
        let response = app(State::Starting)
            .oneshot(
                request(Method::OPTIONS, "/translate?target=en-US")
                    .header(header::ORIGIN, "https://mihontogether.web.app")
                    .header(header::ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header("access-control-request-private-network", "true")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NO_CONTENT);
        let headers = response.headers();
        assert_eq!(
            headers[header::ACCESS_CONTROL_ALLOW_ORIGIN],
            "https://mihontogether.web.app"
        );
        assert_eq!(headers[header::ACCESS_CONTROL_ALLOW_METHODS], "GET, POST, PUT");
        assert_eq!(headers[header::ACCESS_CONTROL_ALLOW_HEADERS], "Content-Type");
        assert_eq!(headers["access-control-allow-private-network"], "true");
        assert_eq!(headers[header::ACCESS_CONTROL_MAX_AGE], "600");
        assert_eq!(headers[header::VARY], "Origin");
    }

    #[tokio::test]
    async fn translate_returns_png_with_timing() {
        let response = app(State::Ready)
            .oneshot(
                request(Method::POST, "/translate?target=en-US")
                    .header(header::ORIGIN, "http://localhost:5050")
                    .header(header::CONTENT_TYPE, "image/png")
                    .body(Body::from(PNG))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let headers = response.headers();
        assert_eq!(headers[header::CONTENT_TYPE], "image/png");
        assert!(headers["x-translate-ms"].to_str().unwrap().parse::<u64>().is_ok());
        assert_eq!(headers[header::ACCESS_CONTROL_EXPOSE_HEADERS], "X-Translate-Ms");
        let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap();
        assert_eq!(&bytes[..], PNG);
    }

    #[tokio::test]
    async fn translate_defaults_target_and_rejects_unknown_targets() {
        let ok = app(State::Ready)
            .oneshot(
                request(Method::POST, "/translate")
                    .header(header::CONTENT_TYPE, "image/png")
                    .body(Body::from(PNG))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(ok.status(), StatusCode::OK);
        let bad = app(State::Ready)
            .oneshot(
                request(Method::POST, "/translate?target=xx-NOPE")
                    .header(header::CONTENT_TYPE, "image/png")
                    .body(Body::from(PNG))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(bad.status(), StatusCode::BAD_REQUEST);
        assert_eq!(body_json(bad).await["error"], "bad_target");
    }

    #[tokio::test]
    async fn translate_is_503_until_ready() {
        for state in [State::Starting, State::Downloading, State::Loading, State::Error] {
            let response = app(state)
                .oneshot(
                    request(Method::POST, "/translate?target=en-US")
                        .header(header::CONTENT_TYPE, "image/png")
                        .body(Body::from(PNG))
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
            assert_eq!(body_json(response).await["error"], "not_ready");
        }
    }

    #[tokio::test]
    async fn translate_rejects_oversized_bodies_with_413() {
        let big = vec![0u8; MAX_BODY_BYTES + 1];
        let response = app(State::Ready)
            .oneshot(
                request(Method::POST, "/translate?target=en-US")
                    .header(header::CONTENT_TYPE, "image/png")
                    .body(Body::from(big))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_eq!(body_json(response).await["error"], "too_large");

        let declared = app(State::Ready)
            .oneshot(
                request(Method::POST, "/translate?target=en-US")
                    .header(header::CONTENT_TYPE, "image/png")
                    .header(header::CONTENT_LENGTH, (MAX_BODY_BYTES + 1).to_string())
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(declared.status(), StatusCode::PAYLOAD_TOO_LARGE);
    }

    #[tokio::test]
    async fn translate_rejects_non_images_with_400() {
        let response = app(State::Ready)
            .oneshot(
                request(Method::POST, "/translate?target=en-US")
                    .header(header::CONTENT_TYPE, "text/plain")
                    .body(Body::from(PNG))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        assert_eq!(body_json(response).await["error"], "bad_image");

        // A decoder failure inside the engine is a bad image too.
        let truncated = app(State::Ready)
            .oneshot(
                request(Method::POST, "/translate?target=en-US")
                    .header(header::CONTENT_TYPE, "image/png")
                    .body(Body::from(&b"\x89PNG\r\n\x1a\nxx"[..]))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(truncated.status(), StatusCode::BAD_REQUEST);
        assert_eq!(body_json(truncated).await["error"], "bad_image");
    }

    #[tokio::test]
    async fn unknown_routes_are_json_404() {
        let response = app(State::Ready)
            .oneshot(request(Method::GET, "/nope").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(body_json(response).await["error"], "not_found");
    }

    #[tokio::test]
    async fn pairing_is_not_offered_on_the_local_port() {
        let response = app(State::Ready)
            .oneshot(
                request(Method::POST, "/pair")
                    .body(Body::from(r#"{"code":"123456","device":"x"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[test]
    fn validation_sniffs_formats_and_checks_declared_types() {
        let jpeg = [0xFF, 0xD8, 0xFF, 0xE0, 0, 0];
        let webp = b"RIFF\0\0\0\0WEBPVP8 ";
        let gif = b"GIF89a....";
        let avif = b"\0\0\0\x1cftypavif\0\0\0\0";
        assert_eq!(validate_image(Some("image/jpeg"), &jpeg), Ok(PageFormat::Jpeg));
        assert_eq!(validate_image(Some("image/png"), PNG), Ok(PageFormat::Png));
        assert_eq!(
            validate_image(Some("image/webp"), webp),
            Ok(PageFormat::Webp)
        );
        assert_eq!(validate_image(Some("image/gif"), gif), Ok(PageFormat::Gif));
        assert_eq!(
            validate_image(Some("image/avif"), avif),
            Ok(PageFormat::Avif)
        );
        // Parameters and case are ignored; untyped uploads are sniffed.
        assert_eq!(
            validate_image(Some("Image/PNG; charset=binary"), PNG),
            Ok(PageFormat::Png)
        );
        assert_eq!(validate_image(None, &jpeg), Ok(PageFormat::Jpeg));
        assert_eq!(
            validate_image(Some("application/octet-stream"), PNG),
            Ok(PageFormat::Png)
        );
        // Mismatches, unsupported types, junk and empty bodies are bad images.
        assert!(matches!(
            validate_image(Some("image/jpeg"), PNG),
            Err(Rejection::BadImage(_))
        ));
        assert!(matches!(
            validate_image(Some("image/svg+xml"), PNG),
            Err(Rejection::BadImage(_))
        ));
        assert!(matches!(
            validate_image(Some("application/x-www-form-urlencoded"), PNG),
            Err(Rejection::BadImage(_))
        ));
        assert!(matches!(
            validate_image(None, b"hello world"),
            Err(Rejection::BadImage(_))
        ));
        assert!(matches!(
            validate_image(Some("image/png"), b""),
            Err(Rejection::BadImage(_))
        ));
    }

    #[test]
    fn declared_length_over_limit_is_too_large() {
        let mut headers = HeaderMap::new();
        assert_eq!(check_declared_length(&headers), Ok(()));
        headers.insert(header::CONTENT_LENGTH, HeaderValue::from(MAX_BODY_BYTES));
        assert_eq!(check_declared_length(&headers), Ok(()));
        headers.insert(header::CONTENT_LENGTH, HeaderValue::from(MAX_BODY_BYTES + 1));
        assert_eq!(check_declared_length(&headers), Err(Rejection::TooLarge));
    }
}
