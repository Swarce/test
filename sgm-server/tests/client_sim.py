"""A minimal re-implementation of the game's HTTP client behaviour, for testing the server
without a device: request signing, session cookie, Base64 bodies and response signatures."""
import base64
import http.client
import json
import re
import ssl
from urllib.parse import urlencode

from sgserver import protocol


class GameClient:
    def __init__(self, host, port, tls=False, device_id='test-device-1'):
        self.host, self.port, self.tls = host, port, tls
        self.device_id = device_id
        self.cookie = None
        self.guest_token = None
        self.trans = 0

    def _conn(self):
        if self.tls:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE  # the real client's validation callback accepts this too
            return http.client.HTTPSConnection(self.host, self.port, context=ctx, timeout=30)
        return http.client.HTTPConnection(self.host, self.port, timeout=30)

    def call(self, method, endpoint, params=None, query=None, signed=False, b64=False, headers=None):
        body = json.dumps(params, separators=(',', ':')) if params is not None else None
        qs = ''
        qlist = []
        if query:
            qs = '?' + urlencode(query)
            qlist = [f'{k}={v}' for k, v in query.items()]
        h = {'Content-Type': 'application/json', 'Accept': 'application/json'}
        self.trans += 1
        h[protocol.TRANSACTION_HEADER] = str(self.trans)
        if self.cookie:
            h['Cookie'] = self.cookie
        if signed:
            h[protocol.SIG_HEADER] = protocol.request_signature(endpoint, qlist, body or '')
        h.update(headers or {})
        c = self._conn()
        c.request(method, protocol.API_PREFIX + endpoint + qs, body=body, headers=h)
        r = c.getresponse()
        raw = r.read().decode('utf-8')
        c.close()
        text = raw
        if b64 and r.status == 200:
            text = base64.b64decode(raw).decode('utf-8')
        if signed and r.status == 200:
            sig = r.getheader(protocol.SIG_HEADER)
            assert sig and sig.upper() == protocol.response_signature(text), f'bad response signature on {endpoint}'
        sc = r.getheader('Set-Cookie')
        if sc:  # AuthenticateRequest.ParseSetCookieHeaders
            pairs = [m.groups() for part in sc.split(',') for m in [re.match(r'(.+?)=(.+?);', part.strip())] if m]
            self.cookie = '; '.join(f'{k}={v}' for k, v in pairs)
        assert r.getheader('Date'), 'server must send a Date header (client clock sync)'
        data = json.loads(text) if text else {}
        return r.status, data

    def login(self, provider='Guest', region='US', new_user=True, email=None, password=None):
        headers = {}
        if self.guest_token and provider == 'Guest':
            headers['X-AUTH-USER-TOKEN'] = self.guest_token
        if email:
            headers['Authorization'] = 'Bearer ' + base64.b64encode(f'{email}:{password}'.encode()).decode()
        st, d = self.call('POST', f'authenticate/android/{self.device_id}/{region}/{provider}',
                          {'ddnaMetricsId': 'x', 'newUser': new_user, 'deviceMetrics': 0,
                           'deviceMetricsContext': None, 'integrityToken': ''}, headers=headers)
        if st == 200:
            self.guest_token = d['data']['session']['providerUserToken']
        return st, d
