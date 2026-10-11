//! One-at-a-time FIFO job queue in front of the translator.
//!
//! Each request submits a job with a oneshot reply channel and waits on it.
//! When the client disconnects, the HTTP server drops the request future, which
//! drops the reply receiver. The worker checks the channel before starting a job
//! and skips jobs whose receiver is gone, so pages the reader scrolled past never
//! reach the GPU.

use std::{
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use tokio::sync::{mpsc, oneshot};

use crate::engine::{EngineError, Page, PageTranslator};

/// A finished job.
#[derive(Debug)]
pub struct Done {
    pub png: Vec<u8>,
    pub elapsed: Duration,
}

/// What happened to a job, reported to the observer (console output, `lastPageMs`).
#[derive(Debug)]
pub enum Event<'a> {
    Started { id: u64 },
    Finished { id: u64, elapsed: Duration },
    Failed { id: u64, error: &'a EngineError },
    Dropped { id: u64 },
}

pub type Observer = Arc<dyn Fn(Event<'_>) + Send + Sync>;

struct Job {
    id: u64,
    page: Page,
    reply: oneshot::Sender<Result<Done, EngineError>>,
}

#[derive(Clone)]
pub struct Queue {
    tx: mpsc::UnboundedSender<Job>,
    open: Arc<AtomicUsize>,
    next_id: Arc<AtomicUsize>,
}

/// Decrements the open-request counter when the request finishes or is dropped.
struct OpenGuard(Arc<AtomicUsize>);

impl Drop for OpenGuard {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

impl Queue {
    /// Starts the worker task. Must be called inside a Tokio runtime.
    pub fn start(engine: Arc<dyn PageTranslator>, observer: Observer) -> Self {
        let (tx, mut rx) = mpsc::unbounded_channel::<Job>();
        tokio::spawn(async move {
            while let Some(job) = rx.recv().await {
                if job.reply.is_closed() {
                    observer(Event::Dropped { id: job.id });
                    continue;
                }
                observer(Event::Started { id: job.id });
                let started = Instant::now();
                let result = engine.translate(job.page).await;
                let elapsed = started.elapsed();
                match &result {
                    Ok(_) => observer(Event::Finished {
                        id: job.id,
                        elapsed,
                    }),
                    Err(error) => observer(Event::Failed { id: job.id, error }),
                }
                // The client may have left while the job ran; ignore that.
                let _ = job.reply.send(result.map(|png| Done { png, elapsed }));
            }
        });
        Self {
            tx,
            open: Arc::new(AtomicUsize::new(0)),
            next_id: Arc::new(AtomicUsize::new(1)),
        }
    }

    /// Requests that are waiting or running.
    pub fn open(&self) -> usize {
        self.open.load(Ordering::Acquire)
    }

    /// Queues a page and waits for its result. Dropping the returned future
    /// before the job starts removes the job from the queue.
    pub async fn submit(&self, page: Page) -> Result<Done, EngineError> {
        self.open.fetch_add(1, Ordering::AcqRel);
        let _guard = OpenGuard(self.open.clone());
        let id = self.next_id.fetch_add(1, Ordering::Relaxed) as u64;
        let (reply, receiver) = oneshot::channel();
        self.tx
            .send(Job { id, page, reply })
            .map_err(|_| EngineError::Failed("translation worker stopped".to_owned()))?;
        receiver
            .await
            .unwrap_or_else(|_| Err(EngineError::Failed("translation worker stopped".to_owned())))
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use async_trait::async_trait;
    use bytes::Bytes;
    use tokio::sync::Semaphore;

    use super::*;
    use crate::engine::PageFormat;

    /// Records which pages ran; each run waits for a permit so tests control timing.
    struct Gated {
        permits: Semaphore,
        ran: Mutex<Vec<String>>,
        started: tokio::sync::Notify,
    }

    #[async_trait]
    impl PageTranslator for Gated {
        fn accepts_target(&self, _: &str) -> bool {
            true
        }

        async fn translate(&self, page: Page) -> Result<Vec<u8>, EngineError> {
            let name = String::from_utf8(page.bytes.to_vec()).unwrap();
            self.ran.lock().unwrap().push(name.clone());
            self.started.notify_one();
            self.permits.acquire().await.unwrap().forget();
            Ok(name.into_bytes())
        }
    }

    fn page(name: &str) -> Page {
        Page {
            bytes: Bytes::from(name.to_owned()),
            format: PageFormat::Png,
            target: "en-US".to_owned(),
        }
    }

    fn gated() -> Arc<Gated> {
        Arc::new(Gated {
            permits: Semaphore::new(0),
            ran: Mutex::new(Vec::new()),
            started: tokio::sync::Notify::new(),
        })
    }

    fn quiet() -> Observer {
        Arc::new(|_| {})
    }

    #[tokio::test]
    async fn runs_one_at_a_time_in_order() {
        let engine = gated();
        let queue = Queue::start(engine.clone(), quiet());
        let a = tokio::spawn({
            let q = queue.clone();
            async move { q.submit(page("a")).await }
        });
        engine.started.notified().await;
        let b = tokio::spawn({
            let q = queue.clone();
            async move { q.submit(page("b")).await }
        });
        let c = tokio::spawn({
            let q = queue.clone();
            async move { q.submit(page("c")).await }
        });
        tokio::time::sleep(Duration::from_millis(50)).await;
        // Only "a" has started; b and c wait.
        assert_eq!(*engine.ran.lock().unwrap(), vec!["a"]);
        assert_eq!(queue.open(), 3);

        engine.permits.add_permits(3);
        assert_eq!(a.await.unwrap().unwrap().png, b"a");
        assert_eq!(b.await.unwrap().unwrap().png, b"b");
        assert_eq!(c.await.unwrap().unwrap().png, b"c");
        assert_eq!(*engine.ran.lock().unwrap(), vec!["a", "b", "c"]);
        assert_eq!(queue.open(), 0);
    }

    #[tokio::test]
    async fn drops_jobs_whose_client_left_before_start() {
        let engine = gated();
        let dropped = Arc::new(Mutex::new(Vec::new()));
        let observer: Observer = {
            let dropped = dropped.clone();
            Arc::new(move |event| {
                if let Event::Dropped { id } = event {
                    dropped.lock().unwrap().push(id);
                }
            })
        };
        let queue = Queue::start(engine.clone(), observer);
        let a = tokio::spawn({
            let q = queue.clone();
            async move { q.submit(page("a")).await }
        });
        engine.started.notified().await;
        let b = tokio::spawn({
            let q = queue.clone();
            async move { q.submit(page("b")).await }
        });
        let c = tokio::spawn({
            let q = queue.clone();
            async move { q.submit(page("c")).await }
        });
        tokio::time::sleep(Duration::from_millis(50)).await;
        // The client for "b" disconnects while "a" runs.
        b.abort();
        assert!(b.await.unwrap_err().is_cancelled());
        assert_eq!(queue.open(), 2);

        engine.permits.add_permits(2);
        a.await.unwrap().unwrap();
        assert_eq!(c.await.unwrap().unwrap().png, b"c");
        assert_eq!(*engine.ran.lock().unwrap(), vec!["a", "c"]);
        assert_eq!(dropped.lock().unwrap().len(), 1);
        assert_eq!(queue.open(), 0);
    }

    #[tokio::test]
    async fn a_running_job_finishes_even_if_its_client_leaves() {
        let engine = gated();
        let queue = Queue::start(engine.clone(), quiet());
        let a = tokio::spawn({
            let q = queue.clone();
            async move { q.submit(page("a")).await }
        });
        engine.started.notified().await;
        a.abort();
        let _ = a.await;
        engine.permits.add_permits(2);
        // The worker is still healthy and serves the next job.
        assert_eq!(queue.submit(page("b")).await.unwrap().png, b"b");
        assert_eq!(*engine.ran.lock().unwrap(), vec!["a", "b"]);
    }

    #[tokio::test]
    async fn engine_errors_reach_the_caller() {
        struct Failing;
        #[async_trait]
        impl PageTranslator for Failing {
            fn accepts_target(&self, _: &str) -> bool {
                true
            }
            async fn translate(&self, _: Page) -> Result<Vec<u8>, EngineError> {
                Err(EngineError::BadImage("nope".to_owned()))
            }
        }
        let queue = Queue::start(Arc::new(Failing), quiet());
        assert!(matches!(
            queue.submit(page("x")).await,
            Err(EngineError::BadImage(_))
        ));
    }
}
