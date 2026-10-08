package app.forapp.core;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Manual update: every build of master is published as a GitHub release named v1.0.&lt;versionCode&gt;.
 * The app reads the latest one, downloads its APK and hands it to the system installer.
 */
public final class Updater {
    private Updater() {}

    private static final String LATEST = "https://api.github.com/repos/erfjab/forapp/releases/latest";

    public static final class Release {
        public int code;
        public String name, notes, url;
        public long size;
    }

    public interface Progress { void on(long done, long total); }

    /** The latest published release, or null if there is none yet. Throws on network errors. */
    public static Release latest() throws Exception {
        HttpURLConnection h = open(LATEST);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = h.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            JSONObject o = new JSONObject(read(h.getInputStream()));
            String tag = o.getString("tag_name");
            Release r = new Release();
            r.code = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
            r.name = tag.startsWith("v") ? tag.substring(1) : tag;
            r.notes = o.optString("body", "").trim();
            JSONArray assets = o.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.getString("name").endsWith(".apk")) {
                    r.url = a.getString("browser_download_url");
                    r.size = a.optLong("size");
                }
            }
            return r.url == null ? null : r;
        } finally {
            h.disconnect();
        }
    }

    public static File download(Context c, Release r, Progress p) throws Exception {
        File f = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection h = open(r.url);
        try {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : r.size, done = 0;
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    p.on(done, total);
                }
            }
            return f;
        } finally {
            h.disconnect();
        }
    }

    /** Android shows its own confirmation (see {@link InstallReceiver}); the app restarts as the new version. */
    public static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("forapp.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            // Mutable: the installer adds the status and the confirmation intent to it.
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, InstallReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(done.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(20_000);
        h.setRequestProperty("User-Agent", "ForApp");
        return h;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
