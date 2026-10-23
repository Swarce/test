"""Limited-Time-Act (LTA) events: the rotating PvE missions.

Each EventSet groups the difficulty variants of one LTA (LTAEventData -> one Act). The private
server rotates a configurable number of sets every UTC day (or runs them all). Entering costs
EconomySettings.playDailyEventPrice (Daily Event Tickets), each completed run counts toward
LTAEventData.maxPlaysAllowed, and progress resets between runs.
"""
from ..protocol import ApiError
from ..rewards import bundle, grant_loot, pick_weighted
from ..server import route

DAY_MS = 86400000
TICKET = 'DailyEventTicket'


def _lta_sets(content):
    sets = []
    for es in content.all('EventSet'):
        evs = [content.get(r) for r in es.get('contentDatas') or []]
        evs = [e for e in evs if e and e.get('_class') == 'LTAEventData' and content.get(e.get('act'))]
        if evs:
            sets.append((es, evs))
    sets.sort(key=lambda x: x[0]['_name'])
    return sets


def active_events(ctx, day_offset=0):
    """[(LTAEventData, instanceId, start_ms, end_ms)] for the given day."""
    cfg = ctx.app.config
    sets = _lta_sets(ctx.content)
    if not sets:
        return []
    day = ctx.now_ms // DAY_MS + day_offset
    if cfg.get('lta_events', 'rotate') == 'all':
        week = day // 7
        start, end, chosen, tag = week * 7 * DAY_MS, (week + 1) * 7 * DAY_MS, sets, f'w{week}'
    else:
        k = max(1, int(cfg.get('lta_sets_per_day', 3)))
        first = (day * k) % len(sets)
        chosen = [sets[(first + i) % len(sets)] for i in range(min(k, len(sets)))]
        start, end, tag = day * DAY_MS, (day + 1) * DAY_MS, f'd{day}'
    return [(ev, f'lta-{ev["guid"]}-{tag}', start, end) for _, evs in chosen for ev in evs]


def find_event(ctx, iid):
    for ev, i, start, end in active_events(ctx):
        if i == iid:
            return ev, start, end
    return None, 0, 0


def _event_record(ctx, iid):
    return ctx.user.setdefault('events', {}).setdefault(iid, {'entered': False, 'playCount': 0, 'progress': {}})


def event_data(ctx, ev, iid, start, end):
    rec = ctx.user.get('events', {}).get(iid, {})
    act = ctx.content.get(ev['act'])
    return {'type': 'LimitedTimeAct', 'guid': ev['guid'], 'eventInstanceId': iid,
            'hasEntered': rec.get('entered', False), 'scheduleInstance': {'startDate': start, 'endDate': end},
            'actGuid': act['guid'], 'playCount': rec.get('playCount', 0),
            'wasEverFullyCompleted': ctx.user.get('ltaCompleted', {}).get(ev['guid'], False)}


@route('GET', r'events/(?P<region>[A-Za-z]{2}|[^/]*)')
def get_events(ctx, region):
    want = ctx.q('type')
    if want not in (None, '', 'None', 'LimitedTimeActs'):
        return {'data': {'activeEvents': [], 'upcomingEvents': []}}
    active = [event_data(ctx, *x) for x in active_events(ctx)]
    upcoming = [event_data(ctx, *x) for x in active_events(ctx, 1)] if ctx.app.config.get('lta_events') != 'all' else []
    return {'data': {'activeEvents': active, 'upcomingEvents': upcoming}}


@route('PUT', r'events/(?P<iid>[^/]+)/enter')
def enter_event(ctx, iid):
    ev, _, _ = find_event(ctx, iid)
    if not ev:
        raise ApiError(404, 'EventNotFound', 'event is not active')
    rec = _event_record(ctx, iid)
    if rec['entered']:
        return {}
    if rec['playCount'] >= ev.get('maxPlaysAllowed', 1):
        raise ApiError(400, 'EventMaxPlays', 'no plays left')
    price = _ticket_price(ctx, 'playDailyEventPrice')
    if not ctx.app.players.spend(ctx.user, price):
        raise ApiError(400, 'NotEnoughCurrency', 'not enough daily event tickets')
    rec['entered'] = True
    rec['progress'] = {}
    ctx.save()
    return {}


