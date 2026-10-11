//! The seam between the HTTP layer and the translation pipeline.
//!
//! The HTTP layer and the queue only know [`PageTranslator`], so they can be
//! tested with a fake pipeline and no models.

use async_trait::async_trait;
use bytes::Bytes;

/// Image formats the API accepts (sniffed from the bytes, not trusted from headers).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PageFormat {
    Jpeg,
    Png,
    Webp,
    Gif,
    Avif,
}

impl PageFormat {
    pub fn media_type(self) -> &'static str {
        match self {
            Self::Jpeg => "image/jpeg",
            Self::Png => "image/png",
            Self::Webp => "image/webp",
            Self::Gif => "image/gif",
            Self::Avif => "image/avif",
        }
    }
}

/// One page to translate.
#[derive(Clone, Debug)]
pub struct Page {
    pub bytes: Bytes,
    pub format: PageFormat,
    /// A target language the translator accepted via [`PageTranslator::accepts_target`].
    pub target: String,
}

#[derive(Debug)]
pub enum EngineError {
    /// The bytes could not be decoded as an image.
    BadImage(String),
    /// Anything else went wrong in the pipeline.
    Failed(String),
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::BadImage(message) => write!(f, "bad image: {message}"),
            Self::Failed(message) => write!(f, "failed: {message}"),
        }
    }
}

/// Translates one page into a PNG of the same pixel size.
#[async_trait]
pub trait PageTranslator: Send + Sync + 'static {
    /// Whether `target` is a language this translator can produce.
    fn accepts_target(&self, target: &str) -> bool;

    /// Returns the translated page as PNG bytes. Pages without text come back
    /// unchanged (re-encoded as PNG).
    async fn translate(&self, page: Page) -> Result<Vec<u8>, EngineError>;
}
