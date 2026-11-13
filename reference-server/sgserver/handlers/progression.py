"""Character and move progression: skill tree, power-up (feeding), evolution, supers, energy,
move upgrades and selling."""
from ..formulas import level_cap
from ..player import CURRENCY_TYPES
from ..protocol import ApiError
from ..rewards import ELEMENTS, RARITY, bundle, currency_item
from ..server import route


def _char(ctx, cid):
    ch = ctx.user['characters'].get(cid)
    if not ch:
        raise ApiError(404, 'CharacterNotFound', 'unknown character')
    return ch


def _base_guid(ctx, ch):
    v = ctx.content.guid(ch['variant']) or {}
    return (ctx.content.get(v.get('baseCharacter')) or {}).get('guid', '')


def currency_name(ctx, cur, ch=None):
    """Content Currency entry -> player currency key, resolving per-element/rarity context."""
    t = CURRENCY_TYPES[cur['currencyType']]
    if t in ('Essence', 'EssenceShard'):
        el = cur.get('elementType', 0)
        if ch is not None and not cur.get('overrideContext'):
            el = (ctx.content.guid(ch['variant']) or {}).get('elementAffiliation', el)
        return ELEMENTS[min(el, 5)] + t
    if t in ('GachaShard', 'Key'):
        return RARITY[min(cur.get('rarityTier', 0), 3)] + t
    return t


class Bill:
    """Accumulates a cost made of currencies and base-character skill points, then charges it atomically."""

    def __init__(self, ctx):
        self.ctx = ctx
        self.cur = {}
        self.sp = {}

    def add(self, name, amount):
        if amount > 0:
            self.cur[name] = self.cur.get(name, 0) + int(amount)
        return self

    def add_sp(self, base, amount):
        if amount > 0:
            self.sp[base] = self.sp.get(base, 0) + int(amount)
        return self

    def add_price(self, price, ch=None, mult=1):
        for cur in (price or {}).get('currencies') or []:
            if CURRENCY_TYPES[cur['currencyType']] == 'SkillPoint' and ch is not None:
                self.add_sp(_base_guid(self.ctx, ch), cur['amount'] * mult)
            else:
                self.add(currency_name(self.ctx, cur, ch), cur['amount'] * mult)
        return self

    def charge(self):
        u = self.ctx.user
        if any(u['currencies'].get(k, 0) < v for k, v in self.cur.items()) or \
                any(u['skillPoints'].get(k, 0) < v for k, v in self.sp.items()):
            raise ApiError(400, 'NotEnoughCurrency', 'not enough currency')
        for k, v in self.cur.items():
            u['currencies'][k] -= v
        for k, v in self.sp.items():
            u['skillPoints'][k] -= v
        self.ctx.save()


def _tier_mult(lst, tier):
    lst = lst or [1]
    return lst[min(tier, len(lst) - 1)]


# ---- skill tree --------------------------------------------------------------------------------

def _tree(ctx, ch):
    v = ctx.content.guid(ch['variant']) or {}
    return ctx.content.get(v.get('skillTree'))


def _node_bill(ctx, ch, node, shortcut):
    econ = ctx.content.economy
    bill = Bill(ctx)
    if shortcut:
        sc = node.get('shortcutCost') or {}
        bill.add(currency_name(ctx, sc, ch), sc.get('amount', 0) * _tier_mult(econ.get('skillNodeHCCostTierMultipliers'), ch['tier']))
    else:
        bill.add_sp(_base_guid(ctx, ch), node.get('skillPointCost', 0) * ctx.app.players.skill_points_per_level(ch['tier']))
        cost = node.get('cost') or {}
        mult = 1 if cost.get('currencyType') == 4 else _tier_mult(econ.get('skillNodeSCCostTierMultipliers'), ch['tier'])
        bill.add(currency_name(ctx, cost, ch), cost.get('amount', 0) * mult)
    return bill