def _ticket_price(ctx, key):
    from ..player import CURRENCY_TYPES
    out = {}
    for cur in (ctx.content.economy.get(key) or {}).get('currencies') or []:
        name = CURRENCY_TYPES[cur['currencyType']]
        out[name] = out.get(name, 0) + cur['amount']
    return out


def _random_drop(ctx, ev):
    drop = pick_weighted(ev.get('randomDropLoots'))
    return grant_loot(ctx.app.players, ctx.user, drop) if drop else []


def _finish_run(ctx, ev, iid, bundles):
    u = ctx.user
    rec = _event_record(ctx, iid)
    rec['playCount'] += 1
    rec['entered'] = False
    rec['progress'] = {}
    done = u.setdefault('ltaCompleted', {})
    items = []
    if not done.get(ev['guid']):
        done[ev['guid']] = True
        for loot in ev.get('firstTimeCompletionLoots') or []:
            items += grant_loot(ctx.app.players, u, loot)
    items += _random_drop(ctx, ev)
    if items:
        bundles.append(bundle('Act', ev['guid'], items))


def on_event_match_completed(ctx, iid, match, bundles):
    """Called by the fight handler the first time a match of an entered event run is completed."""
    ev, _, _ = find_event(ctx, iid)
    if not ev:
        return
    act = ctx.content.get(ev['act'])
    rec = _event_record(ctx, iid)
    matches = [m for ch in ctx.content.chapters_of(act) for m in ctx.content.matches_of(ch)]
    if all(rec['progress'].get(m['guid'], {}).get('completed') for m in matches):
        _finish_run(ctx, ev, iid, bundles)


@route('POST', r'events/(?P<iid>[^/]+)/skip')
def skip_event(ctx, iid):
    ev, _, _ = find_event(ctx, iid)
    if not ev or not ev.get('isSkippable', 1):
        raise ApiError(400, 'EventNotSkippable', 'cannot skip')
    rec = _event_record(ctx, iid)
    if rec['playCount'] >= ev.get('maxPlaysAllowed', 1):
        raise ApiError(400, 'EventMaxPlays', 'no plays left')
    if not ctx.user.get('ltaCompleted', {}).get(ev['guid']):
        raise ApiError(400, 'EventNotCompleted', 'complete the event once before skipping')
    if not rec['entered'] and not ctx.app.players.spend(ctx.user, _ticket_price(ctx, 'skipDailyEventPrice')):
        raise ApiError(400, 'NotEnoughCurrency', 'not enough daily event tickets')
    bundles = []
    # a skip pays out what a full run's matches would have, plus the random drop
    act = ctx.content.get(ev['act'])
    items = []
    for ch in ctx.content.chapters_of(act):
        for m in ctx.content.matches_of(ch):
            for loot in m.get('completionLoot') or []:
                items += grant_loot(ctx.app.players, ctx.user, loot)
    if items:
        bundles.append(bundle('Match', act['guid'], items))
    _finish_run(ctx, ev, iid, bundles)
    ctx.save()
    return {'data': bundles}


@route('PUT', r'events/claimFreeEventTickets/(?P<kind>[^/]+)')
def claim_free_tickets(ctx, kind):
    u = ctx.user
    econ = ctx.content.economy
    day = ctx.now_ms // DAY_MS
    items = []
    if u.get('ticketDay') != day:
        u['ticketDay'] = day
        have = u['currencies'].get(TICKET, 0)
        add = max(0, min(econ.get('claimableDailyEventTicketsPerDay', 4), econ.get('maxDailyEventTickets', 4) - have))
        if add:
            ctx.app.players.add_currency(u, TICKET, add)
            from ..rewards import currency_item
            items.append(currency_item(TICKET, add))
        ctx.save()
    return {'data': {'rewardList': [bundle('Undefined', '', items)] if items else [],
                     'freeEventTicketResetDate': (day + 1) * DAY_MS}}
