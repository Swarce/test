"""Skill tree effects (VariantCharacterData.CalculateSkillTreeBuff / SkillTree.GetFSMultiplierAddition)."""
from .formulas import f32

LIFEBAR, ATTACK = 4, 5
BUFF_SCALE = 0.01  # constant applied to summed node values


def skill_buffs(content, variant, node_guids):
    lb = at = fs = 0.0
    for g in node_guids:
        n = content.guid(g)
        if not n or n.get('_class') != 'SkillTreeNode':
            continue
        t = n.get('nodeType')
        if t == LIFEBAR:
            lb = f32(lb + n.get('value', 0.0))
        elif t == ATTACK:
            at = f32(at + n.get('value', 0.0))
        fs = f32(fs + n.get('fsMultiplierAddition', 0.0))
    gs = content.game_settings
    return f32(lb * BUFF_SCALE), f32(at * BUFF_SCALE), f32(fs * gs.get('skillNodeFSGlobalMultiplier', 1.0))
