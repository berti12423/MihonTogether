//! Mihon Together Translator: Koharu's manga translation pipeline served on
//! http://127.0.0.1:47823 for the Mihon Together website reader, and on
//! 0.0.0.0:47824 for paired Mihon Together phones on the same network.

mod api;
mod engine;
mod gpu;
mod koharu;
mod model_sizes;
mod network;
mod pairing;
mod phone;
mod queue;
mod settings;
mod status;
mod text_color;

use std::{
    io::IsTerminal,
    net::{Ipv4Addr, SocketAddr},
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Instant,
};

use crate::{
    api::{AppState, PORT},
    engine::PageTranslator,
    koharu::{KoharuEngine, LazyEngine},
    pairing::{Config, Loaded, Pairing, SystemClock},
    phone::{PHONE_PORT, PhoneState},
    queue::{Event, Queue},
    status::{State, Status},
};

const VERSION: &str = env!("CARGO_PKG_VERSION");

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_env("MIHON_TRANSLATOR_LOG")
                // llama.cpp and stable-diffusion.cpp warn a lot about harmless model quirks.
                .unwrap_or_else(|_| {
                    tracing_subscriber::EnvFilter::new(
                        "warn,koharu-llama=error,koharu-diffusion=error",
                    )
                }),
        )
        .with_target(false)
        .init();

    let mut phone_flag = true;
    for arg in std::env::args().skip(1) {
        match arg.as_str() {
            "--no-phone" => phone_flag = false,
            "--help" | "-h" => {
                println!("MihonTogether-Translator [--no-phone]");
                println!(
                    "  --no-phone  don't open the phone port {PHONE_PORT} (local website use only)"
                );
                return;
            }
            other => println!("Ignoring unknown option {other}"),
        }
    }

    println!("Mihon Together Translator {VERSION}");
    println!("Runs Koharu on this PC. Pages never leave your computer (or your paired phone).");

    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, PORT));
    let listener = match tokio::net::TcpListener::bind(address).await {
        Ok(listener) => listener,
        Err(error) => {
            println!();
            println!("Could not listen on http://{address}: {error}");
            println!("Is the translator already running in another window?");
            pause_before_exit();
            std::process::exit(1);
        }
    };

    let llms = Arc::new(koharu::local_models().await);
    let gpu = gpu::detect().await;
    match &gpu {
        Some(gpu) => println!("GPU: {} ({:.0} GB)", gpu.name, gpu.vram_mb as f64 / 1024.0),
        None => println!("No NVIDIA GPU found; translation runs on the processor (slow)."),
    }
    let settings = Arc::new(settings::SettingsStore::load(settings::default_path(), &llms));
    let status = Arc::new(Status::new(koharu::models(&settings.current().0)));
    let lazy = Arc::new(LazyEngine::default());
    let engine: Arc<dyn PageTranslator> = lazy.clone();
    let pages = Arc::new(AtomicU64::new(0));
    let queue = Queue::start(engine.clone(), {
        let status = status.clone();
        let lazy = lazy.clone();
        let pages = pages.clone();
        Arc::new(move |event| match event {
            Event::Started { id } => println!("Job {id}: translating ..."),
            Event::Finished { id, elapsed } => {
                let n = pages.fetch_add(1, Ordering::Relaxed) + 1;
                let ms = elapsed.as_millis() as u64;
                status.set_last_page_ms(ms);
                let stages = lazy
                    .get()
                    .map(|engine| {
                        engine
                            .take_stage_times()
                            .iter()
                            .map(|(stage, time)| format!("{stage} {:.1}s", time.as_secs_f64()))
                            .collect::<Vec<_>>()
                            .join(", ")
                    })
                    .unwrap_or_default();
                if stages.is_empty() {
                    println!("Page {n} (job {id}): {:.1} s", elapsed.as_secs_f64());
                } else {
                    println!(
                        "Page {n} (job {id}): {:.1} s ({stages})",
                        elapsed.as_secs_f64()
                    );
                }
            }
            Event::Failed { id, error } => println!("Job {id} failed: {error}"),
            Event::Dropped { id } => println!("Job {id} skipped (the reader moved on)"),
        })
    });

    let state = AppState {
        status: status.clone(),
        queue,
        engine,
        version: VERSION,
        port: PORT,
        settings: settings.clone(),
        llms,
        gpu,
    };
    let app = api::router(state.clone());

    println!();
    println!("Listening on http://{address}  (keep this window open while reading)");
    // Kept alive for the whole run: dropping it would stop the mDNS announcement.
    let _mdns = if phone_flag {
        start_phone(state).await
    } else {
        println!("Phone port off (--no-phone).");
        None
    };
    println!();
    print_state(State::Starting);

    let server = tokio::spawn(async move {
        if let Err(error) = axum::serve(listener, app).await {
            println!("Server stopped: {error}");
        }
    });

    tokio::spawn(start_engine(status.clone(), lazy, settings));

    tokio::select! {
        _ = tokio::signal::ctrl_c() => println!("Stopping."),
        _ = server => {}
    }
}

