"""Opponent generation, following the client's CharacterGenerator:

  tier   = max(variant.initialTier, generator.minCharacterTier)
  level  = random in levelRange, clamped to the tier's level cap
  skills = (level-1) * EconomySettings.GetCharacterLevelUpSkillPoints(tier) + extraSkillPoints,
           auto-spent along GameSettings.aiSkillTree.purchaseOrder
  stats  = computed with the AI skill tree buffs
"""
import random

from .formulas import level_cap
from .player import new_id
from .skilltree import skill_buffs


class EnemyFactory:
    def __init__(self, content, formulas):
        self.c = content
        self.f = formulas
        gs = content.game_settings
        self.ai_tree = content.get(gs.get('_aiSkillTree'))
        self._parents = {}
        for n in content.all('SkillTreeNode'):
            for child in n.get('connections') or []:
                ch = content.get(child)
                if ch:
                    self._parents.setdefault(ch['guid'], []).append(n['guid'])

    def _pick_variant(self, gen, used):
        v = self.c.get(gen.get('characterData'))
        if v:
            return v, False
        pool = self.c.get(gen.get('randomCharacterList'))
        cands = [self.c.get(r) for r in (pool or {}).get('characters', [])]
        cands = [x for x in cands if x and x['guid'] not in used] or [x for x in cands if x]
        if not cands:
            cands = [x for x in self.c.all('VariantCharacterData') if x.get('isInCompetitiveCollection')]
        return random.choice(cands), True

    def _auto_spend(self, tier, level, extra):
        econ = self.c.economy
        mults = econ.get('skillPointTierMultipliers') or [1]
        per_level = econ.get('skillPointsOnCharacterLevelUp', 1) * mults[min(tier, len(mults) - 1)]
        points = per_level * (level - 1) + extra
        bought = []
        if not self.ai_tree:
            return bought
        have = set()
        for ref in self.ai_tree.get('purchaseOrder', []):
            if points <= 0:
                break
            n = self.c.get(ref)
            if not n or n['guid'] in have or n.get('isHidden'):
                continue
            if n.get('requiredLevel', 0) > level:
                continue
            parents = self._parents.get(n['guid'], [])
            if parents and not any(p in have for p in parents):
                continue
            cost = n.get('skillPointCost', 1) * mults[min(tier, len(mults) - 1)]
            if cost > points:
                continue
            points -= cost
            have.add(n['guid'])
            bought.append(n['guid'])
        return bought

    def _moves(self, variant, gen, level):
        base = self.c.get(variant.get('baseCharacter')) or {}
        pool = [self.c.get(r) for r in (base.get('specialMoves') or []) + (base.get('blockbusters') or [])]
        pool = [g for g in pool if g]
        out, used = [], set()
        for gg in gen.get('gearGenerators') or []:
            g = self.c.get(gg.get('gearData'))
            if not g:
                mask = gg.get('gearTierMask') or 0xF
                cands = [x for x in pool if (mask >> x.get('tier', 0)) & 1 and x['guid'] not in used]
                if not cands:
                    continue
                g = random.choice(cands)
            used.add(g['guid'])
            lr = gg.get('levelRange') or {'x': 1, 'y': 1}
            lvl = random.randint(int(lr['x']), max(int(lr['x']), int(lr['y'])))
            out.append({'id': new_id(), 'moveGuid': g['guid'], 'evolution': max(1, min(15, lvl)),
                        'moveStatInstances': [], 'isLocked': False, 'isNew': False})
        return out

    def generate(self, gen, used=None, fs_mult_addition=0.0):
        used = used if used is not None else set()
        variant, is_random = self._pick_variant(gen, used)
        used.add(variant['guid'])
        tier = max(variant.get('initialTier', 0), gen.get('minCharacterTier', 0))
        lr = gen.get('levelRange') or {'x': 1, 'y': 1}
        lo, hi = int(lr['x']), max(int(lr['x']), int(lr['y']))
        level = max(1, min(random.randint(lo, hi), level_cap(tier)))
        nodes = self._auto_spend(tier, level, gen.get('extraSkillPoints', 0))
        lb_buff, at_buff, fs_add = skill_buffs(self.c, variant, nodes)
        lifebar = self.f.lifebar(variant, tier, level, lb_buff)
        attack = self.f.attack(variant, tier, level, at_buff)
        sr = gen.get('superAbilityLevelRange') or {'x': 0, 'y': 0}
        super_level = random.randint(int(sr['x']), max(int(sr['x']), int(sr['y'])))
        pr = gen.get('prestigeAbilityLevelRange') or {'x': 0, 'y': 0}
        prestige = random.randint(int(pr['x']), max(int(pr['x']), int(pr['y'])))
        fs = self.f.fighter_score(lifebar, attack, fs_add + fs_mult_addition, super_level, prestige)
        return {
            'guid': variant['guid'], 'level': level, 'tier': tier, 'lifebar': lifebar, 'attack': attack,
            'fighterScore': fs, 'isRandom': is_random, 'isShiny': False,
            'specialMoveInstances': self._moves(variant, gen, level),
            'skillNodeGuids': nodes,
            'superAbilityLevels': [super_level] if super_level else [],
            'superAbilityActiveFeatureSet': gen.get('superAbilityFeatureSet', 0),
            'prestigeAbilityLevel': prestige,
        }

    def generate_team(self, fight):
        used = set()
        add = fight.get('enemyFighterFSMultiplierAddition', 0.0) or 0.0
        return [self.generate(g, used, add) for g in fight.get('enemyCharacterGenerators') or []]


def preview(enemy):
    """XsCharacter shape used by FightServerData.enemies (map/match preview)."""
    return {'variantGuid': enemy['guid'], 'tier': enemy['tier'], 'fighterScore': enemy['fighterScore'],
            'isShiny': enemy['isShiny'], 'isSuperAbilityUnlocked': bool(enemy['superAbilityLevels']),
            'isPrestigeAbilityUnlocked': enemy['prestigeAbilityLevel'] > 0}
