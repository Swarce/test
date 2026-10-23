#!/usr/bin/env python3
"""Compare two extracted content directories (e.g. Java on-device importer vs extract_content.py).

usage: python tools/compare_content.py <dir-a> <dir-b>
"""
import json
import math
import os
import sys


def load(d):
    out = {}
    for fn in sorted(os.listdir(d)):
        if fn.endswith('.json'):
            with open(os.path.join(d, fn), encoding='utf-8') as f:
                out[fn[:-5]] = json.load(f)
    return out


def same(a, b, path, diffs):
    if isinstance(a, float) and math.isnan(a):
        a = None
    if isinstance(b, float) and math.isnan(b):
        b = None
    if isinstance(a, bool) or isinstance(b, bool):
        ok = a == b and type(a) is type(b)
    elif isinstance(a, (int, float)) and isinstance(b, (int, float)):
        ok = a == b and isinstance(a, float) == isinstance(b, float)
    elif isinstance(a, dict) and isinstance(b, dict):
        if set(a) != set(b):
            diffs.append(f'{path}: keys {sorted(set(a) ^ set(b))[:5]}')
            return
        for k in a:
            same(a[k], b[k], f'{path}.{k}', diffs)
        return
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            diffs.append(f'{path}: len {len(a)} != {len(b)}')
            return
        for i, (x, y) in enumerate(zip(a, b)):
            same(x, y, f'{path}[{i}]', diffs)
        return
    else:
        ok = a == b
    if not ok:
        diffs.append(f'{path}: {a!r:.80} != {b!r:.80}')


def main():
    a, b = load(sys.argv[1]), load(sys.argv[2])
    total = bad = 0
    for cls in sorted(set(a) | set(b)):
        if cls not in a or cls not in b:
            print(f'class {cls} only in', 'A' if cls in a else 'B')
            bad += 1
            continue
        ka, kb = set(a[cls]), set(b[cls])
        if ka != kb:
            print(f'{cls}: {len(ka - kb)} objects only in A, {len(kb - ka)} only in B, e.g. {sorted(ka ^ kb)[:3]}')
            bad += 1
        for k in sorted(ka & kb):
            total += 1
            diffs = []
            same(a[cls][k], b[cls][k], f'{cls}[{k}]', diffs)
            if diffs:
                bad += 1
                if bad <= 15:
                    print('\n  '.join(diffs[:4]))
    print(f'{total} common objects compared, {bad} differences')
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
