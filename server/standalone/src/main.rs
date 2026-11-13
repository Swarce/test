//! The standalone server: the core as a PC process. Used for the dev loop and as the parity target
//! driven by `reference-server/tests/test_flow.py` via `SGMO_SERVER_CMD`.
//!
//!   sgm-standalone --content <dir> --db <dir> [--host 127.0.0.1] [--port 47820]

use sgm_core::{app::App, http, Config};
use std::sync::Arc;

fn arg(args: &[String], name: &str, default: &str) -> String {
    args.iter()
        .position(|a| a == name)
        .and_then(|i| args.get(i + 1))
        .cloned()
        .unwrap_or_else(|| default.to_string())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let content = arg(&args, "--content", "content");
    let db = arg(&args, "--db", "sgserver-data");
    let host = arg(&args, "--host", "127.0.0.1");
    let port = arg(&args, "--port", "47820");
    let mut config = Config::default();
    if let Some(i) = args.iter().position(|a| a == "--config") {
        if let Some(path) = args.get(i + 1) {
            if let Ok(text) = std::fs::read_to_string(path) {
                if let Ok(v) = serde_json::from_str(&text) {
                    config.merge(&v);
                }
            }
        }
    }
    let addr = format!("{host}:{port}");
    // --apk <path> exercises the embedded host's content-from-APK path (for parity testing on a PC).
    let apk = arg(&args, "--apk", "");
    if !apk.is_empty() {
        eprintln!("sgm-standalone (embedded mode) on http://{addr} (apk={apk}, db={db})");
        if let Err(e) = sgm_core::run_embedded(&apk, &db, &addr, config) {
            eprintln!("serve failed: {e}");
            std::process::exit(1);
        }
        return;
    }
    let app = match App::build(&content, &db, config) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("startup error: {e}");
            std::process::exit(1);
        }
    };
    eprintln!("sgm-standalone listening on http://{addr} (content={content}, db={db})");
    let handler: Arc<dyn http::Handler> = Arc::new(app);
    if let Err(e) = http::serve(&addr, handler) {
        eprintln!("serve failed: {e}");
        std::process::exit(1);
    }
}
