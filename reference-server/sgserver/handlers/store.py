"""Relic (gacha) store: listing, buying with in-game currency, opening owned relics."""
import re

from ..protocol import ApiError
from ..rewards import grant_loot, roll_table
from ..server import route
from .progression import Bill, currency_name

# evergreen relics shown when config['store_gachas'] is not set
DEFAULT_STORE = re.compile(r'^Gacha_\d+_(Bronze|Premiere|Moves_\w+|Gold|Silver|Diamond|Sharded|Air|Fire|Water|Light|Dark|'
                           r'Daily|Char_[A-Za-z]+)$')


def store_gachas(ctx):
    cfg = ctx.app.config.get('store_gachas')
    out = []
    for g in ctx.content.all('GachaData'):
        if cfg:
            if g['guid'] in cfg:
                out.append(g)
        elif DEFAULT_STORE.match(g['_name']) and 'NA' not in g['_name'] and 'DEBUG' not in g['_name']:
            out.append(g)
    return out


@route('GET', r'store/gachas/(?P<region>[^/]+)')
def available_gachas(ctx, region):
    return {'data': {'gachas': [{'gachaGuid': g['guid'], 'isEarnOverTime': False,
                                 'schedulingStatus': {'currentlyActive': True, 'startDate': -1, 'endDate': -1}}
                                for g in store_gachas(ctx)]}}


@route('GET', r'user/gachas')
def owned_gachas(ctx):
    return {'data': [{'guid': g, 'count': n} for g, n in ctx.user['gachas'].items() if n > 0]}


def open_relic(ctx, gacha):
    """One relic: weighted LootTableSet, then one roll per table in the set."""
    sets = gacha.get('lootTableSets') or []
    total = sum(s.get('weight', 0) for s in sets)
    import random
    pick = None
    r = random.uniform(0, total) if total > 0 else 0
    for s in sets:
        r -= s.get('weight', 0)
        if r <= 0:
            pick = s
            break
    pick = pick or (sets[0] if sets else None)
    items = []
    lts = ctx.content.get((pick or {}).get('lootTableSet'))
    for table in (lts or {}).get('lootTables') or []:
        loot = roll_table(ctx.content, table)
        if loot:
            items += grant_loot(ctx.app.players, ctx.user, loot)
    return items


def _open(ctx, gacha, count):
    from .challenges import track
    track(ctx, 'open_relic', count)
    rewards = []
    for _ in range(count):
        items = open_relic(ctx, gacha)
        rewards += items or []
    ctx.save()
    return {'data': {'rewards': rewards}}


@route('POST', r'store/gacha/(?P<mode>buy|voucherBuy)/(?P<guid>[^/]+)')
def buy_gacha(ctx, mode, guid):
    g = ctx.content.guid(guid)
    if not g or g.get('_class') != 'GachaData':
        raise ApiError(404, 'GachaNotFound', 'unknown relic')
    count = max(1, int(ctx.q('count', 1) or 1))
    bill = Bill(ctx)
    for cur in (g.get('price') or {}).get('currencies') or []:
        bill.add(currency_name(ctx, cur), cur['amount'] * count)
    bill.charge()
    if ctx.q_bool('openAll', True):
        return _open(ctx, g, count)
    ctx.user['gachas'][guid] = ctx.user['gachas'].get(guid, 0) + count
    ctx.save()
    return {'data': {}}


@route('PUT', r'user/gacha/open/(?P<guid>[^/]+)')
def open_owned(ctx, guid):
    g = ctx.content.guid(guid)
    have = ctx.user['gachas'].get(guid, 0)
    count = max(1, int(ctx.q('count', 1) or 1))
    if not g or have < count:
        raise ApiError(400, 'NotEnoughRelics', 'relic not owned')
    ctx.user['gachas'][guid] = have - count
    return _open(ctx, g, count)


@route('POST', r'store/gacha/convertToShards/(?P<guid>[^/]+)')
def convert_to_shards(ctx, guid):
    return {'data': {}}
