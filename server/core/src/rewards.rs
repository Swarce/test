//! Loot granting and reward serialization (RewardLootUtil / content Loot of LootType).
//!
//! Ported from `reference-server/sgserver/rewards.py`.

use crate::player::PlayerService;
use crate::rng::Rng;
use crate::skilltree::{element_name, rarity_name};
use serde_json::{json, Value};

fn simple_currency(lt: i64) -> Option<&'static str> {
    Some(match lt {
        0 => "SoftCurrency", 1 => "HardCurrency", 12 => "BattleCurrency", 13 => "BattleTicketFree",
        14 => "BattleTicketPaid", 18 => "SocialGiftStandard", 19 => "SocialGiftGold",
        20 => "EnergyRefill", 21 => "DailyEventTicket", 22 => "CharacterGachaShard",
        24 => "UniversalRerollRune", 27 => "AssistEvoCurrency", 28 => "GuildGiftStandard",
        29 => "GuildGiftPremium", 30 => "GuildOpsPoints", 31 => "RaidBossTicketFree",
        32 => "RaidBossTicketPaid",
        _ => return None,
    })
}

fn currency_item(name: &str, amount: i64, claimable: bool) -> Value {
    let key = if claimable { "claimableCurrencyReward" } else { "currencyReward" };
    json!({"type": "Currency", key: {"amounts": {name: amount}}})
}

fn add_map_amount(user: &mut Value, pool: &str, key: &str, amount: i64) {
    let m = user.get_mut(pool).unwrap().as_object_mut().unwrap();
    let now = m.get(key).and_then(|v| v.as_i64()).unwrap_or(0);
    m.insert(key.into(), json!(now + amount));
}

pub fn grant_loot(svc: &PlayerService, user: &mut Value, loot: &Value, mult: i64, rng: &mut Rng) -> Vec<Value> {
    grant_loot_depth(svc, user, loot, mult, rng, 0)
}

fn getref<'a>(svc: &'a PlayerService, loot: &Value, field: &str) -> Option<&'a Value> {
    loot.get(field).and_then(|v| v.as_str()).and_then(|r| svc.c.get(r))
}

