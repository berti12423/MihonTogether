//! Phone pairing: the per-start pairing code, the lockout after wrong codes,
//! and the paired phones' tokens in `config.json`.
//!
//! Only a SHA-256 hash of each token is kept, in memory and on disk. The raw
//! token exists once, in the `/pair` response to the phone.

use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use base64::Engine as _;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Wrong codes allowed before pairing locks.
pub const MAX_FAILURES: u32 = 5;
/// How long pairing stays locked after [`MAX_FAILURES`] wrong codes.
pub const LOCKOUT: Duration = Duration::from_secs(60);
/// Device names are 1 to 60 characters after trimming.
pub const MAX_DEVICE_CHARS: usize = 60;
/// The oldest (least recently used) phone is forgotten past this many.
pub const MAX_TOKENS: usize = 32;
/// `lastUsedAt` is written to disk at most this often per phone.
const LAST_USED_PERSIST_MS: u64 = 60 * 60 * 1000;

/// Time source, so the lockout can be tested without waiting.
pub trait Clock: Send + Sync + 'static {
    fn now(&self) -> Instant;
    fn unix_ms(&self) -> u64;
}

pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> Instant {
        Instant::now()
    }
    fn unix_ms(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or_default()
    }
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TokenEntry {
    pub id: String,
    pub name: String,
    /// Lowercase hex SHA-256 of the token.
    pub hash: String,
    /// Unix milliseconds.
    pub created_at: u64,
    /// Unix milliseconds, or null before the first page.
    #[serde(default)]
    pub last_used_at: Option<u64>,
}

fn yes() -> bool {
    true
}

/// `%LOCALAPPDATA%\MihonTogether-Translator\config.json`.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Config {
    /// Whether the phone listener on port 47824 runs.
    #[serde(default = "yes")]
    pub phone: bool,
    #[serde(default)]
    pub tokens: Vec<TokenEntry>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            phone: true,
            tokens: Vec::new(),
        }
    }
}

/// The default config file location, if `%LOCALAPPDATA%` is known.
pub fn default_config_path() -> Option<PathBuf> {
    std::env::var_os("LOCALAPPDATA")
        .filter(|value| !value.is_empty())
        .map(|dir| {
            PathBuf::from(dir)
                .join("MihonTogether-Translator")
                .join("config.json")
        })
}

/// Result of reading the config file.
pub enum Loaded {
    Found(Config),
    Missing,
    /// The file couldn't be parsed; it was moved aside to `config.json.bad`.
    Corrupt(String),
}

pub fn load_config(path: &Path) -> Loaded {
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Loaded::Missing,
        Err(error) => return Loaded::Corrupt(error.to_string()),
    };
    match serde_json::from_str::<Config>(&text) {
        Ok(config) => Loaded::Found(config),
        Err(error) => {
            let _ = fs::rename(path, path.with_extension("json.bad"));
            Loaded::Corrupt(error.to_string())
        }
    }
}

/// Writes the config atomically: a temporary file next to it, flushed, then renamed
/// over the old one (a replacing rename on Windows too).
pub fn save_config(path: &Path, config: &Config) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let json = serde_json::to_vec_pretty(config).map_err(std::io::Error::other)?;
    let tmp = path.with_extension(format!("json.{}.tmp", std::process::id()));
    let written = (|| {
        let mut file = fs::File::create(&tmp)?;
        file.write_all(&json)?;
        file.sync_all()
    })();
    if let Err(error) = written.and_then(|()| fs::rename(&tmp, path)) {
        let _ = fs::remove_file(&tmp);
        return Err(error);
    }
    Ok(())
}

pub fn sha256_hex(text: &str) -> String {
    Sha256::digest(text.as_bytes())
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).expect("the OS random number generator works");
    bytes
}

/// A uniformly random 6-digit code from the OS CSPRNG.
pub fn new_pairing_code() -> String {
    // 4_294_000_000 is the largest multiple of 1_000_000 below 2^32.
    loop {
        let n = u32::from_le_bytes(random_bytes::<4>());
        if n < 4_294_000_000 {
            return format!("{:06}", n % 1_000_000);
        }
    }
}

/// "123456" -> "123 456", as shown in the console.
pub fn display_code(code: &str) -> String {
    format!("{} {}", &code[..3], &code[3..])
}

/// Trims a device name and checks it: 1 to 60 characters, no control characters.
pub fn validate_device(device: &str) -> Option<String> {
    let trimmed = device.trim();
    let chars = trimmed.chars().count();
    if chars == 0 || chars > MAX_DEVICE_CHARS || trimmed.chars().any(char::is_control) {
        return None;
    }
    Some(trimmed.to_owned())
}