/// Loads the config and, unless it turns the phone port off, starts the phone
/// listener and its mDNS announcement. Failures here never stop the local port.
async fn start_phone(state: AppState) -> Option<mdns_sd::ServiceDaemon> {
    let path = pairing::default_config_path();
    let config = match path.as_deref() {
        None => {
            println!(
                "Note: %LOCALAPPDATA% is not set; paired phones are forgotten when this window closes."
            );
            Config::default()
        }
        Some(path) => match pairing::load_config(path) {
            Loaded::Found(config) => config,
            Loaded::Missing => {
                let config = Config::default();
                if let Err(error) = pairing::save_config(path, &config) {
                    println!("Could not create {}: {error}", path.display());
                }
                config
            }
            Loaded::Corrupt(error) => {
                println!(
                    "{} could not be read ({error}); starting with no paired phones.",
                    path.display()
                );
                Config::default()
            }
        },
    };
    if !config.phone {
        println!("Phone port off (\"phone\": false in config.json).");
        return None;
    }

    let address = SocketAddr::from((Ipv4Addr::UNSPECIFIED, PHONE_PORT));
    let listener = match tokio::net::TcpListener::bind(address).await {
        Ok(listener) => listener,
        Err(error) => {
            println!(
                "Could not open the phone port {PHONE_PORT}: {error}. Phones can't connect this time."
            );
            return None;
        }
    };

    let name = phone::computer_name();
    let paired_before = config.tokens.len();
    let pairing = Arc::new(Pairing::new(
        pairing::new_pairing_code(),
        name.clone(),
        config,
        path,
        Arc::new(SystemClock),
        Arc::new(|line| println!("{line}")),
    ));
    let code = pairing::display_code(pairing.code());
    let app = phone::router(PhoneState {
        app: state,
        pairing,
    });
    tokio::spawn(async move {
        if let Err(error) = axum::serve(listener, app).await {
            println!("Phone port stopped: {error}");
        }
    });

    println!();
    println!("Phones on the same Wi-Fi can use this translator (Mihon Together app > My PC):");
    let addresses = phone::lan_addresses();
    if addresses.is_empty() {
        println!("  No network address found. Is this PC connected to your network?");
    }
    for (adapter, ip) in &addresses {
        println!("  Phone address: {ip}:{PHONE_PORT}  ({adapter})");
    }
    println!("  Phone pairing code: {code}");
    if paired_before > 0 {
        println!("  Paired phones: {paired_before} (they don't need the code again)");
    }
    let public = network::public_networks().await;
    if public.is_empty() {
        println!("  If Windows asks, allow access on private networks so your phone can connect.");
    } else {
        println!();
        println!(
            "  Windows treats {} as a Public network, so its firewall blocks phones.",
            public.join(", ")
        );
        println!(
            "  Fix: Settings > Network & internet > Ethernet or Wi-Fi > Network profile type: Private,"
        );
        println!(
            "  then restart the translator and allow access when Windows asks (or allow it on public networks)."
        );
    }

    let ips: Vec<Ipv4Addr> = addresses.iter().map(|(_, ip)| *ip).collect();
    match phone::advertise(&name, &ips) {
        Ok(daemon) => Some(daemon),
        Err(error) => {
            println!(
                "  Automatic discovery (mDNS) is off: {error}. Enter the address on the phone instead."
            );
            None
        }
    }
}

fn print_state(state: State) {
    println!("State: {}", state.as_str());
}

async fn start_engine(status: Arc<Status>, lazy: Arc<LazyEngine>, settings: Arc<settings::SettingsStore>) {
    koharu::watch_downloads(status.clone());
    let started = Instant::now();
    println!("Preparing Koharu (the first start downloads about 12 GB of runtimes and models) ...");
    let engine = match KoharuEngine::start(&status, settings).await {
        Ok(engine) => Arc::new(engine),
        Err(error) => {
            let message = format!("could not start the pipeline: {error:#}");
            println!("Error: {message}");
            status.set_error(message);
            print_state(State::Error);
            return;
        }
    };

    if status.set_state(State::Loading) {
        print_state(State::Loading);
    }
    println!("Loading models with a warm-up page ...");
    match engine.warm_up(status.clone()).await {
        Ok(true) => {}
        Ok(false) => println!("Some models will load with the first page."),
        Err(error) => {
            // A failed warm-up is not fatal; a real page may still work.
            println!("Warm-up page failed: {error:#}");
        }
    }
    let _ = engine.take_stage_times();
    lazy.set(engine);
    status.set_state(State::Ready);
    print_state(State::Ready);
    println!(
        "Ready in {:.0} s. Open the Mihon Together website reader and turn on Translate.",
        started.elapsed().as_secs_f64()
    );
}

/// Keeps a double-clicked console window open long enough to read the error.
fn pause_before_exit() {
    if std::io::stdin().is_terminal() {
        println!("Press Enter to close.");
        let mut line = String::new();
        let _ = std::io::stdin().read_line(&mut line);
    }
}
