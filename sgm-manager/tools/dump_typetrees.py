#!/usr/bin/env python3
"""Dump the field layouts (typetree nodes) of the game classes the server needs, for the Java
on-device importer (sgm.core.extract). Only names and types are written, no game content.

usage: python tools/dump_typetrees.py <Skullgirls.xapk | base.apk ...> -o app/assets/typetrees.json
Needs the same dependencies as sgm-server/tools/extract_content.py.
"""
import argparse
import json
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'sgm-server', 'tools'))
import extract_content as ec  # noqa: E402
import UnityPy  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('inputs', nargs='+')
    ap.add_argument('-o', '--out', required=True)
    a = ap.parse_args()
    work = tempfile.mkdtemp(prefix='sgm-tt-')
    try:
        data_dir, so, meta = ec.collect_inputs(a.inputs, work)
        gen = ec.FixedTypeTreeGenerator(ec.UNITY_VERSION)
        gen.load_il2cpp(so, meta)
        layouts = {}
        for o in UnityPy.load(os.path.join(data_dir, 'globalgamemanagers.assets')).objects:
            if o.type.name != 'MonoScript':
                continue
            s = o.read()
            if not ec.wanted(s.m_ClassName) or s.m_ClassName in layouts:
                continue
            full = f'{s.m_Namespace}.{s.m_ClassName}' if s.m_Namespace else s.m_ClassName
            try:
                nodes = gen.get_nodes(s.m_AssemblyName, full)
            except Exception as e:  # noqa: BLE001
                print('skip', full, e)
                continue
            layouts[s.m_ClassName] = [[n.m_Level, n.m_Type, n.m_Name, n.m_MetaFlag or 0] for n in nodes]
        with open(a.out, 'w') as f:
            json.dump({'unityVersion': ec.UNITY_VERSION, 'classes': layouts}, f, separators=(',', ':'), sort_keys=True)
        print(f'wrote {len(layouts)} class layouts to {a.out}')
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    main()