def _can_buy(ctx, ch, node):
    if node['guid'] in ch['nodes']:
        return False
    if node.get('requiredLevel', 0) > ctx.app.players.char_level(ch):
        return False
    parents = ctx.app.enemies._parents.get(node['guid'], [])
    multi = [ctx.content.get(r) for r in node.get('multiParentNodes') or []]
    if multi and not all(m and m['guid'] in ch['nodes'] for m in multi):
        return False
    tree = _tree(ctx, ch)
    in_tree = set()
    if tree:
        in_tree = {(ctx.content.get(r) or {}).get('guid') for r in tree.get('purchaseOrder') or []}
    parents = [p for p in parents if not in_tree or p in in_tree]
    if parents and not any(p in ch['nodes'] for p in parents):
        return False
    excl = [(ctx.content.get(r) or {}).get('guid') for r in node.get('mutuallyExclusiveNodes') or []]
    return not any(e in ch['nodes'] for e in excl)


@route('PUT', r'character/(?P<cid>[^/]+)/skilltree/buy/(?P<node>[^/]+)', b64=True, sign=True)
def buy_skill_node(ctx, cid, node):
    ch = _char(ctx, cid)
    n = ctx.content.guid(node)
    if not n or n.get('_class') != 'SkillTreeNode':
        raise ApiError(404, 'NodeNotFound', 'unknown skill node')
    if n['guid'] in ch['nodes']:
        return {}
    if not _can_buy(ctx, ch, n):
        raise ApiError(400, 'NodeLocked', 'node requirements not met')
    _node_bill(ctx, ch, n, bool(ctx.body.get('shortcut'))).charge()
    ch['nodes'].append(n['guid'])
    from .challenges import track
    track(ctx, 'upgrade_skill')
    ctx.save()
    return {}


@route('PUT', r'character/(?P<cid>[^/]+)/skilltree/buyAll', b64=True, sign=True)
def buy_all_skill_nodes(ctx, cid):
    ch = _char(ctx, cid)
    tree = _tree(ctx, ch)
    for ref in (tree or {}).get('purchaseOrder') or []:
        n = ctx.content.get(ref)
        if not n or n['guid'] in ch['nodes'] or not _can_buy(ctx, ch, n):
            continue
        try:
            _node_bill(ctx, ch, n, False).charge()
        except ApiError:
            continue
        ch['nodes'].append(n['guid'])
    ctx.save()
    return {}


# ---- power-up / evolve -------------------------------------------------------------------------

@route('PUT', r'character/powerUpCharacter')
def power_up(ctx):
    u = ctx.user
    svc = ctx.app.players
    ch = _char(ctx, ctx.q('cId'))
    food_ids = [f for f in ctx.q_list('fIds') if f != ch['id']]
    limit = ctx.content.economy.get('feedingCardLimit', 5)
    if len(food_ids) > limit:
        raise ApiError(400, 'TooManyCards', 'too many characters to feed')
    xp = 0
    sp_items = []
    for fid in food_ids:
        food = _char(ctx, fid)
        if food.get('locked') or fid in u['team']:
            raise ApiError(400, 'CharacterLocked', 'cannot feed a locked or team character')
        xp += svc.feeding_xp(ch, food)
        sp = svc.feeding_skill_points(food)
        base = _base_guid(ctx, food)
        if sp and base:
            u['skillPoints'][base] = u['skillPoints'].get(base, 0) + sp
            sp_items.append({'type': 'BaseCharacterCurrency', 'baseCharacterCurrencyReward':
                             {'baseCharacterGuid': base, 'amounts': {'SkillPoint': sp}}})
    for guid, count in (ctx.body.get('consumablesToConsumeMap') or {}).items():
        cons = ctx.content.guid(guid)
        count = int(count)
        if not cons or count <= 0:
            continue
        if u['consumables'].get(guid, 0) < count:
            raise ApiError(400, 'NotEnoughConsumables', 'not enough consumables')
        u['consumables'][guid] -= count
        if cons.get('consumableType') == 1:  # flat XP consumable
            xp += int(cons.get('effectAmount', 0)) * count
    for fid in food_ids:
        _remove_character(u, fid)
    lad = ctx.app.formulas.char_ladder(ch['tier'])
    before, sp, base = svc.add_char_xp(u, ch, xp)
    items = [{'type': 'CharacterXp', 'characterXpReward': {
        'instanceId': ch['id'], 'total': ch['xp'], 'gained': ch['xp'] - before,
        'level': lad.level_for_xp(ch['xp'], ch['shiny']),
        'from': lad.float_level(before, ch['shiny']), 'to': lad.float_level(ch['xp'], ch['shiny'])}}]
    if sp:
        sp_items.append({'type': 'BaseCharacterCurrency', 'baseCharacterCurrencyReward':
                         {'baseCharacterGuid': base, 'amounts': {'SkillPoint': sp}}})
    ctx.save()
    return {'data': {'rewards': [bundle('CharacterFeed', ch['id'], items + sp_items)]}}


