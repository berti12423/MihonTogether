//! The real translator: Koharu's in-process pipeline with models kept warm.
//!
//! Mirrors `crates/koharu-pipeline/src/bin/run.rs` at the pinned commit
//! (same `PipelineConfig`, `Session`, `Committer`, `Renderer`/`Rasterizer` and
//! default models), but builds the pipeline once and reuses it for every page.

use std::{
    collections::{BTreeMap, HashMap},
    io::Cursor,
    str::FromStr,
    sync::{Arc, Mutex, OnceLock},
    time::{Duration, Instant},
};

use anyhow::{Context as _, Result, anyhow};
use async_trait::async_trait;
use bytes::Bytes;
use image::{DynamicImage, ImageEncoder, RgbaImage, codecs::png};
use koharu_config::Config;
use koharu_pipeline::{
    Committer, DetectionModel, InpaintingModel, KoharuLayoutRFDetrSeg2XLConfig, OcrModel,
    Operation, Pipeline, PipelineConfig, Progress, Request, Scope, StageOutput, TranslationConfig,
};
use koharu_rasterizer::{RasterOptions, Rasterizer};
use koharu_renderer::Renderer;
use koharu_scene::{
    AssetInput, AssetMetadata, AssetRole, At, EntityId, PageDraft, Region, RegionSpec, Session,
    Snapshot, TextRegion,
};
use koharu_translator::{GenerationConfig, Language, ModelSelection, Provider, ProvidersConfig};

use crate::{
    engine::{EngineError, Page, PageFormat, PageTranslator},
    status::{Models, Progress as StatusProgress, State, Status},
};

/// Koharu's defaults at the pinned commit (see docs/koharu-spike.md).
pub const DETECTION: &str = "koharu-layout-rfdetr-seg-2xl";
pub const OCR: &str = "paddleocr-vl-1.6";
pub const INPAINTING: &str = "lama";
pub const LLM: &str = "gemma4-12b-it";

pub fn models() -> Models {
    Models {
        detection: DETECTION.to_owned(),
        ocr: OCR.to_owned(),
        inpainting: INPAINTING.to_owned(),
        llm: LLM.to_owned(),
    }
}

fn pipeline_config(target: Language) -> PipelineConfig {
    PipelineConfig {
        detection: DetectionModel::KoharuLayoutRFDetrSeg2XL(
            KoharuLayoutRFDetrSeg2XLConfig::default(),
        ),
        ocr: OcrModel::PaddleOcrVl1_6,
        translation: TranslationConfig {
            model: ModelSelection {
                provider: Provider::Local,
                model: Some(LLM.to_owned()),
                quantization: None,
                vision: true,
                reasoning: true,
            },
            generation: GenerationConfig::default(),
            target_language: target,
            instructions: None,
        },
        inpainting: InpaintingModel::LaMa {},
        processor: Default::default(),
    }
}

/// Koharu reports the translation stage's model as its provider ("local").
fn display_model(stage: koharu_pipeline::Stage, model: &str) -> String {
    if matches!(stage, koharu_pipeline::Stage::Translation) && model == "local" {
        LLM.to_owned()
    } else {
        model.to_owned()
    }
}

pub fn parse_target(target: &str) -> Option<Language> {
    Language::from_str(target).ok()
}

struct SessionCommitter<'a>(&'a mut Session);

#[async_trait]
impl Committer for SessionCommitter<'_> {
    async fn commit(&mut self, output: StageOutput) -> Result<koharu_scene::Snapshot> {
        Ok(self.0.commit(output.patch).await?.snapshot)
    }
}

pub struct KoharuEngine {
    pipeline: Pipeline,
    config: Config<PipelineConfig>,
    target: tokio::sync::Mutex<Language>,
    renderer: Renderer,
    rasterizer: Rasterizer,
    /// Per-stage timings of the page being translated, for the console line.
    stage_times: Arc<Mutex<Vec<(String, Duration)>>>,
}

