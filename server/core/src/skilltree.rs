//! Skill-tree buffs (VariantCharacterData.CalculateSkillTreeBuff / SkillTree.GetFSMultiplierAddition).

use crate::content::Content;

const LIFEBAR: i64 = 4;
const ATTACK: i64 = 5;
const BUFF_SCALE: f32 = 0.01;

/// Returns (lifebar_buff, attack_buff, fs_mult_addition), all f32.
pub fn skill_buffs(content: &Content, node_guids: &[String]) -> (f32, f32, f32) {
    let mut lb: f32 = 0.0;
    let mut at: f32 = 0.0;
    let mut fs: f32 = 0.0;
    for g in node_guids {
        let n = match content.guid(g) {
            Some(n) if n.get("_class").and_then(|v| v.as_str()) == Some("SkillTreeNode") => n,
            _ => continue,
        };
        let t = n.get("nodeType").and_then(|v| v.as_i64()).unwrap_or(-1);
        let val = n.get("value").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
        if t == LIFEBAR {
            lb += val;
        } else if t == ATTACK {
            at += val;
        }
        fs += n.get("fsMultiplierAddition").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32;
    }
    let gs_mult = content.game_settings().get("skillNodeFSGlobalMultiplier").and_then(|v| v.as_f64()).unwrap_or(1.0) as f32;
    (lb * BUFF_SCALE, at * BUFF_SCALE, fs * gs_mult)
}

/// Element name by index (ElementType), with 6/out-of-range meaning "random non-neutral".
pub fn element_name(idx: i64, rng: &mut crate::rng::Rng) -> &'static str {
    const ELEMENTS: [&str; 6] = ["Neutral", "Fire", "Water", "Air", "Dark", "Light"];
    let i = if idx == 6 || idx < 0 || idx as usize >= ELEMENTS.len() {
        rng.range(1, 6) as usize
    } else {
        idx as usize
    };
    ELEMENTS[i]
}

pub fn rarity_name(idx: i64) -> &'static str {
    const RARITY: [&str; 4] = ["Bronze", "Silver", "Gold", "Diamond"];
    RARITY[idx.clamp(0, 3) as usize]
}
