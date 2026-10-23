"""Compare the Java server's formulas with this package's (run by hand, see sgm-manager/README.md).

usage: SGM_CONTENT=content python tests/parity.py <java parity dump json>
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
from sgserver.content import Content  # noqa: E402
from sgserver.formulas import Formulas  # noqa: E402
from sgserver.skilltree import skill_buffs  # noqa: E402

c = Content(os.environ.get('SGM_CONTENT', 'content'))
f = Formulas(c)
rows = json.load(open(sys.argv[1]))
buff_cache = {}
bad = 0
for r in rows:
    kind = r[0]
    if kind == 'ladder':
        _, tier, lvl, a, b = r
        got = [f.char_ladder(tier).xp_for_level(lvl), f.char_ladder(tier).xp_for_level(lvl, True)]
        want = [a, b]
    elif kind == 'player':
        _, lvl, a = r
        got, want = [f.player_ladder.xp_for_level(lvl)], [a]
    elif kind == 'stats':
        _, guid, tier, lvl, maxed, lb, at, fs0, fs_s, fs_p, fs_pm = r
        v = c.guid(guid)
        if (guid, maxed) not in buff_cache:
            tree = c.get(v.get('skillTree')) or {}
            nodes = [c.get(x)['guid'] for x in tree.get('purchaseOrder') or [] if c.get(x)] if maxed else []
            buff_cache[guid, maxed] = skill_buffs(c, v, nodes)
        b = buff_cache[guid, maxed]
        plb, pat = f.lifebar(v, tier, lvl, b[0]), f.attack(v, tier, lvl, b[1])
        got = [plb, pat, f.fighter_score(plb, pat, b[2]), f.fighter_score(plb, pat, b[2], 3),
               f.fighter_score(plb, pat, b[2], 0, 40), f.fighter_score(plb, pat, b[2], 0, 100)]
        want = [lb, at, fs0, fs_s, fs_p, fs_pm]
    elif kind == 'xp':
        _, pf, ef, w, l = r
        got, want = [f.team_xp(pf, ef, True), f.team_xp(pf, ef, False)], [w, l]
    else:
        continue
    if got != want:
        bad += 1
        if bad <= 10:
            print('MISMATCH', r, 'python:', got)
print(f'{len(rows)} rows compared, {bad} mismatches')
sys.exit(1 if bad else 0)
