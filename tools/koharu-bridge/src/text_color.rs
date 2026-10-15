//! Text colors measured on the original page.
//!
//! Koharu's detection snaps a text color to pure black or white when its
//! luminance is low or high (`normalize_text_color` at the pinned commit). Its
//! luminance weights green heavily, so saturated red, blue or purple text snaps
//! to black: red lettering in a black bubble comes back as black text in a
//! black bubble. Before rendering, the translator measures the ink inside each
//! text region of the original page and puts a saturated color back, and it
//! never lets the text color melt into the region's background.

use image::RgbaImage;

/// Squared RGB distance from the background above which a pixel counts as ink.
const INK_DISTANCE: u32 = 3 * 45 * 45;
/// Max minus min channel at or above which a color counts as saturated.
const SATURATED_CHROMA: u8 = 70;
/// Squared RGB distance below which text would be hard to see on its background.
const LOW_CONTRAST: u32 = 3 * 40 * 40;
/// Width of the region border that is sampled as the background.
const BORDER: u32 = 3;
/// Upper bound of sampled pixels per region; larger regions are subsampled.
const MAX_SAMPLES: u32 = 40_000;

/// The color the translated text should use for the region `bbox`
/// (`[left, top, right, bottom)` in page pixels), or `None` to keep `current`.
pub fn corrected_color(image: &RgbaImage, bbox: [u32; 4], current: [u8; 3]) -> Option<[u8; 3]> {
    let [left, top, right, bottom] = clamp(bbox, image.width(), image.height())?;
    let (width, height) = (right - left, bottom - top);
    if width <= BORDER * 2 || height <= BORDER * 2 {
        return None;
    }
    let step = (((width * height) as f64 / f64::from(MAX_SAMPLES)).sqrt().ceil() as u32).max(1);

    let mut border = Vec::new();
    let mut inner = Vec::new();
    for y in (top..bottom).step_by(step as usize) {
        for x in (left..right).step_by(step as usize) {
            let [r, g, b, _] = image.get_pixel(x, y).0;
            let edge = x < left + BORDER
                || x >= right - BORDER
                || y < top + BORDER
                || y >= bottom - BORDER;
            if edge {
                border.push([r, g, b]);
            } else {
                inner.push([r, g, b]);
            }
        }
    }
    if border.is_empty() || inner.is_empty() {
        return None;
    }
    let background = median(&border);
    let ink: Vec<[u8; 3]> = inner
        .into_iter()
        .filter(|pixel| distance(*pixel, background) > INK_DISTANCE)
        .collect();
    let enough_ink = ink.len() >= 20 && ink.len() * 200 >= border.len() + ink.len();

    if enough_ink {
        let saturated: Vec<[u8; 3]> =
            ink.iter().copied().filter(|pixel| chroma(*pixel) >= SATURATED_CHROMA).collect();
        // Mostly colorful ink that Koharu snapped to black or white: restore it.
        if saturated.len() * 2 >= ink.len() && (current == [0, 0, 0] || current == [255, 255, 255]) {
            let measured = median(&most_saturated_half(saturated));
            if distance(measured, current) > INK_DISTANCE {
                return Some(measured);
            }
        }
    }

    if distance(current, background) < LOW_CONTRAST {
        if enough_ink {
            let measured = median(&ink);
            if distance(measured, background) >= LOW_CONTRAST {
                return Some(measured);
            }
        }
        return Some(if luminance(background) < 128 { [255, 255, 255] } else { [0, 0, 0] });
    }
    None
}

fn clamp([left, top, right, bottom]: [u32; 4], width: u32, height: u32) -> Option<[u32; 4]> {
    let (right, bottom) = (right.min(width), bottom.min(height));
    (right > left && bottom > top).then_some([left, top, right, bottom])
}

fn median(pixels: &[[u8; 3]]) -> [u8; 3] {
    let mut out = [0; 3];
    for (channel, value) in out.iter_mut().enumerate() {
        let mut values: Vec<u8> = pixels.iter().map(|pixel| pixel[channel]).collect();
        let middle = values.len() / 2;
        *value = *values.select_nth_unstable(middle).1;
    }
    out
}

/// Glyph edges blend into the background; the most colorful half of the ink is
/// the lettering's own color.
fn most_saturated_half(mut pixels: Vec<[u8; 3]>) -> Vec<[u8; 3]> {
    pixels.sort_unstable_by_key(|pixel| std::cmp::Reverse(chroma(*pixel)));
    pixels.truncate(pixels.len().div_ceil(2));
    pixels
}

