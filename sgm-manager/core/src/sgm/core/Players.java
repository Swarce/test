package sgm.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static sgm.core.Json.add;
import static sgm.core.Json.arr;
import static sgm.core.Json.bool;
import static sgm.core.Json.child;
import static sgm.core.Json.childList;
import static sgm.core.Json.dbl;
import static sgm.core.Json.getList;
import static sgm.core.Json.getMap;
import static sgm.core.Json.i;
import static sgm.core.Json.lng;
import static sgm.core.Json.map;
import static sgm.core.Json.obj;
import static sgm.core.Json.str;

/**
 * Builds and mutates player documents (see the Python server's player.py for the persisted shape)
 * and serializes them to the client's *ServerData shapes.
 */
public final class Players {
    /** TutorialType bitmask values and the "cleared" progress per type (TutorialManager constants). */
    public static final String[] TUTORIALS = {"FTUE", "MoveEquip", "SkillTree", "RiftBattle", "XPBoost", "Maze", "Pity",
            "RaidBoss", "AutoBattle", "PrizeFight", "MissionControl"};
    public static final int[] TUTORIAL_BITS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};
    public static final int[] TUTORIAL_CLEARED = {8, 1, 1, 5, 1, 2, 1, 1, 1, 2, 2};

    /** CurrencyType names in enum order. */
    public static final List<String> CURRENCY_TYPES = Arrays.asList("SoftCurrency", "HardCurrency", "GachaShard", "SkillPoint",
            "Key", "Essence", "EssenceShard", "BattleCurrency", "BattleTicketFree", "BattleTicketPaid", "SocialGiftStandard",
            "SocialGiftGold", "EnergyRefill", "DailyEventTicket", "CharacterGachaShard", "RerollRune", "UniversalRerollRune",
            "AssistEvoCurrency", "GuildGiftStandard", "GuildGiftPremium", "GuildOpsPoints", "RaidBossTicketFree",
            "RaidBossTicketPaid");
    /** Names accepted in UserServerData.otherCurrencies (keys, shards and essences are per rarity/element). */
    public static final List<String> OTHER_CURRENCIES = Arrays.asList("BronzeKey", "SilverKey", "GoldKey", "DiamondKey",
            "SilverGachaShard", "GoldGachaShard", "DiamondGachaShard", "CharacterGachaShard", "BattleCurrency",
            "BattleTicketFree", "BattleTicketPaid", "DailyEventTicket", "EnergyRefill", "UniversalRerollRune",
            "AssistEvoCurrency", "SocialGiftStandard", "SocialGiftGold", "SocialGiftStandardToOpen", "SocialGiftGoldToOpen",
            "GuildGiftStandard", "GuildGiftPremium", "GuildGiftStandardToOpen", "GuildGiftPremiumToOpen",
            "RaidBossTicketFree", "RaidBossTicketPaid", "FireEssence", "WaterEssence", "AirEssence", "LightEssence",
            "DarkEssence", "FireEssenceShard", "WaterEssenceShard", "AirEssenceShard", "LightEssenceShard",
            "DarkEssenceShard");
    public static final String[] RARITY = {"Bronze", "Silver", "Gold", "Diamond"};
    /** Element enum order (ElementType). */
    public static final String[] ELEMENTS = {"Neutral", "Fire", "Water", "Air", "Dark", "Light"};

    // SkillTreeNode.nodeType values that buff stats
    static final long NODE_LIFEBAR = 4;
    static final long NODE_ATTACK = 5;
    static final double BUFF_SCALE = 0.01;

    public final Content c;
    public final Formulas f;
    private final Map<String, Object> cfg;
    public final int maxEnergy = 10;
    public final Random rnd = new Random();

    public Players(Content c, Formulas f, Map<String, Object> cfg) {
        this.c = c;
        this.f = f;
        this.cfg = cfg;
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static long nowMs() {
        return System.currentTimeMillis();
    }

    public static int tutorialIndex(String name) {
        for (int k = 0; k < TUTORIALS.length; k++) {
            if (TUTORIALS[k].equals(name)) {
                return k;
            }
        }
        return -1;
    }

    // ---- creation -----------------------------------------------------------------------------

    public Map<String, Object> create(String deviceId, String handle) {
        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("id", newId());
        user.put("publicId", String.valueOf(100000000 + rnd.nextInt(900000000)));
        user.put("handle", handle != null ? handle : "Player" + (1000 + rnd.nextInt(9000)));
        user.put("created", nowMs());
        user.put("xp", 0L);
        Map<String, Object> cur = new LinkedHashMap<String, Object>();
        Map<String, Object> start = getMap(cfg, "starting_currencies");
        if (start != null) {
            for (Map.Entry<String, Object> e : start.entrySet()) {
                cur.put(e.getKey(), ((Number) e.getValue()).longValue());
            }
        }
        user.put("currencies", cur);
        for (String k : new String[]{"skillPoints", "characters", "moves", "assistMoves", "nodeModifiers", "consumables", "gachas"}) {
            user.put(k, new LinkedHashMap<String, Object>());
        }
        user.put("team", new ArrayList<Object>());
        user.put("variantsEverOwned", new LinkedHashMap<String, Object>());
        user.put("tutorial", obj("progress", 0L, "completed", 0L, "map", new LinkedHashMap<String, Object>()));
        user.put("campaign", new LinkedHashMap<String, Object>());
        user.put("fights", new LinkedHashMap<String, Object>());
        user.put("avatar", "");
        user.put("deviceIds", deviceId != null && !deviceId.isEmpty() ? arr(deviceId) : arr());
        for (Object loot : getList(c.economy(), "startingLoot")) {
            Rewards.grantLoot(this, user, map(loot), 1);
        }
        if (Json.bool(cfg, "skip_tutorials") || !cfg.containsKey("skip_tutorials")) {
            completeAllTutorials(user);
        }
        List<String> starters = new ArrayList<String>();
        for (Map<String, Object> tm : c.all("TutorialManager")) {
            for (Object gen : getList(tm, "finalTeamCompositionV2")) {
                Map<String, Object> v = c.get(map(gen).get("characterData"));
                if (v != null) {
                    starters.add(str(v, "guid"));
                }
            }
            break;
        }
        for (Object g : getList(cfg, "extra_starting_characters")) {
            starters.add(String.valueOf(g));
        }
        List<Object> team = getList(user, "team");
        for (String guid : starters) {
            Map<String, Object> ch = grantCharacter(user, guid, null, 0, false);
            if (ch != null && team.size() < 3) {
                team.add(ch.get("id"));
            }
        }
        if (!team.isEmpty()) {
            user.put("avatar", str(getMap(getMap(user, "characters"), (String) team.get(0)), "variant"));
        }
        return user;
    }

    public void completeAllTutorials(Map<String, Object> user) {
        Map<String, Object> t = child(user, "tutorial");
        long done = lng(t, "completed", 0);
        Map<String, Object> m = child(t, "map");
        for (int k = 0; k < TUTORIALS.length; k++) {
            done |= TUTORIAL_BITS[k];
            m.put(TUTORIALS[k], (long) TUTORIAL_CLEARED[k]);
        }
        t.put("completed", done);
        t.put("progress", (long) TUTORIAL_CLEARED[0]);
    }

    // ---- inventory ------------------------------------------------------------------------------

    public void addCurrency(Map<String, Object> user, String name, long amount) {
        Map<String, Object> cur = child(user, "currencies");
        cur.put(name, Math.max(0, lng(cur, name, 0) + amount));
    }

    /** price: {"CurrencyName": amount}. Returns false (and changes nothing) if unaffordable. */
    public boolean spend(Map<String, Object> user, Map<String, Long> price) {
        Map<String, Object> cur = child(user, "currencies");
        for (Map.Entry<String, Long> e : price.entrySet()) {
            if (lng(cur, e.getKey(), 0) < e.getValue()) {
                return false;
            }
        }
        for (Map.Entry<String, Long> e : price.entrySet()) {
            add(cur, e.getKey(), -e.getValue());
        }
        return true;
    }

    public Map<String, Object> grantCharacter(Map<String, Object> user, String variantGuid, Integer tier, long xp, boolean shiny) {
        Map<String, Object> v = c.guid(variantGuid, "VariantCharacterData");
        if (v == null) {
            return null;
        }
        String cid = newId();
        Map<String, Object> ch = new LinkedHashMap<String, Object>();
        ch.put("id", cid);
        ch.put("variant", variantGuid);
        ch.put("tier", tier == null ? lng(v, "initialTier", 0) : (long) tier);
        ch.put("xp", xp);
        ch.put("shiny", shiny);
        ch.put("nodes", new ArrayList<Object>());
        ch.put("moves", new ArrayList<Object>());
        ch.put("assist", "");
        ch.put("energy", (long) maxEnergy);
        ch.put("energyTs", 0L);
        ch.put("locked", false);
        ch.put("new", true);
        ch.put("superLevels", new ArrayList<Object>());
        ch.put("superFeature", 0L);
        ch.put("prestige", 0L);
        ch.put("created", nowMs());
        Map<String, Object> start = c.get(v.get("startingGear"));
        if (start != null) {
            Map<String, Object> mv = grantMove(user, str(start, "guid"), false);
            getList(ch, "moves").add(mv.get("id"));
        }
        child(user, "characters").put(cid, ch);
        Map<String, Object> base = c.get(v.get("baseCharacter"));
        if (base != null) {
            Map<String, Object> everAll = child(user, "variantsEverOwned");
            Map<String, Object> ever = map(everAll.get(str(base, "guid")));
            if (ever == null) {
                ever = obj("normal", arr(), "shiny", arr());
                everAll.put(str(base, "guid"), ever);
            }
            List<Object> lst = childList(ever, shiny ? "shiny" : "normal");
            if (!lst.contains(variantGuid)) {
                lst.add(variantGuid);
            }
        }
        return ch;
    }

    public double shinyChance(Map<String, Object> variant) {
        List<Object> chances = getList(c.gameSettings(), "percentChanceForShinyPerRarityTier");
        int t = i(variant, "initialTier");
        return t < chances.size() ? ((Number) chances.get(t)).doubleValue() / 100.0 : 0.0;
    }

    public Map<String, Object> grantMove(Map<String, Object> user, String moveGuid, boolean isNew) {
        return grantMove(user, "moves", moveGuid, isNew);
    }

    public Map<String, Object> grantMove(Map<String, Object> user, String pool, String moveGuid, boolean isNew) {
        String mid = newId();
        Map<String, Object> mv = obj("id", mid, "guid", moveGuid, "evolution", 1L, "stats", arr(), "locked", false, "new", isNew);
        child(user, pool).put(mid, mv);
        return mv;
    }

    // ---- derived values -------------------------------------------------------------------------

    public int level(Map<String, Object> user) {
        return f.playerLadder.levelForXp(lng(user, "xp", 0), false);
    }

    public int charLevel(Map<String, Object> ch) {
        return f.charLadder(i(ch, "tier")).levelForXp(lng(ch, "xp", 0), bool(ch, "shiny"));
    }

    /** {level, lifebar, attack, fighterScore}. */
    public int[] charStats(Map<String, Object> ch) {
        Map<String, Object> v = c.guid(str(ch, "variant"));
        int lvl = charLevel(ch);
        float[] buffs = skillBuffs(getList(ch, "nodes"));
        int tier = i(ch, "tier");
        int lb = f.lifebar(v, tier, lvl, buffs[0]);
        int at = f.attack(v, tier, lvl, buffs[1]);
        int sup = 0;
        for (Object o : getList(ch, "superLevels")) {
            sup = Math.max(sup, ((Number) o).intValue());
        }
        int fs = f.fighterScore(lb, at, buffs[2], sup, i(ch, "prestige"));
        return new int[]{lvl, lb, at, fs};
    }

    /** VariantCharacterData.CalculateSkillTreeBuff / SkillTree.GetFSMultiplierAddition: {lifebar, attack, fs}. */
    public float[] skillBuffs(List<Object> nodeGuids) {
        float lb = 0;
        float at = 0;
        float fs = 0;
        for (Object g : nodeGuids) {
            Map<String, Object> n = c.guid(String.valueOf(g), "SkillTreeNode");
            if (n == null) {
                continue;
            }
            long t = lng(n, "nodeType", -1);
            if (t == NODE_LIFEBAR) {
                lb = (float) (lb + dbl(n, "value", 0));
            } else if (t == NODE_ATTACK) {
                at = (float) (at + dbl(n, "value", 0));
            }
            fs = (float) (fs + dbl(n, "fsMultiplierAddition", 0));
        }
        return new float[]{(float) (lb * BUFF_SCALE), (float) (at * BUFF_SCALE), (float) (fs * dbl(c.gameSettings(), "skillNodeFSGlobalMultiplier", 1.0))};
    }

    public long skillPointsPerLevel(int tier) {
        Map<String, Object> econ = c.economy();
        return lng(econ, "skillPointsOnCharacterLevelUp", 1) * tierMult(getList(econ, "skillPointTierMultipliers"), tier);
    }

    /** lst[min(tier, len-1)] or 1 for an empty list. */
    public static long tierMult(List<Object> lst, int tier) {
        if (lst.isEmpty()) {
            return 1;
        }
        return ((Number) lst.get(Math.min(tier, lst.size() - 1))).longValue();
    }

    public String baseGuid(Map<String, Object> ch) {
        Map<String, Object> v = c.guid(str(ch, "variant"));
        Map<String, Object> base = v == null ? null : c.get(v.get("baseCharacter"));
        return base == null ? null : str(base, "guid");
    }

    /**
     * Adds XP (capped at the tier's level cap). Level-ups grant skill points to the base character.
     * Returns {xpBefore, skillPointsGranted}.
     */
    public long[] addCharXp(Map<String, Object> user, Map<String, Object> ch, long amount) {
        Formulas.Ladder lad = f.charLadder(i(ch, "tier"));
        boolean shiny = bool(ch, "shiny");
        long before = lng(ch, "xp", 0);
        int lvlBefore = lad.levelForXp(before, shiny);
        ch.put("xp", Math.min(before + Math.max(0, amount), lad.xpForLevel(lad.cap, shiny)));
        int gained = lad.levelForXp(lng(ch, "xp", 0), shiny) - lvlBefore;
        long sp = gained > 0 ? gained * skillPointsPerLevel(i(ch, "tier")) : 0;
        String base = baseGuid(ch);
        if (sp != 0 && base != null) {
            add(child(user, "skillPoints"), base, sp);
        }
        return new long[]{before, sp};
    }

    /** EconomySettings.CalculateFeedingXp. */
    public long feedingXp(Map<String, Object> fed, Map<String, Object> food) {
        Map<String, Object> econ = c.economy();
        List<Object> byTier = getList(econ, "feedingXpByTier");
        int ft = i(food, "tier");
        Formulas.Ladder lad = f.charLadder(ft);
        long capped = Math.min(lng(food, "xp", 0), lad.xpForLevel(lad.cap, bool(food, "shiny")));
        double perTier = byTier.isEmpty() ? 0 : ((Number) byTier.get(Math.min(ft, byTier.size() - 1))).doubleValue();
        float base = (float) (perTier + (float) (dbl(econ, "feedingMultiplierOfTotalXp", 0) * capped));
        Map<String, Object> fv = c.guid(str(fed, "variant"));
        Map<String, Object> dv = c.guid(str(food, "variant"));
        float elem = 0;
        float same = 0;
        if (eq(fv == null ? null : fv.get("elementAffiliation"), dv == null ? null : dv.get("elementAffiliation"))) {
            elem = (float) (dbl(econ, "feedingMultiplierForSameElement", 0) * base);
        }
        if (eq(fv == null ? null : fv.get("baseCharacter"), dv == null ? null : dv.get("baseCharacter"))) {
            same = (float) (dbl(econ, "feedingMultiplierForSameBaseCharacter", 0) * base);
        }
        return (long) ((base + same) + elem);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** EconomySettings.CalculateFeedingSkillPoints: skill points the food would still have earned. */
    public long feedingSkillPoints(Map<String, Object> food) {
        int tier = i(food, "tier");
        return Math.max(0, (Formulas.levelCap(tier) - charLevel(food)) * skillPointsPerLevel(tier));
    }

    public void refreshEnergy(Map<String, Object> ch, long now) {
        long cooldown = lng(c.gameSettings(), "energyCooldownSeconds", 600) * 1000;
        long energy = lng(ch, "energy", maxEnergy);
        if (energy >= maxEnergy) {
            ch.put("energyTs", 0L);
            return;
        }
        long ts = lng(ch, "energyTs", 0);
        if (ts == 0) {
            ts = now + cooldown;
        }
        while (energy < maxEnergy && now >= ts) {
            energy++;
            ts = energy < maxEnergy ? ts + cooldown : 0;
        }
        ch.put("energy", energy);
        ch.put("energyTs", ts);
    }

    public void refreshEnergy(Map<String, Object> ch) {
        refreshEnergy(ch, nowMs());
    }

    // ---- serialization ----------------------------------------------------------------------------

    public Map<String, Object> characterData(Map<String, Object> user, Map<String, Object> ch) {
        refreshEnergy(ch);
        int[] st = charStats(ch);
        Formulas.Ladder lad = f.charLadder(i(ch, "tier"));
        boolean shiny = bool(ch, "shiny");
        Map<String, Object> owned = child(user, "moves");
        List<Object> moveIds = new ArrayList<Object>();
        for (Object m : getList(ch, "moves")) {
            if (owned.containsKey(m)) {
                moveIds.add(m);
            }
        }
        String assist = str(ch, "assist");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", ch.get("id"));
        out.put("variantGuid", ch.get("variant"));
        out.put("tier", lng(ch, "tier", 0));
        out.put("experience", lng(ch, "xp", 0));
        out.put("floatLevel", lad.floatLevel(lng(ch, "xp", 0), shiny));
        out.put("level", (long) st[0]);
        out.put("skillNodeGuids", new ArrayList<Object>(getList(ch, "nodes")));
        out.put("superAbilityLevels", new ArrayList<Object>(getList(ch, "superLevels")));
        out.put("superAbilityActiveFeatureSet", lng(ch, "superFeature", 0));
        out.put("prestigeAbilityLevel", lng(ch, "prestige", 0));
        out.put("energy", lng(ch, "energy", 0));
        out.put("nextEnergyTimestamp", lng(ch, "energyTs", 0));
        out.put("energyMp", (long) maxEnergy);
        out.put("nextEnergyTimeMpStamp", 0L);
        out.put("lifebar", (long) st[1]);
        out.put("attack", (long) st[2]);
        out.put("specialMoveIds", moveIds);
        out.put("assistMoveInstanceId", assist.isEmpty() ? null : assist);
        out.put("isLocked", bool(ch, "locked"));
        out.put("isNew", bool(ch, "new"));
        out.put("isShiny", shiny);
        out.put("isUsedInDeployment", false);
        return out;
    }

    public Map<String, Object> moveData(Map<String, Object> mv) {
        Map<String, Object> g = c.guid(str(mv, "guid"));
        List<Object> stats = new ArrayList<Object>();
        for (Object s : getList(mv, "stats")) {
            Map<String, Object> sm = map(s);
            stats.add(obj("guid", sm.get("guid"), "level", lng(sm, "level", 1)));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", mv.get("id"));
        out.put("moveGuid", mv.get("guid"));
        out.put("evolution", lng(mv, "evolution", 1));
        out.put("saleValue", obj("amounts", obj("SoftCurrency", moveSaleValue(g))));
        out.put("moveStatInstances", stats);
        out.put("isLocked", bool(mv, "locked"));
        out.put("isNew", bool(mv, "new"));
        return out;
    }

    public long moveSaleValue(Map<String, Object> gear) {
        Map<String, Object> econ = c.economy();
        List<Object> prices = getList(econ, "baseGearSalePricePerTier");
        if (prices.isEmpty()) {
            return 0;
        }
        int t = Math.max(0, Math.min(gear == null ? 0 : i(gear, "tier"), prices.size() - 1));
        return (long) (((Number) prices.get(t)).doubleValue() * dbl(econ, "gearSalePercent", 1.0));
    }

    public Map<String, Object> nodeModifierData(Map<String, Object> nm) {
        return obj("id", nm.get("id"), "guid", nm.get("guid"), "inUse", false, "characterGuid", str(nm, "character"),
                "level", lng(nm, "level", 1), "softCurrencyValue", 0L, "element", str(nm, "element", "Neutral"),
                "isLocked", bool(nm, "locked"), "isNew", bool(nm, "new"));
    }

    public static Map<String, Object> currencyBalance(long amount) {
        return obj("paid", 0L, "free", amount, "bonus", 0L);
    }

    private static List<Object> amountList(Map<String, Object> m, String countKey, boolean positiveOnly, boolean withNew) {
        List<Object> out = new ArrayList<Object>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            long n = ((Number) e.getValue()).longValue();
            if (positiveOnly && n <= 0) {
                continue;
            }
            Map<String, Object> o = obj("guid", e.getKey(), countKey, n);
            if (withNew) {
                o.put("isNew", false);
            }
            out.add(o);
        }
        return out;
    }

    public Map<String, Object> userData(Map<String, Object> user, boolean full) {
        Map<String, Object> cur = child(user, "currencies");
        Map<String, Object> t = child(user, "tutorial");
        Map<String, Object> chars = child(user, "characters");
        long capacity = lng(cfg, "collection_capacity", 1000);
        List<Object> other = new ArrayList<Object>();
        for (String k : OTHER_CURRENCIES) {
            if (lng(cur, k, 0) != 0) {
                other.add(obj("type", k, "amount", lng(cur, k, 0)));
            }
        }
        List<Object> team = new ArrayList<Object>();
        for (Object cid : getList(user, "team")) {
            if (chars.containsKey(cid)) {
                team.add(cid);
            }
        }
        List<Object> ever = new ArrayList<Object>();
        for (Map.Entry<String, Object> e : child(user, "variantsEverOwned").entrySet()) {
            Map<String, Object> v = map(e.getValue());
            ever.add(obj("guid", e.getKey(), "variantsEverOwned", getList(v, "normal"), "variantsEverOwnedShiny", getList(v, "shiny")));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", user.get("id"));
        out.put("publicId", user.get("publicId"));
        out.put("publicUserId", user.get("publicId"));
        out.put("handle", user.get("handle"));
        out.put("hasFreeHandleChange", true);
        out.put("metricsId", user.get("id"));
        out.put("level", (long) level(user));
        out.put("floatLevel", f.playerLadder.floatLevel(lng(user, "xp", 0), false));
        out.put("experience", lng(user, "xp", 0));
        out.put("tutorialProgress", lng(t, "progress", 0));
        out.put("completedTutorials", lng(t, "completed", 0));
        out.put("tutorialProgressMap", new LinkedHashMap<String, Object>(child(t, "map")));
        out.put("hardCurrency", currencyBalance(lng(cur, "HardCurrency", 0)));
        out.put("softCurrency", currencyBalance(lng(cur, "SoftCurrency", 0)));
        out.put("otherCurrencies", other);
        out.put("skillPoints", amountList(child(user, "skillPoints"), "amount", false, false));
        out.put("rerollRunes", amountList(child(user, "rerollRunes"), "amount", false, false));
        out.put("consumables", amountList(child(user, "consumables"), "count", true, true));
        out.put("items", amountList(child(user, "gachas"), "count", true, false));
        out.put("teamIds", team);
        out.put("repCharacterId", str(user, "avatar"));
        out.put("tags", arr());
        out.put("adsAvailable", 0L);
        out.put("nextAdGeneratedTimestamp", 0L);
        out.put("freeBattleTicketRefreshDate", 0L);
        out.put("dailyEventTicketRefreshDate", 0L);
        out.put("dailyRaidBossTicketRefreshDate", 0L);
        out.put("removeFriendsDailyTimestampReset", 0L);
        out.put("numberOfFriendsRemoved", 0L);
        out.put("tier", 0L);
        out.put("accounts", arr());
        out.put("variantsEverOwned", ever);
        out.put("characterCollectionCapacity", capacity);
        out.put("gearCollectionCapacity", capacity);
        out.put("assistMoveCollectionCapacity", capacity);
        out.put("nodeModifierCollectionCapacity", capacity);
        out.put("characterCollectionCount", (long) chars.size());
        out.put("moveCollectionCount", (long) child(user, "moves").size());
        out.put("assistMoveCollectionCount", (long) child(user, "assistMoves").size());
        out.put("nodeModifierCollectionCount", (long) child(user, "nodeModifiers").size());
        out.put("newPortraitCount", 0L);
        out.put("eventLockedCharacterIdMap", obj());
        out.put("claimableChallengeCount", 0L);
        out.put("claimableChallengeRewardThresholdCount", 0L);
        out.put("challengeExpirationDate", 0L);
        out.put("newChallengesAvailable", false);
        out.put("battlePassPoints", 0L);
        out.put("battlePassPremiumActive", false);
        out.put("battlePassNotificationCount", 0L);
        out.put("battlePassAutoClaimedChallenges", arr());
        out.put("adIds", obj());
        out.put("topTeamPower", topTeamPower(user));
        out.put("cosmeticsInstance", obj("portraitsOwned", arr(), "newPortraits", arr()));
        if (full) {
            out.put("characters", collection(user, chars, "char"));
            out.put("specialMoves", collection(user, child(user, "moves"), "move"));
            out.put("assistMoves", collection(user, child(user, "assistMoves"), "move"));
            out.put("nodeModifiers", collection(user, child(user, "nodeModifiers"), "nm"));
        }
        return out;
    }

    public List<Object> collection(Map<String, Object> user, Map<String, Object> items, String kind) {
        List<Object> out = new ArrayList<Object>();
        for (Object o : items.values()) {
            Map<String, Object> m = map(o);
            if (kind.equals("char")) {
                out.add(characterData(user, m));
            } else if (kind.equals("move")) {
                out.add(moveData(m));
            } else {
                out.add(nodeModifierData(m));
            }
        }
        return out;
    }

    public long topTeamPower(Map<String, Object> user) {
        List<Integer> scores = new ArrayList<Integer>();
        for (Object ch : child(user, "characters").values()) {
            scores.add(charStats(map(ch))[3]);
        }
        Collections.sort(scores, Collections.<Integer>reverseOrder());
        long sum = 0;
        for (int k = 0; k < Math.min(3, scores.size()); k++) {
            sum += scores.get(k);
        }
        return sum;
    }
}