#[derive(Debug, Eq, PartialEq)]
pub enum PairError {
    Locked { retry_after: Duration },
    BadCode,
    BadDevice,
    Storage(String),
}

/// What a successful pairing returns to the phone.
#[derive(Debug)]
pub struct Paired {
    pub token: String,
    /// The trimmed device name that was stored.
    #[cfg_attr(not(test), allow(dead_code))]
    pub device: String,
}

pub type Notify = Arc<dyn Fn(String) + Send + Sync>;

struct Inner {
    config: Config,
    failures: u32,
    locked_until: Option<Instant>,
}

pub struct Pairing {
    code: String,
    pc_name: String,
    path: Option<PathBuf>,
    clock: Arc<dyn Clock>,
    notify: Notify,
    inner: Mutex<Inner>,
}

impl Pairing {
    /// `config` is the loaded config; changes are written to `path` (or kept in
    /// memory only when there is no path).
    pub fn new(
        code: String,
        pc_name: String,
        config: Config,
        path: Option<PathBuf>,
        clock: Arc<dyn Clock>,
        notify: Notify,
    ) -> Self {
        assert!(code.len() == 6 && code.bytes().all(|b| b.is_ascii_digit()));
        Self {
            code,
            pc_name,
            path,
            clock,
            notify,
            inner: Mutex::new(Inner {
                config,
                failures: 0,
                locked_until: None,
            }),
        }
    }

    pub fn code(&self) -> &str {
        &self.code
    }

    pub fn pc_name(&self) -> &str {
        &self.pc_name
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn persist(&self, config: &Config) -> std::io::Result<()> {
        match &self.path {
            Some(path) => save_config(path, config),
            None => Ok(()),
        }
    }

    /// Checks the code and, on success, stores a new token for `device`.
    pub fn pair(&self, code: &str, device: &str) -> Result<Paired, PairError> {
        let now = self.clock.now();
        let mut inner = self.lock();
        if let Some(until) = inner.locked_until {
            if now < until {
                return Err(PairError::Locked {
                    retry_after: until - now,
                });
            }
            inner.locked_until = None;
            inner.failures = 0;
        }
        let device = validate_device(device).ok_or(PairError::BadDevice)?;

        // Digits only; spaces or a dash as shown in the console are tolerated.
        let submitted: String = code
            .chars()
            .filter(|c| !c.is_whitespace() && *c != '-')
            .collect();
        if !codes_match(&submitted, &self.code) {
            inner.failures += 1;
            if inner.failures >= MAX_FAILURES {
                inner.failures = 0;
                inner.locked_until = Some(now + LOCKOUT);
            }
            return Err(PairError::BadCode);
        }
        inner.failures = 0;

        let token = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(random_bytes::<32>());
        let id: String = random_bytes::<8>()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        let mut config = inner.config.clone();
        config.tokens.push(TokenEntry {
            id,
            name: device.clone(),
            hash: sha256_hex(&token),
            created_at: self.clock.unix_ms(),
            last_used_at: None,
        });
        while config.tokens.len() > MAX_TOKENS {
            let oldest = config
                .tokens
                .iter()
                .enumerate()
                .min_by_key(|(_, t)| t.last_used_at.unwrap_or(t.created_at))
                .map(|(i, _)| i)
                .expect("tokens is not empty");
            config.tokens.remove(oldest);
        }
        self.persist(&config)
            .map_err(|error| PairError::Storage(error.to_string()))?;
        inner.config = config;
        drop(inner);
        (self.notify)(format!("Paired {device}"));
        Ok(Paired { token, device })
    }

    /// Whether `token` belongs to a paired phone.
    pub fn is_paired(&self, token: &str) -> bool {
        let hash = sha256_hex(token);
        self.lock().config.tokens.iter().any(|t| t.hash == hash)
    }

    /// Like [`Self::is_paired`], and records the use (written to disk at most hourly).
    pub fn authorize(&self, token: &str) -> bool {
        let hash = sha256_hex(token);
        let now = self.clock.unix_ms();
        let mut inner = self.lock();
        let Some(entry) = inner.config.tokens.iter_mut().find(|t| t.hash == hash) else {
            return false;
        };
        let stale = entry
            .last_used_at
            .is_none_or(|last| now.saturating_sub(last) >= LAST_USED_PERSIST_MS);
        entry.last_used_at = Some(now);
        if stale {
            let config = inner.config.clone();
            if let Err(error) = self.persist(&config) {
                tracing::warn!("could not save config.json: {error}");
            }
        }
        true
    }

    /// Removes the phone that owns `token`. Returns its name, or None if unknown.
    pub fn unpair(&self, token: &str) -> Result<Option<String>, PairError> {
        let hash = sha256_hex(token);
        let mut inner = self.lock();
        let Some(index) = inner.config.tokens.iter().position(|t| t.hash == hash) else {
            return Ok(None);
        };
        let mut config = inner.config.clone();
        let removed = config.tokens.remove(index);
        self.persist(&config)
            .map_err(|error| PairError::Storage(error.to_string()))?;
        inner.config = config;
        drop(inner);
        (self.notify)(format!("Removed {}", removed.name));
        Ok(Some(removed.name))
    }

    #[cfg(test)]
    pub fn paired_count(&self) -> usize {
        self.lock().config.tokens.len()
    }
}

/// Compares two 6-digit codes without an early exit.
fn codes_match(submitted: &str, expected: &str) -> bool {
    if submitted.len() != expected.len() {
        return false;
    }
    submitted
        .bytes()
        .zip(expected.bytes())
        .fold(0u8, |diff, (a, b)| diff | (a ^ b))
        == 0
}

#[cfg(test)]
pub mod testing {
    use super::*;

