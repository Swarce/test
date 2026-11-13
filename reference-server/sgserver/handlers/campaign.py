"""Story/campaign acts and the fight lifecycle (prep -> start -> end)."""
import logging

from ..enemies import preview
from ..player import new_id, now_ms
from ..protocol import ApiError
from ..rewards import bundle, grant_loot, pick_weighted
from ..server import route

log = logging.getLogger('sgserver')

CAMPAIGN, LTA = 0, 7


def campaign_acts(content):
    return [a for a in content.all('Act') if a.get('gameType') == CAMPAIGN and 'FTUE' not in a['_name']]


@route('GET', r'acts/campaign')
def available_campaign_acts(ctx):
    return {'data': [a['guid'] for a in campaign_acts(ctx.content)]}


def progress_scope(ctx, event_instance_id=None):
    """Match progress lives in user['campaign'] for the story, or per event instance for events."""
    if event_instance_id:
        ev = ctx.user.setdefault('events', {}).get(event_instance_id)
        if ev is not None:
            return ev.setdefault('progress', {})
    return ctx.user['campaign']


def match_state(ctx, match, scope=None):
    """Per-player state of one match, generating (and caching) its opponents."""
    scope = ctx.user['campaign'] if scope is None else scope
    st = scope.setdefault(match['guid'], {'completed': False, 'challenges': 0, 'wins': 0})
    fights = ctx.content.fights_of(match)
    if len(st.get('enemies') or []) != len(fights):
        st['enemies'] = [ctx.app.enemies.generate_team(f) for f in fights]
        ctx.save()
    return st


def act_progress(ctx, act, event_instance_id=None):
    scope = progress_scope(ctx, event_instance_id)
    chapters = []
    for ch in ctx.content.chapters_of(act):
        matches = []
        for m in ctx.content.matches_of(ch):
            st = match_state(ctx, m, scope)
            matches.append({
                'guid': m['guid'],
                'isFinal': bool(m.get('isCompletionNode')),
                'isCompleted': st['completed'],
                'matchChallengesProgress': st['challenges'] & 0xFF,
                'fights': [{'enemies': [preview(e) for e in team]} for team in st['enemies']],
            })
        chapters.append({'guid': ch['guid'], 'matches': matches})
    return {'guid': act['guid'], 'chapters': chapters}


@route('PUT', r'acts/progress')
def acts_progress(ctx):
    out = []
    for d in ctx.body.get('descriptors') or []:
        guid = d.get('guid') if isinstance(d, dict) else d
        iid = d.get('eventInstanceId') if isinstance(d, dict) else None
        act = ctx.content.guid(guid)
        if act and act.get('_class') == 'Act':
            out.append(act_progress(ctx, act, iid))
    return {'data': out}


# ---- fights ------------------------------------------------------------------------------------

def enemy_payload(e):
    return {k: e[k] for k in ('guid', 'level', 'tier', 'lifebar', 'attack', 'fighterScore', 'isRandom', 'isShiny',
                              'specialMoveInstances', 'skillNodeGuids', 'superAbilityLevels',
                              'superAbilityActiveFeatureSet', 'prestigeAbilityLevel')}


def fight_instance(fid, fight, enemies, name=''):
    return {
        'id': fid,
        'difficulty': fight.get('difficulty', -1),
        'opponentId': '',
        'enemyFighterFSMultiplierAddition': fight.get('enemyFighterFSMultiplierAddition', 0.0) or 0.0,
        'enemyName': name,
        'enemyIsDeveloper': False,
        'defenseBonusCount': 0,
        'characters': [enemy_payload(e) for e in enemies],
    }


def _remember_fight(ctx, rec):
    fights = ctx.user.setdefault('fights', {})
    if len(fights) > 30:  # drop stale unfinished fights
        for k in sorted(fights, key=lambda k: fights[k].get('created', 0))[:len(fights) - 30]:
            del fights[k]
    fights[rec['id']] = rec


@route('POST', r'fight', b64=True, sign=True)
def prep_fight(ctx):
    b = ctx.body
    kind = b.get('type') or 'Campaign'
    match = ctx.content.guid(b.get('matchGuid') or '')
    if not match or match.get('_class') != 'SGMatch':
        raise ApiError(404, 'MatchNotFound', f'unknown match {b.get("matchGuid")}')
    iid = b.get('eventInstanceId') or None
    if iid:
        ev = ctx.user.setdefault('events', {}).get(iid)
        if ev is None or not ev.get('entered'):
            raise ApiError(400, 'EventNotEntered', 'enter the event first')
    st = match_state(ctx, match, progress_scope(ctx, iid))
    out = []
    for i, fight in enumerate(ctx.content.fights_of(match)):
        fid = new_id()
        enemies = st['enemies'][i]
        _remember_fight(ctx, {'id': fid, 'kind': kind, 'match': match['guid'], 'act': b.get('actGuid'),
                              'chapter': b.get('chapterGuid'), 'event': b.get('eventInstanceId'),
                              'index': i, 'enemies': enemies, 'created': now_ms(), 'team': []})
        out.append(fight_instance(fid, fight, enemies))
    ctx.save()
    return {'data': {'fights': out}}


