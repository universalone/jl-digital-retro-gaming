"""One-off: turns the old launcher MainActivity into the all-in-one app's library screen."""
import re
import sys

p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def sub(old, new):
    global s
    if old not in s:
        sys.exit('not found: ' + old[:60])
    s = s.replace(old, new)


sub('''/**
 * Shows the shared JL Digital Retro Gaming dashboard and launches games in RetroArch.
 * Games, box art and library.json live in the JLRetro folder on the phone's storage,
 * copied there from the PC export.
 */''', '''/**
 * Shows the shared JL Digital Retro Gaming dashboard and plays games in the built-in emulator
 * (RetroArch, running as RetroActivityFuture in its own ":game" process).
 * Games, box art and library.json live in the JLRetro folder on the phone's storage,
 * copied there from the PC export. Missing cores are downloaded from the libretro buildbot.
 */''')

sub('''    private static final String[] RETROARCH_PACKAGES = {"com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32"};
    private static final String RETROARCH_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture";
''', '''    private static final String BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest/";
''')

sub('''        prefs = getSharedPreferences("jlretro", MODE_PRIVATE);
''', '''        prefs = getSharedPreferences("jlretro", MODE_PRIVATE);
        // Writes the emulator's config and asks it to unpack the menu assets bundled in the APK.
        UserPreferences.updateConfigFile(this);
''')

sub('''        if (firstResume) {
            firstResume = false;
        } else {
            js("window.jlResume && jlResume()");
        }
    }''', '''        if (firstResume) {
            firstResume = false;
        } else {
            js("window.jlResume && jlResume()");
        }
        installLibraryCores();
    }''')

s, n = re.subn(r'    private String retroArchPackage\(\) \{.*?\n    \}\n\n', '', s, flags=re.S)
assert n == 1

sub('''        String pkg = retroArchPackage();
        Set<String> cores''', '''        Set<String> cores''')
sub('''                .put("ready", access && lib != null && pkg != null)''', '''                .put("ready", access && lib != null)''')
sub('''                .put("retroarch", pkg != null)''', '''                .put("retroarch", true)''')

old_launch = s[s.index('    private JSONObject launch(String id)'):s.index('    private JSONObject favorite(')]
new_launch = '''    private JSONObject launch(String id) throws JSONException {
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

'''
s = s.replace(old_launch, new_launch)

sub('''            case "getRetroArch":
                openUrl("https://www.retroarch.com/?page=platforms");
                return new JSONObject().put("toast", "Get the Android version, install it, then open it once.");
''', '')
sub('''            case "openRetroArch": {
                String pkg = retroArchPackage();
                Intent i = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) startActivity(i);
                return new JSONObject();
            }''', '''            case "openRetroArch":
                startGame(null, null);
                return new JSONObject();''')

sub('''import android.content.ComponentName;
''', '')
sub('''import org.json.JSONArray;''', '''import com.retroarch.browser.preferences.util.UserPreferences;
import com.retroarch.browser.retroactivity.RetroActivityFuture;

import org.json.JSONArray;''')

open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
