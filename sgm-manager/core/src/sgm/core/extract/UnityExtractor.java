package sgm.core.extract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import sgm.core.Json;
import sgm.core.Log;
import sgm.core.Protocol;

/**
 * Reads the game's design data straight out of its APK: Unity SerializedFiles under
 * assets/bin/Data, MonoBehaviours parsed with the field layouts from typetrees.json (the build
 * strips typetrees), plus the English string table. Produces the same records as
 * sgm-server/tools/extract_content.py.
 */
public final class UnityExtractor {
    public interface Progress {
        void update(String message);
    }

    private static final int CLASS_TEXT_ASSET = 49;
    private static final int CLASS_MONO_BEHAVIOUR = 114;
    private static final int CLASS_MONO_SCRIPT = 115;
    private static final int IN_MEMORY_LIMIT = 24 * 1024 * 1024;
    private static final Pattern SPLIT = Pattern.compile("(.*)\\.split(\\d+)$");

    /** Field layout of one script class (UnityPy TypeTreeNode order). */
    static final class Layout {
        final int[] level;
        final String[] type;
        final String[] name;
        final int[] flags;
        final int[] end;

        Layout(List<Object> nodes) {
            int n = nodes.size();
            level = new int[n];
            type = new String[n];
            name = new String[n];
            flags = new int[n];
            end = new int[n];
            for (int i = 0; i < n; i++) {
                List<Object> x = Json.list(nodes.get(i));
                level[i] = ((Number) x.get(0)).intValue();
                type[i] = (String) x.get(1);
                name[i] = (String) x.get(2);
                flags[i] = ((Number) x.get(3)).intValue();
            }
            for (int i = 0; i < n; i++) {
                int j = i + 1;
                while (j < n && level[j] > level[i]) {
                    j++;
                }
                end[i] = j;
            }
        }
    }

    private final Map<String, Layout> layouts = new HashMap<String, Layout>();
    private final File tmpDir;
    private final Progress progress;
    /** class -> key -> record */
    public final Map<String, Map<String, Object>> out = new TreeMap<String, Map<String, Object>>();
    public final Map<String, Integer> errors = new TreeMap<String, Integer>();

    public UnityExtractor(Map<String, Object> typetrees, File tmpDir, Progress progress) {
        for (Map.Entry<String, Object> e : Json.getMap(typetrees, "classes").entrySet()) {
            layouts.put(e.getKey(), new Layout(Json.list(e.getValue())));
        }
        this.tmpDir = tmpDir;
        this.progress = progress;
    }

    // ---- input: the Data directory spread over one or more APKs ----

    private static final class Part {
        final ZipFile zip;
        final ZipEntry entry;

        Part(ZipFile zip, ZipEntry entry) {
            this.zip = zip;
            this.entry = entry;
        }
    }

