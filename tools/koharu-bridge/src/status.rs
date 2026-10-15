//! Shared startup / health state reported by `GET /health`.

use std::sync::RwLock;

use serde::Serialize;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum State {
    Starting,
    Downloading,
    Loading,
    Ready,
    Error,
}

impl State {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Starting => "starting",
            Self::Downloading => "downloading",
            Self::Loading => "loading",
            Self::Ready => "ready",
            Self::Error => "error",
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct Progress {
    pub label: String,
    pub done: u64,
    pub total: u64,
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct Models {
    pub detection: String,
    pub ocr: String,
    pub inpainting: String,
    pub llm: String,
    /// Fingerprint of the website's settings (see `settings::Settings::fingerprint`).
    pub settings: String,
}

#[derive(Clone, Debug)]
pub struct Snapshot {
    pub state: State,
    pub progress: Option<Progress>,
    pub error: Option<String>,
    pub device: String,
    pub models: Models,
    pub last_page_ms: Option<u64>,
}

pub struct Status {
    inner: RwLock<Snapshot>,
}

impl Status {
    pub fn new(models: Models) -> Self {
        Self {
            inner: RwLock::new(Snapshot {
                state: State::Starting,
                progress: None,
                error: None,
                device: "unknown".to_owned(),
                models,
                last_page_ms: None,
            }),
        }
    }

    pub fn snapshot(&self) -> Snapshot {
        self.inner.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn state(&self) -> State {
        self.inner.read().unwrap_or_else(|e| e.into_inner()).state
    }

    fn update(&self, f: impl FnOnce(&mut Snapshot)) {
        f(&mut self.inner.write().unwrap_or_else(|e| e.into_inner()));
    }

    /// Changes the state and clears the progress. Returns true when the state changed.
    pub fn set_state(&self, state: State) -> bool {
        let mut changed = false;
        self.update(|s| {
            changed = s.state != state;
            s.state = state;
            s.progress = None;
        });
        changed
    }

    pub fn set_progress(&self, progress: Option<Progress>) {
        self.update(|s| s.progress = progress);
    }

    pub fn set_error(&self, message: String) {
        self.update(|s| {
            s.state = State::Error;
            s.progress = None;
            s.error = Some(message);
        });
    }

    pub fn set_device(&self, device: String) {
        self.update(|s| s.device = device);
    }

    pub fn set_models(&self, models: Models) {
        self.update(|s| s.models = models);
    }

    pub fn set_last_page_ms(&self, ms: u64) {
        self.update(|s| s.last_page_ms = Some(ms));
    }
}