impl KoharuEngine {
    /// Initializes Koharu's native runtimes (downloading them on first run) and
    /// builds the pipeline. Models load lazily; call [`Self::warm_up`] next.
    pub async fn start(status: &Status) -> Result<Self> {
        initialize_with_retry(status).await;
        let device = koharu_ml::device(false);
        status.set_device(device.backend.to_string().to_ascii_lowercase());
        println!("Device: {} ({})", device.name, device.backend);
        let target = parse_target(crate::api::DEFAULT_TARGET).expect("default target parses");
        let config = Config::memory(pipeline_config(target));
        let pipeline = Pipeline::from_config(
            config.clone(),
            Config::memory(ProvidersConfig::default()),
            device,
        )?;
        let renderer = Renderer::new()?;
        let rasterizer = Rasterizer::new()?;
        Ok(Self {
            pipeline,
            config,
            target: tokio::sync::Mutex::new(target),
            renderer,
            rasterizer,
            stage_times: Arc::new(Mutex::new(Vec::new())),
        })
    }

    /// Runs a synthetic page through the pipeline so every model is downloaded
    /// and loaded before the bridge reports `ready`. Returns whether the warm-up
    /// page reached every stage (if detection finds no text, the remaining models
    /// load with the first real page instead).
    pub async fn warm_up(&self, status: Arc<Status>) -> Result<bool> {
        let png = warm_up_page()?;
        let decoded = image::load_from_memory(&png)?;
        let loaded = Arc::new(Mutex::new(0_u64));
        let progress: koharu_pipeline::ProgressSink = {
            let status = status.clone();
            let loaded = loaded.clone();
            Arc::new(move |event| match event {
                Progress::Loading { stage, model, .. } => {
                    let model = display_model(stage, &model);
                    println!("  loading {model} ...");
                    if status.state() == State::Loading {
                        status.set_progress(Some(StatusProgress {
                            label: format!("Loading {model}"),
                            done: *loaded.lock().unwrap(),
                            total: 4,
                        }));
                    }
                }
                Progress::Finished {
                    stage,
                    model,
                    elapsed,
                    ..
                } => {
                    let model = display_model(stage, &model);
                    let mut done = loaded.lock().unwrap();
                    *done += 1;
                    println!(
                        "  {stage} ready ({model}, {:.1} s)",
                        elapsed.as_secs_f64()
                    );
                    if status.state() == State::Loading {
                        status.set_progress(Some(StatusProgress {
                            label: format!("Loaded {model}"),
                            done: *done,
                            total: 4,
                        }));
                    }
                }
                Progress::Skipped { stage, .. } => {
                    println!("  {stage} skipped on the warm-up page");
                }
                _ => {}
            })
        };
        let (session, page) = self
            .run_pipeline(Bytes::from(png), "image/png", &decoded, Some(progress))
            .await?;
        let all_stages = *loaded.lock().unwrap() >= 4;
        // Render once too, so the renderer's fonts and the GPU rasterizer are warm.
        if has_text(&session.snapshot(), page) {
            self.render(&session, page, &decoded).await?;
        }
        Ok(all_stages)
    }

    /// Switches the target language. Koharu rebuilds its stage runner when the
    /// config changes, so the models reload on the next page.
    async fn ensure_target(&self, target: Language) -> Result<()> {
        let mut current = self.target.lock().await;
        if *current == target {
            return Ok(());
        }
        println!("Switching target language to {target}; models reload with the next page.");
        {
            let mut config = self.config.write()?;
            config.translation.target_language = target;
        }
        // The pipeline swaps its stage runner from a watcher task. Give it a moment
        // so the next execute picks up the new runner.
        for _ in 0..20 {
            tokio::task::yield_now().await;
        }
        tokio::time::sleep(Duration::from_millis(300)).await;
        *current = target;
        Ok(())
    }

    async fn run_pipeline(
        &self,
        source: Bytes,
        media_type: &str,
        decoded: &DynamicImage,
        progress: Option<koharu_pipeline::ProgressSink>,
    ) -> Result<(Session, EntityId)> {
        let mut session = Session::memory().await?;
        let mut page = None;
        let patch = session.snapshot().patch(|edit| {
            let id = edit.add_page(
                PageDraft::new(
                    "page",
                    f64::from(decoded.width()),
                    f64::from(decoded.height()),
                ),
                At::End,
            )?;
            edit.set_asset(
                id,
                &AssetRole::new("source")?,
                AssetInput::new(
                    Arc::<[u8]>::from(source.as_ref()),
                    media_type,
                    AssetMetadata {
                        width: Some(decoded.width()),
                        height: Some(decoded.height()),
                        attributes: BTreeMap::new(),
                    },
                ),
            )?;
            page = Some(id);
            Ok(())
        })?;
        session.commit(patch).await?;
        let page = page.ok_or_else(|| anyhow!("page ID was not assigned"))?;

        let snapshot = session.snapshot();
        let mut committer = SessionCommitter(&mut session);
        self.pipeline
            .execute(
                snapshot,
                Request {
                    operation: Operation::Full,
                    scope: Scope::Pages(vec![page]),
                    progress,
                    ..Request::default()
                },
                &mut committer,
            )
            .await
            .map_err(|error| anyhow!("{error}"))?;
        Ok((session, page))
    }

