//! The application: routing, sessions, signatures and response encoding.
//!
//! Ported from `reference-server/sgserver/server.py`. `Content` is leaked to `'static` at startup
//! (the server runs for the life of the process), which keeps the player service's borrows simple.

use crate::base64;
use crate::config::Config;
use crate::content::Content;
use crate::formulas::Formulas;
use crate::http::{now_secs, Handler, Request, Response};
use crate::player::PlayerService;
use crate::protocol::{self, ApiError};
use crate::rng::Rng;
use crate::storage::Store;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Mutex;

pub struct App {
    pub content: &'static Content,
    pub players: PlayerService<'static>,
    pub store: Store,
    pub config: Config,
    pub unhandled: Mutex<HashMap<String, u64>>,
    pub log_bodies: bool,
}

pub struct Ctx<'a> {
    pub app: &'a App,
    pub method: String,
    pub endpoint: String,
    pub segs: Vec<String>,
    pub query: HashMap<String, Vec<String>>,
    pub raw_query: String,
    pub body: Value,
    pub raw_body: String,
    pub user: Option<Value>,
    pub dirty: bool,
    pub set_cookie: Option<String>,
    pub rng: Rng,
}

impl<'a> Ctx<'a> {
    pub fn q(&self, key: &str) -> Option<&str> {
        self.query.get(key).and_then(|v| v.last()).map(|s| s.as_str())
    }
    pub fn q_bool(&self, key: &str, default: bool) -> bool {
        match self.q(key) {
            Some(v) => matches!(v.to_lowercase().as_str(), "1" | "true" | "yes"),
            None => default,
        }
    }
    pub fn q_i64(&self, key: &str, default: i64) -> i64 {
        self.q(key).and_then(|v| v.parse().ok()).unwrap_or(default)
    }
    pub fn save(&mut self) {
        self.dirty = true;
    }
    pub fn require_user(&self) -> Result<&Value, ApiError> {
        self.user.as_ref().ok_or_else(|| ApiError::unauthorized("no session"))
    }
    pub fn user_mut(&mut self) -> Result<&mut Value, ApiError> {
        self.user.as_mut().ok_or_else(|| ApiError::unauthorized("no session"))
    }
}

pub struct Outcome {
    pub status: u16,
    pub payload: Value,
    pub b64: bool,
    pub sign: bool,
}
pub type RouteOutcome = Outcome;

fn ok(payload: Value) -> Result<Outcome, ApiError> {
    Ok(Outcome { status: 200, payload, b64: false, sign: false })
}
fn data(v: Value) -> Value {
    json!({ "data": v })
}

impl App {
    pub fn build(content_dir: &str, db_path: &str, config: Config) -> Result<App, String> {
        let content: &'static Content = Box::leak(Box::new(Content::load(content_dir)?));
        let formulas = Formulas::new(content);
        let players = PlayerService::new(content, formulas, config.clone());
        let store = Store::open(db_path).map_err(|e| format!("cannot open store {db_path}: {e}"))?;
        Ok(App { content, players, store, config, unhandled: Mutex::new(HashMap::new()), log_bodies: false })
    }

    fn dispatch(&self, method: &str, target: &str, body_bytes: &[u8], headers_cookie: Option<&str>,
                auth_token: Option<&str>, authz: Option<&str>) -> (u16, Vec<(String, String)>, Vec<u8>) {
        let (path, raw_query) = match target.split_once('?') {
            Some((p, q)) => (p.to_string(), q.to_string()),
            None => (target.to_string(), String::new()),
        };
        let path = urldecode(&path);
        if !path.starts_with(protocol::API_PREFIX) {
            return (404, vec![], b"{}".to_vec());
        }
        let endpoint = path[protocol::API_PREFIX.len()..].trim_matches('/').to_string();
        let segs: Vec<String> = endpoint.split('/').map(urldecode).collect();
        let query = parse_query(&raw_query);
        let raw_body = String::from_utf8_lossy(body_bytes).to_string();
        let body: Value = serde_json::from_str(&raw_body).unwrap_or(Value::Null);
        let body = if body.is_object() { body } else { json!({}) };

        // session
        let token = headers_cookie.and_then(|c| cookie(c, protocol::SESSION_COOKIE));
        let user = token.as_ref().and_then(|t| self.store.session_user(t)).and_then(|uid| self.store.load_user(&uid));

        let mut ctx = Ctx {
            app: self, method: method.to_string(), endpoint: endpoint.clone(), segs,
            query, raw_query, body, raw_body, user, dirty: false, set_cookie: None, rng: Rng::new(),
        };

        let result = route(&mut ctx, auth_token, authz);
        let mut headers: Vec<(String, String)> = Vec::new();
        let (status, payload, b64, sign) = match result {
            Ok(o) => (o.status, o.payload, o.b64, o.sign),
            Err(e) => (e.status, json!({"errorCode": e.code, "message": e.message}), false, false),
        };
        if status == 200 && ctx.dirty {
            if let Some(u) = ctx.user.as_ref() {
                self.store.save_user(u);
            }
        }
        let text = serde_json::to_string(&payload).unwrap_or_else(|_| "{}".into());
        if sign {
            headers.push((protocol::SIG_HEADER.into(), protocol::response_signature(&text)));
        }
        if let Some(sc) = ctx.set_cookie {
            headers.push(("Set-Cookie".into(), sc));
        }
        let out = if b64 && status == 200 {
            base64::encode(text.as_bytes()).into_bytes()
        } else {
            text.into_bytes()
        };
        (status, headers, out)
    }
}

