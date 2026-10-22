"""Player document (what we persist) and its serialization to the client's *ServerData shapes.

Persisted shape (all ids are server generated strings):
  currencies   {"SoftCurrency": n, "HardCurrency": n, "Keys": n, ...}  (CurrencyType names)
  skillPoints  {baseCharacterGuid: n}
  characters   {id: {variant, tier, xp, shiny, nodes[], moves[], assist, energy, energyTs, locked, new}}
  moves        {id: {guid, evolution, stats[{guid, level}], locked, new}}
  team         [characterId, ...]
  tutorial     {progress, completed (TutorialType bitmask), map {name: progress}}
  campaign     {matchGuid: {"completed": bool, "challenges": bitmask}}
"""
import random
import time
import uuid

from .formulas import level_cap

# TutorialType bitmask values and the "cleared" progress per type (TutorialManager constants)
TUTORIALS = {'FTUE': (1, 8), 'MoveEquip': (2, 1), 'SkillTree': (4, 1), 'RiftBattle': (8, 5), 'XPBoost': (16, 1),
             'Maze': (32, 2), 'Pity': (64, 1), 'RaidBoss': (128, 1), 'AutoBattle': (256, 1), 'PrizeFight': (512, 2),
             'MissionControl': (1024, 2)}

# CurrencyType names (enum order) and how UserServerData reads them
CURRENCY_TYPES = ['SoftCurrency', 'HardCurrency', 'GachaShard', 'SkillPoint', 'Key', 'Essence', 'EssenceShard',
                  'BattleCurrency', 'BattleTicketFree', 'BattleTicketPaid', 'SocialGiftStandard', 'SocialGiftGold',
                  'EnergyRefill', 'DailyEventTicket', 'CharacterGachaShard', 'RerollRune', 'UniversalRerollRune',
                  'AssistEvoCurrency', 'GuildGiftStandard', 'GuildGiftPremium', 'GuildOpsPoints', 'RaidBossTicketFree',
                  'RaidBossTicketPaid']
# names accepted in UserServerData.otherCurrencies (keys, shards and essences are per rarity/element)
OTHER_CURRENCIES = ['BronzeKey', 'SilverKey', 'GoldKey', 'DiamondKey', 'SilverGachaShard', 'GoldGachaShard',
                    'DiamondGachaShard', 'CharacterGachaShard', 'BattleCurrency', 'BattleTicketFree', 'BattleTicketPaid',
                    'DailyEventTicket', 'EnergyRefill', 'UniversalRerollRune', 'AssistEvoCurrency',
                    'SocialGiftStandard', 'SocialGiftGold', 'SocialGiftStandardToOpen', 'SocialGiftGoldToOpen',
                    'GuildGiftStandard', 'GuildGiftPremium', 'GuildGiftStandardToOpen', 'GuildGiftPremiumToOpen',
                    'RaidBossTicketFree', 'RaidBossTicketPaid', 'FireEssence', 'WaterEssence', 'AirEssence',
                    'LightEssence', 'DarkEssence', 'FireEssenceShard', 'WaterEssenceShard', 'AirEssenceShard',
                    'LightEssenceShard', 'DarkEssenceShard']

RARITY = ['Bronze', 'Silver', 'Gold', 'Diamond']
ELEMENTS = ['Neutral', 'Fire', 'Water', 'Air', 'Dark', 'Light']  # Element enum order (ElementType)


def new_id():
    return uuid.uuid4().hex


def now_ms():
    return int(time.time() * 1000)