    async fn render(
        &self,
        session: &Session,
        page: EntityId,
        original: &DynamicImage,
    ) -> Result<RgbaImage> {
        let snapshot = session.snapshot();
        let frame = self.renderer.render(&snapshot, page).await?;
        let raster_frame = frame.raster_frame()?;
        let raster = tokio::task::block_in_place(|| {
            self.rasterizer
                .rasterize(&raster_frame, RasterOptions::default())
        })?;
        let mut image = raster.image;
        if image.dimensions() != original.dimensions_tuple() {
            tracing::warn!(
                rendered = ?image.dimensions(),
                original = ?original.dimensions_tuple(),
                "rendered page size differs; resizing to the original size"
            );
            image = image::imageops::resize(
                &image,
                original.width(),
                original.height(),
                image::imageops::FilterType::Lanczos3,
            );
        }
        Ok(image)
    }

    pub fn take_stage_times(&self) -> Vec<(String, Duration)> {
        std::mem::take(&mut self.stage_times.lock().unwrap())
    }
}

trait Dimensions {
    fn dimensions_tuple(&self) -> (u32, u32);
}

impl Dimensions for DynamicImage {
    fn dimensions_tuple(&self) -> (u32, u32) {
        (self.width(), self.height())
    }
}

fn has_text(snapshot: &Snapshot, page: EntityId) -> bool {
    let Ok(entities) = snapshot.descendants(page) else {
        return false;
    };
    let text = TextRegion::kind();
    entities.into_iter().any(|entity| {
        snapshot
            .component::<Region>(entity.id())
            .ok()
            .flatten()
            .is_some_and(|region| region.kind == text)
    })
}

fn encode_png(image: &DynamicImage) -> Result<Vec<u8>> {
    let rgba;
    let (bytes, color, width, height) = match image {
        DynamicImage::ImageRgb8(rgb) => (
            rgb.as_raw().as_slice(),
            image::ExtendedColorType::Rgb8,
            rgb.width(),
            rgb.height(),
        ),
        DynamicImage::ImageLuma8(luma) => (
            luma.as_raw().as_slice(),
            image::ExtendedColorType::L8,
            luma.width(),
            luma.height(),
        ),
        other => {
            rgba = other.to_rgba8();
            (
                rgba.as_raw().as_slice(),
                image::ExtendedColorType::Rgba8,
                rgba.width(),
                rgba.height(),
            )
        }
    };
    let mut out = Vec::new();
    png::PngEncoder::new_with_quality(
        Cursor::new(&mut out),
        png::CompressionType::Fast,
        png::FilterType::Adaptive,
    )
    .write_image(bytes, width, height, color)
    .context("failed to encode PNG")?;
    Ok(out)
}

#[async_trait]
impl PageTranslator for KoharuEngine {
    fn accepts_target(&self, target: &str) -> bool {
        parse_target(target).is_some()
    }

