#!/usr/bin/env python3
"""Extract the game content the server needs from your own copy of the Android client.

usage: python tools/extract_content.py <Skullgirls.xapk | base.apk [split.apk ...] | extracted dir> [-o content]

Needs: pip install UnityPy TypeTreeGeneratorAPI

The game ships all of its design data (acts, matches, fights, characters, stat tables, loot
tables, economy settings, events, relics...) as Unity ScriptableObjects inside the APK. Their
typetrees are stripped, so they are rebuilt from libil2cpp.so + global-metadata.dat.
Output: <out>/<ClassName>.json = {"file:pathID": {"_class", "_name", ...fields}}; references
between objects use the same "file:pathID" strings.
"""
import argparse
import collections
import io
import json
import os
import re
import shutil
import sys
import tempfile
import time
import zipfile

try:
    import UnityPy
    from UnityPy.helpers.TypeTreeGenerator import TypeTreeGenerator
except ImportError:
    sys.exit('missing dependencies: pip install UnityPy TypeTreeGeneratorAPI')

UNITY_VERSION = '2022.3.62f2'

ALLOW = set('''
AIProfile Act ActManager AdOfferData AdReward AchievementGraph AchievementManager AchievementNode ArtSettings
AssistCharacterData AssistMoveData BaseCharacterData BattlePassData BattlePassManager BattlePassVersion
BlockbusterMoveData CardManager ChallengeManager Chapter CharacterConstraint CityMapZone CollectibleNodeModifierData
CollectibleNodeModifierManager CollectiblePortraitData ComboCountCondition ConsumableData ConsumableManager
CosmeticManager CurrencyOfferData DebugSettings DeploymentManager DownloadDirectory EconomySettings
ElementConstraint EventSet EventsManager FX32Asset FeaturedOfferData Fight GachaData GachaManager GameContentSet
GameSettings GameState GameTipData GuildManager LTAEventData LeaderboardEventData LifebarThresholdCondition
LimitedTimeOfferData LootTable LootTableSet MapNode MapNodeGraphics MatchChallenge MatchManager MazeArtifactData
MazeEventData MazeMapNode ModifierCondition MoneyOfferData MultiOfferData MusicTrackData NodeMap NodeMapTileset
PrefabDatabase ProgressionManager PurchaseManager RaidBossEventData RaidBossSeasonData RaidBossSettingsData
RaidBossTierData RewardCalendar RiftBattleEventData SGAudioSettings SGMatch SignatureAbility
SignatureAbilityFeature SignatureAbilityTier SkillTree SkillTreeBranch SkillTreeNode SlotOpsCondition
SocialSettings Source SpecialMoveData StatData StatManager StatTable Stage StageSet SubscriptionOfferData
SuperAndLoadoutComboData SyncFightManager SyncPvpEventData TalkingHead TargetFighterScoreList TierConstraint
TutorialManager VariantCharacterData VariantCharacterSet VariantConstraint VersionData Zone
'''.split())
ALLOW_SUFFIX = re.compile(r'(ChallengeData|AchievementData|GuildOpsChallengeData|GuildOpsData|PointsData)$')


def wanted(cls):
    return cls in ALLOW or bool(ALLOW_SUFFIX.search(cls))


class FixedTypeTreeGenerator(TypeTreeGenerator):
    """TypeTreeGeneratorAPI quirks: string[] gets an outer node typed 'string' (UnityPy then reads
    a single string and desyncs) and m_Enabled lacks its 4-byte alignment flag."""

    def get_nodes(self, assembly, fullname):
        # assemblies loaded from il2cpp are named without the ".dll" UnityPy appends
        if assembly.endswith('.dll'):
            assembly = assembly[:-4]
        nodes = super().get_nodes(assembly, fullname)
        for i, n in enumerate(nodes):
            if n.m_Type == 'string' and i + 3 < len(nodes) and nodes[i + 1].m_Type == 'Array' \
                    and nodes[i + 3].m_Level == n.m_Level + 2 and nodes[i + 3].m_Type == 'string':
                n.m_Type = 'vector'
            if n.m_Name == 'm_Enabled' and n.m_Level == 1:
                n.m_MetaFlag = (n.m_MetaFlag or 0) | 0x4000
        return nodes


def localization_table(obj):
    """The English string table is a TextAsset named "en" holding one JSON object."""
    try:
        ta = obj.read()
    except Exception:  # noqa: BLE001 - not every TextAsset parses; none of those matter here
        return None
    if getattr(ta, 'm_Name', '') != 'en':
        return None
    text = ta.m_Script
    if isinstance(text, (bytes, bytearray)):
        text = bytes(text).decode('utf-8', 'replace')
    try:
        table = json.loads(text.lstrip('\ufeff'))
    except ValueError:
        return None
    return table if isinstance(table, dict) and 'Char_Annie_Name' in table else None


