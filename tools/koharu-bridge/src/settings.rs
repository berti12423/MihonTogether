//! Translator settings chosen on the website's /translator page: Koharu's
//! pipeline models, translation model and options, and the font stack. They
//! are saved in `settings.json` next to the pairing config and applied before
//! the next page (models reload with it).

use std::{
    path::{Path, PathBuf},
    sync::RwLock,
};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// OCR models Koharu offers at the pinned commit, with a label for the website.
pub const OCR_MODELS: [(&str, &str); 4] = [
    ("paddleocr-vl-1.6", "PaddleOCR-VL 1.6 (default, Japanese, Korean and Chinese)"),
    ("manga-ocr", "Manga OCR (Japanese)"),
    ("baberu-ocr", "Baberu OCR"),
    ("hayai-ocr", "Hayai OCR (fast)"),
];

/// Inpainting models that need no extra options.
pub const INPAINTING_MODELS: [(&str, &str); 2] = [
    ("lama", "LaMa (default)"),
    ("aot-inpainting", "AOT"),
];

pub const DEFAULT_LLM: &str = "gemma4-12b-it";
pub const MAX_INSTRUCTIONS: usize = 2000;
pub const MAX_FONTS: usize = 8;
pub const MAX_FONT_NAME: usize = 100;

/// A local translation model the website can offer.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LlmOption {
    pub id: String,
    pub name: String,
    pub vision: bool,
    pub reasoning: bool,
    /// Model file size in MiB (the download, and roughly its graphics memory), if known.
    pub size_mb: Option<u64>,
    /// Vision projector size in MiB, loaded only when the page image is shown to the model.
    pub projector_mb: Option<u64>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default, deny_unknown_fields)]
pub struct Settings {
    pub ocr: String,
    pub inpainting: String,
    /// A local model id from Koharu's catalog.
    pub llm: String,
    /// Extra instructions for the translation model; empty for none.
    pub instructions: String,
    /// `None` keeps the model's own default.
    pub temperature: Option<f32>,
    pub top_p: Option<f32>,
    pub top_k: Option<u32>,
    pub max_tokens: Option<u32>,
    pub repeat_penalty: Option<f32>,
    /// Let models that can reason think before answering.
    pub reasoning: bool,
    /// Show the page image to models that can see.
    pub vision: bool,
    /// Preferred font families, tried before Koharu's defaults; empty for the defaults only.
    pub fonts: Vec<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            ocr: OCR_MODELS[0].0.to_owned(),
            inpainting: INPAINTING_MODELS[0].0.to_owned(),
            llm: DEFAULT_LLM.to_owned(),
            instructions: String::new(),
            temperature: None,
            top_p: None,
            top_k: None,
            max_tokens: None,
            repeat_penalty: None,
            reasoning: true,
            vision: true,
            fonts: Vec::new(),
        }
    }
}

impl Settings {
    /// Checks every field; `llms` are the local model ids this build knows.
    pub fn validate(&self, llms: &[LlmOption]) -> Result<(), String> {
        if !OCR_MODELS.iter().any(|(id, _)| *id == self.ocr) {
            return Err(format!("Unknown OCR model {:?}.", self.ocr));
        }
        if !INPAINTING_MODELS.iter().any(|(id, _)| *id == self.inpainting) {
            return Err(format!("Unknown inpainting model {:?}.", self.inpainting));
        }
        if !llms.iter().any(|llm| llm.id == self.llm) {
            return Err(format!("Unknown translation model {:?}.", self.llm));
        }
        if self.instructions.chars().count() > MAX_INSTRUCTIONS {
            return Err(format!("Instructions are limited to {MAX_INSTRUCTIONS} characters."));
        }
        if self.instructions.chars().any(|c| c.is_control() && c != '\n') {
            return Err("Instructions can't contain control characters.".to_owned());
        }
        range("Temperature", self.temperature, 0.0, 2.0)?;
        range("Top P", self.top_p, 0.01, 1.0)?;
        range("Repeat penalty", self.repeat_penalty, 0.5, 2.0)?;
        if self.top_k.is_some_and(|k| !(1..=1000).contains(&k)) {
            return Err("Top K must be between 1 and 1000.".to_owned());
        }
        if self.max_tokens.is_some_and(|n| !(16..=8192).contains(&n)) {
            return Err("Max tokens must be between 16 and 8192.".to_owned());
        }
        if self.fonts.len() > MAX_FONTS {
            return Err(format!("At most {MAX_FONTS} fonts."));
        }
        for font in &self.fonts {
            let length = font.chars().count();
            if font.trim() != font || !(1..=MAX_FONT_NAME).contains(&length) || font.chars().any(char::is_control) {
                return Err(format!("{font:?} isn't a usable font name."));
            }
        }
        Ok(())
    }