    async fn translate(&self, page: Page) -> Result<Vec<u8>, EngineError> {
        let target = parse_target(&page.target)
            .ok_or_else(|| EngineError::Failed(format!("unknown target {}", page.target)))?;
        self.ensure_target(target)
            .await
            .map_err(|error| EngineError::Failed(error.to_string()))?;

        let bytes = page.bytes.clone();
        let decoded = tokio::task::spawn_blocking(move || image::load_from_memory(&bytes))
            .await
            .map_err(|error| EngineError::Failed(error.to_string()))?
            .map_err(|error| EngineError::BadImage(format!("could not decode the image: {error}")))?;
        let decoded = Arc::new(decoded);

        // Koharu decodes the stored source itself; hand it formats it handles
        // directly and normalize the rest to PNG.
        let (source, media_type) = match page.format {
            PageFormat::Jpeg | PageFormat::Png | PageFormat::Webp => {
                (page.bytes.clone(), page.format.media_type())
            }
            PageFormat::Gif | PageFormat::Avif => {
                let image = decoded.clone();
                let png = tokio::task::spawn_blocking(move || encode_png(&image))
                    .await
                    .map_err(|error| EngineError::Failed(error.to_string()))?
                    .map_err(|error| EngineError::Failed(error.to_string()))?;
                (Bytes::from(png), "image/png")
            }
        };

        let times = self.stage_times.clone();
        times.lock().unwrap().clear();
        let progress: koharu_pipeline::ProgressSink = Arc::new(move |event| {
            if let Progress::Finished { stage, elapsed, .. } = event {
                times.lock().unwrap().push((stage.to_string(), elapsed));
            }
        });
        let (session, page_id) = self
            .run_pipeline(source, media_type, &decoded, Some(progress))
            .await
            .map_err(|error| EngineError::Failed(format!("{error:#}")))?;

        if !has_text(&session.snapshot(), page_id) {
            let image = decoded.clone();
            return tokio::task::spawn_blocking(move || encode_png(&image))
                .await
                .map_err(|error| EngineError::Failed(error.to_string()))?
                .map_err(|error| EngineError::Failed(error.to_string()));
        }

        let started = Instant::now();
        let rendered = self
            .render(&session, page_id, &decoded)
            .await
            .map_err(|error| EngineError::Failed(format!("render failed: {error:#}")))?;
        let png = tokio::task::spawn_blocking(move || encode_png(&DynamicImage::ImageRgba8(rendered)))
            .await
            .map_err(|error| EngineError::Failed(error.to_string()))?
            .map_err(|error| EngineError::Failed(error.to_string()))?;
        self.stage_times
            .lock()
            .unwrap()
            .push(("render".to_owned(), started.elapsed()));
        Ok(png)
    }
}

/// The engine is created in the background after the server starts; until then
/// translate requests are refused by the HTTP layer (state is not `ready`).
#[derive(Default)]
pub struct LazyEngine {
    engine: OnceLock<Arc<KoharuEngine>>,
}

impl LazyEngine {
    pub fn set(&self, engine: Arc<KoharuEngine>) {
        let _ = self.engine.set(engine);
    }

    pub fn get(&self) -> Option<&Arc<KoharuEngine>> {
        self.engine.get()
    }
}

#[async_trait]
impl PageTranslator for LazyEngine {
    fn accepts_target(&self, target: &str) -> bool {
        parse_target(target).is_some()
    }

    async fn translate(&self, page: Page) -> Result<Vec<u8>, EngineError> {
        match self.engine.get() {
            Some(engine) => engine.translate(page).await,
            None => Err(EngineError::Failed("the translator is not ready".to_owned())),
        }
    }
}

async fn initialize_with_retry(status: &Status) {
    let mut delay = Duration::from_secs(1);
    let mut attempt = 0_u64;
    loop {
        attempt += 1;
        match koharu_ml::init().await {
            Ok(()) => return,
            Err(error) => {
                let jitter = Duration::from_millis((attempt.wrapping_mul(137)) % 251);
                let wait = delay + jitter;
                let message = format!("runtime initialization failed: {error:#}");
                println!("{message}; retrying in {:.1} s", wait.as_secs_f64());
                status.set_error(message);
                tokio::time::sleep(wait).await;
                status.set_state(State::Starting);
                delay = delay.saturating_mul(2).min(Duration::from_secs(30));
            }
        }
    }
}

