"""Login, profile, collection and other always-needed endpoints."""
import base64
import hashlib
import logging

from .. import protocol
from ..protocol import ApiError
from ..server import route

log = logging.getLogger('sgserver')


def svc(ctx):
    return ctx.app.players


# ---- connectivity / settings ------------------------------------------------------------

@route('GET', r'ping', auth=False)
def ping(ctx):
    return {}


@route('GET', r'(?P<store>[^/]+)/global_settings', auth=False)
def global_settings(ctx, store):
    # GlobalSettings.ParseData: minClientVersion ("0" = never force an update) and toSVersion
    return {'data': {'minClientVersion': '0', 'toSVersion': '2.0.0'}}


# ---- authentication -----------------------------------------------------------------------

def _basic_credentials(ctx):
    auth = ctx.headers.get('Authorization', '')
    if not auth.startswith('Bearer '):
        return None, None
    try:
        raw = base64.b64decode(auth[7:]).decode('utf-8')
    except Exception:
        return None, None
    a, _, b = raw.partition(':')
    return a, b


@route('POST', r'authenticate/(?P<platform>[^/]+)/(?P<device>[^/]*)/(?P<region>[^/]*)/(?P<provider>[^/]+)', auth=False)
def authenticate(ctx, platform, device, region, provider):
    store = ctx.store
    user_id = None
    created = False
    token = ctx.headers.get('X-AUTH-USER-TOKEN')
    if provider == 'Password':
        email, password = _basic_credentials(ctx)
        acct = store.account(email or '')
        pw_hash = hashlib.sha256((password or '').encode()).hexdigest()
        if acct:
            if acct[0] != pw_hash:
                raise ApiError(401, 'InvalidCredentials', 'wrong email or password')
            user_id = acct[1]
        elif ctx.app.config.get('auto_register_password_accounts', True) and email:
            user = svc(ctx).create(device_id=device)
            store.save_user(user)
            store.set_account(email, pw_hash, user['id'])
            user_id, created = user['id'], True
    else:
        if token:
            user_id = store.session_user('guest:' + token)
        if not user_id and device:
            user_id = store.user_for_device(device)
    user = store.load_user(user_id) if user_id else None
    if user is None:
        if not ctx.body.get('newUser', True) and not ctx.app.config.get('allow_implicit_signup', True):
            raise ApiError(404, 'UserNotFound', 'no such user')
        user = svc(ctx).create(device_id=device)
        created = True
    if device:
        store.bind_device(device, user['id'])
    guest_token = user.setdefault('guestToken', hashlib.sha256(user['id'].encode()).hexdigest()[:40])
    with store.lock:  # remember the guest token -> user mapping as a pseudo session
        store.db.execute('INSERT OR REPLACE INTO sessions (token, user_id, created) VALUES (?,?,?)',
                         ('guest:' + guest_token, user['id'], ctx.now))
        store.db.commit()
    session = store.new_session(user['id'])
    ctx.set_cookie = f'{protocol.SESSION_COOKIE}={session}; Path=/; HttpOnly'
    ctx.user = user
    from .challenges import track
    track(ctx, 'login')
    ctx.save()
    log.info('login %s (%s) via %s new=%s', user['handle'], user['id'], provider, created)
    return {'data': {
        'session': {'userId': user['id'], 'providerUserToken': guest_token,
                    'isGuest': provider != 'Password', 'isNewUser': created},
        'user': user_payload(ctx, user),
        'loginRewardCalendarStates': [],
    }}


@route('POST', r'user/postLogin/updates')
def post_login(ctx):
    return {'data': {'count': 0, 'countryCode': ctx.app.config.get('country_code', 'US'), 'canAghanimPay': False}}


@route('GET', r'user/accounts')
def accounts(ctx):
    return {'data': []}


# ---- profile --------------------------------------------------------------------------------

def user_payload(ctx, user):
    from .challenges import summary
    data = svc(ctx).user_data(user)
    data.update(summary(ctx))
    return data


@route('GET', r'user')
def current_user(ctx):
    return {'data': user_payload(ctx, ctx.user)}


@route('GET', r'user/publicid/(?P<pid>[^/]+)', sign=True)
def user_by_public_id(ctx, pid):
    for uid in ctx.store.all_user_ids():
        u = ctx.store.load_user(uid)
        if u and u['publicId'] == pid:
            return {'data': svc(ctx).user_data(u)}
    raise ApiError(404, 'UserNotFound', 'no such user')


@route('GET', r'user/updates')
def user_updates(ctx):
    # GetUpdatesRequest reads these counters straight from the top level of "data"
    return {'data': {'adsAvailable': 0, 'maxAdsBalance': 0, 'nextAdGeneratedTimestamp': 0, 'guildInvitesCount': 0,
                     'guildRequestsCount': 0, 'guildRole': '', 'numberOfRiftBattleUpdates': 0,
                     'numberOfSocialUpdates': 0, 'unclaimedAghanimDirectPurchaseCount': 0}}


@route('PUT', r'user/checkHandleAvailable')
def check_handle(ctx):
    return {}