fn distance(a: [u8; 3], b: [u8; 3]) -> u32 {
    a.iter()
        .zip(b)
        .map(|(x, y)| (i32::from(*x) - i32::from(y)).unsigned_abs().pow(2))
        .sum()
}

fn chroma([r, g, b]: [u8; 3]) -> u8 {
    r.max(g).max(b) - r.min(g).min(b)
}

fn luminance([r, g, b]: [u8; 3]) -> u32 {
    (u32::from(r) * 54 + u32::from(g) * 183 + u32::from(b) * 19) / 256
}

#[cfg(test)]
mod tests {
    use image::{Rgba, RgbaImage};

    use super::corrected_color;

    /// A `size` square of `background` with a `ink` block in the middle third.
    fn page(size: u32, background: [u8; 3], ink: [u8; 3]) -> RgbaImage {
        RgbaImage::from_fn(size, size, |x, y| {
            let third = size / 3;
            let color = if (third..2 * third).contains(&x) && (third..2 * third).contains(&y) {
                ink
            } else {
                background
            };
            Rgba([color[0], color[1], color[2], 255])
        })
    }

    #[test]
    fn red_text_in_a_black_bubble_stays_red() {
        let image = page(90, [0, 0, 0], [235, 20, 30]);
        assert_eq!(corrected_color(&image, [0, 0, 90, 90], [0, 0, 0]), Some([235, 20, 30]));
    }

    #[test]
    fn red_text_on_white_snapped_to_black_comes_back_red() {
        let image = page(90, [255, 255, 255], [200, 10, 10]);
        assert_eq!(corrected_color(&image, [0, 0, 90, 90], [0, 0, 0]), Some([200, 10, 10]));
    }

    #[test]
    fn anti_aliased_edges_do_not_darken_the_color() {
        // The ink block's outer ring is half-blended into the black background.
        let image = RgbaImage::from_fn(90, 90, |x, y| {
            let inside = |m: u32| (30 + m..60 - m).contains(&x) && (30 + m..60 - m).contains(&y);
            let color = if inside(2) {
                [237, 28, 36]
            } else if inside(0) {
                [118, 14, 18]
            } else {
                [0, 0, 0]
            };
            Rgba([color[0], color[1], color[2], 255])
        });
        assert_eq!(corrected_color(&image, [0, 0, 90, 90], [0, 0, 0]), Some([237, 28, 36]));
    }

    #[test]
    fn plain_black_text_on_white_is_left_alone() {
        let image = page(90, [255, 255, 255], [10, 10, 10]);
        assert_eq!(corrected_color(&image, [0, 0, 90, 90], [0, 0, 0]), None);
    }

    #[test]
    fn white_text_on_black_is_left_alone() {
        let image = page(90, [0, 0, 0], [250, 250, 250]);
        assert_eq!(corrected_color(&image, [0, 0, 90, 90], [255, 255, 255]), None);
    }

    #[test]
    fn a_color_koharu_kept_is_left_alone() {
        let image = page(90, [255, 255, 255], [30, 60, 200]);
        assert_eq!(corrected_color(&image, [0, 0, 90, 90], [30, 60, 200]), None);
    }

    #[test]
    fn text_that_would_vanish_gets_the_measured_ink() {
        // Gray ink on black, but Koharu chose black: use the measured gray.
        let image = page(90, [0, 0, 0], [160, 160, 160]);
        assert_eq!(corrected_color(&image, [0, 0, 90, 90], [0, 0, 0]), Some([160, 160, 160]));
    }

    #[test]
    fn no_ink_found_falls_back_to_contrast() {
        let image = page(90, [5, 5, 5], [5, 5, 5]);
        assert_eq!(corrected_color(&image, [0, 0, 90, 90], [0, 0, 0]), Some([255, 255, 255]));
    }

    #[test]
    fn out_of_page_and_tiny_regions_are_skipped() {
        let image = page(30, [0, 0, 0], [235, 20, 30]);
        assert_eq!(corrected_color(&image, [40, 40, 80, 80], [0, 0, 0]), None);
        assert_eq!(corrected_color(&image, [0, 0, 4, 4], [0, 0, 0]), None);
    }
}