@route('PUT', r'fight/(?P<fid>[^/]+)/start', b64=True, sign=True)
def start_fight(ctx, fid):
    rec = ctx.user.get('fights', {}).get(fid)
    if not rec:
        raise ApiError(404, 'FightNotFound', 'unknown fight')
    team = [c for c in ctx.body.get('teamIds') or [] if c in ctx.user['characters']]
    match = ctx.content.guid(rec['match'])
    fight = ctx.content.fights_of(match)[rec['index']]
    cost = fight.get('energyRequirement', 0) or 0
    if cost:
        for cid in team:
            ch = ctx.user['characters'][cid]
            ctx.app.players.refresh_energy(ch)
            if ch['energy'] < cost:
                raise ApiError(400, 'NotEnoughEnergy', 'not enough energy')
        for cid in team:
            ctx.user['characters'][cid]['energy'] -= cost
            ctx.app.players.refresh_energy(ctx.user['characters'][cid])
    rec['team'] = team
    rec['started'] = now_ms()
    ctx.save()
    return {'data': {'uploadReplay': False}}


def _completed_challenges(stats):
    bits = 0
    for s in stats or []:
        if isinstance(s, dict) and s.get('type') == 'MatchChallenge':
            try:
                idx = int((s.get('params') or [0])[0])
            except (TypeError, ValueError):
                continue
            if (s.get('count') or 0) > 0 and 0 <= idx < 8:
                bits |= 1 << idx
    return bits


def _xp_item(kind, key, instance_id, before_xp, after_xp, ladder, shiny=False):
    return {'type': kind, key: {
        'instanceId': instance_id, 'total': after_xp, 'gained': after_xp - before_xp,
        'level': ladder.level_for_xp(after_xp, shiny),
        'from': ladder.float_level(before_xp, shiny), 'to': ladder.float_level(after_xp, shiny)}}


def grant_player_xp(ctx, amount, bundles):
    """Adds player XP and level-up rewards; returns the PlayerXp reward item."""
    svc = ctx.app.players
    u = ctx.user
    before = u['xp']
    old_level = svc.level(u)
    u['xp'] += max(0, int(amount))
    new_level = svc.level(u)
    item = _xp_item('PlayerXp', 'playerXpReward', u['id'], before, u['xp'], ctx.app.formulas.player_ladder)
    if new_level > old_level:
        items = []
        for entry in ctx.content.economy.get('playerLevelUpRewards') or []:
            if old_level < entry.get('level', 0) <= new_level:
                for loot in entry.get('loots') or []:
                    items += grant_loot(svc, u, loot)
        if items:
            bundles.append(bundle('LevelUp', str(new_level), items))
    return item


