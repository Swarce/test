package sgm.core.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sgm.core.Ctx;
import sgm.core.Json;
import sgm.core.Log;
import sgm.core.Module;
import sgm.core.Players;
import sgm.core.Protocol;
import sgm.core.Server;

import static sgm.core.Json.child;
import static sgm.core.Json.childList;
import static sgm.core.Json.getList;
import static sgm.core.Json.map;
import static sgm.core.Json.obj;
import static sgm.core.Json.str;

/** Login, profile, collection and other always-needed endpoints. */
public final class Core extends Module {
    public Core(Server app) {
        super(app);
        route("GET", "ping", "ping", NO_AUTH);
        route("GET", "{store}/global_settings", "global_settings", NO_AUTH);
        route("POST", "authenticate/{platform}/{device:[^/]*}/{region:[^/]*}/{provider}", "authenticate", NO_AUTH);
        route("POST", "user/postLogin/updates", "post_login");
        route("GET", "user/accounts", "accounts");
        route("GET", "user", "current_user");
        route("GET", "user/publicid/{pid}", "user_by_public_id", SIGN);
        route("GET", "user/updates", "user_updates");
        route("PUT", "user/checkHandleAvailable", "ok");
        route("PUT", "user/handle", "change_handle");
        route("PUT", "user/avatar/{avatar}", "set_avatar");
        route("PUT", "user/tutorial", "set_tutorial");
        route("GET", "user/tutorial/reward/xpboosts", "ok");
        route("PUT", "user/flagAccountForDeletion/{flag:\\w+}", "ok");
        route("PUT", "user/guildJoinReminder", "ok");
        route("GET", "user/collection", "collection");
        route("GET", "user/team", "get_team");
        route("PUT", "user/team", "set_team");
        route("PUT", "user/competitive/team", "ok");
        route("PUT", "characters/setNew", "characters_set_new");
        route("PUT", "character/{cid}/lock", "character_lock");
        route("PUT", "moves/setNew", "moves_set_new");
        route("PUT", "move/{mid}/lock", "move_lock");
        route("PUT", "nodeModifiers/setNew", "node_mods_set_new");
        route("PUT", "nodeModifier/{nid}/lock", "node_mod_lock");
        route("PUT", "consumables/setNew", "ok");
        route("PUT", "user/equip", "equip");
        route("PUT", "user/competitive/equip", "ok");
    }

