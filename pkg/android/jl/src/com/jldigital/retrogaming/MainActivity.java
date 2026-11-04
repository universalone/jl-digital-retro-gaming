package com.jldigital.retrogaming;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shows the shared EchoForge Retro Gaming dashboard and plays games in the built-in emulator
 * (RetroArch, running as RetroActivityFuture in its own ":game" process).
 * Games, box art and library.json live in the JLRetro folder on the phone's storage,
 * copied there from the PC export. Missing cores are downloaded from the libretro buildbot.
 */
public class MainActivity extends Activity {
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest/";
    private static final Map<String, String> CORE_NAMES = new LinkedHashMap<>();

    static {
        CORE_NAMES.put("fceumm", "Nintendo - NES / Famicom (FCEUmm)");
        CORE_NAMES.put("snes9x", "Nintendo - SNES / SFC (Snes9x - Current)");
        CORE_NAMES.put("mupen64plus_next_gles3", "Nintendo - Nintendo 64 (Mupen64Plus-Next GLES3)");
        CORE_NAMES.put("mame2000", "Arcade (MAME 2000)");
        CORE_NAMES.put("mame2003_plus", "Arcade (MAME 2003-Plus)");
        CORE_NAMES.put("mame2010", "Arcade (MAME 2010)");
        CORE_NAMES.put("mame2015", "Arcade (MAME 2015)");
        CORE_NAMES.put("mame2016", "Arcade (MAME 2016)");
        CORE_NAMES.put("fbneo", "Arcade (FinalBurn Neo)");
        CORE_NAMES.put("hbmame", "Arcade (HBMAME)");
    }

