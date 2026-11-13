//! Server configuration, mirroring `reference-server`'s DEFAULT_CONFIG.

use serde_json::{json, Map, Value};

#[derive(Clone)]
pub struct Config(pub Value);

impl Default for Config {
    fn default() -> Self {
        Config(json!({
            "skip_tutorials": true,
            "starting_currencies": {"SoftCurrency": 50000, "HardCurrency": 500, "DailyEventTicket": 4},
            "extra_starting_characters": [],
            "collection_capacity": 1000,
            "xp_rate": 1.0,
            "currency_rate": 1.0,
            "player_xp_per_enemy_fs": 0.25,
            "country_code": "US",
            "lta_events": "rotate",
            "lta_sets_per_day": 3,
            "auto_register_password_accounts": true,
            "allow_implicit_signup": true
        }))
    }
}

impl Config {
    /// Merge a JSON object over the defaults.
    pub fn merge(&mut self, over: &Value) {
        if let (Value::Object(base), Value::Object(o)) = (&mut self.0, over) {
            for (k, v) in o {
                base.insert(k.clone(), v.clone());
            }
        }
    }

    pub fn get<'a>(&'a self, key: &str) -> Option<&'a Value> {
        self.0.get(key)
    }
    pub fn bool(&self, key: &str, default: bool) -> bool {
        self.get(key).and_then(|v| v.as_bool()).unwrap_or(default)
    }
    pub fn i64(&self, key: &str, default: i64) -> i64 {
        self.get(key).and_then(|v| v.as_i64()).unwrap_or(default)
    }
    pub fn f64(&self, key: &str, default: f64) -> f64 {
        self.get(key).and_then(|v| v.as_f64()).unwrap_or(default)
    }
    pub fn str<'a>(&'a self, key: &str, default: &'a str) -> &'a str {
        self.get(key).and_then(|v| v.as_str()).unwrap_or(default)
    }
    pub fn starting_currencies(&self) -> Map<String, Value> {
        self.get("starting_currencies")
            .and_then(|v| v.as_object())
            .cloned()
            .unwrap_or_default()
    }
}