@route('PUT', r'user/handle')
def change_handle(ctx):
    handle = str(ctx.body.get('handle') or '').strip()
    if not (3 <= len(handle) <= 20):
        raise ApiError(400, 'InvalidHandle', 'invalid handle')
    ctx.user['handle'] = handle
    ctx.save()
    return {}


@route('PUT', r'user/avatar/(?P<avatar>[^/]+)')
def set_avatar(ctx, avatar):
    ctx.user['avatar'] = avatar
    ctx.save()
    return {}


@route('PUT', r'user/tutorial')
def set_tutorial(ctx):
    from ..player import TUTORIALS
    t = ctx.user['tutorial']
    kind = ctx.body.get('tutorialType') or 'FTUE'
    progress = int(ctx.body.get('progress') or 0)
    t['map'][kind] = progress
    if kind == 'FTUE':
        t['progress'] = progress
    bit, cleared = TUTORIALS.get(kind, (0, 1 << 30))
    if progress >= cleared:
        t['completed'] |= bit
    ctx.save()
    return {}


@route('GET', r'user/tutorial/reward/xpboosts')
def xp_boost_tutorial_rewards(ctx):
    return {}


@route('PUT', r'user/flagAccountForDeletion/(?P<flag>\w+)')
def flag_delete(ctx, flag):
    return {}


@route('PUT', r'user/guildJoinReminder')
def guild_join_reminder(ctx):
    return {}


# ---- collection -------------------------------------------------------------------------------

@route('GET', r'user/collection')
def collection(ctx):
    s = svc(ctx)
    u = ctx.user
    return {'data': {
        'characters': [s.character_data(u, ch) for ch in u['characters'].values()],
        'specialMoves': [s.move_data(m) for m in u['moves'].values()],
        'assistMoves': [s.move_data(m) for m in u['assistMoves'].values()],
        'nodeModifiers': [s.node_modifier_data(n) for n in u['nodeModifiers'].values()],
        'eventLockedCharacterIdMap': {},
    }}


@route('GET', r'user/team')
def get_team(ctx):
    return {'data': {'teamIds': list(ctx.user['team'])}}


@route('PUT', r'user/team')
def set_team(ctx):
    ids = [i for i in ctx.q_list('ids') if i in ctx.user['characters']]
    if ctx.q_bool('defensive'):
        ctx.user['defenseTeam'] = ids
    else:
        ctx.user['team'] = ids[:3]
    ctx.save()
    return {}


@route('PUT', r'user/competitive/team')
def set_competitive_team(ctx):
    return {}


@route('PUT', r'characters/setNew')
def characters_set_new(ctx):
    is_new = ctx.q_bool('isNew')
    for cid in ctx.body.get('ids') or ctx.q_list('ids'):
        ch = ctx.user['characters'].get(cid)
        if ch:
            ch['new'] = is_new
    ctx.save()
    return {}


@route('PUT', r'character/(?P<cid>[^/]+)/lock')
def character_lock(ctx, cid):
    ch = ctx.user['characters'].get(cid)
    if ch:
        ch['locked'] = ctx.q_bool('enabled')
        ctx.save()
    return {}


@route('PUT', r'moves/setNew')
def moves_set_new(ctx):
    is_new = ctx.q_bool('isNew')
    pool = ctx.user['assistMoves' if ctx.q_bool('isAssist') else 'moves']
    for mid in ctx.body.get('ids') or []:
        if mid in pool:
            pool[mid]['new'] = is_new
    ctx.save()
    return {}


@route('PUT', r'move/(?P<mid>[^/]+)/lock')
def move_lock(ctx, mid):
    pool = ctx.user['assistMoves' if ctx.q_bool('isAssist') else 'moves']
    if mid in pool:
        pool[mid]['locked'] = ctx.q_bool('enabled')
        ctx.save()
    return {}


@route('PUT', r'nodeModifiers/setNew')
def node_mods_set_new(ctx):
    for nid in ctx.body.get('ids') or []:
        nm = ctx.user['nodeModifiers'].get(nid)
        if nm:
            nm['new'] = ctx.q_bool('isNew')
    ctx.save()
    return {}


@route('PUT', r'nodeModifier/(?P<nid>[^/]+)/lock')
def node_mod_lock(ctx, nid):
    nm = ctx.user['nodeModifiers'].get(nid)
    if nm:
        nm['locked'] = ctx.q_bool('enabled')
        ctx.save()
    return {}


@route('PUT', r'consumables/setNew')
def consumables_set_new(ctx):
    return {}


@route('PUT', r'user/equip')
def equip(ctx):
    u = ctx.user
    ch = u['characters'].get(ctx.q('cId'))
    if not ch:
        raise ApiError(404, 'CharacterNotFound', 'unknown character')
    move_ids = [m for m in ctx.q_list('mIds') if m in u['moves']]
    # a move can only be equipped on one character
    for other in u['characters'].values():
        if other is not ch:
            other['moves'] = [m for m in other.get('moves', []) if m not in move_ids]
    ch['moves'] = move_ids
    aid = ctx.q('aId')
    ch['assist'] = aid if aid in u['assistMoves'] else ''
    ctx.save()
    return {}


@route('PUT', r'user/competitive/equip')
def equip_competitive(ctx):
    return {}
