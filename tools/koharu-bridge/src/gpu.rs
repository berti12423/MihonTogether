//! The NVIDIA GPU and its memory, read once at startup with `nvidia-smi` (it
//! ships with the driver). The website uses it to say which translation models
//! fit; without an NVIDIA GPU (or the tool) there is no GPU to report.

use std::time::Duration;

use serde::Serialize;

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Gpu {
    pub name: String,
    /// Total graphics memory in MiB.
    pub vram_mb: u64,
}

pub async fn detect() -> Option<Gpu> {
    let query = tokio::process::Command::new("nvidia-smi")
        .args(["--query-gpu=name,memory.total", "--format=csv,noheader,nounits"])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true)
        .output();
    match tokio::time::timeout(Duration::from_secs(5), query).await {
        Ok(Ok(output)) if output.status.success() => parse(&String::from_utf8_lossy(&output.stdout)),
        _ => None,
    }
}

/// The GPU with the most memory from lines of `<name>, <MiB>`.
fn parse(output: &str) -> Option<Gpu> {
    output
        .lines()
        .filter_map(|line| {
            let (name, memory) = line.trim().rsplit_once(',')?;
            let vram_mb = memory.trim().parse::<u64>().ok().filter(|mb| *mb > 0)?;
            let name = name.trim();
            (!name.is_empty()).then(|| Gpu { name: name.to_owned(), vram_mb })
        })
        .max_by_key(|gpu| gpu.vram_mb)
}

#[cfg(test)]
mod tests {
    use super::{Gpu, parse};

    #[test]
    fn reads_nvidia_smi_lines() {
        assert_eq!(
            parse("NVIDIA GeForce RTX 5070 Ti, 16303\r\n"),
            Some(Gpu { name: "NVIDIA GeForce RTX 5070 Ti".into(), vram_mb: 16303 })
        );
    }

    #[test]
    fn picks_the_biggest_of_several_gpus() {
        let gpu = parse("NVIDIA GeForce GTX 1650, 4096\nNVIDIA GeForce RTX 3090, 24576\n").unwrap();
        assert_eq!(gpu.vram_mb, 24576);
    }

    #[test]
    fn garbage_is_no_gpu() {
        assert_eq!(parse(""), None);
        assert_eq!(parse("No devices were found"), None);
        assert_eq!(parse("Card, [N/A]"), None);
    }
}