    /// A short id of these settings, reported in `/health` so caches keyed by
    /// the models (the website's) don't reuse pages made with other settings.
    pub fn fingerprint(&self) -> String {
        let json = serde_json::to_vec(self).expect("settings serialize");
        let digest = Sha256::digest(json);
        digest[..4].iter().map(|byte| format!("{byte:02x}")).collect()
    }
}

fn range(name: &str, value: Option<f32>, min: f32, max: f32) -> Result<(), String> {
    match value {
        Some(value) if !value.is_finite() || value < min || value > max => {
            Err(format!("{name} must be between {min} and {max}."))
        }
        _ => Ok(()),
    }
}

/// Where the settings live: next to the pairing config.
pub fn default_path() -> Option<PathBuf> {
    crate::pairing::default_config_path().map(|config| config.with_file_name("settings.json"))
}

/// The current settings plus a revision that increases with every change.
pub struct SettingsStore {
    path: Option<PathBuf>,
    inner: RwLock<(Settings, u64)>,
}

impl SettingsStore {
    /// Loads `path`; a missing file means defaults, an unreadable one too (with a note).
    pub fn load(path: Option<PathBuf>, llms: &[LlmOption]) -> Self {
        let settings = path.as_deref().and_then(|path| read(path, llms)).unwrap_or_default();
        Self {
            path,
            inner: RwLock::new((settings, 0)),
        }
    }

