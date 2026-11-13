//! sgm-core: the offline Skullgirls Mobile server core, shared by the standalone and embedded hosts.
//!
//! A faithful port of `reference-server` (the Python server), gated against it by
//! `reference-server/tests/test_flow.py` through the `SGMO_SERVER_CMD` harness.
#![recursion_limit = "512"]

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

/// Build the app and serve forever on `addr` (blocks). Used by both hosts.
pub fn run(content_dir: &str, db_path: &str, addr: &str, config: Config) -> Result<(), String> {
    let app = App::build(content_dir, db_path, config)?;
    let handler: Arc<dyn http::Handler> = Arc::new(app);
    http::serve(addr, handler).map_err(|e| format!("serve failed: {e}"))
}
