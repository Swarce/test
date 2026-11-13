"""HTTP(S) front end: routing, sessions, signatures and response encoding."""
import json
import logging
import re
import ssl
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

from . import protocol
from .protocol import ApiError

log = logging.getLogger('sgserver')

ROUTES = []


class Route:
    def __init__(self, method, pattern, fn, auth, b64, sign):
        self.method = method
        self.regex = re.compile('^' + pattern + '$')
        self.fn = fn
        self.auth = auth
        self.b64 = b64
        self.sign = sign


def route(method, pattern, auth=True, b64=False, sign=False):
    """Register a handler for /v1/<pattern>. Named groups become handler kwargs.

    b64:  the client sets expectedResponseEncoding=Base64 for this request.
    sign: the client checks X-SG-SIG on the response.
    """
    def deco(fn):
        for m in method.split('|'):
            ROUTES.append(Route(m, pattern, fn, auth, b64, sign))
        return fn
    return deco


class Ctx:
    """Everything a handler needs for one request."""

    def __init__(self, app, method, endpoint, query, body, headers):
        self.app = app
        self.content = app.content
        self.store = app.store
        self.method = method
        self.endpoint = endpoint
        self.query = query
        self.body = body
        self.headers = headers
        self.user = None
        self.dirty = False
        self.set_cookie = None
        self.now = time.time()

    @property
    def now_ms(self):
        return int(self.now * 1000)

    def q(self, key, default=None):
        v = self.query.get(key)
        return v[-1] if v else default

    def q_list(self, key):
        """Query list values: the client sends repeated keys or comma separated values."""
        out = []
        for v in self.query.get(key, []):
            out.extend(x for x in v.split(',') if x)
        return out

    def q_bool(self, key, default=False):
        v = self.q(key)
        return default if v is None else v.lower() in ('1', 'true', 'yes')

    def save(self):
        self.dirty = True


class App:
    def __init__(self, content, store, config):
        self.content = content
        self.store = store
        self.config = config
        self.unhandled = {}

    def dispatch(self, method, raw_path, body_bytes, headers):
        """Returns (status, headers dict, body bytes)."""
        parts = urlsplit(raw_path)
        path = unquote(parts.path)
        if not path.startswith(protocol.API_PREFIX):
            return 404, {}, b'{}'
        endpoint = path[len(protocol.API_PREFIX):].strip('/')
        query = parse_qs(parts.query, keep_blank_values=True)

        raw_body = body_bytes.decode('utf-8', 'replace') if body_bytes else ''
        body = {}
        if raw_body.strip():
            try:
                body = json.loads(raw_body)
            except ValueError:
                body = {}
        ctx = Ctx(self, method, endpoint, query, body if isinstance(body, dict) else {'_': body}, headers)

        rt, params = None, {}
        for r in ROUTES:
            if r.method == method:
                m = r.regex.match(endpoint)
                if m:
                    rt, params = r, m.groupdict()
                    break

        out_headers = {}
        status = 200
        try:
            token = _cookie(headers.get('Cookie', ''), protocol.SESSION_COOKIE)
            user_id = self.store.session_user(token) if token else None
            if user_id:
                ctx.user = self.store.load_user(user_id)
            if rt is None:
                key = f'{method} {endpoint}'
                self.unhandled[key] = self.unhandled.get(key, 0) + 1
                log.warning('UNHANDLED %s /v1/%s query=%s body=%s', method, endpoint, dict(query), raw_body[:300])
                payload = {}
            else:
                if rt.auth and ctx.user is None:
                    raise ApiError(401, 'Unauthorized', 'no session')
                if rt.sign:
                    self._check_request_signature(endpoint, query, raw_body, headers)
                payload = rt.fn(ctx, **params)
                if payload is None:
                    payload = {}
                if ctx.dirty and ctx.user is not None:
                    self.store.save_user(ctx.user)
        except ApiError as e:
            status = e.status
            payload = {'errorCode': e.code, 'message': e.message}
            log.info('error %s /v1/%s -> %s %s', method, endpoint, e.status, e.code)
        except Exception:
            status = 500
            payload = {'errorCode': 'InternalError', 'message': 'internal server error'}
            log.error('handler crashed for %s /v1/%s\n%s', method, endpoint, traceback.format_exc())

        text = protocol.dumps(payload)
        b64 = rt is not None and rt.b64 and status == 200
        if rt is not None and rt.sign:
            out_headers[protocol.SIG_HEADER] = protocol.response_signature(text)
        if ctx.set_cookie:
            out_headers['Set-Cookie'] = ctx.set_cookie
        out_headers['Content-Type'] = 'application/json; charset=utf-8'
        log.debug('<- %s %s', status, text[:2000])
        return status, out_headers, protocol.encode_body(text, b64)

    def _check_request_signature(self, endpoint, query, raw_body, headers):
        got = headers.get(protocol.SIG_HEADER)
        if not got:
            log.info('request to %s carries no signature', endpoint)
            return
        queries = [f'{k}={v}' for k, vs in query.items() for v in vs]
        want = protocol.request_signature(endpoint, queries, raw_body)
        if got.upper() != want:
            # Not fatal: the private server trusts its client, this only helps debugging.
            log.info('request signature mismatch on %s (got %s want %s)', endpoint, got, want)


def _cookie(header, name):
    for part in header.split(';'):
        k, _, v = part.strip().partition('=')
        if k == name:
            return v
    return None


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'sgm-private-server'
    app: App = None

    def _handle(self):
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b''
        started = time.time()
        status, headers, out = self.app.dispatch(self.command, self.path, body, self.headers)
        self.send_response(status)
        for k, v in headers.items():
            self.send_header(k, v)
        self.send_header('Content-Length', str(len(out)))
        self.end_headers()
        self.wfile.write(out)
        log.info('%s %s -> %d (%d ms)', self.command, self.path, status, (time.time() - started) * 1000)
        if body:
            log.debug('-> body %s', body[:2000])

    do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = _handle

    def log_message(self, fmt, *args):  # silence default stderr logging
        pass


def serve(app, host, port, certfile=None, keyfile=None):
    handler = type('BoundHandler', (Handler,), {'app': app})
    httpd = ThreadingHTTPServer((host, port), handler)
    scheme = 'http'
    if certfile:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(certfile, keyfile)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
        scheme = 'https'
    log.info('listening on %s://%s:%d', scheme, host, port)
    httpd.serve_forever()