    /// A clock that only moves when told to.
    pub struct FakeClock {
        base: Instant,
        offset: Mutex<Duration>,
    }

    impl FakeClock {
        pub fn new() -> Arc<Self> {
            Arc::new(Self {
                base: Instant::now(),
                offset: Mutex::new(Duration::ZERO),
            })
        }
        pub fn advance(&self, by: Duration) {
            *self.offset.lock().unwrap() += by;
        }
    }

    impl Clock for FakeClock {
        fn now(&self) -> Instant {
            self.base + *self.offset.lock().unwrap()
        }
        fn unix_ms(&self) -> u64 {
            1_800_000_000_000 + self.offset.lock().unwrap().as_millis() as u64
        }
    }

    /// A unique, empty temporary directory for one test.
    pub fn temp_dir(tag: &str) -> PathBuf {
        let suffix: String = random_bytes::<6>()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        let dir = std::env::temp_dir().join(format!("mt-translator-{tag}-{suffix}"));
        fs::create_dir_all(&dir).unwrap();
        dir
    }
}

#[cfg(test)]
mod tests {
    use super::{testing::*, *};

    fn pairing(path: Option<PathBuf>, clock: Arc<FakeClock>) -> (Pairing, Arc<Mutex<Vec<String>>>) {
        let lines = Arc::new(Mutex::new(Vec::new()));
        let sink = lines.clone();
        let config = path
            .as_deref()
            .and_then(|p| match load_config(p) {
                Loaded::Found(config) => Some(config),
                _ => None,
            })
            .unwrap_or_default();
        let pairing = Pairing::new(
            "123456".to_owned(),
            "TEST-PC".to_owned(),
            config,
            path,
            clock,
            Arc::new(move |line| sink.lock().unwrap().push(line)),
        );
        (pairing, lines)
    }

    #[test]
    fn codes_are_six_digits_and_vary() {
        let codes: Vec<String> = (0..20).map(|_| new_pairing_code()).collect();
        assert!(
            codes
                .iter()
                .all(|c| c.len() == 6 && c.bytes().all(|b| b.is_ascii_digit()))
        );
        assert!(codes.iter().any(|c| c != &codes[0]));
        assert_eq!(display_code("123456"), "123 456");
    }

    #[test]
    fn device_names_are_trimmed_and_bounded() {
        assert_eq!(
            validate_device("  Galaxy A35 ").as_deref(),
            Some("Galaxy A35")
        );
        assert_eq!(
            validate_device(&"é".repeat(60)).map(|s| s.chars().count()),
            Some(60)
        );
        assert!(validate_device(&"x".repeat(61)).is_none());
        assert!(validate_device("").is_none());
        assert!(validate_device("   ").is_none());
        assert!(validate_device("bad\nname").is_none());
    }

    #[test]
    fn pairing_succeeds_with_the_code_and_tolerates_spacing() {
        let (pairing, lines) = pairing(None, FakeClock::new());
        let paired = pairing.pair("123 456", " Galaxy A35 ").unwrap();
        assert_eq!(paired.device, "Galaxy A35");
        // 32 bytes as unpadded base64url.
        assert_eq!(paired.token.len(), 43);
        assert!(
            paired
                .token
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        );
        assert!(pairing.is_paired(&paired.token));
        assert!(!pairing.is_paired("not-a-token"));
        assert_eq!(*lines.lock().unwrap(), vec!["Paired Galaxy A35".to_owned()]);
        // A second phone gets a different token.
        let other = pairing.pair("123456", "Pixel").unwrap();
        assert_ne!(other.token, paired.token);
        assert_eq!(pairing.paired_count(), 2);
    }

