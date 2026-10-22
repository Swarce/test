"""Loot granting and reward serialization.

Content Loot entries use the LootType enum; the client receives rewards as RewardServerData:
  {"key": {"type": <RewardOrigin>, "associatedId": str},
   "rewards": [{"type": <RewardType>, "<kind>Reward": {...}}, ...]}
(see RewardLootUtil.ParseLootFromRewardDict / ParseRewardListFromServerReward).
"""
import random

ELEMENTS = ['Neutral', 'Fire', 'Water', 'Air', 'Dark', 'Light']
RARITY = ['Bronze', 'Silver', 'Gold', 'Diamond']

# LootType -> currency name for the simple, amount-only loot kinds
SIMPLE = {
    0: 'SoftCurrency', 1: 'HardCurrency', 12: 'BattleCurrency', 13: 'BattleTicketFree', 14: 'BattleTicketPaid',
    18: 'SocialGiftStandard', 19: 'SocialGiftGold', 20: 'EnergyRefill', 21: 'DailyEventTicket',
    22: 'CharacterGachaShard', 24: 'UniversalRerollRune', 27: 'AssistEvoCurrency', 28: 'GuildGiftStandard',
    29: 'GuildGiftPremium', 30: 'GuildOpsPoints', 31: 'RaidBossTicketFree', 32: 'RaidBossTicketPaid',
}


def currency_item(name, amount, claimable=False):
    key = 'claimableCurrencyReward' if claimable else 'currencyReward'
    return {'type': 'Currency', key: {'amounts': {name: amount}}}


def _element(loot):
    e = loot.get('elementType', 0)
    if e == 6 or e >= len(ELEMENTS):
        e = random.randint(1, 5)
    return ELEMENTS[e]


def pick_weighted(weighted):
    """Pick one entry from a list of {"loot", "weight"} (WeightedLoot)."""
    if not weighted:
        return None
    total = sum(max(0, x.get('weight', 0)) for x in weighted)
    if total <= 0:
        return random.choice(weighted)['loot']
    r = random.uniform(0, total)
    for x in weighted:
        r -= max(0, x.get('weight', 0))
        if r <= 0:
            return x['loot']
    return weighted[-1]['loot']


def roll_table(content, table_ref):
    """Pick one weighted loot from a LootTable."""
    t = content.get(table_ref)
    return pick_weighted(t.get('loots')) if t else None


def grant_loot(svc, user, loot, mult=1, depth=0):
    """Apply one content Loot to the player; returns client reward items."""
    c = svc.c
    lt = loot.get('lootType', 8)
    amount = int(round(loot.get('amount', 0) * mult))
    if lt in SIMPLE:
        if amount <= 0:
            return []
        svc.add_currency(user, SIMPLE[lt], amount)
        return [currency_item(SIMPLE[lt], amount)]
    if lt == 6:  # GachaShards per rarity
        name = RARITY[max(1, min(loot.get('rarityTier', 1), 3))] + 'GachaShard'
        svc.add_currency(user, name, amount)
        return [currency_item(name, amount)]
    if lt == 9:  # Keys per rarity
        name = RARITY[min(loot.get('rarityTier', 0), 3)] + 'Key'
        svc.add_currency(user, name, amount)
        return [currency_item(name, amount)]
    if lt in (10, 11):  # (essence) shards / essences per element
        name = _element(loot) + ('EssenceShard' if lt == 10 else 'Essence')
        svc.add_currency(user, name, amount)
        return [currency_item(name, amount)]
    if lt == 23:  # RerollRune per base character
        base = c.get(loot.get('baseCharacter'))
        if not base:
            return []
        rr = user.setdefault('rerollRunes', {})
        rr[base['guid']] = rr.get(base['guid'], 0) + amount
        return [{'type': 'BaseCharacterCurrency', 'baseCharacterCurrencyReward':
                 {'baseCharacterGuid': base['guid'], 'amounts': {'RerollRune': amount}}}]
    if lt == 7:  # SkillPoints per base character
        base = c.get(loot.get('baseCharacter'))
        if not base:
            return []
        sp = user['skillPoints']
        sp[base['guid']] = sp.get(base['guid'], 0) + amount
        return [{'type': 'BaseCharacterCurrency', 'baseCharacterCurrencyReward':
                 {'baseCharacterGuid': base['guid'], 'amounts': {'SkillPoint': amount}}}]
    if lt == 3:  # Character
        v = c.get(loot.get('character'))
        if not v:
            return []
        out = []
        for _ in range(max(1, loot.get('amount', 1))):
            shiny = bool(loot.get('forceShiny')) or random.random() < svc.shiny_chance(v)
            ch = svc.grant_character(user, v['guid'], tier=loot.get('rarityTier') or None, shiny=shiny)
            out.append({'type': 'Character', 'characterReward': {
                'variantGuid': v['guid'], 'characterInstanceId': ch['id'], 'isShiny': ch['shiny']},
                'isFirstTimeAppearance': False})
        return out
    if lt in (2, 26):  # SpecialMove / AssistMove
        g = c.get(loot.get('gear'))
        if not g:
            return []
        out = []
        for _ in range(max(1, loot.get('amount', 1))):
            mv = svc.grant_move(user, g['guid'])
            key = 'moveReward' if lt == 2 else 'assistMoveReward'
            out.append({'type': 'Move' if lt == 2 else 'AssistMove',
                        key: {'moveGuid': g['guid'], 'moveInstanceId': mv['id'], 'statGuids': []}})
        return out
    if lt == 4:  # Gacha (relic)
        g = c.get(loot.get('reel')) if loot.get('reel') else None
        g = g if g and g.get('_class') == 'GachaData' else None
        if not g:
            return []
        user['gachas'][g['guid']] = user['gachas'].get(g['guid'], 0) + amount
        return [{'type': 'Gacha', 'gachaReward': {'guid': g['guid'], 'count': amount}}]
    if lt == 16:  # Consumable
        cons = c.get(loot.get('consumable'))
        if not cons:
            return []
        user['consumables'][cons['guid']] = user['consumables'].get(cons['guid'], 0) + amount
        return [{'type': 'Consumable', 'consumableReward': {'guid': cons['guid'], 'count': amount}}]
    if lt == 5:  # nested LootTable
        if depth > 8:
            return []
        out = []
        for _ in range(max(1, loot.get('amount', 1))):
            picked = roll_table(c, loot.get('nestedLootTable'))
            if picked:
                out += grant_loot(svc, user, picked, mult, depth + 1)
        return out
    if lt == 15:  # NodeModifier
        nm = c.get(loot.get('nodeModifier'))
        if not nm:
            return []
        from .player import new_id
        nid = new_id()
        element = _element(loot)
        user['nodeModifiers'][nid] = {'id': nid, 'guid': nm['guid'], 'level': 1, 'element': element, 'new': True}
        return [{'type': 'NodeModifier', 'nodeModifierReward': {'guid': nm['guid'], 'element': element,
                                                                'nodeModifierInstanceId': nid}}]
    # 8 None, 17 GachaVoucher, 25 Cosmetic: not tracked yet
    return []


def bundle(origin, associated_id, items):
    return {'key': {'type': origin, 'associatedId': associated_id or ''}, 'rewards': items}