fn grant_loot_depth(svc: &PlayerService, user: &mut Value, loot: &Value, mult: i64, rng: &mut Rng, depth: i32) -> Vec<Value> {
    let lt = loot.get("lootType").and_then(|v| v.as_i64()).unwrap_or(8);
    let amount = ((loot.get("amount").and_then(|v| v.as_f64()).unwrap_or(0.0)) * mult as f64).round() as i64;

    if let Some(name) = simple_currency(lt) {
        if amount <= 0 {
            return vec![];
        }
        svc.add_currency(user, name, amount);
        return vec![currency_item(name, amount, false)];
    }
    match lt {
        6 => {
            let name = format!("{}GachaShard", rarity_name(loot.get("rarityTier").and_then(|v| v.as_i64()).unwrap_or(1).max(1)));
            svc.add_currency(user, &name, amount);
            vec![currency_item(&name, amount, false)]
        }
        9 => {
            let name = format!("{}Key", rarity_name(loot.get("rarityTier").and_then(|v| v.as_i64()).unwrap_or(0)));
            svc.add_currency(user, &name, amount);
            vec![currency_item(&name, amount, false)]
        }
        10 | 11 => {
            let elem = element_name(loot.get("elementType").and_then(|v| v.as_i64()).unwrap_or(0), rng);
            let name = format!("{}{}", elem, if lt == 10 { "EssenceShard" } else { "Essence" });
            svc.add_currency(user, &name, amount);
            vec![currency_item(&name, amount, false)]
        }
        23 | 7 => {
            let base_guid = getref(svc, loot, "baseCharacter").and_then(|b| b.get("guid")).and_then(|g| g.as_str()).map(String::from);
            let base_guid = match base_guid { Some(g) => g, None => return vec![] };
            let (pool, cur) = if lt == 23 { ("rerollRunes", "RerollRune") } else { ("skillPoints", "SkillPoint") };
            add_map_amount(user, pool, &base_guid, amount);
            vec![json!({"type": "BaseCharacterCurrency", "baseCharacterCurrencyReward":
                {"baseCharacterGuid": base_guid, "amounts": {cur: amount}}})]
        }
        3 => {
            let v = match getref(svc, loot, "character") { Some(v) => v.clone(), None => return vec![] };
            let vguid = v.get("guid").and_then(|g| g.as_str()).unwrap_or("").to_string();
            let tier = loot.get("rarityTier").and_then(|x| x.as_i64());
            let mut out = Vec::new();
            for _ in 0..loot.get("amount").and_then(|v| v.as_i64()).unwrap_or(1).max(1) {
                let shiny = loot.get("forceShiny").and_then(|v| v.as_bool()).unwrap_or(false) || rng.unit() < shiny_chance(svc, &v);
                if let Some(cid) = svc.grant_character(user, rng, &vguid, tier, 0, shiny) {
                    out.push(json!({"type": "Character", "characterReward":
                        {"variantGuid": vguid, "characterInstanceId": cid, "isShiny": shiny},
                        "isFirstTimeAppearance": false}));
                }
            }
            out
        }
        2 | 26 => {
            let g = match getref(svc, loot, "gear").and_then(|g| g.get("guid")).and_then(|g| g.as_str()).map(String::from) {
                Some(g) => g, None => return vec![],
            };
            let pool = if lt == 2 { "moves" } else { "assistMoves" };
            let mut out = Vec::new();
            for _ in 0..loot.get("amount").and_then(|v| v.as_i64()).unwrap_or(1).max(1) {
                let mid = svc.grant_move(user, rng, &g, true, pool);
                let (ty, key) = if lt == 2 { ("Move", "moveReward") } else { ("AssistMove", "assistMoveReward") };
                out.push(json!({"type": ty, key: {"moveGuid": g, "moveInstanceId": mid, "statGuids": []}}));
            }
            out
        }
        4 => {
            let g = match getref(svc, loot, "reel") {
                Some(g) if g.get("_class").and_then(|v| v.as_str()) == Some("GachaData") => g.get("guid").and_then(|x| x.as_str()).map(String::from),
                _ => None,
            };
            let g = match g { Some(g) => g, None => return vec![] };
            add_map_amount(user, "gachas", &g, amount);
            vec![json!({"type": "Gacha", "gachaReward": {"guid": g, "count": amount}})]
        }
        16 => {
            let cons = match getref(svc, loot, "consumable").and_then(|c| c.get("guid")).and_then(|g| g.as_str()).map(String::from) {
                Some(c) => c, None => return vec![],
            };
            add_map_amount(user, "consumables", &cons, amount);
            vec![json!({"type": "Consumable", "consumableReward": {"guid": cons, "count": amount}})]
        }
        5 => {
            if depth > 8 {
                return vec![];
            }
            let mut out = Vec::new();
            for _ in 0..loot.get("amount").and_then(|v| v.as_i64()).unwrap_or(1).max(1) {
                if let Some(picked) = roll_table(svc, loot.get("nestedLootTable")) {
                    out.extend(grant_loot_depth(svc, user, &picked, mult, rng, depth + 1));
                }
            }
            out
        }
        15 => {
            let nm = match getref(svc, loot, "nodeModifier").and_then(|n| n.get("guid")).and_then(|g| g.as_str()).map(String::from) {
                Some(n) => n, None => return vec![],
            };
            let nid = rng.hex32();
            let element = element_name(loot.get("elementType").and_then(|v| v.as_i64()).unwrap_or(0), rng).to_string();
            user.get_mut("nodeModifiers").unwrap().as_object_mut().unwrap().insert(nid.clone(),
                json!({"id": nid, "guid": nm, "level": 1, "element": element, "new": true}));
            vec![json!({"type": "NodeModifier", "nodeModifierReward": {"guid": nm, "element": element, "nodeModifierInstanceId": nid}})]
        }
        _ => vec![], // 8 None, 17 GachaVoucher, 25 Cosmetic: not tracked yet
    }
}

fn shiny_chance(svc: &PlayerService, variant: &Value) -> f64 {
    let chances = svc.c.game_settings().get("percentChanceForShinyPerRarityTier").and_then(|v| v.as_array());
    let t = variant.get("initialTier").and_then(|v| v.as_i64()).unwrap_or(0);
    chances.and_then(|a| a.get(t as usize)).and_then(|v| v.as_f64()).map(|p| p / 100.0).unwrap_or(0.0)
}

fn roll_table(svc: &PlayerService, table_ref: Option<&Value>) -> Option<Value> {
    let t = table_ref.and_then(|v| v.as_str()).and_then(|r| svc.c.get(r))?;
    let loots = t.get("loots").and_then(|v| v.as_array())?;
    pick_weighted(loots)
}

fn pick_weighted(_weighted: &[Value]) -> Option<Value> {
    // startingLoot does not use weighted tables; full implementation lands with the store/relic work.
    None
}

pub fn bundle(origin: &str, associated_id: &str, items: Vec<Value>) -> Value {
    json!({"key": {"type": origin, "associatedId": associated_id}, "rewards": items})
}