/// Feeds Koharu's download events into `/health` and the console.
pub fn watch_downloads(status: Arc<Status>) {
    let mut events = koharu_runtime::download::subscribe();
    tokio::spawn(async move {
        use koharu_runtime::download::Event;
        let mut active: HashMap<u64, (String, u64, u64)> = HashMap::new();
        let mut last_print = Instant::now() - Duration::from_secs(60);
        let mut previous_state = State::Starting;
        loop {
            let event = match events.recv().await {
                Ok(event) => event,
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(tokio::sync::broadcast::error::RecvError::Closed) => return,
            };
            let mut label = None;
            match event {
                Event::Started { id, name } => {
                    println!("Downloading {name} ...");
                    label = Some(name.clone());
                    active.insert(id, (name, 0, 0));
                }
                Event::Progress {
                    id,
                    name,
                    completed,
                    total,
                } => {
                    label = Some(name.clone());
                    active.insert(id, (name, completed, total));
                }
                Event::Finished { id } => {
                    if let Some((name, _, _)) = active.remove(&id) {
                        println!("Downloaded {name}");
                    }
                }
                Event::Failed { id, name, error } => {
                    active.remove(&id);
                    println!("Download of {name} failed: {error}");
                }
            }
            // After `ready`, a late download (rare) must not make translate return 503.
            let state = status.state();
            if state == State::Ready || state == State::Error {
                continue;
            }
            if active.is_empty() {
                if state == State::Downloading {
                    status.set_state(previous_state);
                }
                continue;
            }
            if state != State::Downloading {
                previous_state = state;
                status.set_state(State::Downloading);
            }
            let done: u64 = active.values().map(|(_, done, _)| *done).sum();
            let total: u64 = active.values().map(|(_, _, total)| *total).sum();
            let name = label.unwrap_or_else(|| active.values().next().unwrap().0.clone());
            status.set_progress(Some(StatusProgress {
                label: format!("Downloading {name}"),
                done,
                total,
            }));
            if last_print.elapsed() >= Duration::from_secs(5) && total > 0 {
                last_print = Instant::now();
                println!(
                    "  {:.0}% of {:.1} GB",
                    done as f64 * 100.0 / total as f64,
                    total as f64 / 1e9
                );
            }
        }
    });
}

/// A small original test page (a speech bubble with Japanese text drawn with a
/// Windows system font) used to load every model at startup.
fn warm_up_page() -> Result<Vec<u8>> {
    use ab_glyph::{FontVec, PxScale};
    use imageproc::drawing::{draw_filled_ellipse_mut, draw_hollow_ellipse_mut, draw_text_mut};

    let (width, height) = (800_u32, 1000_u32);
    let mut canvas = RgbaImage::from_pixel(width, height, image::Rgba([235, 235, 235, 255]));
    let white = image::Rgba([255, 255, 255, 255]);
    let black = image::Rgba([0, 0, 0, 255]);

    let font = ["msgothic.ttc", "YuGothM.ttc", "meiryo.ttc"]
        .iter()
        .find_map(|name| {
            let windir = std::env::var("WINDIR").unwrap_or_else(|_| "C:\\Windows".to_owned());
            let data = std::fs::read(format!("{windir}\\Fonts\\{name}")).ok()?;
            FontVec::try_from_vec_and_index(data, 0).ok()
        });

    // Bubble 1: vertical text, read right to left.
    draw_filled_ellipse_mut(&mut canvas, (560, 300), 170, 240, white);
    for thickness in 0..4 {
        draw_hollow_ellipse_mut(&mut canvas, (560, 300), 170 + thickness, 240 + thickness, black);
    }
    // Bubble 2: horizontal text.
    draw_filled_ellipse_mut(&mut canvas, (280, 760), 230, 130, white);
    for thickness in 0..4 {
        draw_hollow_ellipse_mut(&mut canvas, (280, 760), 230 + thickness, 130 + thickness, black);
    }

    if let Some(font) = font {
        let scale = PxScale::from(44.0);
        let columns = ["きょうは", "いいてんきだね", "さんぽにいこう"];
        for (column, text) in columns.iter().enumerate() {
            let x = 640 - (column as i32) * 70;
            for (row, ch) in text.chars().enumerate() {
                draw_text_mut(
                    &mut canvas,
                    black,
                    x,
                    110 + (row as i32) * 50,
                    scale,
                    &font,
                    &ch.to_string(),
                );
            }
        }
        draw_text_mut(&mut canvas, black, 120, 710, scale, &font, "ありがとう！");
        draw_text_mut(&mut canvas, black, 120, 770, scale, &font, "またあしたね");
    }

    encode_png(&DynamicImage::ImageRgba8(canvas))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_target_and_common_tags_parse() {
        assert!(parse_target("en-US").is_some());
        assert!(parse_target("xx-NOPE").is_none());
    }

    #[test]
    fn warm_up_page_is_a_png() {
        let png = warm_up_page().unwrap();
        let image = image::load_from_memory(&png).unwrap();
        assert_eq!((image.width(), image.height()), (800, 1000));
    }

    #[test]
    fn png_encoding_keeps_size() {
        let image = DynamicImage::ImageRgb8(image::RgbImage::new(37, 91));
        let png = encode_png(&image).unwrap();
        let back = image::load_from_memory(&png).unwrap();
        assert_eq!((back.width(), back.height()), (37, 91));
    }
}