def _remove_character(u, cid):
    u['characters'].pop(cid, None)
    u['team'] = [c for c in u['team'] if c != cid]
    u['defenseTeam'] = [c for c in u.get('defenseTeam', []) if c != cid]


@route('PUT', r'character/(?P<cid>[^/]+)/evolve')
def evolve(ctx, cid):
    u = ctx.user
    svc = ctx.app.players
    econ = ctx.content.economy
    ch = _char(ctx, cid)
    tier = ch['tier']
    if tier >= 3:
        raise ApiError(400, 'MaxTier', 'already diamond')
    level = svc.char_level(ch)
    if level < level_cap(tier):
        raise ApiError(400, 'NotMaxLevel', 'character must be max level')
    food_ids = [f for f in ctx.q_list('fIds') if f != cid]
    need = _tier_mult(econ.get('cardsRequiredForEvolutionByTier'), tier)
    if len(food_ids) < need:
        raise ApiError(400, 'NotEnoughCards', 'not enough characters')
    for fid in food_ids:
        if _char(ctx, fid).get('locked'):
            raise ApiError(400, 'CharacterLocked', 'locked character')
    prices = econ.get('priceForEvolutionByTier') or []
    if tier < len(prices):
        Bill(ctx).add_price(prices[tier], ch).charge()
    for fid in food_ids:
        _remove_character(u, fid)
    ch['tier'] = tier + 1
    ch['xp'] = ctx.app.formulas.char_ladder(ch['tier']).xp_for_level(level, ch['shiny'])
    ctx.save()
    return {'data': {'rewards': []}}


@route('POST', r'character/(?P<cid>[^/]+)/convert')
def convert_max_tier(ctx, cid):
    return {'data': []}


# ---- super abilities -------------------------------------------------------------------------------

def _super(ctx, cid, action):
    econ = ctx.content.economy
    ch = _char(ctx, cid)
    feature = int(ctx.q('feature', 0) or 0)
    levels = ch.setdefault('superLevels', [])
    while len(levels) <= feature:
        levels.append(0)
    key = {'unlock': 'superAbilityUnlockCost', 'upgrade': 'superAbilityUpgradeCost', 'swap': 'superAbilitySwapCost'}[action]
    Bill(ctx).add_price(econ.get(key), ch, _tier_mult(econ.get('superAbilityCostTierMultipliers'), ch['tier'])).charge()
    if action == 'unlock':
        levels[feature] = max(1, levels[feature])
        ch['superFeature'] = feature
    elif action == 'upgrade':
        levels[feature] = min(levels[feature] + 1, 10)
    else:
        ch['superFeature'] = feature
    ctx.save()
    return {}


@route('PUT', r'character/(?P<cid>[^/]+)/super/(?P<action>unlock|upgrade|swap)')
def super_ability(ctx, cid, action):
    return _super(ctx, cid, action)


# ---- energy ------------------------------------------------------------------------------------------

@route('PUT', r'characters/refill')
def refill_energy(ctx):
    u = ctx.user
    ids = [c for c in ctx.q_list('ids') if c in u['characters']]
    if not ids:
        return {}
    if not ctx.q_bool('adRefill'):
        if u['currencies'].get('EnergyRefill', 0) > 0:
            u['currencies']['EnergyRefill'] -= 1
        else:
            Bill(ctx).add('HardCurrency', ctx.content.economy.get('energyRefillCost', 0)).charge()
    for cid in ids:
        ch = u['characters'][cid]
        ch['energy'] = ctx.app.players.max_energy
        ch['energyTs'] = 0
    ctx.save()
    return {}