    @Override
    protected Object call(String name, Ctx ctx, Map<String, String> p) {
        Map<String, Object> u = ctx.user;
        if (name.equals("ok")) {
            return empty();
        } else if (name.equals("ping")) {
            return empty();
        } else if (name.equals("global_settings")) {
            // GlobalSettings.ParseData: minClientVersion ("0" = never force an update) and toSVersion
            return data(obj("minClientVersion", "0", "toSVersion", "2.0.0"));
        } else if (name.equals("authenticate")) {
            return authenticate(ctx, p.get("device"), p.get("provider"));
        } else if (name.equals("post_login")) {
            return data(obj("count", 0L, "countryCode", app.cfgS("country_code", "US"), "canAghanimPay", false));
        } else if (name.equals("accounts")) {
            return data(Json.arr());
        } else if (name.equals("current_user")) {
            return data(userPayload(ctx, u));
        } else if (name.equals("user_by_public_id")) {
            for (String uid : ctx.store.allUserIds()) {
                Map<String, Object> other = ctx.store.loadUser(uid);
                if (other != null && p.get("pid").equals(str(other, "publicId"))) {
                    return data(ctx.players().userData(other, true));
                }
            }
            throw err(404, "UserNotFound", "no such user");
        } else if (name.equals("user_updates")) {
            // GetUpdatesRequest reads these counters straight from the top level of "data"
            return data(obj("adsAvailable", 0L, "maxAdsBalance", 0L, "nextAdGeneratedTimestamp", 0L, "guildInvitesCount", 0L,
                    "guildRequestsCount", 0L, "guildRole", "", "numberOfRiftBattleUpdates", 0L, "numberOfSocialUpdates", 0L,
                    "unclaimedAghanimDirectPurchaseCount", 0L));
        } else if (name.equals("change_handle")) {
            String handle = str(ctx.body, "handle").trim();
            if (handle.length() < 3 || handle.length() > 20) {
                throw err(400, "InvalidHandle", "invalid handle");
            }
            u.put("handle", handle);
            ctx.save();
            return empty();
        } else if (name.equals("set_avatar")) {
            u.put("avatar", p.get("avatar"));
            ctx.save();
            return empty();
        } else if (name.equals("set_tutorial")) {
            return setTutorial(ctx);
        } else if (name.equals("collection")) {
            Players s = ctx.players();
            return data(obj("characters", s.collection(u, child(u, "characters"), "char"),
                    "specialMoves", s.collection(u, child(u, "moves"), "move"),
                    "assistMoves", s.collection(u, child(u, "assistMoves"), "move"),
                    "nodeModifiers", s.collection(u, child(u, "nodeModifiers"), "nm"),
                    "eventLockedCharacterIdMap", obj()));
        } else if (name.equals("get_team")) {
            return data(obj("teamIds", new ArrayList<Object>(getList(u, "team"))));
        } else if (name.equals("set_team")) {
            List<Object> ids = new ArrayList<Object>();
            for (String id : ctx.qList("ids")) {
                if (ctx.chars().containsKey(id)) {
                    ids.add(id);
                }
            }
            if (ctx.qBool("defensive")) {
                u.put("defenseTeam", ids);
            } else {
                u.put("team", new ArrayList<Object>(ids.subList(0, Math.min(3, ids.size()))));
            }
            ctx.save();
            return empty();
        } else if (name.equals("characters_set_new")) {
            boolean isNew = ctx.qBool("isNew");
            List<Object> ids = getList(ctx.body, "ids");
            if (ids.isEmpty()) {
                ids = new ArrayList<Object>(ctx.qList("ids"));
            }
            for (Object cid : ids) {
                Map<String, Object> ch = map(ctx.chars().get(cid));
                if (ch != null) {
                    ch.put("new", isNew);
                }
            }
            ctx.save();
            return empty();
        } else if (name.equals("character_lock")) {
            Map<String, Object> ch = map(ctx.chars().get(p.get("cid")));
            if (ch != null) {
                ch.put("locked", ctx.qBool("enabled"));
                ctx.save();
            }
            return empty();
        } else if (name.equals("moves_set_new")) {
            Map<String, Object> pool = child(u, ctx.qBool("isAssist") ? "assistMoves" : "moves");
            for (Object mid : getList(ctx.body, "ids")) {
                Map<String, Object> mv = map(pool.get(mid));
                if (mv != null) {
                    mv.put("new", ctx.qBool("isNew"));
                }
            }
            ctx.save();
            return empty();
        } else if (name.equals("move_lock")) {
            Map<String, Object> mv = map(child(u, ctx.qBool("isAssist") ? "assistMoves" : "moves").get(p.get("mid")));
            if (mv != null) {
                mv.put("locked", ctx.qBool("enabled"));
                ctx.save();
            }
            return empty();
        } else if (name.equals("node_mods_set_new")) {
            for (Object nid : getList(ctx.body, "ids")) {
                Map<String, Object> nm = map(child(u, "nodeModifiers").get(nid));
                if (nm != null) {
                    nm.put("new", ctx.qBool("isNew"));
                }
            }
            ctx.save();
            return empty();
        } else if (name.equals("node_mod_lock")) {
            Map<String, Object> nm = map(child(u, "nodeModifiers").get(p.get("nid")));
            if (nm != null) {
                nm.put("locked", ctx.qBool("enabled"));
                ctx.save();
            }
            return empty();
        } else if (name.equals("equip")) {
            return equip(ctx);
        }
        throw new IllegalStateException(name);
    }

    private String[] basicCredentials(Ctx ctx) {
        String auth = ctx.headers.get("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return new String[]{null, null};
        }
        String raw = new String(Protocol.unbase64(auth.substring(7).trim()), Protocol.UTF8);
        int c = raw.indexOf(':');
        return c < 0 ? new String[]{raw, ""} : new String[]{raw.substring(0, c), raw.substring(c + 1)};
    }