    pub fn current(&self) -> (Settings, u64) {
        self.inner.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// Stores already validated settings and saves them; returns the new revision.
    pub fn update(&self, settings: Settings) -> Result<u64, String> {
        if let Some(path) = &self.path {
            write(path, &settings).map_err(|error| format!("Could not save the settings: {error}"))?;
        }
        let mut inner = self.inner.write().unwrap_or_else(|e| e.into_inner());
        inner.0 = settings;
        inner.1 += 1;
        Ok(inner.1)
    }
}

fn read(path: &Path, llms: &[LlmOption]) -> Option<Settings> {
    let bytes = std::fs::read(path).ok()?;
    match serde_json::from_slice::<Settings>(&bytes) {
        Ok(settings) => match settings.validate(llms) {
            Ok(()) => Some(settings),
            Err(error) => {
                println!("{}: {error} Using the default settings.", path.display());
                None
            }
        },
        Err(error) => {
            println!("{} could not be read ({error}); using the default settings.", path.display());
            None
        }
    }
}

fn write(path: &Path, settings: &Settings) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, serde_json::to_vec_pretty(settings).expect("settings serialize"))?;
    std::fs::rename(temporary, path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn llms() -> Vec<LlmOption> {
        ["gemma4-12b-it", "qwen3.5-9b"]
            .map(|id| LlmOption {
                id: id.to_owned(),
                name: id.to_owned(),
                vision: true,
                reasoning: true,
                size_mb: None,
                projector_mb: None,
            })
            .to_vec()
    }

    #[test]
    fn defaults_are_valid_and_match_the_old_fixed_pipeline() {
        let settings = Settings::default();
        assert_eq!(settings.validate(&llms()), Ok(()));
        assert_eq!(settings.ocr, "paddleocr-vl-1.6");
        assert_eq!(settings.inpainting, "lama");
        assert_eq!(settings.llm, "gemma4-12b-it");
    }

    #[test]
    fn unknown_models_and_bad_ranges_are_refused() {
        let base = Settings::default();
        for broken in [
            Settings { ocr: "tesseract".into(), ..base.clone() },
            Settings { inpainting: "flux".into(), ..base.clone() },
            Settings { llm: "gpt-5".into(), ..base.clone() },
            Settings { temperature: Some(3.0), ..base.clone() },
            Settings { temperature: Some(f32::NAN), ..base.clone() },
            Settings { top_p: Some(0.0), ..base.clone() },
            Settings { top_k: Some(0), ..base.clone() },
            Settings { max_tokens: Some(8), ..base.clone() },
            Settings { repeat_penalty: Some(5.0), ..base.clone() },
            Settings { instructions: "x".repeat(MAX_INSTRUCTIONS + 1), ..base.clone() },
            Settings { instructions: "tab\there".into(), ..base.clone() },
            Settings { fonts: vec![" Arial".into()], ..base.clone() },
            Settings { fonts: vec![String::new()], ..base.clone() },
            Settings { fonts: vec!["A".into(); MAX_FONTS + 1], ..base.clone() },
        ] {
            assert!(broken.validate(&llms()).is_err(), "{broken:?}");
        }
    }

    #[test]
    fn reasonable_choices_pass() {
        let settings = Settings {
            ocr: "manga-ocr".into(),
            inpainting: "aot-inpainting".into(),
            llm: "qwen3.5-9b".into(),
            instructions: "Keep honorifics like -san.\nUse casual English.".into(),
            temperature: Some(0.3),
            top_p: Some(0.9),
            top_k: Some(40),
            max_tokens: Some(1200),
            repeat_penalty: Some(1.1),
            reasoning: false,
            vision: false,
            fonts: vec!["Anime Ace 2.0 BB".into(), "Comic Sans MS".into()],
        };
        assert_eq!(settings.validate(&llms()), Ok(()));
    }

    #[test]
    fn json_uses_camel_case_and_rejects_unknown_fields() {
        let json = serde_json::to_value(Settings::default()).unwrap();
        assert!(json.get("maxTokens").is_some());
        assert!(serde_json::from_str::<Settings>(r#"{"ocr":"manga-ocr","bogus":1}"#).is_err());
        // Missing fields take defaults, so older files keep working.
        let partial: Settings = serde_json::from_str(r#"{"llm":"qwen3.5-9b"}"#).unwrap();
        assert_eq!(partial.ocr, "paddleocr-vl-1.6");
    }

    #[test]
    fn fingerprint_changes_with_the_settings() {
        let a = Settings::default();
        let b = Settings { instructions: "Keep honorifics.".into(), ..a.clone() };
        assert_eq!(a.fingerprint().len(), 8);
        assert_eq!(a.fingerprint(), Settings::default().fingerprint());
        assert_ne!(a.fingerprint(), b.fingerprint());
    }

    #[test]
    fn the_store_saves_reloads_and_counts_revisions() {
        let dir = std::env::temp_dir().join(format!("mt-settings-{}", std::process::id()));
        let path = dir.join("settings.json");
        let store = SettingsStore::load(Some(path.clone()), &llms());
        assert_eq!(store.current(), (Settings::default(), 0));
        let changed = Settings { llm: "qwen3.5-9b".into(), ..Settings::default() };
        assert_eq!(store.update(changed.clone()), Ok(1));
        assert_eq!(SettingsStore::load(Some(path.clone()), &llms()).current().0, changed);
        // An invalid file falls back to the defaults.
        std::fs::write(&path, r#"{"llm":"gone-model"}"#).unwrap();
        assert_eq!(SettingsStore::load(Some(path), &llms()).current().0, Settings::default());
        let _ = std::fs::remove_dir_all(dir);
    }
}
