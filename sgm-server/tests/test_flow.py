"""End-to-end tests: start the server on a free port and drive it like the game client would.

Requires extracted content: set SGM_CONTENT (default: ./content).
Run: python -m unittest discover -s tests -v
"""
import os
import socket
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.dirname(__file__))

from sgserver.__main__ import DEFAULT_CONFIG, build_app  # noqa: E402
from sgserver import protocol  # noqa: E402
from sgserver.server import Handler  # noqa: E402
from client_sim import GameClient  # noqa: E402

CONTENT = os.environ.get('SGM_CONTENT', os.path.join(os.path.dirname(__file__), '..', 'content'))


def free_port():
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    p = s.getsockname()[1]
    s.close()
    return p


@unittest.skipUnless(os.path.isdir(CONTENT), 'extracted content not available')
class FlowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.app = build_app(CONTENT, os.path.join(cls.tmp, 'test.db'), dict(DEFAULT_CONFIG))
        cls.port = free_port()
        handler = type('H', (Handler,), {'app': cls.app})
        cls.httpd = ThreadingHTTPServer(('127.0.0.1', cls.port), handler)
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def client(self, device='dev-1'):
        return GameClient('127.0.0.1', self.port, device_id=device)

    def test_signature_known_vector(self):
        # request and response signatures are plain SHA-256 over path+queries+body+secret
        sig = protocol.request_signature('fight/abc/start', None, '{"teamIds":[]}')
        self.assertEqual(len(sig), 64)
        self.assertEqual(sig, sig.upper())

    def test_login_creates_and_resumes_user(self):
        c = self.client('dev-login')
        st, d = c.login()
        self.assertEqual(st, 200)
        sess, user = d['data']['session'], d['data']['user']
        self.assertTrue(sess['isNewUser'])
        self.assertEqual(len(user['characters']), 3)
        self.assertEqual(len(user['teamIds']), 3)
        self.assertEqual(user['completedTutorials'] & 1, 1)
        # second login with the stored guest token resumes the same account
        st, d2 = c.login(new_user=False)
        self.assertEqual(d2['data']['user']['id'], user['id'])
        self.assertFalse(d2['data']['session']['isNewUser'])
        # same device, fresh install (no token): still the same account
        c2 = self.client('dev-login')
        st, d3 = c2.login()
        self.assertEqual(d3['data']['user']['id'], user['id'])

    def test_requires_session(self):
        st, d = self.client('dev-nosess').call('GET', 'user')
        self.assertEqual(st, 401)
        self.assertIn('errorCode', d)

    def test_startup_calls(self):
        c = self.client('dev-startup')
        c.login()
        for method, ep, query in [('POST', 'user/postLogin/updates', {'locale': 'en'}), ('GET', 'user', None),
                                  ('GET', 'user/collection', None), ('GET', 'Google/global_settings', None),
                                  ('GET', 'acts/campaign', None), ('GET', 'challenges', None),
                                  ('GET', 'achievements', None), ('GET', 'battlePass', None),
                                  ('GET', 'offers/US', None), ('GET', 'mail/count/unread', None),
                                  ('GET', 'chat/info', None), ('GET', 'user/updates', None), ('GET', 'ping', None)]:
            st, d = c.call(method, ep, {} if method == 'POST' else None, query)
            self.assertEqual(st, 200, ep)
            self.assertIsInstance(d, dict, ep)
        st, d = c.call('GET', 'user/collection')
        ch = d['data']['characters'][0]
        for k in ('id', 'variantGuid', 'tier', 'experience', 'lifebar', 'attack', 'specialMoveIds'):
            self.assertIn(k, ch)
        self.assertTrue(d['data']['specialMoves'])  # starting blockbusters

    def test_campaign_fight_loop(self):
        c = self.client('dev-fight')
        st, d = c.login()
        user = d['data']['user']
        team = user['teamIds']
        st, acts = c.call('GET', 'acts/campaign')
        self.assertTrue(acts['data'])
        content = self.app.content
        act = next(a for a in content.all('Act') if a['humanReadableGuid'] == 'a02-fish-0')
        st, prog = c.call('PUT', 'acts/progress', {'descriptors': [{'guid': act['guid'], 'eventInstanceId': None}]})
        self.assertEqual(st, 200)
        ap = prog['data'][0]
        chapter = ap['chapters'][0]
        m0 = chapter['matches'][0]
        self.assertFalse(m0['isCompleted'])
        self.assertTrue(m0['fights'][0]['enemies'])
        preview_fs = m0['fights'][0]['enemies'][0]['fighterScore']

        st, prep = c.call('POST', 'fight', {'type': 'Campaign', 'actGuid': act['guid'], 'chapterGuid': chapter['guid'],
                                            'matchGuid': m0['guid'], 'eventInstanceId': None},
                          signed=True, b64=True)
        self.assertEqual(st, 200)
        fi = prep['data']['fights'][0]
        self.assertEqual(fi['characters'][0]['fighterScore'], preview_fs)  # same opponent as previewed
        st, start = c.call('PUT', f'fight/{fi["id"]}/start', {'teamIds': team}, signed=True, b64=True)
        self.assertEqual(st, 200)
        self.assertIn('uploadReplay', start['data'])
        before_sc = user['softCurrency']['free']
        st, end = c.call('PUT', f'fight/{fi["id"]}/end',
                         {'stats': [{'type': 'MatchChallenge', 'params': ['0'], 'count': 1}], 'replayData': ''},
                         query={'status': 'won'}, signed=True, b64=True)
        self.assertEqual(st, 200)
        rewards = end['data']['rewards']
        kinds = {r['type'] for b in rewards for r in b['rewards']}
        self.assertIn('CharacterXp', kinds)
        self.assertIn('Currency', kinds)
        self.assertEqual(end['data']['matchChallengesResults'] & 1, 1)
        st, prog2 = c.call('PUT', 'acts/progress', {'descriptors': [{'guid': act['guid']}]})
        self.assertTrue(prog2['data'][0]['chapters'][0]['matches'][0]['isCompleted'])
        st, u2 = c.call('GET', 'user')
        self.assertGreater(u2['data']['softCurrency']['free'], before_sc)
        xp = {ch['id']: ch['experience'] for ch in u2['data']['characters']}
        self.assertTrue(all(xp[cid] > 0 for cid in team))
        # the fight is consumed
        st, _ = c.call('PUT', f'fight/{fi["id"]}/end', {}, query={'status': 'won'}, signed=True, b64=True)
        self.assertEqual(st, 404)

    def test_team_and_equip(self):
        c = self.client('dev-team')
        st, d = c.login()
        ids = d['data']['user']['teamIds']
        st, _ = c.call('PUT', 'user/team', query={'ids': ','.join(reversed(ids)), 'defensive': 'false'})
        self.assertEqual(st, 200)
        st, u = c.call('GET', 'user')
        self.assertEqual(u['data']['teamIds'], list(reversed(ids)))

    def _win_match(self, c, act_guid, chapter_guid, match_guid, team, iid=None):
        st, prep = c.call('POST', 'fight', {'type': 'LimitedTimeAct' if iid else 'Campaign', 'actGuid': act_guid,
                                            'chapterGuid': chapter_guid, 'matchGuid': match_guid,
                                            'eventInstanceId': iid}, signed=True, b64=True)
        self.assertEqual(st, 200, prep)
        out = []
        for fi in prep['data']['fights']:
            c.call('PUT', f'fight/{fi["id"]}/start', {'teamIds': team}, signed=True, b64=True)
            st, end = c.call('PUT', f'fight/{fi["id"]}/end', {'stats': []}, query={'status': 'won'},
                             signed=True, b64=True)
            self.assertEqual(st, 200)
            out.append(end)
        return out

    def test_lta_event_run(self):
        c = self.client('dev-lta')
        st, d = c.login()
        team = d['data']['user']['teamIds']
        tickets = {x['type']: x['amount'] for x in d['data']['user']['otherCurrencies']}.get('DailyEventTicket', 0)
        st, evs = c.call('GET', 'events/US')
        active = evs['data']['activeEvents']
        self.assertTrue(active)
        ev = active[0]
        self.assertEqual(ev['type'], 'LimitedTimeAct')
        iid = ev['eventInstanceId']
        # fighting before entering is refused
        act = self.app.content.guid(ev['actGuid'])
        ch = self.app.content.chapters_of(act)[0]
        m = self.app.content.matches_of(ch)[0]
        st, _ = c.call('POST', 'fight', {'type': 'LimitedTimeAct', 'actGuid': act['guid'], 'chapterGuid': ch['guid'],
                                         'matchGuid': m['guid'], 'eventInstanceId': iid}, signed=True, b64=True)
        self.assertEqual(st, 400)
        st, _ = c.call('PUT', f'events/{iid}/enter')
        self.assertEqual(st, 200)
        st, prog = c.call('PUT', 'acts/progress', {'descriptors': [{'guid': act['guid'], 'eventInstanceId': iid}]})
        for chapter in prog['data'][0]['chapters']:
            for match in chapter['matches']:
                self._win_match(c, act['guid'], chapter['guid'], match['guid'], team, iid)
        st, evs = c.call('GET', 'events/US')
        ev2 = next(e for e in evs['data']['activeEvents'] if e['eventInstanceId'] == iid)
        self.assertEqual(ev2['playCount'], 1)
        self.assertFalse(ev2['hasEntered'])
        self.assertTrue(ev2['wasEverFullyCompleted'])
        st, u = c.call('GET', 'user')
        left = {x['type']: x['amount'] for x in u['data']['otherCurrencies']}.get('DailyEventTicket', 0)
        self.assertEqual(left, tickets - 1)

    def test_relics_and_progression(self):
        c = self.client('dev-prog')
        st, d = c.login()
        u0 = d['data']['user']
        hc0 = u0['hardCurrency']['free']
        st, store = c.call('GET', 'store/gachas/US')
        guids = [g['gachaGuid'] for g in store['data']['gachas']]
        bronze = next(g['guid'] for g in self.app.content.all('GachaData') if g['_name'] == 'Gacha_12_Bronze')
        self.assertIn(bronze, guids)
        st, opened = c.call('POST', f'store/gacha/buy/{bronze}', query={'count': 3, 'openAll': 'true'})
        self.assertEqual(st, 200, opened)
        self.assertEqual(len(opened['data']['rewards']), 3)
        self.assertTrue(all(r['type'] for r in opened['data']['rewards']))
        st, u = c.call('GET', 'user')
        self.assertEqual(u['data']['hardCurrency']['free'], hc0 - 3 * 75)
        # feed every non-team character to the first team member
        team = u['data']['teamIds']
        food = [ch['id'] for ch in u['data']['characters'] if ch['id'] not in team]
        target = team[0]
        st, pu = c.call('PUT', 'character/powerUpCharacter', {'consumablesToConsumeMap': {}},
                        query={'cId': target, 'fIds': ','.join(food)})
        self.assertEqual(st, 200, pu)
        st, u2 = c.call('GET', 'user')
        ch = next(x for x in u2['data']['characters'] if x['id'] == target)
        self.assertGreater(ch['experience'], 0)
        self.assertEqual(len(u2['data']['characters']), 3)
        # skill tree: spend the character's skill points
        st, _ = c.call('PUT', f'character/{target}/skilltree/buyAll', {'shortcut': False}, signed=True, b64=True)
        self.assertEqual(st, 200)
        # insufficient funds is a clean error
        for _ in range(10):
            st, _ = c.call('POST', f'store/gacha/buy/{bronze}', query={'count': 1, 'openAll': 'true'})
        self.assertEqual(st, 400)

    def test_daily_challenges(self):
        c = self.client('dev-daily')
        st, d = c.login()
        self.assertEqual(d['data']['user']['claimableChallengeCount'], 1)  # the login challenge
        st, ch = c.call('GET', 'challenges')
        by_status = {x['status'] for x in ch['data']['challenges']}
        self.assertIn('Completed', by_status)
        login = next(x for x in ch['data']['challenges'] if x['status'] == 'Completed')
        st, _ = c.call('PUT', f'challenge/{login["challengeGuid"]}/claim')
        self.assertEqual(st, 200)
        st, _ = c.call('PUT', f'challenge/{login["challengeGuid"]}/claim')
        self.assertEqual(st, 400)
        st, _ = c.call('PUT', 'challenges/reward/0/claim/false')
        self.assertEqual(st, 400)  # 10 points is below the first 20-point threshold
        bronze = next(g['guid'] for g in self.app.content.all('GachaData') if g['_name'] == 'Gacha_12_Bronze')
        c.call('POST', f'store/gacha/buy/{bronze}', query={'count': 1, 'openAll': 'true'})
        st, ch = c.call('GET', 'challenges')
        relic = next(x for x in ch['data']['challenges'] if x['status'] == 'Completed')
        c.call('PUT', f'challenge/{relic["challengeGuid"]}/claim')
        st, r = c.call('PUT', 'challenges/reward/0/claim/false')
        self.assertEqual(st, 200)
        self.assertTrue(r['data'][0]['rewards'])

    def test_unknown_endpoint_is_harmless(self):
        c = self.client('dev-unknown')
        c.login()
        st, d = c.call('GET', 'some/new/endpoint')
        self.assertEqual(st, 200)
        self.assertEqual(d, {})


if __name__ == '__main__':
    unittest.main()
