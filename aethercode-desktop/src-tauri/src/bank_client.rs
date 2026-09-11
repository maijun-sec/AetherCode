//! R249 (O-10): Rust HTTP client for the strategy-bank
//! surface (R244.2 endpoints). Symmetric with the TypeScript
//! `BankClient` shipped in `aethercode-memory@0.1.0` (R244.3)
//! and the Java `BankClient` in `aethercode-deepagents`
//! (R244.2) — same URL path, method, query string, JSON
//! shape, and 404 dual-semantics, so all three surfaces
//! (TS / Java / Rust) can talk to the same daemon bank.
//!
//! <h2>Why a separate client</h2>
//!
//! <p>The Java side already ships a `BankClient` (used by the
//! TUI's parent daemon). The TS side ships a `BankClient`
//! (used by `aethercode-tui` via the welcome banner and
//! slash commands, R245.1). This module is the third
//! instance of the same wire format, so the desktop Tauri
//! shell can show bank status without routing through the
//! daemon's WebSocket bridge.</p>
//!
//! <h2>TLS / auth (prior round)</h2>
//!
//! <p>When the daemon is configured with
//! {@code AETHERCODE_BANK_TOKEN} (prior round) and/or
//! {@code AETHERCODE_BANK_TLS_KEYSTORE} (prior round), the
//! client mirrors those flags: pass the token to the
//! constructor and the URL is {@code https://} to talk
//! TLS. We do <em>not</em> support custom CA pinning here
//! — the desktop app is built against a known internal CA
//! in production, and a self-signed cert in tests is
//! accommodated by the JVM-style "trust the system
//! keystore" default of {@code rustls-tls-webpki-roots}
//! (configured via {@code rustls-tls}).</p>
//!
//! <h2>Error model</h2>
//!
//! <p>{@link BankClientError} mirrors the TS client's
//! {@code BankClientError}: a transport failure surfaces
//! as {@code BankClientError::Transport(message)} and an
//! HTTP 4xx/5xx surfaces as {@code BankClientError::Http{
//! status, body}}. Recall-style endpoints ({@code /bank/
//! recall*} + {@code /bank/stats}) interpret a 404 as "no
//! such kind" and return an empty list, not an error —
//! this matches the JVM and TS behaviour. Write endpoints
//! ({@code /bank/touch}, {@code /bank/record-outcome})
//! propagate the 404 as a {@code Http(404, ...)} error.</p>

use serde::{Deserialize, Serialize};
use std::time::Duration;

/// One {@code ReasoningUnit} as emitted by the Java
/// {@code ReasoningUnit.toMap()} wire format (R244.2 +
/// R244.1 self-eval fields).
///
/// <p>The wire format is camelCase (matching the JVM
/// {@code toMap()} output, which is what the TS
/// client and the Rust client both consume). We
/// override the Rust default snake_case via
/// {@code #[serde(rename_all = "camelCase")]} so
/// {@code task_kind} maps to {@code taskKind} on the
/// wire — without this, R249 round-trips fail at
/// the first non-`id` field with a "missing field"
/// panic during deserialisation.</p>
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BankUnit {
    pub id: String,
    pub task_kind: String,
    pub error_pattern: String,
    pub fix_strategy: String,
    #[serde(default)]
    pub example: String,
    pub utility: f64,
    pub uses: u64,
    pub ok_count: u64,
    pub not_ok_count: u64,
    pub created_at: String,
}

/// Bank stats snapshot emitted by {@code GET /bank/stats}.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct BankStats {
    pub size: u64,
    pub kinds: Vec<String>,
    #[serde(default)]
    pub per_kind: std::collections::HashMap<String, u64>,
    pub total_ok: u64,
    pub total_not_ok: u64,
}

/// All failures from a {@link BankClient} call. Split into
/// transport (no HTTP) and HTTP (response was 4xx/5xx) so
/// the caller's error handling stays branch-free: a
/// "daemon is down" surfaces as {@code Transport} and a
/// "wrong token" surfaces as {@code Http(401, ...)} —
/// matching the TS {@code BankClientError.status} field.
#[derive(Debug, thiserror::Error)]
pub enum BankClientError {
    #[error("bank client transport error: {0}")]
    Transport(String),
    #[error("bank client HTTP {status}: {body}")]
    Http { status: u16, body: String },
}

/// Async HTTP client for the bank surface. Cheap to clone
/// (the underlying {@code reqwest::Client} is an
/// {@code Arc} internally), so passing it around the
/// Tauri state is essentially free.
#[derive(Debug, Clone)]
pub struct BankClient {
    base_url: String,
    http: reqwest::Client,
    auth_token: Option<String>,
}

