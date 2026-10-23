package sgm.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File persistence: one JSON document per player (users/&lt;id&gt;.json, same shape as the Python
 * server's save) plus index.json with device bindings, sessions and password accounts.
 * Every load returns a fresh copy, so a handler's unsaved changes are discarded.
 */
public final class Store {
    private static final int MAX_SESSIONS = 256;

    private final File usersDir;
    private final File indexFile;
    private final Map<String, String> cache = new LinkedHashMap<String, String>();
    private final Map<String, Object> index;
    private final SecureRandom rnd = new SecureRandom();

    public Store(File dir) throws IOException {
        usersDir = new File(dir, "users");
        indexFile = new File(dir, "index.json");
        if (!usersDir.isDirectory() && !usersDir.mkdirs()) {
            throw new IOException("cannot create " + usersDir);
        }
        Map<String, Object> idx = indexFile.isFile() ? Json.map(Json.parse(Content.read(new FileInputStream(indexFile)))) : null;
        index = idx != null ? idx : new LinkedHashMap<String, Object>();
        for (String k : new String[]{"devices", "sessions", "guestTokens", "accounts"}) {
            Json.child(index, k);
        }
    }

    // ---- users ------------------------------------------------------------------------------

    public synchronized Map<String, Object> loadUser(String id) {
        if (id == null || !validId(id)) {
            return null;
        }
        String text = cache.get(id);
        if (text == null) {
            File f = userFile(id);
            if (!f.isFile()) {
                return null;
            }
            try {
                text = Content.read(new FileInputStream(f));
            } catch (IOException e) {
                Log.e("cannot read " + f, e);
                return null;
            }
            cache.put(id, text);
        }
        return Json.map(Json.parse(text));
    }

    public synchronized void saveUser(Map<String, Object> user) {
        String id = Json.str(user, "id");
        if (!validId(id)) {
            throw new IllegalArgumentException("bad user id");
        }
        String text = Json.write(user);
        writeAtomic(userFile(id), text);
        cache.put(id, text);
    }

    public synchronized boolean deleteUser(String id) {
        if (!validId(id)) {
            return false;
        }
        cache.remove(id);
        for (String k : new String[]{"devices", "sessions", "guestTokens"}) {
            removeValues(Json.getMap(index, k), id);
        }
        Map<String, Object> accts = Json.getMap(index, "accounts");
        for (Iterator<Map.Entry<String, Object>> it = accts.entrySet().iterator(); it.hasNext(); ) {
            if (id.equals(Json.str(Json.map(it.next().getValue()), "userId"))) {
                it.remove();
            }
        }
        saveIndex();
        return userFile(id).delete();
    }

    public synchronized List<String> allUserIds() {
        List<String> out = new ArrayList<String>();
        String[] names = usersDir.list();
        if (names != null) {
            for (String n : names) {
                if (n.endsWith(".json")) {
                    out.add(n.substring(0, n.length() - 5));
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    // ---- devices, sessions, accounts -----------------------------------------------------------

    public synchronized String userForDevice(String deviceId) {
        return (String) Json.getMap(index, "devices").get(deviceId);
    }

    public synchronized void bindDevice(String deviceId, String userId) {
        Json.getMap(index, "devices").put(deviceId, userId);
        saveIndex();
    }

    /** Returns {password, userId} or null. */
    public synchronized Map<String, Object> account(String email) {
        return Json.getMap(Json.getMap(index, "accounts"), email.toLowerCase(java.util.Locale.ROOT));
    }

    public synchronized void setAccount(String email, String passwordHash, String userId) {
        Json.getMap(index, "accounts").put(email.toLowerCase(java.util.Locale.ROOT), Json.obj("password", passwordHash, "userId", userId));
        saveIndex();
    }

    public synchronized String newSession(String userId) {
        byte[] b = new byte[24];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        String token = sb.toString();
        Map<String, Object> s = Json.getMap(index, "sessions");
        s.put(token, userId);
        Iterator<String> it = s.keySet().iterator();
        while (s.size() > MAX_SESSIONS && it.hasNext()) {
            it.next();
            it.remove();
        }
        saveIndex();
        return token;
    }

    public synchronized String sessionUser(String token) {
        return (String) Json.getMap(index, "sessions").get(token);
    }

    public synchronized void setGuestToken(String token, String userId) {
        Map<String, Object> g = Json.getMap(index, "guestTokens");
        if (!userId.equals(g.get(token))) {
            g.put(token, userId);
            saveIndex();
        }
    }

    public synchronized String guestTokenUser(String token) {
        return (String) Json.getMap(index, "guestTokens").get(token);
    }

    /** device id -> user id and guest token -> user id bindings (copies). */
    public synchronized Map<String, Object> bindings() {
        return Json.obj("devices", Json.copy(Json.getMap(index, "devices")), "guestTokens", Json.copy(Json.getMap(index, "guestTokens")));
    }

    /** Points every known device and guest token at this user: the game logs into it next time. */
    public synchronized void makeActive(String userId) {
        for (String k : new String[]{"devices", "guestTokens"}) {
            Map<String, Object> m = Json.getMap(index, k);
            for (Map.Entry<String, Object> e : m.entrySet()) {
                e.setValue(userId);
            }
        }
        Json.getMap(index, "sessions").clear();
        saveIndex();
    }

    // ---- files ------------------------------------------------------------------------------

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    private File userFile(String id) {
        return new File(usersDir, id + ".json");
    }

    private static void removeValues(Map<String, Object> m, String value) {
        for (Iterator<Map.Entry<String, Object>> it = m.entrySet().iterator(); it.hasNext(); ) {
            if (value.equals(it.next().getValue())) {
                it.remove();
            }
        }
    }

    private void saveIndex() {
        writeAtomic(indexFile, Json.write(index));
    }

    static void writeAtomic(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(text.getBytes(Protocol.UTF8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                throw new IOException("rename failed for " + f);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + f, e);
        }
    }
}
