#!/usr/bin/env python3
"""Linux test bench for the VPN packet engine (needs root / CAP_NET_ADMIN).

Creates a TUN interface with the manager's addresses (10.111.222.1/24), runs the Java harness
(sgm.core.vpn.TunHarness) and relays packets between the kernel and it, then drives the game
protocol through the virtual addresses exactly like the phone would:
DNS query to 10.111.222.2 -> TLS to the returned address -> guest login and a campaign fight.

usage: sudo python3 tools/tun_shuttle.py <java classpath> <content dir> <sgm-server dir>

A fake "official CDN" runs on localhost; the second half of the run switches it off to check that
saved copies are served and that the start-screen maintenance check never fails (error 867).
"""
import fcntl
import hashlib
import http.client
import http.server
import os
import ssl
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

TUNSETIFF, IFF_TUN, IFF_NO_PI = 0x400454ca, 0x0001, 0x1000
SIOCSIFADDR, SIOCSIFNETMASK, SIOCGIFFLAGS, SIOCSIFFLAGS = 0x8916, 0x891c, 0x8913, 0x8914
NAME = b'sgmtun0'
API_HOST = 'skgm-prod-game-api.skullgirlsmobile.com'


def ifreq_addr(name, addr):
    return struct.pack('16sH2s4s8s', name, socket.AF_INET, b'\0\0', socket.inet_aton(addr), b'\0' * 8)


def make_tun():
    fd = os.open('/dev/net/tun', os.O_RDWR)
    fcntl.ioctl(fd, TUNSETIFF, struct.pack('16sH', NAME, IFF_TUN | IFF_NO_PI))
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    fcntl.ioctl(s, SIOCSIFADDR, ifreq_addr(NAME, '10.111.222.1'))
    fcntl.ioctl(s, SIOCSIFNETMASK, ifreq_addr(NAME, '255.255.255.0'))
    flags = struct.unpack('16sH', fcntl.ioctl(s, SIOCGIFFLAGS, struct.pack('16sH', NAME, 0)))[1]
    fcntl.ioctl(s, SIOCSIFFLAGS, struct.pack('16sH', NAME, flags | 0x1 | 0x40))
    return fd


def dns_query(name, qtype=1):
    q = struct.pack('>HHHHHH', 0x1234, 0x0100, 1, 0, 0, 0)
    for label in name.split('.'):
        q += bytes([len(label)]) + label.encode()
    q += b'\0' + struct.pack('>HH', qtype, 1)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(6)
    s.sendto(q, ('10.111.222.2', 53))
    r = s.recv(4096)
    an = struct.unpack('>H', r[6:8])[0]
    rcode = r[3] & 0xf
    return rcode, (socket.inet_ntoa(r[-4:]) if an else None)


