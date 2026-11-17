package com.jldigital.retrogaming;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Add games on the device itself, with no PC: finds game files in Download, JLRetro/Add games and on USB drives or SD
 * cards, works out each one's system from the file (the game file inside a .zip, never the folder it came from, except
 * arcade sets, which only an "Arcade" folder can name), moves or copies it into JLRetro/roms, adds it to library.json and
 * fetches its box art and screenshots from the libretro thumbnails library (only the system and game name are sent).
 * Programs and other files are never opened.
 */
final class GameInbox {
    private static final Map<String, String> EXT = new HashMap<>();
    private static final Map<String, String> CORE = new HashMap<>();
    private static final Map<String, String[]> SYSTEM = new LinkedHashMap<>(); // id -> name, full name, libretro thumbnails folder
    private static final String[] IGNORED_INSIDE = {".txt", ".nfo", ".diz", ".url", ".jpg", ".png", ".htm", ".html"};
    private static final String[] PARTIAL = {".part", ".crdownload", ".tmp", ".download", ".partial"};
    private static final Pattern ARCADE_FOLDER = Pattern.compile("(?i).*(arcade|mame|fbneo|fba|neo ?geo).*");
    private static final String THUMBS = "https://thumbnails.libretro.com/";

    static {
        String[][] ext = {{".nes", "nes"}, {".fds", "nes"}, {".sfc", "snes"}, {".smc", "snes"}, {".z64", "n64"}, {".n64", "n64"},
                {".v64", "n64"}, {".gb", "gb"}, {".gbc", "gbc"}, {".md", "genesis"}, {".gen", "genesis"}, {".smd", "genesis"},
                {".gg", "gamegear"}, {".32x", "32x"}, {".chd", "psx"}, {".pbp", "psx"}, {".cue", "psx"}};
        for (String[] e : ext) EXT.put(e[0], e[1]);
        String[][] core = {{"nes", "fceumm"}, {"snes", "snes9x"}, {"n64", "mupen64plus_next_gles3"}, {"gb", "gambatte"},
                {"gbc", "gambatte"}, {"genesis", "genesis_plus_gx"}, {"gamegear", "genesis_plus_gx"}, {"32x", "picodrive"},
                {"psx", "pcsx_rearmed"}, {"arcade", "fbneo"}};
        for (String[] c : core) CORE.put(c[0], c[1]);
        SYSTEM.put("nes", new String[]{"NES", "Nintendo Entertainment System", "Nintendo - Nintendo Entertainment System"});
        SYSTEM.put("snes", new String[]{"Super Nintendo", "Super Nintendo Entertainment System", "Nintendo - Super Nintendo Entertainment System"});
        SYSTEM.put("n64", new String[]{"Nintendo 64", "Nintendo 64", "Nintendo - Nintendo 64"});
        SYSTEM.put("arcade", new String[]{"Arcade", "Arcade (MAME)", null});
        SYSTEM.put("gb", new String[]{"Game Boy", "Nintendo Game Boy", "Nintendo - Game Boy"});
        SYSTEM.put("gbc", new String[]{"Game Boy Color", "Nintendo Game Boy Color", "Nintendo - Game Boy Color"});
        SYSTEM.put("genesis", new String[]{"Genesis", "Sega Genesis", "Sega - Mega Drive - Genesis"});
        SYSTEM.put("gamegear", new String[]{"Game Gear", "Sega Game Gear", "Sega - Game Gear"});
        SYSTEM.put("32x", new String[]{"Sega 32X", "Sega 32X", "Sega - 32X"});
        SYSTEM.put("psx", new String[]{"PlayStation", "Sony PlayStation", "Sony - PlayStation"});
    }

    /** What a found file is. */
    static final class Found {
        File file;
        String system, inner; // inner: the game file inside a .zip
        List<File> parts = new ArrayList<>(); // a .cue and its .bin tracks
    }

    private final File root, cacheDir;
    private final Runnable onChange;
    private final Map<String, Found> memo = new HashMap<>(); // per file version: the page asks again every few seconds

