//! Persistence: one JSON document per player, plus device bindings, sessions and accounts.
//!
//! File-based (no database dependency, so the embedded `.so` stays pure Rust + Bionic). The store
//! directory holds `users/<id>.json` and small index files. All access is guarded by one mutex;
//! writes are atomic (temp file + rename).

use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub struct Store {
    dir: PathBuf,
    inner: Mutex<Index>,
}

#[derive(Default)]
struct Index {
    devices: HashMap<String, String>, // device_id -> user_id
    sessions: HashMap<String, String>, // token -> user_id
    accounts: HashMap<String, (String, String)>, // email -> (password, user_id)
}

fn write_atomic(path: &Path, data: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(data)?;
        f.sync_all().ok();
    }
    fs::rename(&tmp, path)
}

impl Store {
    pub fn open(dir: &str) -> std::io::Result<Store> {
        let dir = PathBuf::from(dir);
        fs::create_dir_all(dir.join("users"))?;
        let mut idx = Index::default();
        for (name, map) in [("devices.json", 0), ("sessions.json", 1), ("accounts.json", 2)] {
            let p = dir.join(name);
            if let Ok(text) = fs::read_to_string(&p) {
                if let Ok(v) = serde_json::from_str::<Value>(&text) {
                    if let Some(o) = v.as_object() {
                        for (k, val) in o {
                            match map {
                                0 => { idx.devices.insert(k.clone(), val.as_str().unwrap_or("").into()); }
                                1 => { idx.sessions.insert(k.clone(), val.as_str().unwrap_or("").into()); }
                                _ => {
                                    let pw = val.get("password").and_then(|x| x.as_str()).unwrap_or("").to_string();
                                    let uid = val.get("user_id").and_then(|x| x.as_str()).unwrap_or("").to_string();
                                    idx.accounts.insert(k.clone(), (pw, uid));
                                }
                            }
                        }
                    }
                }
            }
        }
        Ok(Store { dir, inner: Mutex::new(idx) })
    }

    fn persist_devices(&self, idx: &Index) {
        let v: Value = idx.devices.iter().map(|(k, u)| (k.clone(), json!(u))).collect::<serde_json::Map<_, _>>().into();
        let _ = write_atomic(&self.dir.join("devices.json"), v.to_string().as_bytes());
    }
    fn persist_sessions(&self, idx: &Index) {
        let v: Value = idx.sessions.iter().map(|(k, u)| (k.clone(), json!(u))).collect::<serde_json::Map<_, _>>().into();
        let _ = write_atomic(&self.dir.join("sessions.json"), v.to_string().as_bytes());
    }
    fn persist_accounts(&self, idx: &Index) {
        let v: Value = idx.accounts.iter().map(|(k, (p, u))| (k.clone(), json!({"password": p, "user_id": u}))).collect::<serde_json::Map<_, _>>().into();
        let _ = write_atomic(&self.dir.join("accounts.json"), v.to_string().as_bytes());
    }

    pub fn load_user(&self, user_id: &str) -> Option<Value> {
        let text = fs::read_to_string(self.dir.join("users").join(format!("{user_id}.json"))).ok()?;
        serde_json::from_str(&text).ok()
    }
    pub fn save_user(&self, user: &Value) {
        if let Some(id) = user.get("id").and_then(|v| v.as_str()) {
            let _ = write_atomic(&self.dir.join("users").join(format!("{id}.json")), user.to_string().as_bytes());
        }
    }
    pub fn user_for_device(&self, device_id: &str) -> Option<String> {
        self.inner.lock().unwrap().devices.get(device_id).cloned()
    }
    pub fn bind_device(&self, device_id: &str, user_id: &str) {
        let mut idx = self.inner.lock().unwrap();
        idx.devices.insert(device_id.into(), user_id.into());
        self.persist_devices(&idx);
    }
    pub fn new_session(&self, user_id: &str, token: String) -> String {
        let mut idx = self.inner.lock().unwrap();
        idx.sessions.insert(token.clone(), user_id.into());
        self.persist_sessions(&idx);
        token
    }
    pub fn session_user(&self, token: &str) -> Option<String> {
        self.inner.lock().unwrap().sessions.get(token).cloned()
    }
    pub fn account(&self, email: &str) -> Option<(String, String)> {
        self.inner.lock().unwrap().accounts.get(&email.to_lowercase()).cloned()
    }
    pub fn set_account(&self, email: &str, password: &str, user_id: &str) {
        let mut idx = self.inner.lock().unwrap();
        idx.accounts.insert(email.to_lowercase(), (password.into(), user_id.into()));
        self.persist_accounts(&idx);
    }
}
