//! The embedded host: `libsgmserver.so`, started in the game's own process.
//!
//! A tiny `Lsgm/offline/Boot;` class in the patched APK calls `System.loadLibrary("sgmserver")` and
//! then the no-argument native `nativeStart`. We derive the APK path and a writable data directory
//! from `/proc` (so no Android `Context` has to cross the JNI boundary), read the bundled content
//! straight out of the APK, and serve the game's backend on 127.0.0.1:47820 from a background
//! thread. The call returns at once so the game keeps booting.

use sgm_core::Config;
use std::ffi::{c_void, CString};
use std::fs;
use std::os::raw::{c_char, c_int};

const PORT: u16 = 47820;
const TAG: &[u8] = b"sgm-offline\0";

#[link(name = "log")]
extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn log(msg: &str) {
    if let Ok(c) = CString::new(msg) {
        unsafe {
            __android_log_write(4 /* INFO */, TAG.as_ptr() as *const c_char, c.as_ptr());
        }
    }
}

/// `private static native void nativeStart();` on `sgm.offline.Boot`.
#[no_mangle]
pub extern "C" fn Java_sgm_offline_Boot_nativeStart(_env: *mut c_void, _class: *mut c_void) {
    // Never let a panic cross the JNI boundary.
    let _ = std::panic::catch_unwind(start);
}

fn start() {
    let pkg = package_name().unwrap_or_else(|| "com.autumn.skullgirls".to_string());
    let apk = match base_apk_path() {
        Some(p) => p,
        None => {
            log("could not locate base.apk in /proc/self/maps; server not started");
            return;
        }
    };
    let data_dir = format!("/data/data/{pkg}/files/sgm");
    if let Err(e) = fs::create_dir_all(&data_dir) {
        log(&format!("cannot create data dir {data_dir}: {e}"));
        return;
    }
    log(&format!("starting: apk={apk} data={data_dir} port={PORT}"));
    std::thread::Builder::new()
        .name("sgm-server".into())
        .spawn(move || {
            let addr = format!("127.0.0.1:{PORT}");
            match sgm_core::run_embedded(&apk, &data_dir, &addr, Config::default()) {
                Ok(()) => log("server stopped"),
                Err(e) => log(&format!("server error: {e}")),
            }
        })
        .ok();
}

/// The app's package name from /proc/self/cmdline (first NUL-terminated token, minus any ":proc").
fn package_name() -> Option<String> {
    let raw = fs::read("/proc/self/cmdline").ok()?;
    let first = raw.split(|&b| b == 0).next()?;
    let s = String::from_utf8_lossy(first);
    Some(s.split(':').next().unwrap_or("").trim().to_string()).filter(|s| !s.is_empty())
}

/// The running APK path: the first mapped file ending in "base.apk" in /proc/self/maps.
fn base_apk_path() -> Option<String> {
    let maps = fs::read_to_string("/proc/self/maps").ok()?;
    for line in maps.lines() {
        if let Some(idx) = line.find('/') {
            let path = line[idx..].trim();
            if path.ends_with("base.apk") {
                return Some(path.to_string());
            }
        }
    }
    // fall back to any mapped .apk
    for line in maps.lines() {
        if let Some(idx) = line.find('/') {
            let path = line[idx..].trim();
            if path.ends_with(".apk") {
                return Some(path.to_string());
            }
        }
    }
    None
}
