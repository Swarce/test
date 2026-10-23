package sgm.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Read-only game content extracted from the client: {"file:pathID": {_class, _name, ...fields}}.
 * References between objects use the same "file:pathID" strings.
 */
public final class Content {
    public final Map<String, Map<String, Object>> objs = new HashMap<String, Map<String, Object>>();
    public final Map<String, Map<String, Map<String, Object>>> byClass = new HashMap<String, Map<String, Map<String, Object>>>();
    public final Map<String, Map<String, Object>> byGuid = new HashMap<String, Map<String, Object>>();
    public final Map<String, Map<String, Map<String, Object>>> byName = new HashMap<String, Map<String, Map<String, Object>>>();

    public final Map<String, List<Map<String, Object>>> actChapters = new HashMap<String, List<Map<String, Object>>>();
    public final Map<String, Map<String, Object>> chapterAct = new HashMap<String, Map<String, Object>>();
    public final Map<String, Map<String, Object>> matchChapter = new HashMap<String, Map<String, Object>>();

    /** Loads a directory of <Class>.json files, or a zip containing them. */
    public static Content load(File path) throws IOException {
        Content c = new Content();
        if (path.isDirectory()) {
            File[] files = path.listFiles();
            if (files == null) {
                throw new IOException("cannot list " + path);
            }
            Arrays.sort(files);
            for (File f : files) {
                if (f.getName().endsWith(".json")) {
                    c.addFile(read(new FileInputStream(f)));
                }
            }
        } else {
            ZipInputStream zin = new ZipInputStream(new FileInputStream(path));
            try {
                ZipEntry e;
                List<String> names = new ArrayList<String>();
                Map<String, String> data = new HashMap<String, String>();
                while ((e = zin.getNextEntry()) != null) {
                    if (!e.isDirectory() && e.getName().endsWith(".json")) {
                        names.add(e.getName());
                        data.put(e.getName(), readNoClose(zin));
                    }
                }
                Collections.sort(names);
                for (String n : names) {
                    c.addFile(data.get(n));
                }
            } finally {
                zin.close();
            }
        }
        if (c.objs.isEmpty()) {
            throw new IOException("no content found in " + path);
        }
        c.indexCampaign();
        return c;
    }

    public static String read(InputStream in) throws IOException {
        try {
            return readNoClose(in);
        } finally {
            in.close();
        }
    }

    static String readNoClose(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), Protocol.UTF8);
    }

    private void addFile(String text) {
        Map<String, Object> m = Json.map(Json.parse(text));
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Map<String, Object> rec = Json.map(e.getValue());
            String key = e.getKey();
            rec.put("_key", key);
            objs.put(key, rec);
            String cls = Json.str(rec, "_class");
            sub(byClass, cls).put(key, rec);
            String name = Json.str(rec, "_name");
            if (!name.isEmpty() && !sub(byName, cls).containsKey(name)) {
                sub(byName, cls).put(name, rec);
            }
            Object g = rec.get("guid");
            if (g instanceof String && !((String) g).isEmpty() && !byGuid.containsKey(g)) {
                byGuid.put((String) g, rec);
            }
        }
    }

    private static Map<String, Map<String, Object>> sub(Map<String, Map<String, Map<String, Object>>> m, String k) {
        Map<String, Map<String, Object>> s = m.get(k);
        if (s == null) {
            s = new LinkedHashMap<String, Map<String, Object>>();
            m.put(k, s);
        }
        return s;
    }

    // ---- lookups --------------------------------------------------------------------------

    public Map<String, Object> get(Object ref) {
        if (!(ref instanceof String) || ((String) ref).isEmpty()) {
            return null;
        }
        return objs.get(ref);
    }

    public Map<String, Object> guid(String g) {
        return g == null ? null : byGuid.get(g);
    }

    /** Object by guid, only if it has the given class. */
    public Map<String, Object> guid(String g, String cls) {
        Map<String, Object> o = guid(g);
        return o != null && cls.equals(o.get("_class")) ? o : null;
    }

    public Collection<Map<String, Object>> all(String cls) {
        Map<String, Map<String, Object>> m = byClass.get(cls);
        return m == null ? Collections.<Map<String, Object>>emptyList() : m.values();
    }

    public Map<String, Object> single(String cls) {
        for (Map<String, Object> o : all(cls)) {
            return o;
        }
        throw new IllegalStateException("no " + cls + " in content");
    }

    public Map<String, Object> gameSettings() {
        return single("GameSettings");
    }

    public Map<String, Object> economy() {
        return single("EconomySettings");
    }

    public List<Map<String, Object>> refs(Map<String, Object> o, String field) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Object r : Json.getList(o, field)) {
            Map<String, Object> x = get(r);
            if (x != null) {
                out.add(x);
            }
        }
        return out;
    }

    // ---- campaign graph -------------------------------------------------------------------

    private void indexCampaign() {
        for (Map<String, Object> act : all("Act")) {
            List<Map<String, Object>> chapters = refs(act, "chapters");
            Map<String, Map<String, Object>> chByName = byName.get("Chapter");
            for (Object r : Json.getList(act, "chapterResources")) {
                String path = Json.str(Json.map(r), "resourcePath");
                String nm = path.substring(path.lastIndexOf('/') + 1);
                Map<String, Object> ch = chByName == null ? null : chByName.get(nm);
                if (ch != null && !chapters.contains(ch)) {
                    chapters.add(ch);
                }
            }
            actChapters.put(Json.str(act, "guid"), chapters);
            for (Map<String, Object> ch : chapters) {
                if (!chapterAct.containsKey(Json.str(ch, "guid"))) {
                    chapterAct.put(Json.str(ch, "guid"), act);
                }
                for (Map<String, Object> m : refs(ch, "matches")) {
                    if (!matchChapter.containsKey(Json.str(m, "guid"))) {
                        matchChapter.put(Json.str(m, "guid"), ch);
                    }
                }
            }
        }
    }

    public List<Map<String, Object>> chaptersOf(Map<String, Object> act) {
        List<Map<String, Object>> l = actChapters.get(Json.str(act, "guid"));
        return l == null ? new ArrayList<Map<String, Object>>() : l;
    }

    public List<Map<String, Object>> matchesOf(Map<String, Object> chapter) {
        return refs(chapter, "matches");
    }

    public List<Map<String, Object>> fightsOf(Map<String, Object> match) {
        return refs(match, "fights");
    }
}
