//! sgm-core: the offline Skullgirls Mobile server core, shared by the standalone and embedded hosts.
//!
//! A faithful port of `reference-server` (the Python server), gated against it by
//! `reference-server/tests/test_flow.py` through the `SGMO_SERVER_CMD` harness.
#![recursion_limit = "512"]

pub mod apkzip;
pub mod app;
pub mod base64;
pub mod config;
pub mod content;
pub mod formulas;
pub mod handlers;
pub mod http;
pub mod player;
pub mod protocol;
pub mod rewards;
pub mod rng;
pub mod sha256;
pub mod skilltree;
pub mod storage;

pub use app::App;
pub use config::Config;

use std::sync::Arc;

/// Build the app and serve forever on `addr` (blocks). Used by the standalone host.
pub fn run(content_dir: &str, db_path: &str, addr: &str, config: Config) -> Result<(), String> {
    let app = App::build(content_dir, db_path, config)?;
    let handler: Arc<dyn http::Handler> = Arc::new(app);
    http::serve(addr, handler).map_err(|e| format!("serve failed: {e}"))
}

/// Build the app reading its content straight from an APK's stored `assets/sgm-content.json` entry
/// (so nothing is extracted to disk), saving under `data_dir`, and serve forever on `addr`. Used by
/// the embedded host.
pub fn run_embedded(apk_path: &str, data_dir: &str, addr: &str, config: Config) -> Result<(), String> {
    let bytes = apkzip::read_stored_entry(apk_path, "assets/sgm-content.json")
        .ok_or_else(|| format!("assets/sgm-content.json not found (stored) in {apk_path}"))?;
    let content: &'static content::Content =
        Box::leak(Box::new(content::Content::from_combined_bytes(&bytes)?));
    let formulas = formulas::Formulas::new(content);
    let players = player::PlayerService::new(content, formulas, config.clone());
    let store = storage::Store::open(data_dir).map_err(|e| format!("cannot open store {data_dir}: {e}"))?;
    let app = App {
        content,
        players,
        store,
        config,
        unhandled: std::sync::Mutex::new(std::collections::HashMap::new()),
        log_bodies: false,
    };
    let handler: Arc<dyn http::Handler> = Arc::new(app);
    http::serve(addr, handler).map_err(|e| format!("serve failed: {e}"))
}