def collect_inputs(paths, work):
    """Lay out assets/bin/Data (split files merged) plus libil2cpp.so and global-metadata.dat."""
    data_dir = os.path.join(work, 'Data')
    os.makedirs(data_dir, exist_ok=True)
    so = meta = None
    splits = collections.defaultdict(dict)

    def take(name, read):
        nonlocal so, meta
        if name.endswith('lib/arm64-v8a/libil2cpp.so'):
            so = read()
        elif name.endswith('Managed/Metadata/global-metadata.dat'):
            meta = read()
        elif '/bin/Data/' in name and not name.endswith('/') and '/Managed/' not in name:
            rel = name.split('/bin/Data/', 1)[1]
            m = re.match(r'(.*)\.split(\d+)$', rel)
            if m:
                splits[m.group(1)][int(m.group(2))] = read()
            else:
                dst = os.path.join(data_dir, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(dst, 'wb') as f:
                    f.write(read())

    def walk_zip(zf):
        for info in zf.infolist():
            if info.filename.endswith('.apk'):
                with zf.open(info) as f:
                    walk_zip(zipfile.ZipFile(io.BytesIO(f.read())))
            else:
                take(info.filename, lambda i=info: zf.read(i))

    for p in paths:
        if os.path.isdir(p):
            for root, _, files in os.walk(p):
                for fn in files:
                    full = os.path.join(root, fn)
                    rel = os.path.relpath(full, p).replace(os.sep, '/')
                    if fn.endswith('.apk'):
                        walk_zip(zipfile.ZipFile(full))
                    else:
                        take('/' + rel, lambda f=full: open(f, 'rb').read())
        else:
            walk_zip(zipfile.ZipFile(p))
    for name, parts in splits.items():
        with open(os.path.join(data_dir, name), 'wb') as f:
            for i in sorted(parts):
                f.write(parts[i])
    if not so or not meta:
        sys.exit('could not find libil2cpp.so (arm64-v8a) and global-metadata.dat in the input')
    if not os.path.exists(os.path.join(data_dir, 'globalgamemanagers.assets')):
        sys.exit('could not find assets/bin/Data/globalgamemanagers.assets in the input')
    return data_dir, so, meta


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('inputs', nargs='+')
    ap.add_argument('-o', '--out', default='content')
    a = ap.parse_args()
    t0 = time.time()
    work = tempfile.mkdtemp(prefix='sgm-extract-')
    try:
        data_dir, so, meta = collect_inputs(a.inputs, work)
        print(f'unpacked client data in {time.time() - t0:.0f}s', flush=True)
        scripts = {}
        for o in UnityPy.load(os.path.join(data_dir, 'globalgamemanagers.assets')).objects:
            if o.type.name == 'MonoScript':
                scripts[o.path_id] = o.read().m_ClassName
        gen = FixedTypeTreeGenerator(UNITY_VERSION)
        gen.load_il2cpp(so, meta)
        env = UnityPy.load(data_dir)
        env.typetree_generator = gen
        out = collections.defaultdict(dict)
        errors = collections.Counter()

        def resolve(af, fid, pid):
            if pid == 0:
                return ''
            if fid == 0:
                name = af.name
            else:
                try:
                    name = af.externals[fid - 1].path
                except IndexError:
                    return f'?{fid}:{pid}'
            return f'{os.path.basename(name)}:{pid}'

        def conv(af, v):
            if isinstance(v, dict):
                if set(v) == {'m_FileID', 'm_PathID'}:
                    return resolve(af, v['m_FileID'], v['m_PathID'])
                return {k: conv(af, x) for k, x in v.items()}
            if isinstance(v, list):
                return [conv(af, x) for x in v]
            if isinstance(v, (bytes, bytearray)):
                return v.hex()
            return v

        for obj in env.objects:
            if obj.type.name == 'TextAsset':
                strings = localization_table(obj)
                if strings:
                    key = f'{os.path.basename(obj.assets_file.name)}:{obj.path_id}'
                    out['Localization'][key] = {'_class': 'Localization', '_name': 'en', 'strings': strings}
                continue
            if obj.type.name != 'MonoBehaviour':
                continue
            af = obj.assets_file
            raw = obj.get_raw_data()
            go_fid, go_pid = int.from_bytes(raw[0:4], 'little', signed=True), int.from_bytes(raw[4:12], 'little', signed=True)
            sc_fid, sc_pid = int.from_bytes(raw[16:20], 'little', signed=True), int.from_bytes(raw[20:28], 'little', signed=True)
            cls = scripts.get(sc_pid) if resolve(af, sc_fid, sc_pid).startswith('globalgamemanagers.assets:') else None
            if not cls or not wanted(cls):
                continue
            try:
                tt = obj.read_typetree()
            except Exception as e:  # noqa: BLE001 - keep going, report at the end
                errors[f'{cls}: {type(e).__name__}'] += 1
                continue
            nlen = int.from_bytes(raw[28:32], 'little')
            rec = {'_class': cls, '_name': raw[32:32 + nlen].decode('utf-8', 'replace')}
            if go_pid:
                rec['_go_ref'] = resolve(af, go_fid, go_pid)
            for k, v in tt.items():
                if k not in ('m_GameObject', 'm_Enabled', 'm_Script', 'm_Name'):
                    rec[k] = conv(af, v)
            out[cls][f'{os.path.basename(af.name)}:{obj.path_id}'] = rec
        os.makedirs(a.out, exist_ok=True)
        total = 0
        for cls, objs in out.items():
            with open(os.path.join(a.out, f'{cls}.json'), 'w', encoding='utf-8') as f:
                json.dump(objs, f, indent=1, sort_keys=True)
            total += len(objs)
        print(f'wrote {total} objects in {len(out)} classes to {a.out}/ in {time.time() - t0:.0f}s')
        if errors:
            print('objects that could not be parsed:', dict(errors))
        for need in ('Act', 'Chapter', 'SGMatch', 'Fight', 'VariantCharacterData', 'GameSettings', 'EconomySettings'):
            if need not in out:
                print(f'WARNING: no {need} objects found - is this the right client build?')
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    main()