class PlayerService:
    """Builds and mutates player documents using game content + formulas."""

    def __init__(self, content, formulas, config):
        self.c = content
        self.f = formulas
        self.cfg = config
        self.max_energy = 10

    # ---- creation ---------------------------------------------------------------------
    def create(self, device_id=None, handle=None):
        uid = new_id()
        user = {
            'id': uid,
            'publicId': str(random.randint(10 ** 8, 10 ** 9 - 1)),
            'handle': handle or f'Player{random.randint(1000, 9999)}',
            'created': now_ms(),
            'xp': 0,
            'currencies': dict(self.cfg.get('starting_currencies', {})),
            'skillPoints': {},
            'characters': {},
            'moves': {},
            'assistMoves': {},
            'nodeModifiers': {},
            'consumables': {},
            'gachas': {},
            'team': [],
            'variantsEverOwned': {},
            'tutorial': {'progress': 0, 'completed': 0, 'map': {}},
            'campaign': {},
            'fights': {},
            'avatar': '',
            'deviceIds': [device_id] if device_id else [],
        }
        for loot in self.c.economy.get('startingLoot', []):
            self.apply_loot(user, loot, 1)
        if self.cfg.get('skip_tutorials', True):
            self.complete_all_tutorials(user)
        starters = []
        tm = self.c.by_class.get('TutorialManager')
        if tm:
            for gen in next(iter(tm.values())).get('finalTeamCompositionV2', []):
                v = self.c.get(gen.get('characterData'))
                if v:
                    starters.append(v['guid'])
        for guid in self.cfg.get('extra_starting_characters', []):
            starters.append(guid)
        for guid in starters:
            ch = self.grant_character(user, guid)
            if ch and len(user['team']) < 3:
                user['team'].append(ch['id'])
        if user['team']:
            user['avatar'] = user['characters'][user['team'][0]]['variant']
        return user

    def complete_all_tutorials(self, user):
        t = user['tutorial']
        for name, (bit, cleared) in TUTORIALS.items():
            t['completed'] |= bit
            t['map'][name] = cleared
        t['progress'] = TUTORIALS['FTUE'][1]

    # ---- inventory --------------------------------------------------------------------
    def add_currency(self, user, name, amount):
        cur = user['currencies']
        cur[name] = max(0, cur.get(name, 0) + amount)

    def spend(self, user, price):
        """price: {"CurrencyName": amount}. Returns False (and changes nothing) if unaffordable."""
        cur = user['currencies']
        if any(cur.get(k, 0) < v for k, v in price.items()):
            return False
        for k, v in price.items():
            cur[k] -= v
        return True

    def grant_character(self, user, variant_guid, tier=None, xp=0, shiny=False):
        v = self.c.guid(variant_guid)
        if not v or v['_class'] != 'VariantCharacterData':
            return None
        cid = new_id()
        ch = {
            'id': cid, 'variant': variant_guid, 'tier': v.get('initialTier', 0) if tier is None else tier,
            'xp': xp, 'shiny': bool(shiny), 'nodes': [], 'moves': [], 'assist': '',
            'energy': self.max_energy, 'energyTs': 0, 'locked': False, 'new': True,
            'superLevels': [], 'superFeature': 0, 'prestige': 0, 'created': now_ms(),
        }
        start = self.c.get(v.get('startingGear'))
        if start:
            mv = self.grant_move(user, start['guid'], new=False)
            ch['moves'].append(mv['id'])
        user['characters'][cid] = ch
        base = self.c.get(v.get('baseCharacter'))
        if base:
            ever = user['variantsEverOwned'].setdefault(base['guid'], {'normal': [], 'shiny': []})
            lst = ever['shiny' if shiny else 'normal']
            if variant_guid not in lst:
                lst.append(variant_guid)
        return ch

    def shiny_chance(self, variant):
        chances = self.c.game_settings.get('percentChanceForShinyPerRarityTier') or []
        t = variant.get('initialTier', 0)
        return (chances[t] / 100.0) if t < len(chances) else 0.0

    def grant_move(self, user, move_guid, new=True):
        mid = new_id()
        mv = {'id': mid, 'guid': move_guid, 'evolution': 1, 'stats': [], 'locked': False, 'new': new}
        user['moves'][mid] = mv
        return mv

    def apply_loot(self, user, loot, mult=1):
        """Grant a content Loot entry (LootType enum). Returns a list of grant records for reward building."""
        from .rewards import grant_loot
        return grant_loot(self, user, loot, mult)

    # ---- derived values ------------------------------------------------------------------
    def level(self, user):
        return self.f.player_ladder.level_for_xp(user['xp'])

    def char_level(self, ch):
        return self.f.char_ladder(ch['tier']).level_for_xp(ch['xp'], ch['shiny'])

    def char_stats(self, ch):
        v = self.c.guid(ch['variant'])
        lvl = self.char_level(ch)
        lb_buff, atk_buff, fs_add = self.skill_buffs(v, ch)
        lb = self.f.lifebar(v, ch['tier'], lvl, lb_buff)
        at = self.f.attack(v, ch['tier'], lvl, atk_buff)
        sup = max(ch.get('superLevels') or [0])
        fs = self.f.fighter_score(lb, at, fs_add, sup, ch.get('prestige', 0))
        return lvl, lb, at, fs

    def skill_buffs(self, variant, ch):
        from .skilltree import skill_buffs
        return skill_buffs(self.c, variant, ch.get('nodes', []))

    def skill_points_per_level(self, tier):
        econ = self.c.economy
        mults = econ.get('skillPointTierMultipliers') or [1]
        return econ.get('skillPointsOnCharacterLevelUp', 1) * mults[min(tier, len(mults) - 1)]

    def add_char_xp(self, user, ch, amount):
        """Adds XP (capped at the tier's level cap). Level-ups grant skill points to the base character.
        Returns (xp_before, skill_points_granted, base_guid)."""
        lad = self.f.char_ladder(ch['tier'])
        before = ch['xp']
        lvl_before = lad.level_for_xp(before, ch['shiny'])
        ch['xp'] = min(before + max(0, int(amount)), lad.xp_for_level(lad.cap, ch['shiny']))
        gained = lad.level_for_xp(ch['xp'], ch['shiny']) - lvl_before
        sp = gained * self.skill_points_per_level(ch['tier']) if gained > 0 else 0
        base = (self.c.get((self.c.guid(ch['variant']) or {}).get('baseCharacter')) or {}).get('guid')
        if sp and base:
            user['skillPoints'][base] = user['skillPoints'].get(base, 0) + sp
        return before, sp, base

    def feeding_xp(self, fed, food):
        """EconomySettings.CalculateFeedingXp"""
        from .formulas import f32
        econ = self.c.economy
        by_tier = econ.get('feedingXpByTier') or [0]
        lad = self.f.char_ladder(food['tier'])
        capped = min(food['xp'], lad.xp_for_level(lad.cap, food['shiny']))
        base = f32(by_tier[min(food['tier'], len(by_tier) - 1)] + f32(econ.get('feedingMultiplierOfTotalXp', 0) * capped))
        fv, dv = self.c.guid(fed['variant']) or {}, self.c.guid(food['variant']) or {}
        elem = f32(econ.get('feedingMultiplierForSameElement', 0) * base) \
            if fv.get('elementAffiliation') == dv.get('elementAffiliation') else 0.0
        same = f32(econ.get('feedingMultiplierForSameBaseCharacter', 0) * base) \
            if fv.get('baseCharacter') == dv.get('baseCharacter') else 0.0
        return int(f32(f32(base + same) + elem))

    def feeding_skill_points(self, food):
        """EconomySettings.CalculateFeedingSkillPoints: skill points the food would still have earned."""
        return max(0, (level_cap(food['tier']) - self.char_level(food)) * self.skill_points_per_level(food['tier']))

    def refresh_energy(self, ch, now=None):
        now = now or now_ms()
        cooldown = self.c.game_settings.get('energyCooldownSeconds', 600) * 1000
        if ch['energy'] >= self.max_energy:
            ch['energyTs'] = 0
            return
        if not ch['energyTs']:
            ch['energyTs'] = now + cooldown
        while ch['energy'] < self.max_energy and now >= ch['energyTs']:
            ch['energy'] += 1
            ch['energyTs'] = ch['energyTs'] + cooldown if ch['energy'] < self.max_energy else 0

    # ---- serialization -----------------------------------------------------------------
    def character_data(self, user, ch):
        self.refresh_energy(ch)
        lvl, lb, at, _ = self.char_stats(ch)
        lad = self.f.char_ladder(ch['tier'])
        return {
            'id': ch['id'],
            'variantGuid': ch['variant'],
            'tier': ch['tier'],
            'experience': ch['xp'],
            'floatLevel': lad.float_level(ch['xp'], ch['shiny']),
            'level': lvl,
            'skillNodeGuids': list(ch.get('nodes', [])),
            'superAbilityLevels': list(ch.get('superLevels') or []),
            'superAbilityActiveFeatureSet': ch.get('superFeature', 0),
            'prestigeAbilityLevel': ch.get('prestige', 0),
            'energy': ch['energy'],
            'nextEnergyTimestamp': ch['energyTs'] or 0,
            'energyMp': self.max_energy,
            'nextEnergyTimeMpStamp': 0,
            'lifebar': lb,
            'attack': at,
            'specialMoveIds': [m for m in ch.get('moves', []) if m in user['moves']],
            'assistMoveInstanceId': ch.get('assist') or None,
            'isLocked': ch.get('locked', False),
            'isNew': ch.get('new', False),
            'isShiny': ch.get('shiny', False),
            'isUsedInDeployment': False,
        }

    def move_data(self, mv):
        g = self.c.guid(mv['guid']) or {}
        return {
            'id': mv['id'],
            'moveGuid': mv['guid'],
            'evolution': mv.get('evolution', 1),
            'saleValue': {'amounts': {'SoftCurrency': self.move_sale_value(g, mv)}},
            'moveStatInstances': [{'guid': s['guid'], 'level': s.get('level', 1)} for s in mv.get('stats', [])],
            'isLocked': mv.get('locked', False),
            'isNew': mv.get('new', False),
        }

    def move_sale_value(self, gear, mv):
        prices = self.c.economy.get('baseGearSalePricePerTier') or [0]
        t = max(0, min(gear.get('tier', 0), len(prices) - 1))
        return int(prices[t] * self.c.economy.get('gearSalePercent', 1.0))

    def node_modifier_data(self, nm):
        return {'id': nm['id'], 'guid': nm['guid'], 'inUse': False, 'characterGuid': nm.get('character', ''),
                'level': nm.get('level', 1), 'softCurrencyValue': 0, 'element': nm.get('element', 'Neutral'),
                'isLocked': nm.get('locked', False), 'isNew': nm.get('new', False)}

    def currency_balance(self, amount):
        return {'paid': 0, 'free': int(amount), 'bonus': 0}

    def user_data(self, user, full=True):
        cur = user['currencies']
        lvl = self.level(user)
        t = user['tutorial']
        out = {
            'id': user['id'],
            'publicId': user['publicId'],
            'publicUserId': user['publicId'],
            'handle': user['handle'],
            'hasFreeHandleChange': True,
            'metricsId': user['id'],
            'level': lvl,
            'floatLevel': self.f.player_ladder.float_level(user['xp']),
            'experience': user['xp'],
            'tutorialProgress': t['progress'],
            'completedTutorials': t['completed'],
            'tutorialProgressMap': dict(t['map']),
            'hardCurrency': self.currency_balance(cur.get('HardCurrency', 0)),
            'softCurrency': self.currency_balance(cur.get('SoftCurrency', 0)),
            'otherCurrencies': [{'type': k, 'amount': cur[k]} for k in OTHER_CURRENCIES if cur.get(k)],
            'skillPoints': [{'guid': g, 'amount': n} for g, n in user['skillPoints'].items()],
            'rerollRunes': [{'guid': g, 'amount': n} for g, n in user.get('rerollRunes', {}).items()],
            'consumables': [{'guid': g, 'count': n, 'isNew': False} for g, n in user['consumables'].items() if n > 0],
            'items': [{'guid': g, 'count': n} for g, n in user['gachas'].items() if n > 0],
            'teamIds': [cid for cid in user['team'] if cid in user['characters']],
            'repCharacterId': user.get('avatar') or '',
            'tags': [],
            'adsAvailable': 0,
            'nextAdGeneratedTimestamp': 0,
            'freeBattleTicketRefreshDate': 0,
            'dailyEventTicketRefreshDate': 0,
            'dailyRaidBossTicketRefreshDate': 0,
            'removeFriendsDailyTimestampReset': 0,
            'numberOfFriendsRemoved': 0,
            'tier': 0,
            'accounts': [],
            'variantsEverOwned': [{'guid': g, 'variantsEverOwned': e['normal'], 'variantsEverOwnedShiny': e['shiny']}
                                  for g, e in user['variantsEverOwned'].items()],
            'characterCollectionCapacity': self.cfg.get('collection_capacity', 1000),
            'gearCollectionCapacity': self.cfg.get('collection_capacity', 1000),
            'assistMoveCollectionCapacity': self.cfg.get('collection_capacity', 1000),
            'nodeModifierCollectionCapacity': self.cfg.get('collection_capacity', 1000),
            'characterCollectionCount': len(user['characters']),
            'moveCollectionCount': len(user['moves']),
            'assistMoveCollectionCount': len(user['assistMoves']),
            'nodeModifierCollectionCount': len(user['nodeModifiers']),
            'newPortraitCount': 0,
            'eventLockedCharacterIdMap': {},
            'claimableChallengeCount': 0,
            'claimableChallengeRewardThresholdCount': 0,
            'challengeExpirationDate': 0,
            'newChallengesAvailable': False,
            'battlePassPoints': 0,
            'battlePassPremiumActive': False,
            'battlePassNotificationCount': 0,
            'battlePassAutoClaimedChallenges': [],
            'adIds': {},
            'topTeamPower': self.top_team_power(user),
            'cosmeticsInstance': {'portraitsOwned': [], 'newPortraits': []},
        }
        if full:
            out['characters'] = [self.character_data(user, ch) for ch in user['characters'].values()]
            out['specialMoves'] = [self.move_data(m) for m in user['moves'].values()]
            out['assistMoves'] = [self.move_data(m) for m in user['assistMoves'].values()]
            out['nodeModifiers'] = [self.node_modifier_data(n) for n in user['nodeModifiers'].values()]
        return out

    def top_team_power(self, user):
        scores = sorted((self.char_stats(ch)[3] for ch in user['characters'].values()), reverse=True)
        return sum(scores[:3])
