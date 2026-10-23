#!/usr/bin/env python3
"""Tiny DNS server that points the game's API host at your server and forwards everything else.

usage: sudo python tools/dns_redirect.py --server-ip 192.168.1.50 [--upstream 1.1.1.1]

Then set the phone's Wi-Fi DNS to this machine's IP (Wi-Fi settings -> IP settings: Static ->
DNS 1) and turn off Android "Private DNS". Only the API host is redirected; the official CDN
still serves the game's art/audio downloads.
"""
import argparse
import socket
import struct
import threading

DEFAULT_HOSTS = ['skgm-prod-game-api.skullgirlsmobile.com']


def parse_question(pkt):
    i, labels = 12, []
    while pkt[i]:
        n = pkt[i]
        labels.append(pkt[i + 1:i + 1 + n].decode('ascii', 'replace'))
        i += n + 1
    qtype, qclass = struct.unpack('>HH', pkt[i + 1:i + 5])
    return '.'.join(labels).lower(), qtype, qclass, i + 5


def answer(pkt, q_end, ip, qtype):
    tid = pkt[:2]
    flags = struct.pack('>H', 0x8180)  # standard response, recursion available, no error
    if qtype == 1:  # A
        header = tid + flags + struct.pack('>HHHH', 1, 1, 0, 0)
        rr = b'\xc0\x0c' + struct.pack('>HHIH', 1, 1, 60, 4) + socket.inet_aton(ip)
        return header + pkt[12:q_end] + rr
    # AAAA and others: empty answer so the client falls back to IPv4
    return tid + flags + struct.pack('>HHHH', 1, 0, 0, 0) + pkt[12:q_end]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--server-ip', required=True, help='IP of the machine running sgserver')
    ap.add_argument('--upstream', default='1.1.1.1')
    ap.add_argument('--listen', default='0.0.0.0')
    ap.add_argument('--port', type=int, default=53)
    ap.add_argument('--host', action='append', help='extra hostnames to redirect')
    a = ap.parse_args()
    hosts = set(DEFAULT_HOSTS + (a.host or []))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((a.listen, a.port))
    print(f'DNS on {a.listen}:{a.port}: {", ".join(sorted(hosts))} -> {a.server_ip}; other names via {a.upstream}')

    def forward(pkt, addr):
        up = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        up.settimeout(3)
        try:
            up.sendto(pkt, (a.upstream, 53))
            sock.sendto(up.recv(4096), addr)
        except OSError:
            pass
        finally:
            up.close()

    while True:
        pkt, addr = sock.recvfrom(4096)
        try:
            name, qtype, qclass, q_end = parse_question(pkt)
        except (IndexError, struct.error):
            continue
        if name in hosts:
            print(f'{addr[0]} asked {name} (type {qtype}) -> {a.server_ip}')
            sock.sendto(answer(pkt, q_end, a.server_ip, qtype), addr)
        else:
            threading.Thread(target=forward, args=(pkt, addr), daemon=True).start()


if __name__ == '__main__':
    main()