    GameInbox(File root, File cacheDir, Runnable onChange) {
        this.root = root;
        this.cacheDir = cacheDir;
        this.onChange = onChange;
    }

    /** Download, JLRetro/Add games, and each USB drive or SD card. */
    List<File> sources() {
        List<File> out = new ArrayList<>();
        out.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        File add = new File(root, "Add games");
        add.mkdirs();
        out.add(add);
        File[] vols = new File("/storage").listFiles();
        if (vols != null) for (File v : vols) {
            if (!v.getName().equals("emulated") && !v.getName().equals("self") && v.isDirectory() && v.canRead()) out.add(v);
        }
        return out;
    }

    static String describe() {
        return "Download, JLRetro/Add games, or a USB drive or SD card";
    }

    // ---- Finding games ----

    synchronized JSONObject list(JSONObject library) throws JSONException {
        JSONArray items = new JSONArray();
        java.util.Set<String> have = ids(library);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (File src : sources()) {
            List<File> files = new ArrayList<>();
            walk(src, 0, files);
            for (File f : files) {
                Found g = classify(f);
                if (g == null) continue;
                String id = id(g.system, nameOf(g));
                if (have.contains(id) || !seen.add(id)) continue;
                boolean busy = System.currentTimeMillis() - f.lastModified() < 8000 || hasPartial(f);
                items.put(new JSONObject().put("file", f.getPath()).put("name", f.getName()).put("size", size(g))
                        .put("system", g.system).put("label", title(nameOf(g))).put("status", busy ? "copying" : "ready"));
            }
        }
        return new JSONObject().put("folder", describe()).put("items", items);
    }