    #[test]
    fn wrong_codes_fail_and_five_lock_pairing_for_60_seconds() {
        let clock = FakeClock::new();
        let (pairing, _) = pairing(None, clock.clone());
        for _ in 0..4 {
            assert_eq!(
                pairing.pair("000000", "Phone").unwrap_err(),
                PairError::BadCode
            );
        }
        // A right code before the fifth failure still works and resets the count.
        assert!(pairing.pair("123456", "Phone").is_ok());
        for _ in 0..5 {
            assert_eq!(
                pairing.pair("654321", "Phone").unwrap_err(),
                PairError::BadCode
            );
        }
        // Locked: even the right code is refused.
        match pairing.pair("123456", "Phone") {
            Err(PairError::Locked { retry_after }) => assert_eq!(retry_after, LOCKOUT),
            other => panic!("expected Locked, got {other:?}"),
        }
        clock.advance(Duration::from_secs(59));
        assert!(matches!(
            pairing.pair("123456", "Phone"),
            Err(PairError::Locked { .. })
        ));
        clock.advance(Duration::from_secs(1));
        assert!(pairing.pair("123456", "Phone").is_ok());
    }

    #[test]
    fn bad_device_does_not_count_as_a_wrong_code() {
        let (pairing, _) = pairing(None, FakeClock::new());
        for _ in 0..10 {
            assert_eq!(
                pairing.pair("000000", "").unwrap_err(),
                PairError::BadDevice
            );
        }
        assert!(pairing.pair("123456", "Phone").is_ok());
    }

    #[test]
    fn config_stores_hashes_only_and_survives_a_restart() {
        let dir = temp_dir("persist");
        let path = dir.join("config.json");
        let clock = FakeClock::new();
        let (first, _) = pairing(Some(path.clone()), clock.clone());
        let paired = first.pair("123456", "Galaxy A35").unwrap();

        let text = fs::read_to_string(&path).unwrap();
        assert!(
            !text.contains(&paired.token),
            "raw token must not be stored"
        );
        let config: Config = serde_json::from_str(&text).unwrap();
        assert!(config.phone);
        assert_eq!(config.tokens.len(), 1);
        let entry = &config.tokens[0];
        assert_eq!(entry.name, "Galaxy A35");
        assert_eq!(entry.hash, sha256_hex(&paired.token));
        assert_eq!(entry.hash.len(), 64);
        assert_eq!(entry.created_at, 1_800_000_000_000);
        assert!(entry.last_used_at.is_none());
        let raw: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert!(raw["tokens"][0]["createdAt"].is_u64());
        assert!(raw["tokens"][0].get("lastUsedAt").is_some());

        // A new start (new code, same file) still knows the phone.
        let (second, lines) = pairing(Some(path.clone()), clock);
        assert!(second.authorize(&paired.token));
        let reread: Config = serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
        assert!(reread.tokens[0].last_used_at.is_some());
        assert_eq!(
            second.unpair(&paired.token).unwrap().as_deref(),
            Some("Galaxy A35")
        );
        assert_eq!(
            *lines.lock().unwrap(),
            vec!["Removed Galaxy A35".to_owned()]
        );
        let empty: Config = serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
        assert!(empty.tokens.is_empty());
        // No temporary files are left behind.
        let leftovers: Vec<_> = fs::read_dir(&dir).unwrap().collect();
        assert_eq!(leftovers.len(), 1);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn config_defaults_and_phone_switch() {
        let config: Config = serde_json::from_str("{}").unwrap();
        assert!(config.phone);
        let off: Config = serde_json::from_str(r#"{"phone": false}"#).unwrap();
        assert!(!off.phone);

        let dir = temp_dir("corrupt");
        let path = dir.join("config.json");
        assert!(matches!(load_config(&path), Loaded::Missing));
        fs::write(&path, "{ not json").unwrap();
        assert!(matches!(load_config(&path), Loaded::Corrupt(_)));
        assert!(dir.join("config.json.bad").exists());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn oldest_phone_is_forgotten_past_the_cap() {
        let clock = FakeClock::new();
        let (pairing, _) = pairing(None, clock.clone());
        let first = pairing.pair("123456", "first").unwrap();
        for i in 0..MAX_TOKENS {
            clock.advance(Duration::from_millis(1));
            pairing.pair("123456", &format!("phone {i}")).unwrap();
        }
        assert_eq!(pairing.paired_count(), MAX_TOKENS);
        assert!(!pairing.is_paired(&first.token));
    }
}