    public void run(List<File> apks) throws IOException {
        Map<String, TreeMap<Integer, Part>> files = new TreeMap<String, TreeMap<Integer, Part>>();
        List<ZipFile> zips = new ArrayList<ZipFile>();
        try {
            for (File apk : apks) {
                ZipFile z = new ZipFile(apk);
                zips.add(z);
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    int at = n.indexOf("/bin/Data/");
                    if (e.isDirectory() || at < 0 || n.contains("/Managed/")) {
                        continue;
                    }
                    String rel = n.substring(at + "/bin/Data/".length());
                    Matcher m = SPLIT.matcher(rel);
                    String logical = m.matches() ? m.group(1) : rel;
                    int idx = m.matches() ? Integer.parseInt(m.group(2)) : 0;
                    TreeMap<Integer, Part> parts = files.get(logical);
                    if (parts == null) {
                        parts = new TreeMap<Integer, Part>();
                        files.put(logical, parts);
                    }
                    parts.put(idx, new Part(z, e));
                }
            }
            if (!files.containsKey("globalgamemanagers.assets")) {
                throw new IOException("this does not look like the Skullgirls APK (no globalgamemanagers.assets)");
            }
            Map<Long, String> scripts = monoScripts(load("globalgamemanagers.assets", files.get("globalgamemanagers.assets")));
            int done = 0;
            for (Map.Entry<String, TreeMap<Integer, Part>> f : files.entrySet()) {
                if (++done % 200 == 0 && progress != null) {
                    progress.update("Reading game data: " + done + " / " + files.size() + " files");
                }
                String base = f.getKey().substring(f.getKey().lastIndexOf('/') + 1);
                Loaded l = load(base, f.getValue());
                try {
                    SerializedFile sf = SerializedFile.parse(base, l.buf);
                    if (sf != null) {
                        extract(sf, scripts);
                    }
                } catch (RuntimeException e) {
                    count("unreadable file: " + e.getClass().getSimpleName());
                } finally {
                    l.close();
                }
            }
        } finally {
            for (ZipFile z : zips) {
                z.close();
            }
        }
    }

    /** A logical file's bytes: in memory, or memory-mapped from a temp file when large. */
    private static final class Loaded {
        final ByteBuffer buf;
        final File tmp;

        Loaded(ByteBuffer buf, File tmp) {
            this.buf = buf;
            this.tmp = tmp;
        }

        void close() {
            if (tmp != null && !tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }

    private Loaded load(String name, TreeMap<Integer, Part> parts) throws IOException {
        long size = 0;
        for (Part p : parts.values()) {
            size += p.entry.getSize();
        }
        if (size >= 0 && size <= IN_MEMORY_LIMIT) {
            byte[] b = new byte[(int) size];
            int off = 0;
            for (Part p : parts.values()) {
                InputStream in = p.zip.getInputStream(p.entry);
                try {
                    int r;
                    while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) {
                        off += r;
                    }
                } finally {
                    in.close();
                }
            }
            return new Loaded(ByteBuffer.wrap(b, 0, off).slice(), null);
        }
        File tmp = File.createTempFile("sgm-" + name.replaceAll("[^A-Za-z0-9.]", "_"), ".tmp", tmpDir);
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[65536];
            for (Part p : parts.values()) {
                InputStream in = p.zip.getInputStream(p.entry);
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                    }
                } finally {
                    in.close();
                }
            }
        } finally {
            out.close();
        }
        RandomAccessFile raf = new RandomAccessFile(tmp, "r");
        try {
            ByteBuffer mapped = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length());
            return new Loaded(mapped, tmp);
        } finally {
            raf.close();
        }
    }

    // ---- MonoScript table ----

    private Map<Long, String> monoScripts(Loaded l) {
        try {
            SerializedFile sf = SerializedFile.parse("globalgamemanagers.assets", l.buf);
            Map<Long, String> scripts = new HashMap<Long, String>();
            for (SerializedFile.Obj o : sf.objects) {
                if (o.classId != CLASS_MONO_SCRIPT) {
                    continue;
                }
                Reader r = sf.reader(o);
                r.string(); // m_Name
                r.i32(); // m_ExecutionOrder
                r.skip(16); // m_PropertiesHash
                scripts.put(o.pathId, r.string()); // m_ClassName
            }
            return scripts;
        } finally {
            l.close();
        }
    }

    // ---- objects ----

    private void count(String what) {
        Integer n = errors.get(what);
        errors.put(what, n == null ? 1 : n + 1);
    }

    private Map<String, Object> bucket(String cls) {
        Map<String, Object> m = out.get(cls);
        if (m == null) {
            m = new LinkedHashMap<String, Object>();
            out.put(cls, m);
        }
        return m;
    }

    private void extract(SerializedFile sf, Map<Long, String> scripts) {
        for (SerializedFile.Obj o : sf.objects) {
            if (o.classId == CLASS_TEXT_ASSET) {
                localization(sf, o);
                continue;
            }
            if (o.classId != CLASS_MONO_BEHAVIOUR || o.size < 32) {
                continue;
            }
            Reader r = sf.reader(o);
            int goFid = r.i32();
            long goPid = r.i64();
            r.skip(4); // m_Enabled + alignment
            int scFid = r.i32();
            long scPid = r.i64();
            String scFile = sf.resolveFile(scFid);
            String cls = "globalgamemanagers.assets".equals(scFile) ? scripts.get(scPid) : null;
            Layout lay = cls == null ? null : layouts.get(cls);
            if (lay == null) {
                continue;
            }
            Map<String, Object> rec = new LinkedHashMap<String, Object>();
            rec.put("_class", cls);
            r = sf.reader(o);
            Object tree;
            try {
                tree = read(lay, 0, r, sf);
            } catch (RuntimeException e) {
                count(cls + ": " + e.getClass().getSimpleName());
                continue;
            }
            Map<String, Object> fields = Json.map(tree);
            rec.put("_name", fields.get("m_Name"));
            if (goPid != 0) {
                rec.put("_go_ref", sf.ref(goFid, goPid));
            }
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                String k = e.getKey();
                if (!k.equals("m_GameObject") && !k.equals("m_Enabled") && !k.equals("m_Script") && !k.equals("m_Name")) {
                    rec.put(k, e.getValue());
                }
            }
            bucket(cls).put(sf.name + ":" + o.pathId, rec);
        }
    }

    private void localization(SerializedFile sf, SerializedFile.Obj o) {
        Reader r = sf.reader(o);
        if (!"en".equals(r.string())) {
            return;
        }
        byte[] script = r.bytes();
        String text = new String(script, Protocol.UTF8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        Object table;
        try {
            table = Json.parse(text.trim());
        } catch (RuntimeException e) {
            return;
        }
        Map<String, Object> m = Json.map(table);
        if (m == null || !m.containsKey("Char_Annie_Name")) {
            return;
        }
        bucket("Localization").put(sf.name + ":" + o.pathId, Json.obj("_class", "Localization", "_name", "en", "strings", m));
    }

    /** UnityPy TypeTreeHelper.read_value, same quirks included. */
    private Object read(Layout l, int i, Reader r, SerializedFile sf) {
        String t = l.type[i];
        boolean align = (l.flags[i] & 0x4000) != 0;
        Object v;
        if (t.equals("SInt8")) {
            v = (long) r.i8();
        } else if (t.equals("UInt8") || t.equals("char")) {
            v = (long) r.u8();
        } else if (t.equals("SInt16") || t.equals("short")) {
            v = (long) r.i16();
        } else if (t.equals("UInt16") || t.equals("unsigned short")) {
            v = (long) r.u16();
        } else if (t.equals("int") || t.equals("SInt32")) {
            v = (long) r.i32();
        } else if (t.equals("UInt32") || t.equals("unsigned int") || t.equals("Type*")) {
            v = r.i32() & 0xffffffffL;
        } else if (t.equals("SInt64") || t.equals("long long") || t.equals("UInt64") || t.equals("unsigned long long") || t.equals("FileSize")) {
            v = r.i64();
        } else if (t.equals("float")) {
            float f = r.f32();
            v = Float.isNaN(f) || Float.isInfinite(f) ? null : (Object) (double) f;
        } else if (t.equals("double")) {
            double d = r.f64();
            v = Double.isNaN(d) || Double.isInfinite(d) ? null : (Object) d;
        } else if (t.equals("bool")) {
            v = r.u8() != 0;
        } else if (t.equals("string")) {
            v = new String(r.bytes(), Protocol.UTF8);
        } else if (t.equals("TypelessData")) {
            v = hex(r.raw(r.i32()));
        } else if (i + 1 < l.type.length && l.type[i + 1].equals("Array") && l.level[i + 1] == l.level[i] + 1) {
            if ((l.flags[i + 1] & 0x4000) != 0) {
                align = true;
            }
            int size = r.i32();
            if (size < 0 || size > 10000000) {
                throw new IllegalStateException("bad array size");
            }
            List<Object> list = new ArrayList<Object>(Math.min(size, 4096));
            for (int k = 0; k < size; k++) {
                list.add(read(l, i + 3, r, sf));
            }
            v = list;
        } else {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            int j = i + 1;
            while (j < l.end[i]) {
                m.put(l.name[j], read(l, j, r, sf));
                j = l.end[j];
            }
            if (m.size() == 2 && m.containsKey("m_FileID") && m.containsKey("m_PathID")) {
                v = sf.ref(((Number) m.get("m_FileID")).intValue(), ((Number) m.get("m_PathID")).longValue());
            } else {
                v = m;
            }
        }
        if (align) {
            r.align();
        }
        return v;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    /** Writes &lt;Class&gt;.json files like the Python extractor. */
    public void write(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        for (Map.Entry<String, Map<String, Object>> e : out.entrySet()) {
            OutputStream o = new FileOutputStream(new File(dir, e.getKey() + ".json"));
            try {
                o.write(Json.write(e.getValue()).getBytes(Protocol.UTF8));
            } finally {
                o.close();
            }
        }
    }

    public int objectCount() {
        int n = 0;
        for (Map<String, Object> m : out.values()) {
            n += m.size();
        }
        return n;
    }

    // ---- SerializedFile (format 22, as written by Unity 2022.3) ----

    static final class SerializedFile {
        static final class Obj {
            long pathId;
            long start;
            int size;
            int classId;
        }

        final String name;
        final ByteBuffer buf;
        long dataOffset;
        final List<Obj> objects = new ArrayList<Obj>();
        final List<String> externals = new ArrayList<String>();

        private SerializedFile(String name, ByteBuffer buf) {
            this.name = name;
            this.buf = buf;
        }

        static SerializedFile parse(String name, ByteBuffer src) {
            ByteBuffer b = src.duplicate().order(ByteOrder.BIG_ENDIAN);
            if (b.limit() < 48) {
                return null;
            }
            long metaSize = b.getInt(0) & 0xffffffffL;
            long fileSize = b.getInt(4) & 0xffffffffL;
            int version = b.getInt(8);
            long dataOffset = b.getInt(12) & 0xffffffffL;
            if (version < 9 || version > 50) {
                return null; // not a SerializedFile (resource blobs, audio...)
            }
            int pos = 16;
            boolean big = b.get(pos) != 0;
            pos += 4;
            if (version >= 22) {
                metaSize = b.getInt(pos) & 0xffffffffL;
                fileSize = b.getLong(pos + 4);
                dataOffset = b.getLong(pos + 12);
                pos += 28;
            }
            if (fileSize != b.limit() || dataOffset > fileSize || metaSize > fileSize) {
                return null;
            }
            SerializedFile sf = new SerializedFile(name, src.duplicate().order(big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN));
            sf.dataOffset = dataOffset;
            Reader r = new Reader(sf.buf, pos);
            r.cstring(); // unity version
            r.i32(); // target platform
            boolean typeTrees = r.u8() != 0;
            int typeCount = r.i32();
            int[] classIds = new int[typeCount];
            for (int t = 0; t < typeCount; t++) {
                int classId = r.i32();
                classIds[t] = classId;
                if (version >= 16) {
                    r.u8(); // isStrippedType
                }
                if (version >= 17) {
                    r.i16(); // scriptTypeIndex
                }
                if (version >= 13) {
                    if ((version < 16 && classId < 0) || (version >= 16 && classId == CLASS_MONO_BEHAVIOUR)) {
                        r.skip(16); // script id
                    }
                    r.skip(16); // old type hash
                }
                if (typeTrees) {
                    // embedded typetree blob: node count, string buffer size, nodes, strings
                    int nodes = r.i32();
                    int strings = r.i32();
                    r.skip(nodes * (version >= 19 ? 32 : 24) + strings);
                    if (version >= 21) {
                        r.skip(4 * r.i32()); // type dependencies
                    }
                }
            }
            int objCount = r.i32();
            for (int k = 0; k < objCount; k++) {
                if (version >= 14) {
                    r.align();
                }
                Obj o = new Obj();
                o.pathId = version >= 14 ? r.i64() : r.i32();
                o.start = version >= 22 ? r.i64() : (r.i32() & 0xffffffffL);
                o.size = r.i32();
                int typeId = r.i32();
                o.classId = typeId >= 0 && typeId < typeCount ? classIds[typeId] : -1;
                sf.objects.add(o);
            }
            if (version >= 11) {
                int scriptCount = r.i32();
                for (int k = 0; k < scriptCount; k++) {
                    r.i32();
                    if (version >= 14) {
                        r.align();
                        r.i64();
                    } else {
                        r.i32();
                    }
                }
            }
            int extCount = r.i32();
            for (int k = 0; k < extCount; k++) {
                if (version >= 6) {
                    r.cstring();
                }
                if (version >= 5) {
                    r.skip(16);
                    r.i32();
                }
                String path = r.cstring();
                sf.externals.add(path.substring(path.lastIndexOf('/') + 1));
            }
            return sf;
        }

        Reader reader(Obj o) {
            return new Reader(buf, (int) (dataOffset + o.start));
        }

        String resolveFile(int fileId) {
            if (fileId == 0) {
                return name;
            }
            return fileId - 1 < externals.size() ? externals.get(fileId - 1) : null;
        }

        /** "file:pathID" reference, '' for null, like the Python extractor. */
        String ref(int fileId, long pathId) {
            if (pathId == 0) {
                return "";
            }
            String f = resolveFile(fileId);
            return f == null ? "?" + fileId + ":" + pathId : f + ":" + pathId;
        }
    }

    /** Positioned little/big-endian reader with Unity's 4-byte alignment rule. */
    static final class Reader {
        final ByteBuffer b;
        final int base;
        int pos;

        Reader(ByteBuffer b, int pos) {
            this.b = b;
            this.base = pos;
            this.pos = pos;
        }

        int u8() {
            return b.get(pos++) & 0xff;
        }

        int i8() {
            return b.get(pos++);
        }

        int i16() {
            short v = b.getShort(pos);
            pos += 2;
            return v;
        }

        int u16() {
            return i16() & 0xffff;
        }

        int i32() {
            int v = b.getInt(pos);
            pos += 4;
            return v;
        }

        long i64() {
            long v = b.getLong(pos);
            pos += 8;
            return v;
        }

        float f32() {
            float v = b.getFloat(pos);
            pos += 4;
            return v;
        }

        double f64() {
            double v = b.getDouble(pos);
            pos += 8;
            return v;
        }

        void skip(int n) {
            pos += n;
        }

        /** Unity aligns relative to the start of the object (objects start 8-aligned in practice). */
        void align() {
            pos = base + ((pos - base + 3) & ~3);
        }

        byte[] raw(int n) {
            if (n < 0 || pos + n > b.limit()) {
                throw new IllegalStateException("read past end");
            }
            byte[] out = new byte[n];
            ByteBuffer d = b.duplicate();
            d.position(pos);
            d.get(out);
            pos += n;
            return out;
        }

        /** int32 length, bytes, align. */
        byte[] bytes() {
            byte[] out = raw(i32());
            align();
            return out;
        }

        String string() {
            return new String(bytes(), Protocol.UTF8);
        }

        String cstring() {
            int start = pos;
            while (b.get(pos) != 0) {
                pos++;
            }
            byte[] out = new byte[pos - start];
            ByteBuffer d = b.duplicate();
            d.position(start);
            d.get(out);
            pos++;
            return new String(out, Protocol.UTF8);
        }
    }

    /** Convenience for tools: extract APKs into a content directory. */
    public static int extractTo(List<File> apks, Map<String, Object> typetrees, File outDir, File tmpDir, Progress p) throws IOException {
        UnityExtractor x = new UnityExtractor(typetrees, tmpDir, p);
        x.run(apks);
        if (!x.out.containsKey("GameSettings")) {
            throw new IOException("no GameSettings found: is this the right game version?");
        }
        x.write(outDir);
        if (!x.errors.isEmpty()) {
            Log.w("extract: objects that could not be read: " + x.errors);
        }
        return x.objectCount();
    }
}
