package com.jldigital.retrogaming;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Updates the app from this project's GitHub releases, like the PC version: checks once a day (Settings › Updates),
 * downloads the new APK, checks it against GitHub's SHA-256, and hands it to Android's installer, which asks the user
 * to confirm. Android only installs it over this app when it's signed with the same key. Answers the page's
 * /api/update calls with the same shape as the PC server.
 */
final class AppUpdate {
    static final String RELEASES = "https://api.github.com/repos/universalone/jl-digital-retro-gaming/releases?per_page=10";
    static final String APK = "JL-Digital-Retro-Gaming.apk";
    private static final String ACTION = "com.jldigital.retrogaming.UPDATE_STATUS";
    private static final long DAY = 86400000L;

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Runnable onChange; // the page refreshes its banner
    private volatile boolean busy;
    private volatile int pct;
    private volatile String error = "";

    AppUpdate(Activity activity, SharedPreferences prefs, Runnable onChange) {
        this.activity = activity;
        this.prefs = prefs;
        this.onChange = onChange;
        IntentFilter f = new IntentFilter(ACTION);
        activity.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { activity.startActivity(confirm); } catch (ActivityNotFoundException ignored) { }
                    }
                    return;
                }
                busy = false;
                if (status != PackageInstaller.STATUS_SUCCESS) {
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    error = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "The update was cancelled."
                            : "Android couldn't install the update" + (msg == null ? "." : " (" + msg + ").");
                }
                onChange.run();
            }
        }, f);
    }

    String current() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** {"current", "installed", "last", "busy", "pct", "error"}: what the page's Updates tab and Home banner show. */
    JSONObject status() throws JSONException {
        JSONObject last = null;
        try { last = new JSONObject(prefs.getString("updateLast", "")); } catch (JSONException ignored) { }
        if (last != null && last.optJSONObject("latest") != null) // still newer than this copy? (it may have just been installed)
            last.put("update", newer(last.getJSONObject("latest").optString("version"), current()));
        return new JSONObject().put("current", current()).put("installed", true).put("last", last == null ? JSONObject.NULL : last)
                .put("busy", busy).put("pct", pct).put("error", error);
    }

    /** Checks GitHub once a day when updates are on (the page's autoUpdate setting). */
    void checkDaily(boolean on) {
        if (!on || System.currentTimeMillis() - prefs.getLong("updateChecked", 0) < DAY) return;
        new Thread(() -> { check(); onChange.run(); }).start();
    }

    synchronized JSONObject check() {
        JSONObject last;
        try {
            JSONObject best = newest(new JSONArray(new String(GameInbox.readAll(open(RELEASES)), StandardCharsets.UTF_8)));
            last = new JSONObject().put("ok", true).put("latest", best == null ? JSONObject.NULL : best)
                    .put("update", best != null && newer(best.getString("version"), current()));
        } catch (IOException | JSONException e) {
            try {
                last = new JSONObject().put("ok", false).put("error", "Couldn't reach GitHub to check for updates. Check your internet connection.");
            } catch (JSONException x) {
                return new JSONObject();
            }
        }
        prefs.edit().putString("updateLast", last.toString()).putLong("updateChecked", System.currentTimeMillis()).apply();
        error = "";
        return last;
    }

    /** The newest published release with the app's APK and its checksum. */
    static JSONObject newest(JSONArray releases) throws JSONException {
        JSONObject best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.getJSONObject(i);
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
            String version = r.optString("tag_name").replaceFirst("^[vV]", "");
            if (!version.matches("\\d+(\\.\\d+)*")) continue;
            JSONArray assets = r.optJSONArray("assets");
            for (int a = 0; assets != null && a < assets.length(); a++) {
                JSONObject x = assets.getJSONObject(a);
                String url = x.optString("browser_download_url"), digest = x.optString("digest");
                if (!APK.equals(x.optString("name")) || !url.startsWith("https://github.com/") || !digest.matches("sha256:[0-9a-f]{64}")) continue;
                if (best == null || newer(version, best.getString("version")))
                    best = new JSONObject().put("version", version).put("url", url).put("size", x.optLong("size"))
                            .put("sha256", digest.substring(7)).put("page", r.optString("html_url"));
            }
        }
        return best;
    }

    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** Starts downloading and installing; returns at once. */
    JSONObject install() throws JSONException {
        if (busy) return new JSONObject().put("ok", true);
        JSONObject latest;
        try { latest = new JSONObject(prefs.getString("updateLast", "{}")).optJSONObject("latest"); } catch (JSONException e) { latest = null; }
        if (latest == null || !newer(latest.optString("version"), current()))
            return new JSONObject().put("ok", false).put("error", "There's no update to install. Press Check for updates.");
        // Android 8+: this app needs "Install unknown apps" turned on once.
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.runOnUiThread(() -> {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                } catch (ActivityNotFoundException e) {
                    try { activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (ActivityNotFoundException ignored) { }
                }
            });
            return new JSONObject().put("ok", false).put("error", "Turn on \"Allow from this source\" (Install unknown apps) for EchoForge Retro Gaming, then come back and press Update now again.");
        }
        busy = true;
        pct = 0;
        error = "";
        final JSONObject r = latest;
        new Thread(() -> run(r)).start();
        return new JSONObject().put("ok", true);
    }

    private void run(JSONObject latest) {
        File apk = new File(activity.getCacheDir(), "update.apk");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long size = latest.optLong("size"), got = 0;
            try (InputStream in = open(latest.getString("url")); OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[1 << 16];
                int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    got += n;
                    pct = size > 0 ? (int) (got * 100 / size) : 0;
                    if (pct != lastPct && pct % 5 == 0) { lastPct = pct; onChange.run(); }
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format(Locale.US, "%02x", b));
            if (!hex.toString().equals(latest.getString("sha256")))
                throw new IOException("The download didn't match GitHub's checksum, so it wasn't installed.");
            PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(activity.getPackageName());
            int id = pi.createSession(p);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent i = new Intent(ACTION).setPackage(activity.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
                s.commit(PendingIntent.getBroadcast(activity, id, i, flags).getIntentSender());
            }
            pct = 100; // Android now asks the user to confirm; this app closes while it updates
        } catch (Exception e) {
            busy = false;
            error = e instanceof IOException && e.getMessage() != null && e.getMessage().startsWith("The download")
                    ? e.getMessage() : "The update couldn't be downloaded. Check your internet connection and try again.";
            apk.delete();
        }
        onChange.run();
    }

    private static InputStream open(String url) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "EchoForge-Retro-Gaming");
            c.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "*/*");
            int code = c.getResponseCode();
            if (code / 100 == 3) { url = new URL(new URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
            if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code); }
            return c.getInputStream();
        }
        throw new IOException("Too many redirects");
    }
}
