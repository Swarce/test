package sgm.core.handlers;

import java.util.Map;

import sgm.core.Ctx;
import sgm.core.Module;
import sgm.core.Server;

import static sgm.core.Json.arr;
import static sgm.core.Json.obj;

/**
 * Main-menu services that must answer for the client to work, but whose live-ops content (battle
 * pass seasons, offers, mail, chat, guilds, friends, ads...) is not reproduced. Responses keep the
 * container types the client's parsers expect, so menus open empty instead of erroring.
 */
public final class Frontend extends Module {
    public Frontend(Server app) {
        super(app);
        // challenges / achievements / calendars
        route("PUT", "user/challenges/seen", "ok");
        route("GET", "achievements", "list");
        route("GET", "user/calendars", "list");
        route("GET", "user/webStoreCalendar", "map");
        route("GET", "guildChallenges", "map");
        route("GET", "battlePass", "battle_pass");
        // store front (real-money / live-ops parts are empty)
        route("GET", "offers/{region}", "offers");
        route("GET", "directPurchase", "map");
        route("POST", "directPurchase/refresh", "map");
        route("GET", "user/premiereGachaPity", "pity_premiere");
        route("GET", "user/headlinerGachaPity", "pity_headliner");
        route("PUT", "user/{kind:premiereGachaPity|headlinerGachaPity}", "ok");
        route("GET", "billing/purchase/status", "map");
        // mail
        route("GET", "mails", "map");
        route("GET", "mail/count/unread", "unread");
        // chat (PubNub based, disabled)
        route("GET", "chat/info", "chat_info");
        route("GET", "chat/pubnub/accessToken", "empty_string");
        route("GET", "guildChatInfo", "map");
        // deployments (Mission Control)
        route("GET", "deployments", "list");
        // ads
        route("GET", "ads/info", "ads_info");
        // social / guild (multiplayer features: intentionally empty)
        route("GET", "friends", "list");
        route("GET", "user/guild", "map");
        route("GET", "guildOpsProgress", "map");
        route("GET", "raidBoss/progress", "map");
        route("GET", "guildFriends", "list");
        route("GET", "guilds", "list");
        route("GET", "replays", "map");
        route("GET", "user/cosmetics", "cosmetics");
        route("PUT", "user/cosmetics/markPortraitsSeen", "ok");
        route("GET", "loadouts/{guid}", "list");
    }

    @Override
    protected Object call(String name, Ctx ctx, Map<String, String> p) {
        if (name.equals("ok")) {
            return empty();
        } else if (name.equals("list")) {
            return data(arr());
        } else if (name.equals("map")) {
            return data(obj());
        } else if (name.equals("empty_string")) {
            return data("");
        } else if (name.equals("battle_pass")) {
            long far = ctx.nowMs + 30L * 86400000L;
            return data(obj("battlePassGuid", "", "battlePassPoints", 0L, "isPremiumActive", false,
                    "isPremiumPlusActive", false, "claimedFreeRewards", arr(), "claimedPremiumRewards", arr(),
                    "dailyChallenges", arr(), "weeklyChallenges", arr(), "monthlyChallenges", arr(),
                    "dailyExpirationDate", far, "weeklyExpirationDate", far, "monthlyExpirationDate", far,
                    "pointsWereAutoClaimed", false));
        } else if (name.equals("offers")) {
            return data(obj("featuredOffers", arr(), "currencyStoreOffers", arr(), "adStoreOffers", arr(),
                    "moneyStoreOffers", arr(), "limitedTimeOffers", arr(), "bundleStoreOffers", arr(),
                    "blockedMultiOfferGuids", arr(), "globalOfferPopupCooldownHours", 1000L,
                    "globalAdOfferPopupCooldownHours", 1000L));
        } else if (name.equals("pity_premiere")) {
            return data(obj("goldFighters", arr(), "diamondFighters", arr(), "bannedFighters", arr(), "openedRelicsCount", 0L));
        } else if (name.equals("pity_headliner")) {
            return data(obj("goldFighters", arr(), "diamondFighters", arr(), "allowedFighters", arr(), "openedRelicsCount", 0L));
        } else if (name.equals("unread")) {
            return data(obj("count", 0L));
        } else if (name.equals("chat_info")) {
            return data(obj("enabled", false, "publishKey", "", "subscribeKey", "", "channelCount", 0L,
                    "idealChannelOccupancy", 0L, "maxChannelOccupancy", 0L, "chatChannelGroups", arr(), "name", ""));
        } else if (name.equals("ads_info")) {
            return obj("watchCount", 0L, "cycleWatchCount", 0L, "adsAvailableAt", 0L);
        } else if (name.equals("cosmetics")) {
            return data(obj("portraitsOwned", arr(), "newPortraits", arr()));
        }
        throw new IllegalStateException(name);
    }
}
