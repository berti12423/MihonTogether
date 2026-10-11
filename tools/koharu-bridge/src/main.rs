//! Mihon Together Translator: Koharu's manga translation pipeline served on
//! http://127.0.0.1:47823 for the Mihon Together website reader.

mod api;
mod engine;
mod koharu;
mod queue;
mod status;

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

    println!("Mihon Together Translator {VERSION}");
    println!("Runs Koharu on this PC. Pages never leave your computer.");

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

    let status = Arc::new(Status::new(koharu::models()));
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

    let app = api::router(AppState {
        status: status.clone(),
        queue,
        engine,
        version: VERSION,
        port: PORT,
    });

    println!();
    println!("Listening on http://{address}  (keep this window open while reading)");
    print_state(State::Starting);

    let server = tokio::spawn(async move {
        if let Err(error) = axum::serve(listener, app).await {
            println!("Server stopped: {error}");
        }
    });

    tokio::spawn(start_engine(status.clone(), lazy));

    tokio::select! {
        _ = tokio::signal::ctrl_c() => println!("Stopping."),
        _ = server => {}
    }
}

fn print_state(state: State) {
    println!("State: {}", state.as_str());
}

async fn start_engine(status: Arc<Status>, lazy: Arc<LazyEngine>) {
    koharu::watch_downloads(status.clone());
    let started = Instant::now();
    println!("Preparing Koharu (the first start downloads about 12 GB of runtimes and models) ...");
    let engine = match KoharuEngine::start(&status).await {
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
