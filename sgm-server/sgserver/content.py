"""Read-only game content extracted from the client (see tools/extract_content.py).

Every object is a dict of its serialized Unity fields plus:
  _class  script class name      _name  Unity object name
  _key    "file:pathID"          (references to other objects use the same "file:pathID" form)
"""
import json
import os
from collections import defaultdict


class Content:
    def __init__(self, directory):
        self.dir = directory
        self.objs = {}
        self.by_class = defaultdict(dict)
        self.by_guid = {}
        self.by_name = defaultdict(dict)
        if not os.path.isdir(directory):
            raise SystemExit(f'content directory not found: {directory} (run tools/extract_content.py first)')
        for fn in sorted(os.listdir(directory)):
            if not fn.endswith('.json'):
                continue
            with open(os.path.join(directory, fn), encoding='utf-8') as f:
                objs = json.load(f)
            for key, rec in objs.items():
                rec['_key'] = key
                self.objs[key] = rec
                cls = rec['_class']
                self.by_class[cls][key] = rec
                if rec.get('_name'):
                    self.by_name[cls].setdefault(rec['_name'], rec)
                g = rec.get('guid')
                if isinstance(g, str) and g:
                    self.by_guid.setdefault(g, rec)
        self._index_campaign()

    # ---- generic helpers -------------------------------------------------
    def get(self, ref):
        """Resolve a "file:pathID" reference (or return None for empty refs)."""
        if not ref:
            return None
        return self.objs.get(ref)

    def guid(self, guid):
        return self.by_guid.get(guid)

    def all(self, cls):
        return self.by_class.get(cls, {}).values()

    def single(self, cls):
        objs = self.by_class.get(cls)
        if not objs:
            raise KeyError(f'no {cls} in content')
        return next(iter(objs.values()))

    @property
    def game_settings(self):
        return self.single('GameSettings')

    @property
    def economy(self):
        return self.single('EconomySettings')

    # ---- campaign graph ----------------------------------------------------
    def _index_campaign(self):
        """Act -> chapters -> matches -> fights, plus reverse lookups."""
        self.act_chapters = {}
        self.chapter_act = {}
        self.match_chapter = {}
        for act in self.all('Act'):
            chapters = []
            for ref in act.get('chapters') or []:
                ch = self.get(ref)
                if ch:
                    chapters.append(ch)
            for res in act.get('chapterResources') or []:
                ch = self.by_name['Chapter'].get(res.get('resourcePath', '').split('/')[-1])
                if ch and ch not in chapters:
                    chapters.append(ch)
            self.act_chapters[act['guid']] = chapters
            for ch in chapters:
                self.chapter_act.setdefault(ch['guid'], act)
                for mref in ch.get('matches') or []:
                    m = self.get(mref)
                    if m:
                        self.match_chapter.setdefault(m['guid'], ch)

    def chapters_of(self, act):
        return self.act_chapters.get(act['guid'], [])

    def matches_of(self, chapter):
        return [m for m in (self.get(r) for r in chapter.get('matches') or []) if m]

    def fights_of(self, match):
        return [f for f in (self.get(r) for r in match.get('fights') or []) if f]