impl Handler for App {
    fn handle(&self, req: &Request) -> Response {
        let (status, mut headers, body) = self.dispatch(
            &req.method,
            &req.target,
            &req.body,
            req.header("Cookie"),
            req.header("X-AUTH-USER-TOKEN"),
            req.header("Authorization"),
        );
        headers.push(("Date".into(), crate::http::http_date(now_secs())));
        let mut resp = Response::new(status);
        resp.headers = headers;
        resp.body(body)
    }
}

// ---- routing ---------------------------------------------------------------------------------

fn route(ctx: &mut Ctx, auth_token: Option<&str>, authz: Option<&str>) -> Result<Outcome, ApiError> {
    let method = ctx.method.clone();
    let owned: Vec<String> = ctx.segs.clone(); // own the segments so ctx stays free to borrow mutably
    let segs: Vec<&str> = owned.iter().map(|s| s.as_str()).collect();
    match (method.as_str(), segs.as_slice()) {
        ("GET", ["ping"]) => ok(data(json!({"ok": true, "time": now_secs() as i64 * 1000}))),
        ("GET", [_store, "global_settings"]) => ok(data(crate::handlers::global_settings(ctx))),
        ("POST", ["authenticate", platform, device, region, provider]) => {
            crate::handlers::authenticate(ctx, platform, device, region, provider, auth_token, authz)
        }
        // everything below needs a session
        _ => {
            if ctx.user.is_none() {
                return Err(ApiError::unauthorized("no session"));
            }
            authed(ctx, &method, &segs)
        }
    }
}

fn authed(ctx: &mut Ctx, method: &str, segs: &[&str]) -> Result<Outcome, ApiError> {
    match (method, segs) {
        ("POST", ["user", "postLogin", "updates"]) => ok(data(json!({}))),
        ("GET", ["user"]) => {
            let mut u = ctx.user.take().unwrap();
            let d = ctx.app.players.user_data(&mut u, true);
            ctx.user = Some(u);
            ctx.save();
            ok(data(d))
        }
        ("GET", ["user", "collection"]) => {
            let mut u = ctx.user.take().unwrap();
            let d = ctx.app.players.user_data(&mut u, true);
            ctx.user = Some(u);
            ctx.save();
            ok(data(d))
        }
        ("GET", ["user", "updates"]) => ok(data(json!({}))),
        ("GET", ["acts", "campaign"]) => ok(data(crate::handlers::acts_campaign(ctx))),
        ("GET", ["challenges"]) => ok(data(json!({
            "challenges": [], "claimedRewards": [], "expirationDate": 0, "newChallengesAvailable": false
        }))),
        ("GET", ["achievements"]) => ok(data(json!({"achievements": [], "claimedRewards": []}))),
        ("GET", ["battlePass"]) => ok(data(json!({"active": false, "points": 0, "challenges": [], "tiers": []}))),
        ("GET", ["offers", _region]) => ok(data(json!({"offers": []}))),
        ("GET", ["mail", "count", "unread"]) => ok(data(json!({"count": 0}))),
        ("GET", ["mails"]) => ok(data(json!({"mails": []}))),
        ("GET", ["chat", "info"]) => ok(data(json!({"enabled": false, "channels": []}))),
        ("GET", ["events", region]) => ok(data(crate::handlers::events(ctx, region))),
        ("GET", ["store", "gachas", _region]) => ok(data(json!({"gachas": []}))),
        ("GET", ["deployments"]) => ok(data(json!({"deployments": []}))),
        ("GET", ["ads", "info"]) => ok(data(json!({"placements": []}))),
        ("GET", ["friends"]) => ok(data(json!({"friends": [], "requests": []}))),
        ("GET", ["user", "guild"]) => ok(data(json!({}))),
        ("GET", ["user", "calendars"]) => ok(data(json!({"calendars": []}))),
        ("GET", ["user", "cosmetics"]) => ok(data(json!({"portraitsOwned": [], "newPortraits": []}))),
        ("GET", ["user", "accounts"]) => ok(data(json!({"accounts": []}))),
        // Unknown endpoints are harmless: 200 {} and counted.
        _ => {
            let key = format!("{} {}", method, ctx.endpoint);
            *ctx.app.unhandled.lock().unwrap().entry(key).or_insert(0) += 1;
            ok(json!({}))
        }
    }
}

// ---- helpers ---------------------------------------------------------------------------------

fn cookie(header: &str, name: &str) -> Option<String> {
    for part in header.split(';') {
        let part = part.trim();
        if let Some((k, v)) = part.split_once('=') {
            if k == name {
                return Some(v.to_string());
            }
        }
    }
    None
}

fn parse_query(raw: &str) -> HashMap<String, Vec<String>> {
    let mut out: HashMap<String, Vec<String>> = HashMap::new();
    for pair in raw.split('&').filter(|s| !s.is_empty()) {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        out.entry(urldecode(k)).or_default().push(urldecode(v));
    }
    out
}

fn urldecode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                let hi = (b[i + 1] as char).to_digit(16);
                let lo = (b[i + 2] as char).to_digit(16);
                if let (Some(h), Some(l)) = (hi, lo) {
                    out.push((h * 16 + l) as u8);
                    i += 3;
                    continue;
                }
                out.push(b[i]);
                i += 1;
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub(crate) fn make_outcome(status: u16, payload: Value, b64: bool, sign: bool) -> Outcome {
    Outcome { status, payload, b64, sign }
}