# ---- moves -------------------------------------------------------------------------------------------

def _move_pool(ctx):
    return ctx.user['assistMoves' if ctx.q_bool('isAssist') else 'moves']


@route('PUT', r'(?P<kind>move|assistMove)/(?P<mid>[^/]+)/evolve')
def evolve_move(ctx, kind, mid):
    pool = ctx.user['assistMoves' if kind == 'assistMove' else 'moves']
    mv = pool.get(mid)
    if not mv:
        raise ApiError(404, 'MoveNotFound', 'unknown move')
    econ = ctx.content.economy
    g = ctx.content.guid(mv['guid']) or {}
    levels = max(1, int(ctx.q('levels', 1) or 1))
    costs = econ.get('specialEvolutionCosts') or []
    tm = _tier_mult(econ.get('specialEvolutionCostsTierMultiplier'), g.get('tier', 0))
    bill = Bill(ctx)
    lvl = mv.get('evolution', 1)
    target = min(15, lvl + levels)
    for l in range(lvl, target):
        if l - 1 < len(costs):
            bill.add('SoftCurrency', costs[l - 1] * tm)
    bill.charge()
    mv['evolution'] = target
    from .challenges import track
    track(ctx, 'upgrade_move')
    ctx.save()
    md = ctx.app.players.move_data(mv)
    return {'data': {'newLevel': target, 'saleValue': md['saleValue'], 'moveStatInstances': md['moveStatInstances']}}


@route('PUT', r'moves/sell')
def sell_moves(ctx):
    u = ctx.user
    pool = _move_pool(ctx)
    equipped = {m for ch in u['characters'].values() for m in ch.get('moves', [])} | \
               {ch.get('assist') for ch in u['characters'].values()}
    total = 0
    for mid in ctx.body.get('ids') or []:
        mv = pool.get(mid)
        if not mv or mv.get('locked') or mid in equipped:
            continue
        total += ctx.app.players.move_sale_value(ctx.content.guid(mv['guid']) or {}, mv)
        del pool[mid]
    if total:
        ctx.app.players.add_currency(u, 'SoftCurrency', total)
    ctx.save()
    return {'data': {'rewards': [currency_item('SoftCurrency', total)] if total else []}}


@route('PUT', r'nodeModifiers/sell')
def sell_node_modifiers(ctx):
    for nid in ctx.body.get('ids') or []:
        ctx.user['nodeModifiers'].pop(nid, None)
    ctx.save()
    return {'data': {}}


@route('POST', r'user/collection/(?P<op>queryRecycleInfo|recycle)')
def recycle(ctx, op):
    """Recycling characters returns skill points (like feeding) and soft currency for moves."""
    u = ctx.user
    svc = ctx.app.players
    char_items, move_items = [], []
    for cid in ctx.body.get('characters') or []:
        ch = u['characters'].get(cid)
        if not ch or ch.get('locked') or cid in u['team']:
            continue
        sp = svc.feeding_skill_points(ch)
        base = _base_guid(ctx, ch)
        if sp and base:
            char_items.append({'type': 'BaseCharacterCurrency', 'baseCharacterCurrencyReward':
                               {'baseCharacterGuid': base, 'amounts': {'SkillPoint': sp}}})
            if op == 'recycle':
                u['skillPoints'][base] = u['skillPoints'].get(base, 0) + sp
        if op == 'recycle':
            _remove_character(u, cid)
    total = 0
    for mid in ctx.body.get('moves') or []:
        mv = u['moves'].get(mid)
        if mv and not mv.get('locked'):
            total += svc.move_sale_value(ctx.content.guid(mv['guid']) or {}, mv)
            if op == 'recycle':
                del u['moves'][mid]
    if total:
        move_items.append(currency_item('SoftCurrency', total))
        if op == 'recycle':
            svc.add_currency(u, 'SoftCurrency', total)
    ctx.save()
    return {'data': {'characterRewards': char_items, 'moveRewards': move_items, 'assistMoveRewards': [],
                     'nodeModifierRewards': []}}