impl BankClient {
    /// Build a client targeting {@code base_url}. The URL
    /// is normalised to strip a single trailing slash so
    /// downstream paths can be concatenated as
    /// {@code {base}/bank/...}. A blank or missing token
    /// is treated as "no auth" (R244.2 default).
    pub fn new(base_url: impl Into<String>, auth_token: Option<String>) -> Self {
        let raw = base_url.into();
        let base_url = if raw.ends_with('/') {
            raw[..raw.len() - 1].to_string()
        } else {
            raw
        };
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(2))
            .timeout(Duration::from_secs(10))
            .build()
            .expect("reqwest client builder is infallible");
        let auth_token = auth_token.and_then(|t| {
            if t.trim().is_empty() {
                None
            } else {
                Some(t)
            }
        });
        BankClient { base_url, http, auth_token }
    }

    /// Cheap liveness probe. Returns {@code true} on HTTP
    /// 200 within the request timeout, {@code false} on
    /// any other status or network error.
    pub async fn ping(&self) -> bool {
        let url = format!("{}/healthz", self.base_url);
        match self.http.get(&url).send().await {
            Ok(r) => r.status().as_u16() == 200,
            Err(_) => false,
        }
    }

    /// Top-N units for a given task kind. Returns
    /// {@code Ok(vec![])} when the server replies 404 (no
    /// such kind) — matches the JVM and TS behaviour so
    /// the TUI / desktop callers can render "no
    /// experience yet" without try/catch noise.
    pub async fn recall_for(&self, kind: &str, n: u32) -> Result<Vec<BankUnit>, BankClientError> {
        if kind.is_empty() {
            return Err(BankClientError::Transport("kind must be non-empty".into()));
        }
        let url = format!("{}/bank/recall?kind={}&n={}", self.base_url, urlencode(kind), n);
        let body = self.get_json(&url).await?;
        parse_units(&body)
    }

    /// Cross-kind top-N. Same 404 semantics as
    /// {@link recall_for}.
    pub async fn recall_all_kinds(&self, n: u32) -> Result<Vec<BankUnit>, BankClientError> {
        let url = format!("{}/bank/recall-all-kinds?n={}", self.base_url, n);
        let body = self.post_empty(&url).await?;
        parse_units(&body)
    }

    /// Bump uses/utility for a unit by id. Returns the
    /// updated wire record. 404 here is an error
    /// (the user asked to touch a specific id).
    pub async fn touch(&self, id: &str) -> Result<BankUnit, BankClientError> {
        if id.is_empty() {
            return Err(BankClientError::Transport("id must be non-empty".into()));
        }
        let url = format!("{}/bank/touch?id={}", self.base_url, urlencode(id));
        let body = self.post_empty(&url).await?;
        parse_unit(&body)
    }

    /// R244.1 self-eval feedback: record an ok / notOk
    /// outcome for a unit. 404 propagates as an error.
    pub async fn record_outcome(&self, id: &str, ok: bool) -> Result<BankUnit, BankClientError> {
        if id.is_empty() {
            return Err(BankClientError::Transport("id must be non-empty".into()));
        }
        let url = format!("{}/bank/record-outcome?id={}&ok={}", self.base_url, urlencode(id), ok);
        let body = self.post_empty(&url).await?;
        parse_unit(&body)
    }

    /// Snapshot of bank stats: total size, per-kind counts,
    /// total ok / notOk. Used by the desktop status bar
    /// (R250+ wiring) and any /memory-audit-style command.
    pub async fn stats(&self) -> Result<BankStats, BankClientError> {
        let url = format!("{}/bank/stats", self.base_url);
        let body = self.get_json(&url).await?;
        serde_json::from_value(body).map_err(|e| {
            BankClientError::Transport(format!("malformed stats response: {}", e))
        })
    }

    // --- internals ---

    async fn get_json(&self, url: &str) -> Result<serde_json::Value, BankClientError> {
        let mut req = self.http.get(url).header("Accept", "application/json");
        if let Some(token) = &self.auth_token {
            req = req.header("Authorization", format!("Bearer {}", token));
        }
        let resp = req.send().await.map_err(|e| {
            BankClientError::Transport(format!("connect error: {}", e))
        })?;
        let status = resp.status().as_u16();
        let text = resp.text().await.unwrap_or_default();
        if status == 404 {
            // Recall-style: caller treats this as "no such kind".
            return Ok(serde_json::json!({"__notFound": true, "body": text}));
        }
        if !(200..300).contains(&status) {
            return Err(BankClientError::Http {
                status,
                body: if text.is_empty() { "empty error body".into() } else { text },
            });
        }
        if text.is_empty() {
            return Ok(serde_json::json!({}));
        }
        serde_json::from_str(&text)
            .map_err(|e| BankClientError::Transport(format!("decode error: {}", e)))
    }

    async fn post_empty(&self, url: &str) -> Result<serde_json::Value, BankClientError> {
        let mut req = self.http
            .post(url)
            .header("Accept", "application/json")
            .body(Vec::<u8>::new());
        if let Some(token) = &self.auth_token {
            req = req.header("Authorization", format!("Bearer {}", token));
        }
        let resp = req.send().await.map_err(|e| {
            BankClientError::Transport(format!("connect error: {}", e))
        })?;
        let status = resp.status().as_u16();
        let text = resp.text().await.unwrap_or_default();
        if status == 404 {
            return Ok(serde_json::json!({"__notFound": true, "body": text}));
        }
        if !(200..300).contains(&status) {
            return Err(BankClientError::Http {
                status,
                body: if text.is_empty() { "empty error body".into() } else { text },
            });
        }
        if text.is_empty() {
            return Ok(serde_json::json!({}));
        }
        serde_json::from_str(&text)
            .map_err(|e| BankClientError::Transport(format!("decode error: {}", e)))
    }
}

