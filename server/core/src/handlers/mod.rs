//! Request handlers. The startup / main-menu set is implemented here; the action endpoints
//! (fights, events, store, progression, challenges) are being ported and currently return valid
//! empty shapes so the menu opens.

use crate::app::{make_outcome, Ctx, RouteOutcome};
use crate::protocol::ApiError;
use crate::sha256::sha256;
use serde_json::{json, Value};

const CAMPAIGN_GAME_TYPE: i64 = 0;

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

pub fn global_settings(_ctx: &mut Ctx) -> Value {
    // GlobalSettings.ParseData: minClientVersion "0" never forces an update.
    json!({"minClientVersion": "0", "toSVersion": "2.0.0"})
}

pub fn post_login(ctx: &mut Ctx) -> Value {
    json!({"count": 0, "countryCode": ctx.app.config.str("country_code", "US"), "canAghanimPay": false})
}

pub fn acts_campaign(ctx: &mut Ctx) -> Value {
    let acts: Vec<Value> = ctx
        .app
        .content
        .all("Act")
        .filter(|a| {
            a.get("gameType").and_then(|v| v.as_i64()) == Some(CAMPAIGN_GAME_TYPE)
                && !a.get("_name").and_then(|v| v.as_str()).unwrap_or("").contains("FTUE")
        })
        .filter_map(|a| a.get("guid").cloned())
        .collect();
    json!(acts)
}

pub fn events(_ctx: &mut Ctx, _region: &str) -> Value {
    // Full LTA schedule lands with the live-ops work; an empty set is a valid menu response.
    json!({"activeEvents": [], "upcomingEvents": []})
}

fn basic_credentials(authz: Option<&str>) -> (Option<String>, Option<String>) {
    let a = match authz {
        Some(a) if a.starts_with("Bearer ") => &a[7..],
        _ => return (None, None),
    };
    let raw = match crate::base64::decode(a) {
        Some(b) => String::from_utf8_lossy(&b).into_owned(),
        None => return (None, None),
    };
    match raw.split_once(':') {
        Some((e, p)) => (Some(e.to_string()), Some(p.to_string())),
        None => (Some(raw), None),
    }
}

pub fn authenticate(
    ctx: &mut Ctx,
    _platform: &str,
    device: &str,
    _region: &str,
    provider: &str,
    auth_token: Option<&str>,
    authz: Option<&str>,
) -> Result<RouteOutcome, ApiError> {
    let store = &ctx.app.store;
    let mut user_id: Option<String> = None;
    let mut created = false;

    if provider == "Password" {
        let (email, password) = basic_credentials(authz);
        let pw_hash = hex(&sha256(password.as_deref().unwrap_or("").as_bytes()));
        let email = email.unwrap_or_default();
        match store.account(&email) {
            Some((stored, uid)) => {
                if stored != pw_hash {
                    return Err(ApiError::new(401, "InvalidCredentials", "wrong email or password"));
                }
                user_id = Some(uid);
            }
            None => {
                if ctx.app.config.bool("auto_register_password_accounts", true) && !email.is_empty() {
                    let user = ctx.app.players.create(&mut ctx.rng, Some(device));
                    store.save_user(&user);
                    let uid = user["id"].as_str().unwrap().to_string();
                    store.set_account(&email, &pw_hash, &uid);
                    user_id = Some(uid);
                    created = true;
                }
            }
        }
    } else {
        if let Some(tok) = auth_token {
            user_id = store.session_user(&format!("guest:{tok}"));
        }
        if user_id.is_none() && !device.is_empty() {
            user_id = store.user_for_device(device);
        }
    }

    let mut user = user_id.as_ref().and_then(|id| store.load_user(id));
    if user.is_none() {
        let new_user_flag = ctx.body.get("newUser").and_then(|v| v.as_bool()).unwrap_or(true);
        if !new_user_flag && !ctx.app.config.bool("allow_implicit_signup", true) {
            return Err(ApiError::new(404, "UserNotFound", "no such user"));
        }
        let u = ctx.app.players.create(&mut ctx.rng, if device.is_empty() { None } else { Some(device) });
        created = true;
        user = Some(u);
    }
    let mut user = user.unwrap();
    let uid = user["id"].as_str().unwrap().to_string();
    if !device.is_empty() {
        store.bind_device(device, &uid);
    }
    // guest token: stable per user, stored as a pseudo-session so a later login resumes.
    let guest_token = match user.get("guestToken").and_then(|v| v.as_str()) {
        Some(t) => t.to_string(),
        None => {
            let t = hex(&sha256(uid.as_bytes()))[..40].to_string();
            user["guestToken"] = json!(t);
            t
        }
    };
    store.new_session(&uid, format!("guest:{guest_token}"));
    store.save_user(&user);
    let session = store.new_session(&uid, ctx.rng.hex32());
    ctx.set_cookie = Some(format!("{}={}; Path=/; HttpOnly", crate::protocol::SESSION_COOKIE, session));

    let user_payload = ctx.app.players.user_data(&mut user, true);
    ctx.user = Some(user);
    ctx.save();

    let payload = json!({"data": {
        "session": {"userId": uid, "providerUserToken": guest_token,
                    "isGuest": provider != "Password", "isNewUser": created},
        "user": user_payload,
        "loginRewardCalendarStates": [],
    }});
    Ok(make_outcome(200, payload, false, false))
}