class FakeCdn(http.server.BaseHTTPRequestHandler):
    files = {}
    hits = []

    def do_GET(self):
        FakeCdn.hits.append(self.path)
        body = FakeCdn.files.get(self.path)
        if body is None:
            self.send_response(404)
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        # slow upstream: ~3 s for big files, to check that the proxy streams instead of buffering
        step = max(1, len(body) // 30)
        for i in range(0, len(body), step):
            self.wfile.write(body[i:i + step])
            if len(body) > 1000000:
                time.sleep(0.1)

    def log_message(self, *a):
        pass


def https_get(ip, path, host, headers=None, timeout=60):
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
    conn = http.client.HTTPSConnection(ip, 443, context=ctx, timeout=timeout)
    h = {'Host': host}
    h.update(headers or {})
    t0 = time.time()
    conn.request('GET', path, headers=h)
    resp = conn.getresponse()
    first = resp.read(1)
    ttfb = time.time() - t0
    body = first + resp.read()
    return resp.status, body, ttfb, time.time() - t0, resp


def main():
    cp, content, server_dir = sys.argv[1:4]
    fd = make_tun()
    data = tempfile.mkdtemp()
    cdn_host = 'skgm-prod-cdn.skullgirlsmobile.com'
    big = os.urandom(8 * 1024 * 1024 + 77)
    FakeCdn.files = {'/v1/fingerprints.bytes': os.urandom(4000), '/v1/vram.bytes': os.urandom(9000),
                     '/v1/Android/platform_fingerprints.bytes': os.urandom(3000), '/v1/Android/big.bundle': big,
                     '/maintenanceMode.json': b'{"enabled": true}'}
    cdn = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FakeCdn)
    threading.Thread(target=cdn.serve_forever, daemon=True).start()
    java = subprocess.Popen(['java', '-cp', cp, 'sgm.core.vpn.TunHarness', content, data, str(cdn.server_address[1])],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=open(os.path.join(data, 'log'), 'w'))

    def tun_to_java():
        try:
            while True:
                p = os.read(fd, 65535)
                java.stdin.write(struct.pack('>H', len(p)) + p)
                java.stdin.flush()
        except (OSError, ValueError):
            return  # harness finished

    def java_to_tun():
        while True:
            h = java.stdout.read(2)
            if len(h) < 2:
                return
            n = struct.unpack('>H', h)[0]
            os.write(fd, java.stdout.read(n))

    threading.Thread(target=tun_to_java, daemon=True).start()
    threading.Thread(target=java_to_tun, daemon=True).start()
    for _ in range(100):
        if 'READY' in open(os.path.join(data, 'log')).read():
            break
        time.sleep(0.2)

    results = []

    def check(name, ok, detail=''):
        results.append(ok)
        print(('PASS ' if ok else 'FAIL ') + name + (f'  [{detail}]' if detail else ''))

    rcode, ip = dns_query(API_HOST)
    check('DNS: API host -> 10.111.222.3', ip == '10.111.222.3')
    rcode, ip6 = dns_query(API_HOST, 28)
    check('DNS: no IPv6 answer for the API host', ip6 is None and rcode == 0)
    rcode, cdn_ip = dns_query(cdn_host)
    check('DNS: CDN host -> 10.111.222.4', cdn_ip == '10.111.222.4')
    rcode, lh = dns_query('localhost')
    check('DNS: other names go through the system resolver', lh == '127.0.0.1', f'rcode {rcode}, {lh}')

    sys.path.insert(0, os.path.join(server_dir, 'tests'))
    sys.path.insert(0, server_dir)
    from client_sim import GameClient
    c = GameClient('10.111.222.3', 443, tls=True, device_id='tun-device')
    st, d = c.login()
    check('API: TLS guest login through the VPN path', st == 200)
    st, acts = c.call('GET', 'acts/campaign')
    st2, prog = c.call('PUT', 'acts/progress', {'descriptors': [{'guid': acts['data'][0]}]})
    m = prog['data'][0]['chapters'][0]['matches'][0]
    st3, prep = c.call('POST', 'fight', {'type': 'Campaign', 'matchGuid': m['guid']}, signed=True, b64=True)
    fid = prep['data']['fights'][0]['id']
    c.call('PUT', f'fight/{fid}/start', {'teamIds': d['data']['user']['teamIds']}, signed=True, b64=True)
    st4, end = c.call('PUT', f'fight/{fid}/end', {'stats': []}, query={'status': 'won'}, signed=True, b64=True)
    check('API: campaign fight', st3 == 200 and st4 == 200)

    st, body, ttfb, total, _ = https_get(cdn_ip, '/maintenanceMode.json', cdn_host)
    check('CDN: maintenance check answered locally (HTTP error = no maintenance, never 867)',
          st == 404 and '/maintenanceMode.json' not in FakeCdn.hits, f'HTTP {st}')
    for p in ('/v1/vram.bytes', '/v1/fingerprints.bytes', '/v1/Android/platform_fingerprints.bytes'):
        st, body, ttfb, total, _ = https_get(cdn_ip, p, cdn_host)
        check(f'CDN: {p} passed through', st == 200 and body == FakeCdn.files[p])
    st, body, ttfb, total, _ = https_get(cdn_ip, '/v1/Android/big.bundle', cdn_host)
    check('CDN: 8 MB bundle streamed while downloading (first byte fast, content intact)',
          st == 200 and body == big and ttfb < 1.5, f'first byte {ttfb:.2f}s, total {total:.2f}s')
    st, body, ttfb, total, resp = https_get(cdn_ip, '/v1/Android/big.bundle', cdn_host, {'Range': 'bytes=5000-'})
    check('CDN: resume (Range) from the saved copy', st == 206 and body == big[5000:], resp.getheader('Content-Range'))

    cdn.shutdown()
    cdn.server_close()
    print('-- official CDN switched off --')
    st, body, ttfb, total, _ = https_get(cdn_ip, '/maintenanceMode.json', cdn_host)
    check('CDN down: maintenance check still answered', st == 404)
    for p in ('/v1/vram.bytes', '/v1/fingerprints.bytes', '/v1/Android/platform_fingerprints.bytes', '/v1/Android/big.bundle'):
        st, body, ttfb, total, _ = https_get(cdn_ip, p, cdn_host)
        check(f'CDN down: {p} served from the saved copy', st == 200 and body == FakeCdn.files[p])
    st, body, ttfb, total, _ = https_get(cdn_ip, '/v1/Android/never_downloaded.bundle', cdn_host)
    check('CDN down: unsaved file fails fast with an HTTP error (no 15 s hang)', st == 503 and total < 10, f'{total:.1f}s')

    rc, cip = dns_query('clients3.google.com')
    s = socket.create_connection((cip, 80), timeout=5)
    s.sendall(b'GET /generate_204 HTTP/1.1\r\nHost: clients3.google.com\r\nConnection: close\r\n\r\n')
    line = s.recv(100).split(b'\r\n')[0]
    check('connectivity check answered locally', b' 204 ' in line, line.decode())

    java.stdin.close()
    java.wait(10)
    log = open(os.path.join(data, 'log')).read()
    print('\n'.join(l for l in log.splitlines() if ' I cdn' in l or ' W cdn' in l or 'stats:' in l))
    print('RESULT', 'PASS' if all(results) else 'FAIL', f'({sum(results)}/{len(results)})')
    sys.exit(0 if all(results) else 1)


if __name__ == '__main__':
    main()