fn parse_units(body: &serde_json::Value) -> Result<Vec<BankUnit>, BankClientError> {
    if let Some(b) = body.as_object() {
        if b.get("__notFound").and_then(|v| v.as_bool()) == Some(true) {
            return Ok(Vec::new());
        }
    }
    let list = body.get("units").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    list.into_iter()
        .map(|u| serde_json::from_value(u).map_err(|e| BankClientError::Transport(format!("bad unit: {}", e))))
        .collect()
}

fn parse_unit(body: &serde_json::Value) -> Result<BankUnit, BankClientError> {
    if let Some(b) = body.as_object() {
        if b.get("__notFound").and_then(|v| v.as_bool()) == Some(true) {
            return Err(BankClientError::Http { status: 404, body: "unknown id".into() });
        }
    }
    let unit = body.get("unit").cloned().unwrap_or_else(|| body.clone());
    serde_json::from_value(unit)
        .map_err(|e| BankClientError::Transport(format!("bad unit: {}", e)))
}

fn urlencode(s: &str) -> String {
    // Minimal URL-encode for the few characters that
    // appear in taskKind / id. Same encoding the JVM
    // server's URLEncoder (UTF-8) produces.
    let mut out = String::with_capacity(s.len());
    for byte in s.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char);
            }
            _ => out.push_str(&format!("%{:02X}", byte)),
        }
    }
    out
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urlencode_handles_special_chars() {
        assert_eq!(urlencode("file_edit"), "file_edit");
        assert_eq!(urlencode("a/b c"), "a%2Fb%20c");
        assert_eq!(urlencode("中文"), "%E4%B8%AD%E6%96%87");
    }

    #[test]
    fn bankunit_round_trips_through_json() {
        let u = BankUnit {
            id: "u1".into(),
            task_kind: "file_edit".into(),
            error_pattern: "perm".into(),
            fix_strategy: "mkdir -p first".into(),
            example: "".into(),
            utility: 0.85,
            uses: 3,
            ok_count: 2,
            not_ok_count: 0,
            created_at: "2026-09-10T00:00:00Z".into(),
        };
        let s = serde_json::to_string(&u).unwrap();
        let back: BankUnit = serde_json::from_str(&s).unwrap();
        assert_eq!(u, back);
    }

    #[test]
    fn bankstats_round_trips_through_json() {
        let s = BankStats {
            size: 12,
            kinds: vec!["file_edit".into(), "build".into()],
            per_kind: [("file_edit".into(), 8), ("build".into(), 4)]
                .into_iter()
                .collect(),
            total_ok: 9,
            total_not_ok: 1,
        };
        let j = serde_json::to_string(&s).unwrap();
        let back: BankStats = serde_json::from_str(&j).unwrap();
        assert_eq!(s, back);
    }

    #[test]
    fn parse_units_empty_for_not_found() {
        let v = serde_json::json!({"__notFound": true, "body": ""});
        let out = parse_units(&v).unwrap();
        assert!(out.is_empty());
    }

    #[test]
    fn parse_units_reads_units_array() {
        let v = serde_json::json!({
            "units": [
                {"id":"u1","taskKind":"file_edit","errorPattern":"e","fixStrategy":"f","example":"",
                 "utility":0.5,"uses":0,"okCount":0,"notOkCount":0,"createdAt":"2026-09-10T00:00:00Z"}
            ]
        });
        let out = parse_units(&v).unwrap();
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].id, "u1");
    }

    #[test]
    fn parse_unit_throws_on_not_found() {
        let v = serde_json::json!({"__notFound": true});
        let err = parse_unit(&v).unwrap_err();
        match err {
            BankClientError::Http { status, .. } => assert_eq!(status, 404),
            other => panic!("expected Http 404, got {:?}", other),
        }
    }

    #[test]
    fn new_strips_trailing_slash() {
        let c = BankClient::new("http://127.0.0.1:7777/", None);
        assert_eq!(c.base_url, "http://127.0.0.1:7777");
    }

    #[test]
    fn new_blank_token_falls_back_to_no_auth() {
        let c = BankClient::new("http://127.0.0.1:7777", Some("   ".into()));
        assert!(c.auth_token.is_none());
    }
}
