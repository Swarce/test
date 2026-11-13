"""Daily challenges ("missions" list on the main menu).

ChallengeManager.challengesDatas holds the daily pool; each completed+claimed challenge awards
pointReward points and every 100/len(thresholds) points unlocks one of
EconomySettings.challengesThresholdRewards. Only challenges that can progress without
multiplayer are offered.
"""
from ..protocol import ApiError
from ..rewards import bundle, grant_loot
from ..server import route

DAY_MS = 86400000

# challenge class -> progress key reported by track()
TRACKERS = {
    'LogInChallengeData': 'login',
    'OpenGachaChallengeData': 'open_relic',
    'UpgradeMoveChallengeData': 'upgrade_move',
    'UpgradeCharacterChallengeData': 'upgrade_skill',
}
WIN_MODES = {0: 'win_story', 7: 'win_lta'}  # WinFightsInModeChallengeData.mode (GameType)


def _kind(ch):
    if ch['_class'] == 'WinFightsInModeChallengeData':
        return WIN_MODES.get(ch.get('mode'))
    return TRACKERS.get(ch['_class'])


def daily_pool(content):
    cm = content.by_class.get('ChallengeManager')
    if not cm:
        return []
    out = []
    for ref in next(iter(cm.values())).get('challengesDatas') or []:
        ch = content.get(ref)
        if ch and ch.get('active', 1) and _kind(ch):
            out.append(ch)
    return out


def _state(ctx):
    u = ctx.user
    day = ctx.now_ms // DAY_MS
    st = u.get('daily')
    if not st or st.get('day') != day:
        st = u['daily'] = {'day': day, 'progress': {}, 'claimed': [], 'thresholds': []}
    return st


def track(ctx, kind, amount=1):
    """Advance every active daily challenge of this kind."""
    if ctx.user is None:
        return
    st = _state(ctx)
    for ch in daily_pool(ctx.content):
        if _kind(ch) == kind and ch['guid'] not in st['claimed']:
            st['progress'][ch['guid']] = min(ch.get('target', 1), st['progress'].get(ch['guid'], 0) + amount)
    ctx.save()


def _points(ctx, st):
    pool = {c['guid']: c for c in daily_pool(ctx.content)}
    return sum(pool[g].get('pointReward', 0) for g in st['claimed'] if g in pool)


def _thresholds(ctx):
    return ctx.content.economy.get('challengesThresholdRewards') or []


def summary(ctx):
    """Counters UserServerData exposes for badges."""
    st = _state(ctx)
    pool = daily_pool(ctx.content)
    claimable = sum(1 for c in pool if c['guid'] not in st['claimed'] and st['progress'].get(c['guid'], 0) >= c.get('target', 1))
    th = _thresholds(ctx)
    interval = 100 // len(th) if th else 0
    unlocked = min(len(th), _points(ctx, st) // interval) if interval else 0
    return {'claimableChallengeCount': claimable,
            'claimableChallengeRewardThresholdCount': max(0, unlocked - len(st['thresholds'])),
            'challengeExpirationDate': (st['day'] + 1) * DAY_MS}


@route('GET', r'challenges')
def challenges(ctx):
    st = _state(ctx)
    out = []
    for c in daily_pool(ctx.content):
        n = st['progress'].get(c['guid'], 0)
        status = 'Claimed' if c['guid'] in st['claimed'] else ('Completed' if n >= c.get('target', 1) else 'InProgress')
        out.append({'challengeGuid': c['guid'], 'count': n, 'status': status, 'constraints': []})
    return {'data': {'challenges': out,
                     'claimedRewards': [i in st['thresholds'] for i in range(len(_thresholds(ctx)))],
                     'expirationDate': (st['day'] + 1) * DAY_MS, 'newChallengesAvailable': False}}


@route('PUT', r'challenge/(?P<guid>[^/]+)/claim')
def claim_challenge(ctx, guid):
    st = _state(ctx)
    c = next((c for c in daily_pool(ctx.content) if c['guid'] == guid), None)
    if not c or guid in st['claimed']:
        raise ApiError(400, 'ChallengeNotClaimable', 'challenge not claimable')
    if st['progress'].get(guid, 0) < c.get('target', 1):
        raise ApiError(400, 'ChallengeNotComplete', 'challenge not complete')
    st['claimed'].append(guid)
    ctx.save()
    return {}


@route('PUT', r'challenges/reward/(?P<idx>\d+)/claim/(?P<double>\w+)')
def claim_threshold(ctx, idx, double):
    st = _state(ctx)
    th = _thresholds(ctx)
    i = int(idx)
    interval = 100 // len(th) if th else 0
    if i >= len(th) or i in st['thresholds'] or not interval or _points(ctx, st) < (i + 1) * interval:
        raise ApiError(400, 'RewardNotClaimable', 'threshold not reached')
    st['thresholds'].append(i)
    items = []
    for loot in th[i].get('loots') or []:
        items += grant_loot(ctx.app.players, ctx.user, loot)
    ctx.save()
    return {'data': [bundle('Undefined', str(i), items)]}


@route('PUT', r'challenge/update/login')
def challenge_login(ctx):
    track(ctx, 'login')
    return {}