    private void walk(File dir, int depth, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null || out.size() > 5000) return;
        for (File k : kids) {
            String n = k.getName();
            if (n.startsWith(".")) continue;
            if (k.isDirectory()) {
                if (depth < 3 && !n.equals("Android") && !n.equals("JLRetro") && !n.equals("LOST.DIR")) walk(k, depth + 1, out);
            } else if (k.isFile()) out.add(k);
        }
    }

    private static boolean hasPartial(File f) {
        for (String p : PARTIAL) if (new File(f.getPath() + p).exists()) return true;
        return false;
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.US);
    }

    Found classify(File f) {
        String key = f.getPath() + "|" + f.length() + "|" + f.lastModified();
        if (memo.containsKey(key)) return memo.get(key);
        Found g = classifyNow(f);
        memo.put(key, g);
        return g;
    }

    private static Found classifyNow(File f) {
        String name = f.getName(), e = ext(name);
        for (String p : PARTIAL) if (name.toLowerCase(Locale.US).endsWith(p)) return null;
        Found g = new Found();
        g.file = f;
        if (e.equals(".cue")) {
            List<File> tracks = cueTracks(f);
            if (tracks == null) return null; // a track it names is missing
            g.system = "psx";
            g.parts.add(f);
            g.parts.addAll(tracks);
            return g;
        }
        if (EXT.containsKey(e)) { g.system = EXT.get(e); g.parts.add(f); return g; }
        if (!e.equals(".zip")) return null;
        try (ZipFile z = new ZipFile(f)) {
            String rom = null;
            int roms = 0, others = 0;
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry x = en.nextElement();
                if (x.isDirectory()) continue;
                String ie = ext(x.getName());
                if (EXT.containsKey(ie) && !EXT.get(ie).equals("psx")) { roms++; rom = x.getName(); }
                else if (!ignored(ie)) others++;
            }
            if (roms == 1 && others == 0) { g.system = EXT.get(ext(rom)); g.inner = rom; g.parts.add(f); return g; }
            // Arcade sets hold chip dumps with no telling extension: only a folder named for arcade games marks them.
            File parent = f.getParentFile();
            if (roms == 0 && others > 0 && parent != null && ARCADE_FOLDER.matcher(parent.getName()).matches()) {
                g.system = "arcade"; g.parts.add(f); return g;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static boolean ignored(String e) {
        for (String i : IGNORED_INSIDE) if (i.equals(e)) return true;
        return false;
    }

    /** The .bin files a .cue names, or null when one is missing (a .cue whose .bin was renamed falls back to its folder's only .bin). */
    static List<File> cueTracks(File cue) {
        List<File> out = new ArrayList<>();
        try {
            String text = new String(readAll(new FileInputStream(cue)), StandardCharsets.ISO_8859_1);
            Matcher m = Pattern.compile("(?im)^\\s*FILE\\s+\"([^\"]+)\"").matcher(text);
            while (m.find()) {
                File t = new File(cue.getParentFile(), m.group(1));
                if (!t.isFile()) {
                    File[] bins = cue.getParentFile().listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".bin"));
                    if (bins == null || bins.length != 1) return null;
                    t = bins[0];
                }
                if (!out.contains(t)) out.add(t);
            }
        } catch (IOException e) {
            return null;
        }
        return out.isEmpty() ? null : out;
    }

    private static long size(Found g) {
        long n = 0;
        for (File p : g.parts) n += p.length();
        return n;
    }

    private static String nameOf(Found g) {
        return g.inner != null ? new File(g.inner).getName() : g.file.getName();
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** "Legend of Zelda, The (USA) (Rev 1)" -> "The Legend of Zelda". */
    static String title(String file) {
        String t = stem(file).replaceAll("\\([^)]*\\)|\\[[^\\]]*\\]", " ").replaceAll("\\s+", " ").trim();
        Matcher m = Pattern.compile("^(.*?), (The|A|An)\\b(.*)$").matcher(t);
        if (m.matches()) t = m.group(2) + " " + m.group(1) + m.group(3);
        return t.isEmpty() ? stem(file) : t;
    }

    static String region(String file) {
        String n = file.toLowerCase(Locale.US);
        if (n.contains("(usa") || n.contains(", usa")) return "USA";
        if (n.contains("(world")) return "WORLD";
        if (n.contains("(europe") || n.contains(", europe")) return "EUR";
        if (n.contains("(japan")) return "JPN";
        return "";
    }

    static String id(String system, String name) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest((system + "|" + name.toLowerCase(Locale.US)).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 6; i++) s.append(String.format(Locale.US, "%02x", d[i]));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString((system + name).hashCode());
        }
    }

    private static java.util.Set<String> ids(JSONObject library) {
        java.util.Set<String> out = new java.util.HashSet<>();
        JSONArray games = library == null ? null : library.optJSONArray("games");
        for (int i = 0; games != null && i < games.length(); i++) out.add(games.optJSONObject(i).optString("id"));
        return out;
    }

    // ---- Adding games ----

    /** Adds one found file, or every ready one when file is null. */
    synchronized JSONObject add(String file) throws JSONException {
        JSONArray added = new JSONArray(), errors = new JSONArray();
        JSONObject lib = readLibrary();
        if (lib == null) return new JSONObject().put("added", added).put("errors", errors.put(new JSONObject().put("error", "Your library file couldn't be read, so nothing was added.")));
        JSONArray items = list(lib).getJSONArray("items");
        List<JSONObject> newGames = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            if (file != null ? !it.getString("file").equals(file) : !"ready".equals(it.getString("status"))) continue;
            Found g = classify(new File(it.getString("file")));
            if (g == null) { errors.put(new JSONObject().put("error", "That file isn't a game anymore.")); continue; }
            try {
                JSONObject game = place(g);
                lib.getJSONArray("games").put(game);
                addSystem(lib, g.system);
                newGames.add(game);
                added.put(new JSONObject().put("label", game.getString("title")).put("system", g.system));
            } catch (IOException e) {
                errors.put(new JSONObject().put("error", it.getString("label") + ": " + e.getMessage()));
            }
        }
        if (!newGames.isEmpty()) {
            writeLibrary(lib);
            final List<JSONObject> forArt = newGames;
            new Thread(() -> fetchArt(forArt)).start();
        }
        return new JSONObject().put("added", added).put("errors", errors);
    }

    /** Moves the game's files into JLRetro/roms/<system> (copies them from another drive, leaving the originals). */
    private JSONObject place(Found g) throws IOException, JSONException {
        String name = nameOf(g);
        File dir = new File(root, "roms/" + g.system + (g.system.equals("psx") && g.parts.size() > 1 ? "/" + stem(g.file.getName()) : ""));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't make the folder for it.");
        for (File p : g.parts) {
            File to = new File(dir, p.getName());
            if (to.isFile() && to.length() == p.length()) continue;
            if (!p.renameTo(to)) { // another drive: copy, check the size, and keep the original there
                File part = new File(to.getPath() + ".part");
                try (InputStream in = new FileInputStream(p); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (part.length() != p.length()) { part.delete(); throw new IOException("The copy came out the wrong size, so it wasn't added."); }
                if (to.exists()) to.delete();
                if (!part.renameTo(to)) throw new IOException("Couldn't save it.");
            }
        }
        String rel = root.toURI().relativize(new File(dir, g.file.getName()).toURI()).getPath();
        return new JSONObject().put("id", id(g.system, name)).put("title", title(name)).put("region", region(name))
                .put("system", g.system).put("rom", rel + (g.inner != null ? "#" + g.inner : "")).put("core", CORE.get(g.system))
                .put("art", "").put("thumb", "").put("snap", "").put("title_shot", "").put("added", System.currentTimeMillis() / 1000);
    }

    private static void addSystem(JSONObject lib, String system) throws JSONException {
        JSONArray systems = lib.getJSONArray("systems");
        for (int i = 0; i < systems.length(); i++) if (system.equals(systems.getJSONObject(i).optString("id"))) return;
        String[] s = SYSTEM.get(system);
        systems.put(new JSONObject().put("id", system).put("name", s[0]).put("full", s[1]));
        // Keep the usual order: NES, SNES, N64, ...
        List<JSONObject> sorted = new ArrayList<>();
        for (String id : SYSTEM.keySet())
            for (int i = 0; i < systems.length(); i++) if (id.equals(systems.getJSONObject(i).optString("id"))) sorted.add(systems.getJSONObject(i));
        for (int i = 0; i < systems.length(); i++) if (!SYSTEM.containsKey(systems.getJSONObject(i).optString("id"))) sorted.add(systems.getJSONObject(i));
        lib.put("systems", new JSONArray(sorted));
    }

    // ---- library.json ----

    private File libraryFile() {
        return new File(root, "library.json");
    }

    synchronized JSONObject readLibrary() {
        File f = libraryFile();
        try {
            if (f.isFile()) {
                JSONObject lib = new JSONObject(new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
                if (!lib.has("games")) lib.put("games", new JSONArray());
                if (!lib.has("systems")) lib.put("systems", new JSONArray());
                return lib;
            }
            return new JSONObject().put("version", 1).put("systems", new JSONArray()).put("games", new JSONArray());
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private synchronized void writeLibrary(JSONObject lib) throws JSONException {
        File f = libraryFile(), tmp = new File(f.getPath() + ".tmp");
        root.mkdirs();
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(lib.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
        try { new File(root, ".nomedia").createNewFile(); } catch (IOException ignored) { }
        onChange.run();
    }

    // ---- Box art, screenshot and title screen from the libretro thumbnails library ----

    private void fetchArt(List<JSONObject> games) {
        Map<String, JSONObject> found = new HashMap<>();
        for (JSONObject g : games) {
            String sys = SYSTEM.get(g.optString("system"))[2];
            if (sys == null) continue;
            String rom = g.optString("rom"), name = rom.contains("#") ? rom.substring(rom.indexOf('#') + 1) : rom;
            String hit = pick(index(sys), stem(new File(name).getName()));
            if (hit == null) continue;
            String id = g.optString("id");
            JSONObject got = new JSONObject();
            try {
                if (download(sys, "Named_Boxarts", hit, new File(root, "art/" + id + ".png"))) {
                    got.put("art", "art/" + id + ".png");
                    if (thumb(new File(root, "art/" + id + ".png"), new File(root, "art/" + id + "-thumb.jpg"))) got.put("thumb", "art/" + id + "-thumb.jpg");
                }
                if (download(sys, "Named_Snaps", hit, new File(root, "art/" + id + "-snap.png"))) got.put("snap", "art/" + id + "-snap.png");
                if (download(sys, "Named_Titles", hit, new File(root, "art/" + id + "-title.png"))) got.put("title_shot", "art/" + id + "-title.png");
            } catch (JSONException ignored) {
            }
            if (got.length() > 0) found.put(id, got);
        }
        if (found.isEmpty()) return;
        synchronized (this) {
            try {
                JSONObject lib = readLibrary();
                if (lib == null) return;
                JSONArray list = lib.getJSONArray("games");
                for (int i = 0; i < list.length(); i++) {
                    JSONObject g = list.getJSONObject(i), got = found.get(g.optString("id"));
                    if (got == null) continue;
                    for (java.util.Iterator<String> k = got.keys(); k.hasNext(); ) { String key = k.next(); g.put(key, got.get(key)); }
                }
                writeLibrary(lib);
            } catch (JSONException ignored) {
            }
        }
    }

    /** The system's list of box art names, kept for a week. */
    private List<String> index(String sys) {
        File cache = new File(cacheDir, "art-index/" + sys + ".txt");
        List<String> out = new ArrayList<>();
        try {
            if (!cache.isFile() || System.currentTimeMillis() - cache.lastModified() > 7 * 86400000L) {
                String html = new String(readAll(open(THUMBS + enc(sys) + "/Named_Boxarts/")), StandardCharsets.UTF_8);
                StringBuilder s = new StringBuilder();
                Matcher m = Pattern.compile("href=\"([^\"]+\\.png)\"").matcher(html);
                while (m.find()) s.append(java.net.URLDecoder.decode(m.group(1), "UTF-8")).append('\n');
                cache.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(cache)) { o.write(s.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            for (String line : new String(readAll(new FileInputStream(cache)), StandardCharsets.UTF_8).split("\n")) if (!line.isEmpty()) out.add(line);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** libretro's file-name rule, then a looser match on the name without tags (USA first). */
    static String pick(List<String> list, String stem) {
        String exact = stem.replaceAll("[&*/:`<>?\\\\|\"]", "_") + ".png";
        for (String n : list) if (n.equalsIgnoreCase(exact)) return n;
        String key = key(stem);
        String best = null;
        for (String n : list) {
            if (!key(n).equals(key)) continue;
            if (n.contains("(USA")) return n;
            if (best == null || n.contains("(World")) best = n;
        }
        return best;
    }

    static String key(String s) {
        String t = s.toLowerCase(Locale.US).replaceAll("\\.png$", "").replaceAll("\\([^)]*\\)|\\[[^\\]]*\\]", " ");
        t = t.replaceAll("^(.*?),\\s*the\\b", "the $1").replace("&", " and ").replaceAll("[^a-z0-9]+", " ");
        return t.replaceAll("\\b(the|a|an)\\b", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean download(String sys, String kind, String file, File to) {
        try {
            byte[] png = readAll(open(THUMBS + enc(sys) + "/" + kind + "/" + enc(file)));
            if (png.length < 8 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return false;
            to.getParentFile().mkdirs();
            try (OutputStream o = new FileOutputStream(to)) { o.write(png); }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** A small JPEG of the cover for the grid; the full one is for the game's page. */
    private static boolean thumb(File png, File jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(png.getPath(), o);
        if (o.outWidth <= 0) return false;
        o.inJustDecodeBounds = false;
        o.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, o.outWidth / 360)));
        Bitmap b = BitmapFactory.decodeFile(png.getPath(), o);
        if (b == null) return false;
        try (OutputStream out = new FileOutputStream(jpg)) {
            return b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            return false;
        } finally {
            b.recycle();
        }
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private static InputStream open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "EchoForge-Retro-Gaming");
        if (c.getResponseCode() != 200) { c.disconnect(); throw new IOException("HTTP " + c.getResponseCode()); }
        return c.getInputStream();
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
