//! File sizes of Koharu's local translation models at the pinned commit (the default
//! quantization and the vision projector), read from Hugging Face at each model's pinned
//! revision on 2026-10-14. The website uses them to tell which models fit the GPU.

/// `(model id, model file MiB, vision projector MiB)`.
pub const MODEL_SIZES: &[(&str, u64, u64)] = &[
    ("lfm2.5-1.2b-instruct", 698, 0),
    ("ministral-3-8b-instruct", 4959, 0),
    ("gemma4-e2b-it", 2499, 940),
    ("gemma4-e4b-it", 4021, 945),
    ("gemma4-12b-it", 6406, 168),
    ("gemma4-26b-a4b-it", 13589, 1138),
    ("gemma4-31b-it", 16487, 1144),
    ("gemma4-e2b-uncensored", 3291, 940),
    ("gemma4-e4b-uncensored", 5121, 945),
    ("gemma4-12b-uncensored", 7040, 168),
    ("gemma4-26b-a4b-uncensored", 16018, 1140),
    ("gemma4-31b-uncensored", 17822, 1146),
    ("qwen3.5-0.8b", 533, 196),
    ("qwen3.5-2b", 1278, 638),
    ("qwen3.5-4b", 2778, 642),
    ("qwen3.5-9b", 5690, 876),
    ("qwen3.5-27b", 16805, 885),
    ("qwen3.5-35b-a3b", 21212, 858),
    ("qwen3.6-27b", 16797, 885),
    ("qwen3.6-35b-a3b", 21325, 858),
    ("qwen3.8-27b", 17094, 885),
    ("qwen3.5-2b-uncensored", 1212, 638),
    ("qwen3.5-4b-uncensored", 2583, 645),
    ("qwen3.5-9b-uncensored", 5367, 880),
    ("qwen3.6-27b-uncensored", 16724, 885),
    ("qwen3.6-35b-a3b-uncensored", 22340, 858),
    ("qwen3.8-27b-uncensored", 17094, 889),
];

/// Sizes for `id`, if known: `(model MiB, projector MiB)`.
pub fn sizes(id: &str) -> Option<(u64, u64)> {
    MODEL_SIZES.iter().find(|(known, _, _)| *known == id).map(|(_, model, projector)| (*model, *projector))
}

#[cfg(test)]
mod tests {
    #[test]
    fn the_default_model_is_listed() {
        let (model, projector) = super::sizes("gemma4-12b-it").unwrap();
        assert!((6_000..7_000).contains(&model) && projector > 0);
        assert_eq!(super::sizes("nope"), None);
    }
}