@route('PUT', r'fight/(?P<fid>[^/]+)/end', b64=True, sign=True)
def end_fight(ctx, fid):
    u = ctx.user
    rec = u.get('fights', {}).pop(fid, None)
    if not rec:
        raise ApiError(404, 'FightNotFound', 'unknown fight')
    ctx.save()
    status = ctx.q('status', 'lost')
    won = status == 'won'
    c = ctx.content
    svc = ctx.app.players
    F = ctx.app.formulas
    cfg = ctx.app.config
    gs = c.game_settings
    match = c.guid(rec['match'])
    fight = c.fights_of(match)[rec['index']]
    scope = progress_scope(ctx, rec.get('event'))
    st = match_state(ctx, match, scope)
    replay = st['completed']

    team = [u['characters'][cid] for cid in rec.get('team') or [] if cid in u['characters']]
    player_fs = sum(svc.char_stats(ch)[3] for ch in team)
    enemy_fs = sum(e['fighterScore'] for e in rec['enemies'])

    bundles = []
    fight_items = []
    # character XP
    xp = F.team_xp(player_fs, enemy_fs, won)
    if fight.get('xpOverride', -1) >= 0:
        xp = fight['xpOverride'] if won else 0
    xp = int(xp * (fight.get('xpMultiplier', 1.0) or 1.0) * cfg.get('xp_rate', 1.0))
    if replay:
        xp = int(xp * gs.get('storyModeReplayXpMultiplier', 1.0))
    if fight.get('skipXP'):
        xp = 0
    level_items = []
    for ch in team:
        lad = F.char_ladder(ch['tier'])
        before, sp, base = svc.add_char_xp(u, ch, xp)
        fight_items.append(_xp_item('CharacterXp', 'characterXpReward', ch['id'], before, ch['xp'], lad, ch['shiny']))
        if sp:
            level_items.append({'type': 'BaseCharacterCurrency', 'baseCharacterCurrencyReward':
                                {'baseCharacterGuid': base, 'amounts': {'SkillPoint': sp}}})
    if level_items:
        bundles.append(bundle('CharacterLevelUp', fid, level_items))

    challenge_bits = 0
    if won:
        from .challenges import track
        track(ctx, 'win_lta' if rec.get('event') else 'win_story')
        mult = fight.get('rewardMultiplier', 1.0) or 1.0
        if replay:
            mult *= gs.get('replayRewardMultiplier', 1.0)
        sc = int(c.economy.get('fightBaseSoftCurrencyReward', 0) * mult * cfg.get('currency_rate', 1.0))
        if sc > 0:
            svc.add_currency(u, 'SoftCurrency', sc)
            from ..rewards import currency_item
            fight_items.append(currency_item('SoftCurrency', sc))
        fight_items.append(grant_player_xp(ctx, enemy_fs * cfg.get('player_xp_per_enemy_fs', 0.25), bundles))
        drop = pick_weighted(match.get('randomDropLoots'))
        if drop:
            fight_items += grant_loot(svc, u, drop)

        # match challenges completed this fight (first completion grants their rewards)
        challenge_bits = _completed_challenges(ctx.body.get('stats'))
        new_bits = challenge_bits & ~st['challenges']
        ch_items = []
        for i, mc in enumerate(fight.get('matchChallenges') or []):
            if new_bits & (1 << i):
                for loot in mc.get('rewards') or []:
                    ch_items += grant_loot(svc, u, loot)
        st['challenges'] |= challenge_bits
        if ch_items:
            bundles.append(bundle('MatchChallenge', match['guid'], ch_items))

        last_fight = rec['index'] == len(c.fights_of(match)) - 1
        if last_fight:
            st['wins'] = st.get('wins', 0) + 1
            if not st['completed']:
                st['completed'] = True
                items = []
                for loot in match.get('completionLoot') or []:
                    items += grant_loot(svc, u, loot)
                if items:
                    bundles.append(bundle('Match', match['guid'], items))
                if rec.get('event'):
                    from .events import on_event_match_completed
                    on_event_match_completed(ctx, rec['event'], match, bundles)
                else:
                    _check_chapter_and_act(ctx, match, bundles)
            st['enemies'] = []  # fresh opponents next time
    bundles.insert(0, bundle('Fight', fid, fight_items))
    ctx.save()
    return {'data': {'rewards': bundles, 'matchChallengesResults': challenge_bits & 0xFF, 'publicReplayId': ''}}


def _check_chapter_and_act(ctx, match, bundles):
    c = ctx.content
    svc = ctx.app.players
    u = ctx.user
    camp = u['campaign']
    chapter = c.match_chapter.get(match['guid'])
    if not chapter:
        return
    flags = u.setdefault('claimed', {})

    def done(m):
        return camp.get(m['guid'], {}).get('completed', False)

    def full(m):
        n = sum(len(f.get('matchChallenges') or []) for f in c.fights_of(m))
        return done(m) and bin(camp.get(m['guid'], {}).get('challenges', 0)).count('1') >= n

    matches = c.matches_of(chapter)
    for label, ok, key, origin in (('chapter', all(done(m) for m in matches), 'completionLoots', 'Chapter'),
                                   ('chapterfull', all(full(m) for m in matches), 'fullCompletionLoots', 'FullChapter')):
        fk = f'{label}:{chapter["guid"]}'
        if ok and not flags.get(fk):
            flags[fk] = True
            items = []
            for loot in chapter.get(key) or []:
                items += grant_loot(svc, u, loot)
            if items:
                bundles.append(bundle(origin, chapter['guid'], items))
    act = c.chapter_act.get(chapter['guid'])
    if not act:
        return
    all_matches = [m for ch in c.chapters_of(act) for m in c.matches_of(ch)]
    for label, ok, key, origin in (('act', all(done(m) for m in all_matches), 'completionLoots', 'Act'),
                                   ('actfull', all(full(m) for m in all_matches), 'fullCompletionLoots', 'FullAct')):
        fk = f'{label}:{act["guid"]}'
        if ok and not flags.get(fk):
            flags[fk] = True
            items = []
            for loot in act.get(key) or []:
                items += grant_loot(svc, u, loot)
            if items:
                bundles.append(bundle(origin, act['guid'], items))
