//! Read-only game content loaded from the directory produced by `tools/content-gen`.
//!
//! Ported from `reference-server/sgserver/content.py`. Every object is a JSON dict with `_class`,
//! `_name`, `_key` ("file:pathID"); references between objects use the same "file:pathID" string.

use serde_json::Value;
use std::collections::HashMap;
use std::fs;
use std::path::Path;

pub struct Content {
    pub objs: HashMap<String, Value>,
    by_class: HashMap<String, Vec<String>>,
    by_guid: HashMap<String, String>,
    by_name: HashMap<String, HashMap<String, String>>, // class -> name -> key
    // campaign graph
    act_chapters: HashMap<String, Vec<String>>, // act guid -> chapter keys
}

impl Content {
    /// Load from a directory of per-class JSON files (what `tools/content-gen` writes).
    pub fn load(dir: &str) -> Result<Content, String> {
        let p = Path::new(dir);
        if !p.is_dir() {
            // allow pointing --content directly at a combined .json file
            if p.is_file() {
                let text = fs::read_to_string(p).map_err(|e| e.to_string())?;
                return Content::from_combined_str(&text);
            }
            return Err(format!("content directory not found: {dir}"));
        }
        let mut files: Vec<_> = fs::read_dir(p)
            .map_err(|e| e.to_string())?
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.extension().map(|x| x == "json").unwrap_or(false))
            .collect();
        files.sort();
        let mut merged: HashMap<String, Value> = HashMap::new();
        for f in files {
            let text = fs::read_to_string(&f).map_err(|e| e.to_string())?;
            let map: HashMap<String, Value> =
                serde_json::from_str(&text).map_err(|e| format!("{}: {e}", f.display()))?;
            merged.extend(map);
        }
        Ok(Content::from_map(merged))
    }

    /// Load from one combined JSON object `{ "file:pathID": {record}, ... }` (what the embedded
    /// host reads from the APK). Bytes, not a path.
    pub fn from_combined_bytes(bytes: &[u8]) -> Result<Content, String> {
        let map: HashMap<String, Value> = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
        Ok(Content::from_map(map))
    }
    pub fn from_combined_str(text: &str) -> Result<Content, String> {
        let map: HashMap<String, Value> = serde_json::from_str(text).map_err(|e| e.to_string())?;
        Ok(Content::from_map(map))
    }

    fn from_map(map: HashMap<String, Value>) -> Content {
        let mut objs = HashMap::new();
        let mut by_class: HashMap<String, Vec<String>> = HashMap::new();
        let mut by_guid = HashMap::new();
        let mut by_name: HashMap<String, HashMap<String, String>> = HashMap::new();
        for (key, mut rec) in map {
            if let Value::Object(ref mut o) = rec {
                o.insert("_key".into(), Value::String(key.clone()));
                let cls = o.get("_class").and_then(|v| v.as_str()).unwrap_or("").to_string();
                by_class.entry(cls.clone()).or_default().push(key.clone());
                if let Some(name) = o.get("_name").and_then(|v| v.as_str()) {
                    if !name.is_empty() {
                        by_name.entry(cls.clone()).or_default()
                            .entry(name.to_string()).or_insert_with(|| key.clone());
                    }
                }
                if let Some(g) = o.get("guid").and_then(|v| v.as_str()) {
                    if !g.is_empty() {
                        by_guid.entry(g.to_string()).or_insert_with(|| key.clone());
                    }
                }
            }
            objs.insert(key, rec);
        }
        // by_class order must be deterministic (dir load sorts files); sort keys for stability.
        for v in by_class.values_mut() {
            v.sort();
        }
        let mut c = Content { objs, by_class, by_guid, by_name, act_chapters: HashMap::new() };
        c.index_campaign();
        c
    }

    /// Resolve a "file:pathID" reference.
    pub fn get(&self, reference: &str) -> Option<&Value> {
        if reference.is_empty() {
            return None;
        }
        self.objs.get(reference)
    }
    pub fn guid(&self, guid: &str) -> Option<&Value> {
        self.by_guid.get(guid).and_then(|k| self.objs.get(k))
    }
    pub fn by_name(&self, class: &str, name: &str) -> Option<&Value> {
        self.by_name.get(class).and_then(|m| m.get(name)).and_then(|k| self.objs.get(k))
    }
    pub fn all(&self, class: &str) -> impl Iterator<Item = &Value> {
        self.by_class
            .get(class)
            .into_iter()
            .flat_map(move |keys| keys.iter().filter_map(move |k| self.objs.get(k)))
    }
    pub fn single(&self, class: &str) -> Option<&Value> {
        self.by_class.get(class).and_then(|v| v.first()).and_then(|k| self.objs.get(k))
    }
    pub fn game_settings(&self) -> &Value {
        self.single("GameSettings").expect("no GameSettings in content")
    }
    pub fn economy(&self) -> &Value {
        self.single("EconomySettings").expect("no EconomySettings in content")
    }

    fn index_campaign(&mut self) {
        let mut act_chapters: HashMap<String, Vec<String>> = HashMap::new();
        let act_keys: Vec<String> =
            self.by_class.get("Act").cloned().unwrap_or_default();
        for ak in act_keys {
            let act = match self.objs.get(&ak) {
                Some(a) => a,
                None => continue,
            };
            let guid = act.get("guid").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let mut chapters: Vec<String> = Vec::new();
            if let Some(refs) = act.get("chapters").and_then(|v| v.as_array()) {
                for r in refs {
                    if let Some(rs) = r.as_str() {
                        if self.objs.contains_key(rs) && !chapters.contains(&rs.to_string()) {
                            chapters.push(rs.to_string());
                        }
                    }
                }
            }
            if let Some(ress) = act.get("chapterResources").and_then(|v| v.as_array()) {
                for res in ress {
                    let rp = res.get("resourcePath").and_then(|v| v.as_str()).unwrap_or("");
                    let last = rp.rsplit('/').next().unwrap_or("");
                    if let Some(key) = self.by_name.get("Chapter").and_then(|m| m.get(last)) {
                        if !chapters.contains(key) {
                            chapters.push(key.clone());
                        }
                    }
                }
            }
            act_chapters.insert(guid, chapters);
        }
        self.act_chapters = act_chapters;
    }

    pub fn chapters_of(&self, act_guid: &str) -> Vec<&Value> {
        self.act_chapters
            .get(act_guid)
            .into_iter()
            .flat_map(|keys| keys.iter().filter_map(|k| self.objs.get(k)))
            .collect()
    }
    pub fn refs_to_objs(&self, refs: Option<&Value>) -> Vec<&Value> {
        refs.and_then(|v| v.as_array())
            .into_iter()
            .flatten()
            .filter_map(|r| r.as_str())
            .filter_map(|r| self.objs.get(r))
            .collect()
    }
}
