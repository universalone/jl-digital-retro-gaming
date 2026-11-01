"""One-off: adds "Download my games" (game pack from a cloud share link) to the app's MainActivity."""
import sys

p = sys.argv[1]
s = open(p, encoding='utf-8').read()


def sub(old, new):
    global s
    if s.count(old) != 1:
        sys.exit('not found once: ' + old[:70])
    s = s.replace(old, new)


sub('''                    case "/api/action": return action(body.optString("name")).toString();''',
    '''                    case "/api/action": return action(body.optString("name"), body.optString("value")).toString();''')

sub('''                .put("root", root().getPath())''',
    '''                .put("root", root().getPath())
                .put("gamesUrl", !prefs.getString("gamesUrl", "").isEmpty())
                .put("downloading", downloading)''')

sub('''    private JSONObject action(String name) throws JSONException {
        switch (name) {''', '''    private JSONObject action(String name, String value) throws JSONException {
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
            }''')

sub('''    // ---- Cores: downloaded on demand''', '''    // ---- Game pack: a zip of the JLRetro folder (export_phone.py --pack), shared from the cloud ----

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
                    String name = e.getName().replace('\\\\', '/');
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
            if (!sawLibrary) error = "That link isn't a JL game pack (no library.json inside).";
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

    // ---- Cores: downloaded on demand''')

open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
