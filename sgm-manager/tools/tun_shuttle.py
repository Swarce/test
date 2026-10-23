#!/usr/bin/env python3
"""Linux test bench for the VPN packet engine (needs root / CAP_NET_ADMIN).

Creates a TUN interface with the manager's addresses (10.111.222.1/24), runs the Java harness
(sgm.core.vpn.TunHarness) and relays packets between the kernel and it, then drives the game
protocol through the virtual addresses exactly like the phone would:
DNS query to 10.111.222.2 -> TLS to the returned address -> guest login and a campaign fight.

usage: sudo python3 tools/tun_shuttle.py <java classpath> <content dir> <sgm-server dir>
"""
import fcntl
import os
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


def main():
    cp, content, server_dir = sys.argv[1:4]
    mode = sys.argv[4] if len(sys.argv) > 4 else 'online'
    fd = make_tun()
    data = tempfile.mkdtemp()
    java = subprocess.Popen(['java', '-cp', cp, 'sgm.core.vpn.TunHarness', content, data, mode],
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

    ok = True
    rcode, ip = dns_query(API_HOST)
    print('DNS A', API_HOST, '->', ip, 'rcode', rcode)
    ok &= ip == '10.111.222.3'
    rcode, ip6 = dns_query(API_HOST, 28)
    print('DNS AAAA', API_HOST, '->', ip6, 'rcode', rcode)
    ok &= ip6 is None and rcode == 0
    rcode, other = dns_query('example.com')
    print('DNS A example.com (forwarded upstream) ->', other, 'rcode', rcode)

    sys.path.insert(0, os.path.join(server_dir, 'tests'))
    sys.path.insert(0, server_dir)
    from client_sim import GameClient
    c = GameClient('10.111.222.3', 443, tls=True, device_id='tun-device')
    st, d = c.login()
    print('TLS login through the VPN path:', st, d['data']['user']['handle'])
    ok &= st == 200
    st, acts = c.call('GET', 'acts/campaign')
    st2, prog = c.call('PUT', 'acts/progress', {'descriptors': [{'guid': acts['data'][0]}]})
    m = prog['data'][0]['chapters'][0]['matches'][0]
    st3, prep = c.call('POST', 'fight', {'type': 'Campaign', 'matchGuid': m['guid']}, signed=True, b64=True)
    fid = prep['data']['fights'][0]['id']
    c.call('PUT', f'fight/{fid}/start', {'teamIds': d['data']['user']['teamIds']}, signed=True, b64=True)
    st4, end = c.call('PUT', f'fight/{fid}/end', {'stats': []}, query={'status': 'won'}, signed=True, b64=True)
    print('fight through the VPN path:', st3, st4, len(end['data']['rewards']), 'reward bundles')
    ok &= st4 == 200
    # a big response (full collection) to exercise segmentation across many packets
    st5, coll = c.call('GET', 'user/collection')
    print('collection', st5, len(str(coll)), 'bytes')
    if mode == 'offline':
        # a pre-cached multi-megabyte "asset bundle" served by the CDN cache over TLS
        import hashlib
        import http.client
        import ssl
        blob = os.urandom(6 * 1024 * 1024 + 123)
        os.makedirs(os.path.join(data, 'cdn-cache', 'v1', 'Android'), exist_ok=True)
        with open(os.path.join(data, 'cdn-cache', 'v1', 'Android', 'bundle_test.bundle'), 'wb') as f:
            f.write(blob)
        rc, cdn_ip = dns_query('skgm-prod-cdn.skullgirlsmobile.com')
        ctx = ssl.create_default_context()
        ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
        conn = http.client.HTTPSConnection(cdn_ip, 443, context=ctx, timeout=60)
        t0 = time.time()
        conn.request('GET', '/v1/Android/bundle_test.bundle', headers={'Host': 'skgm-prod-cdn.skullgirlsmobile.com'})
        resp = conn.getresponse()
        got = resp.read()
        dt = time.time() - t0
        print(f'CDN cache via {cdn_ip}: HTTP {resp.status}, {len(got)} bytes in {dt:.2f}s, intact={got == blob}')
        ok &= got == blob
        conn.request('GET', '/v1/Android/bundle_test.bundle', headers={'Host': 'skgm-prod-cdn.skullgirlsmobile.com', 'Range': 'bytes=1000-'})
        resp = conn.getresponse()
        part = resp.read()
        print('range request:', resp.status, resp.getheader('Content-Range'), part == blob[1000:])
        ok &= resp.status == 206 and part == blob[1000:]
        rc, cip = dns_query('clients3.google.com')
        s = socket.create_connection((cip, 80), timeout=5)
        s.sendall(b'GET /generate_204 HTTP/1.1\r\nHost: clients3.google.com\r\nConnection: close\r\n\r\n')
        line = s.recv(100).split(b'\r\n')[0]
        print('connectivity check ->', cip, line)
        ok &= b' 204 ' in line
    java.stdin.close()
    java.wait(10)
    print(open(os.path.join(data, 'log')).read()[-1500:])
    print('RESULT', 'PASS' if ok else 'FAIL')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