    private WebView web;
    private SharedPreferences prefs;
    private JSONObject library;
    private long libraryStamp;
    private Map<String, JSONObject> gamesById = Collections.emptyMap();
    private boolean firstResume = true;
    private String lastStickDir;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("jlretro", MODE_PRIVATE);
        // Writes the emulator's config and asks it to unpack the menu assets bundled in the APK.
        UserPreferences.updateConfigFile(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1322);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return serve(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (APP_HOST.equals(u.getHost())) return false;
                openUrl(u.toString());
                return true;
            }
        });
        setContentView(web);
        hideSystemBars();
        web.loadUrl("https://" + APP_HOST + "/");
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (firstResume) {
            firstResume = false;
        } else {
            js("window.jlResume && jlResume()");
        }
        installLibraryCores();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        js("window.jlResume && jlResume()");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    // ---- Controller input: forwarded to the page as jlInput('up' | 'a' | ...) ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean fromPad = isSource(e.getSource(), InputDevice.SOURCE_GAMEPAD)
                || isSource(e.getSource(), InputDevice.SOURCE_JOYSTICK)
                || isSource(e.getSource(), InputDevice.SOURCE_DPAD);
        String action = null;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: action = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: action = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: action = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: action = "right"; break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: action = "a"; break;  // TV remote OK button
            case KeyEvent.KEYCODE_BUTTON_B: action = "b"; break;
            case KeyEvent.KEYCODE_BUTTON_Y: action = "y"; break;
            case KeyEvent.KEYCODE_BUTTON_L1: action = "lb"; break;
            case KeyEvent.KEYCODE_BUTTON_R1: action = "rb"; break;
            default: break;
        }
        if (action != null && fromPad) {
            boolean direction = action.equals("up") || action.equals("down") || action.equals("left") || action.equals("right");
            if (e.getAction() == KeyEvent.ACTION_DOWN && (e.getRepeatCount() == 0 || direction)) {
                if (action.equals("b")) goBack();
                else js("window.jlInput && jlInput('" + action + "')");
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (isSource(ev.getSource(), InputDevice.SOURCE_JOYSTICK) && ev.getAction() == MotionEvent.ACTION_MOVE) {
            float x = ev.getAxisValue(MotionEvent.AXIS_HAT_X), y = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
                x = ev.getAxisValue(MotionEvent.AXIS_X);
                y = ev.getAxisValue(MotionEvent.AXIS_Y);
            }
            String dir = null;
            if (Math.abs(x) > 0.6f || Math.abs(y) > 0.6f) {
                dir = Math.abs(x) > Math.abs(y) ? (x > 0 ? "right" : "left") : (y > 0 ? "down" : "up");
            }
            if (dir != null && !dir.equals(lastStickDir)) js("window.jlInput && jlInput('" + dir + "')");
            lastStickDir = dir;
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private static boolean isSource(int source, int kind) {
        return (source & kind) == kind;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        goBack();
    }

    /** Back steps out of search or a system page first; on Home it leaves the app. */
    private void goBack() {
        runOnUiThread(() -> web.evaluateJavascript("window.jlInput ? jlInput('b') : false", handled -> {
            if (!"true".equals(handled)) moveTaskToBack(true);
        }));
    }

    // ---- Files: dashboard page from the APK, box art from the JLRetro folder ----

    private File root() {
        return new File(Environment.getExternalStorageDirectory(), "JLRetro");
    }

    private WebResourceResponse serve(Uri u) {
        if (!APP_HOST.equals(u.getHost())) return null;
        String path = u.getPath() == null ? "/" : u.getPath();
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                return new WebResourceResponse("text/html", "utf-8", getAssets().open("index.html"));
            }
            if (path.equals("/icon.png")) {
                return new WebResourceResponse("image/png", null, getAssets().open("icon.png"));
            }
            if (path.equals("/PressStart2P.ttf")) {
                return new WebResourceResponse("font/ttf", null, getAssets().open("PressStart2P.ttf"));
            }
            if (path.equals("/logo.svg")) {
                return new WebResourceResponse("image/svg+xml", null, getAssets().open("logo.svg"));
            }
            // Game images: box art, the small grid cover (falls back to box art), screenshot and title screen.
            String[][] images = {{"/art/", "art"}, {"/thumb/", "thumb"}, {"/snap/", "snap"}, {"/title/", "title_shot"}};
            for (String[] kind : images) {
                if (!path.startsWith(kind[0])) continue;
                JSONObject g = findGame(path.substring(kind[0].length()));
                String rel = g == null ? "" : g.optString(kind[1], "");
                if (rel.isEmpty() && g != null && kind[1].equals("thumb")) rel = g.optString("art", "");
                File f = rel.isEmpty() ? null : inRoot(rel);
                if (f != null && f.isFile()) {
                    String mime = rel.endsWith(".jpg") ? "image/jpeg" : "image/png";
                    return new WebResourceResponse(mime, null, 200, "OK",
                            Collections.singletonMap("Cache-Control", "max-age=604800"), new FileInputStream(f));
                }
            }
        } catch (IOException ignored) {
        }
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    /** Resolves a library-relative path, refusing anything outside the JLRetro folder. */
    private File inRoot(String relative) throws IOException {
        File base = root().getCanonicalFile();
        File f = new File(base, relative).getCanonicalFile();
        return f.getPath().startsWith(base.getPath() + File.separator) ? f : null;
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private synchronized JSONObject library() {
        File f = new File(root(), "library.json");
        if (!hasStorageAccess() || !f.isFile()) return null;
        if (library == null || f.lastModified() != libraryStamp) {
            try (InputStream in = new FileInputStream(f)) {
                library = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
                libraryStamp = f.lastModified();
                gamesById = new java.util.HashMap<>();
                JSONArray games = library.optJSONArray("games");
                for (int i = 0; games != null && i < games.length(); i++) {
                    JSONObject g = games.optJSONObject(i);
                    if (g != null) gamesById.put(g.optString("id"), g);
                }
            } catch (IOException | JSONException e) {
                return null;
            }
        }
        return library;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Looked up in a map: the grid asks for every game's cover, so a list scan per image adds up on TV boxes. */
    private synchronized JSONObject findGame(String id) {
        return library() == null ? null : gamesById.get(id);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // ---- The bridge the page calls in place of the PC server's /api routes ----

    private class Bridge {
        @JavascriptInterface
        public String call(String path, String bodyJson) {
            try {
                JSONObject body = new JSONObject(bodyJson == null || bodyJson.isEmpty() ? "{}" : bodyJson);
                switch (path) {
                    case "/api/status": return status().toString();
                    case "/api/library": return libraryForPage().toString();
                    case "/api/launch": return launch(body.optString("id")).toString();
                    case "/api/favorite": return favorite(body.optString("id"), body.optBoolean("on")).toString();
                    case "/api/action": return action(body.optString("name"), body.optString("value")).toString();
                    default: return new JSONObject().put("ok", true).put("settings", new JSONObject()).toString();
                }
            } catch (JSONException e) {
                return "{\"ok\":false,\"error\":\"Something went wrong.\"}";
            }
        }
    }

    private JSONObject status() throws JSONException {
        boolean access = hasStorageAccess();
        JSONObject lib = library();
        Set<String> cores = new LinkedHashSet<>();
        if (lib != null) {
            JSONArray games = lib.optJSONArray("games");
            for (int i = 0; games != null && i < games.length(); i++) {
                String core = games.optJSONObject(i).optString("core");
                cores.add(CORE_NAMES.containsKey(core) ? CORE_NAMES.get(core) : core);
            }
        }
        return new JSONObject()
                .put("ready", access && lib != null)
                .put("permission", access)
                .put("library", lib != null)
                .put("retroarch", true)
                .put("root", root().getPath())
                .put("gamesUrl", !prefs.getString("gamesUrl", "").isEmpty())
                .put("downloading", downloading)
                .put("cores", String.join(", ", cores));
    }

    private JSONObject libraryForPage() throws JSONException {
        JSONObject lib = library();
        JSONArray out = new JSONArray();
        JSONArray systems = new JSONArray();
        if (lib != null) {
            systems = lib.optJSONArray("systems");
            JSONArray games = lib.optJSONArray("games");
            for (int i = 0; games != null && i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                out.put(new JSONObject()
                        .put("id", g.optString("id"))
                        .put("title", g.optString("title"))
                        .put("region", g.optString("region"))
                        .put("system", g.optString("system"))
                        .put("art", !g.optString("art").isEmpty())
                        .put("snap", !g.optString("snap").isEmpty())
                        .put("titleShot", !g.optString("title_shot").isEmpty()));
            }
        }
        return new JSONObject()
                .put("systems", systems)
                .put("games", out)
                .put("favorites", new JSONArray(prefs.getString("favorites", "[]")))
                .put("history", new JSONObject(prefs.getString("history", "{}")))
                .put("settings", new JSONObject())
                .put("version", libraryStamp);
    }

    // Older boxes (e.g. Mi Box 3, Mali-450) only have OpenGL ES 2.0, where GLES3 cores crash on load.
    private String deviceCore(String core) {
        if (!core.endsWith("_gles3")) return core;
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        boolean gles3 = am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
        return gles3 ? core : core.substring(0, core.length() - 1) + "2";
    }

    private JSONObject launch(String id) throws JSONException {
        JSONObject g = findGame(id);
        if (g == null) return new JSONObject().put("ok", false).put("error", "That game isn't in your library.");
        String rom = root().getPath() + "/" + g.optString("rom");
        if (!new File(rom.split("#")[0]).isFile()) {
            return new JSONObject().put("ok", false).put("error", "The game file is missing from the JLRetro folder.");
        }
        String core = deviceCore(g.optString("core"));
        if (!coreFile(core).isFile()) {
            // First play of this system: fetch its core, then start the game.
            new Thread(() -> {
                if (installCore(core)) {
                    startGame(rom, core);
                } else {
                    js("window.jlResume && jlResume()");
                    toastLater("Couldn't download the emulator for this game. Check your internet connection.");
                }
            }).start();
        } else if (!startGame(rom, core)) {
            return new JSONObject().put("ok", false).put("error", "The game couldn't be started.");
        }
        JSONObject history = new JSONObject(prefs.getString("history", "{}"));
        JSONObject entry = history.optJSONObject(id);
        if (entry == null) entry = new JSONObject().put("plays", 0);
        entry.put("plays", entry.optInt("plays") + 1).put("last", System.currentTimeMillis() / 1000);
        history.put(id, entry);
        prefs.edit().putString("history", history.toString()).apply();
        return new JSONObject().put("ok", true).put("error", "");
    }

    /** Starts the emulator; with no ROM it opens its menu (settings, core downloads). */
    private boolean startGame(String rom, String core) {
        ApplicationInfo ai = getApplicationInfo();
        String sdcard = Environment.getExternalStorageDirectory().getPath();
        Intent i = new Intent(this, RetroActivityFuture.class);
        if (rom != null) i.putExtra("ROM", rom);
        i.putExtra("LIBRETRO", core == null ? coresDir().getPath() + "/" : coreFile(core).getPath());
        i.putExtra("CONFIGFILE", UserPreferences.getDefaultConfigPath(this));
        i.putExtra("IME", Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD));
        i.putExtra("DATADIR", ai.dataDir);
        i.putExtra("APK", ai.sourceDir);
        i.putExtra("SDCARD", sdcard);
        i.putExtra("EXTERNAL", sdcard + "/Android/data/" + getPackageName() + "/files");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ---- Game pack: a zip of the JLRetro folder (export_phone.py --pack), shared from the cloud ----

    private volatile boolean downloading;

    /** Turns a Dropbox / Google Drive / OneDrive share page link into a direct download link. */
    static String directLink(String url) {
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost();
        if (host.endsWith("dropbox.com")) {
            return u.buildUpon().clearQuery().appendQueryParameter("dl", "1").build().toString()
                    + (u.getQueryParameter("rlkey") != null ? "&rlkey=" + u.getQueryParameter("rlkey") : "");
        }
        if (host.endsWith("drive.google.com")) {
            String id = u.getQueryParameter("id");
            java.util.List<String> seg = u.getPathSegments();
            int d = seg.indexOf("d");
            if (id == null && d >= 0 && d + 1 < seg.size()) id = seg.get(d + 1);
            if (id != null) return "https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t";
        }
        if (host.equals("1drv.ms") || host.endsWith("onedrive.live.com")) {
            String enc = android.util.Base64.encodeToString(url.getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING);
            return "https://api.onedrive.com/v1.0/shares/u!" + enc + "/root/content";
        }
        return url;
    }

    private void progress(String text) {
        js("window.jlProgress && jlProgress(" + (text == null ? "null" : JSONObject.quote(text)) + ")");
    }

    private static String mb(long bytes) {
        return bytes >= 1L << 30 ? String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0)
                : (bytes >> 20) + " MB";
    }

    /** Streams the pack and unzips it into the JLRetro folder as it arrives; files already there are kept. */
    private void downloadGames(String url) {
        java.net.HttpURLConnection c = null;
        String error = null;
        int added = 0;
        try {
            String link = directLink(url);
            for (int hop = 0; ; hop++) {
                c = (java.net.HttpURLConnection) new java.net.URL(link).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                int code = c.getResponseCode();
                if (code / 100 == 3 && hop < 8) {
                    link = new java.net.URL(new java.net.URL(link), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                break;
            }
            long total = c.getContentLengthLong();
            File base = root();
            base.mkdirs();
            final long[] read = {0};
            InputStream counted = new java.io.FilterInputStream(new java.io.BufferedInputStream(c.getInputStream(), 1 << 16)) {
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) read[0] += n;
                    return n;
                }
                @Override public int read() throws IOException {
                    int n = super.read();
                    if (n >= 0) read[0]++;
                    return n;
                }
            };
            long lastReport = 0;
            boolean sawLibrary = false;
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(counted)) {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[1 << 16];
                while ((e = zin.getNextEntry()) != null) {
                    String name = e.getName().replace('\\', '/');
                    if (name.startsWith("JLRetro/")) name = name.substring(8);
                    if (e.isDirectory() || name.isEmpty()) continue;
                    File f = inRoot(name);
                    if (f == null) continue;
                    if (name.equals("library.json")) sawLibrary = true;
                    boolean same = f.isFile() && e.getSize() >= 0 && f.length() == e.getSize() && !name.equals("library.json");
                    if (same) {
                        while (zin.read(buf) > 0) { }
                    } else {
                        f.getParentFile().mkdirs();
                        File part = new File(f.getPath() + ".part");
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                            int n;
                            while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        if (f.exists()) f.delete();
                        if (!part.renameTo(f)) throw new IOException("Couldn't save " + name);
                        added++;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 1000) {
                        lastReport = now;
                        progress("Downloading your games... " + mb(read[0])
                                + (total > 0 ? " of " + mb(total) + " (" + (read[0] * 100 / total) + "%)" : ""));
                    }
                }
            }
            if (!sawLibrary) error = "That link isn't an EchoForge game pack (no library.json inside).";
        } catch (IOException e) {
            error = "The download stopped (" + e.getMessage() + "). Check the link and your internet, then try again.";
        } finally {
            if (c != null) c.disconnect();
            downloading = false;
        }
        progress(null);
        if (error == null) {
            try { new File(root(), ".nomedia").createNewFile(); } catch (IOException ignored) { }
            toastLater(added == 0 ? "Your games are already up to date." : "Games ready! " + added + " files updated.");
            js("window.jlResume && jlResume()");
            installLibraryCores();
        } else {
            toastLater(error);
            js("window.jlResume && jlResume()");
        }
    }

    // ---- Cores: downloaded on demand from the libretro buildbot into <dataDir>/cores ----

    private File coresDir() {
        return new File(getApplicationInfo().dataDir, "cores");
    }

    private File coreFile(String core) {
        return new File(coresDir(), core + "_libretro_android.so");
    }

    private void toastLater(String msg) {
        js("window.toast && toast(" + JSONObject.quote(msg) + ")");
    }

    /** Downloads and unzips one core. Safe to call from several threads. */
    private boolean installCore(String core) {
        synchronized (MainActivity.class) {
            File dest = coreFile(core);
            if (dest.isFile()) return true;
            toastLater("Getting the emulator for this system, one moment...");
            String abi = android.os.Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a";
            File tmp = new File(coresDir(), core + ".part");
            coresDir().mkdirs();
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(BUILDBOT + abi + "/" + core + "_libretro_android.so.zip").openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(c.getInputStream())) {
                    java.util.zip.ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        if (!e.getName().equals(dest.getName())) continue;
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        return tmp.renameTo(dest);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (c != null) c.disconnect();
                tmp.delete();
            }
            return false;
        }
    }

    /** Installs, in the background, the cores the library's games need. */
    private void installLibraryCores() {
        JSONObject lib = library();
        JSONArray games = lib == null ? null : lib.optJSONArray("games");
        if (games == null) return;
        Set<String> needed = new LinkedHashSet<>();
        for (int i = 0; i < games.length(); i++) {
            String core = deviceCore(games.optJSONObject(i).optString("core"));
            if (!core.isEmpty() && !coreFile(core).isFile()) needed.add(core);
        }
        if (needed.isEmpty()) return;
        new Thread(() -> {
            for (String core : needed) installCore(core);
        }).start();
    }

    private JSONObject favorite(String id, boolean on) throws JSONException {
        JSONArray favs = new JSONArray(prefs.getString("favorites", "[]"));
        JSONArray next = new JSONArray();
        for (int i = 0; i < favs.length(); i++) {
            if (!favs.getString(i).equals(id)) next.put(favs.getString(i));
        }
        if (on) next.put(id);
        prefs.edit().putString("favorites", next.toString()).apply();
        return new JSONObject().put("ok", true).put("favorites", next);
    }

    private JSONObject action(String name, String value) throws JSONException {
        switch (name) {
            case "downloadGames": {
                String url = value == null || value.trim().isEmpty() ? prefs.getString("gamesUrl", "") : value.trim();
                if (url.isEmpty()) return new JSONObject().put("toast", "Paste the share link to your game pack first.");
                if (!hasStorageAccess()) return new JSONObject().put("toast", "Allow access to your games first.");
                if (downloading) return new JSONObject().put("toast", "Your games are already downloading.");
                prefs.edit().putString("gamesUrl", url).apply();
                downloading = true;
                new Thread(() -> downloadGames(url)).start();
                return new JSONObject().put("toast", "Downloading your games...");
            }
            case "grantAccess":
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    } else {
                        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
                    }
                });
                return new JSONObject().put("toast", "Turn on access for EchoForge Retro Gaming, then come back.");
            case "openRetroArch":
                startGame(null, null);
                return new JSONObject();
            case "rescan":
                js("window.jlResume && jlResume()");
                return new JSONObject().put("toast", library() == null ? "JLRetro folder not found yet." : "Library found!");
            default:
                return new JSONObject();
        }
    }
}
