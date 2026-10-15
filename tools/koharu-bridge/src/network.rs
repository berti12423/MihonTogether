//! Windows network profiles. On a network Windows calls "Public", the firewall
//! keeps phones out of the phone port unless the translator was allowed on
//! public networks, and the phone only sees a timeout. Startup names those
//! networks so the console can say what to change.

use std::time::Duration;

/// How long the one-time profile lookup may take before it is skipped.
const LOOKUP_TIMEOUT: Duration = Duration::from_secs(5);

/// Names of the connected networks whose Windows profile is Public. Empty when
/// none are, off Windows, or when the lookup fails (it is only a hint).
pub async fn public_networks() -> Vec<String> {
    if !cfg!(windows) {
        return Vec::new();
    }
    let lookup = tokio::process::Command::new("powershell.exe")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "Get-NetConnectionProfile | ForEach-Object { \"$($_.NetworkCategory)`t$($_.Name)\" }",
        ])
        .stdin(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true)
        .output();
    match tokio::time::timeout(LOOKUP_TIMEOUT, lookup).await {
        Ok(Ok(output)) if output.status.success() => {
            parse_public(&String::from_utf8_lossy(&output.stdout))
        }
        _ => Vec::new(),
    }
}

/// Parses `<category>\t<name>` lines and keeps the names of Public networks.
fn parse_public(output: &str) -> Vec<String> {
    let mut names: Vec<String> = output
        .lines()
        .filter_map(|line| {
            let (category, name) = line.trim_end_matches('\r').split_once('\t')?;
            category
                .trim()
                .eq_ignore_ascii_case("public")
                .then(|| name.trim().to_owned())
        })
        .map(|name| if name.is_empty() { "this network".to_owned() } else { name })
        .collect();
    names.dedup();
    names
}

#[cfg(test)]
mod tests {
    use super::parse_public;

    #[test]
    fn keeps_public_networks_only() {
        let output = "Public\tNetwork\r\nPrivate\tHome Wi-Fi\r\nDomainAuthenticated\tWork\r\n";
        assert_eq!(parse_public(output), vec!["Network"]);
    }

    #[test]
    fn nothing_public_is_empty() {
        assert!(parse_public("Private\tHome\n").is_empty());
        assert!(parse_public("").is_empty());
        assert!(parse_public("garbage without a tab\n").is_empty());
    }

    #[test]
    fn unnamed_public_network_gets_a_label() {
        assert_eq!(parse_public("Public\t\n"), vec!["this network"]);
    }

    #[test]
    fn several_public_networks_are_listed() {
        assert_eq!(
            parse_public("Public\tCafe\nPublic\tHotspot\n"),
            vec!["Cafe", "Hotspot"]
        );
    }
}