    private Object authenticate(Ctx ctx, String device, String provider) {
        sgm.core.Store store = ctx.store;
        String userId = null;
        boolean created = false;
        String token = ctx.headers.get("X-AUTH-USER-TOKEN");
        if (provider.equals("Password")) {
            String[] cred = basicCredentials(ctx);
            String email = cred[0];
            Map<String, Object> acct = store.account(email == null ? "" : email);
            String pwHash = Protocol.sha256Hex((cred[1] == null ? "" : cred[1]).getBytes(Protocol.UTF8)).toLowerCase(java.util.Locale.ROOT);
            if (acct != null) {
                if (!pwHash.equals(str(acct, "password"))) {
                    throw err(401, "InvalidCredentials", "wrong email or password");
                }
                userId = str(acct, "userId");
            } else if (app.cfgB("auto_register_password_accounts", true) && email != null && !email.isEmpty()) {
                Map<String, Object> user = ctx.players().create(device, null);
                store.saveUser(user);
                store.setAccount(email, pwHash, str(user, "id"));
                userId = str(user, "id");
                created = true;
            }
        } else {
            if (token != null && !token.isEmpty()) {
                userId = store.guestTokenUser(token);
            }
            if (userId == null && device != null && !device.isEmpty()) {
                userId = store.userForDevice(device);
            }
        }
        Map<String, Object> user = userId != null ? store.loadUser(userId) : null;
        if (user == null) {
            Object nu = ctx.body.get("newUser");
            boolean newUser = nu == null || Json.bool(ctx.body, "newUser");
            if (!newUser && !app.cfgB("allow_implicit_signup", true)) {
                throw err(404, "UserNotFound", "no such user");
            }
            user = ctx.players().create(device, null);
            created = true;
        }
        if (device != null && !device.isEmpty()) {
            store.bindDevice(device, str(user, "id"));
        }
        String guestToken = str(user, "guestToken");
        if (guestToken.isEmpty()) {
            guestToken = Protocol.sha256Hex(str(user, "id").getBytes(Protocol.UTF8)).toLowerCase(java.util.Locale.ROOT).substring(0, 40);
            user.put("guestToken", guestToken);
        }
        store.setGuestToken(guestToken, str(user, "id"));
        String session = store.newSession(str(user, "id"));
        ctx.setCookie = Protocol.SESSION_COOKIE + "=" + session + "; Path=/; HttpOnly";
        ctx.user = user;
        Challenges.track(ctx, "login", 1);
        ctx.save();
        Log.i("login " + str(user, "handle") + " (" + str(user, "id") + ") via " + provider + " new=" + created);
        return data(obj("session", obj("userId", user.get("id"), "providerUserToken", guestToken,
                        "isGuest", !provider.equals("Password"), "isNewUser", created),
                "user", userPayload(ctx, user),
                "loginRewardCalendarStates", Json.arr()));
    }

    static Map<String, Object> userPayload(Ctx ctx, Map<String, Object> user) {
        Map<String, Object> d = ctx.players().userData(user, true);
        d.putAll(Challenges.summary(ctx));
        return d;
    }

    private Object setTutorial(Ctx ctx) {
        Map<String, Object> t = child(ctx.user, "tutorial");
        String kind = str(ctx.body, "tutorialType");
        if (kind.isEmpty()) {
            kind = "FTUE";
        }
        long progress = Json.lng(ctx.body, "progress", 0);
        child(t, "map").put(kind, progress);
        if (kind.equals("FTUE")) {
            t.put("progress", progress);
        }
        int k = Players.tutorialIndex(kind);
        long bit = k < 0 ? 0 : Players.TUTORIAL_BITS[k];
        long cleared = k < 0 ? (1L << 30) : Players.TUTORIAL_CLEARED[k];
        if (progress >= cleared) {
            t.put("completed", Json.lng(t, "completed", 0) | bit);
        }
        ctx.save();
        return empty();
    }

    private Object equip(Ctx ctx) {
        Map<String, Object> u = ctx.user;
        Map<String, Object> ch = map(ctx.chars().get(ctx.q("cId")));
        if (ch == null) {
            throw err(404, "CharacterNotFound", "unknown character");
        }
        Map<String, Object> owned = child(u, "moves");
        List<Object> moveIds = new ArrayList<Object>();
        for (String m : ctx.qList("mIds")) {
            if (owned.containsKey(m)) {
                moveIds.add(m);
            }
        }
        // a move can only be equipped on one character
        for (Object o : ctx.chars().values()) {
            Map<String, Object> other = map(o);
            if (other != ch) {
                childList(other, "moves").removeAll(moveIds);
            }
        }
        ch.put("moves", moveIds);
        String aid = ctx.q("aId");
        ch.put("assist", aid != null && child(u, "assistMoves").containsKey(aid) ? aid : "");
        ctx.save();
        return empty();
    }
}
