package app.forapp.core;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.ApplicationStartInfo;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Telephony;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A plain-text diary of everything the app does and sees: when its process starts and why, how it ended last time,
 * every SMS that reaches it, each decision about it, every send, job and network change, and crashes.
 * One file per day in the app's private storage, kept {@value #KEEP_DAYS} days. Never shown in the app;
 * Settings exports it as one file. Written synchronously, so a line is on disk even if the process is killed right after.
 *
 * Not written: the text of SMS from senders that are not a bank (only sender and length), API keys, headers and
 * full destination addresses (only the host, since a Telegram address holds the bot token).
 */
public final class Trace {
    private Trace() {}

    private static final int KEEP_DAYS = 7;
    /** A runaway loop must not fill the phone; past this a day's file only gets one note. */
    private static final long MAX_FILE_BYTES = 5_000_000;

    private static File dir;
    private static String day = "";
    private static boolean full;
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    // ---------- writing ----------

    public static void i(String tag, String msg) { write(tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        StringWriter s = new StringWriter();
        t.printStackTrace(new PrintWriter(s));
        write(tag, msg + "\n" + s.toString().trim());
    }

    private static synchronized void write(String tag, String msg) {
        if (dir == null) return;
        long now = System.currentTimeMillis();
        String today = DAY.format(new Date(now));
        if (!today.equals(day)) {
            day = today;
            full = false;
            prune(now);
        }
        File f = new File(dir, "trace-" + today + ".log");
        if (full) return;
        String line = STAMP.format(new Date(now)) + "  " + Process.myPid() + "/" + Thread.currentThread().getName()
                + "  " + tag + "  " + msg.replace("\n", "\n    ") + "\n";
        if (f.length() > MAX_FILE_BYTES) {
            full = true;
            line = STAMP.format(new Date(now)) + "  TRACE  file for today is full; nothing more is written until tomorrow\n";
        }
        try (OutputStream out = new FileOutputStream(f, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static void prune(long now) {
        String oldest = DAY.format(new Date(now - (KEEP_DAYS - 1) * 24 * 3600_000L));
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            // "trace-yyyy-MM-dd.log": dates compare as text
            if (n.startsWith("trace-") && n.compareTo("trace-" + oldest) < 0) f.delete();
        }
    }

    // ---------- process start ----------

    /** First thing in App.onCreate: opens the diary, catches crashes, and notes that the process started. */
    public static void init(Context c) {
        File d = new File(c.getFilesDir(), "trace");
        d.mkdirs();
        synchronized (Trace.class) { dir = d; }
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            e("CRASH", "uncaught in thread " + t.getName(), ex);
            if (prev != null) prev.uncaughtException(t, ex);
        });
        i("APP", "process started" + startReason(c));
    }

    /** Android 15+: what woke the process (an SMS broadcast, a job, the launcher …). */
    private static String startReason(Context c) {
        if (Build.VERSION.SDK_INT < 35) return "";
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            List<ApplicationStartInfo> l = am.getHistoricalProcessStartReasons(1);
            if (l.isEmpty()) return "";
            ApplicationStartInfo s = l.get(0);
            String[] reasons = {"alarm", "backup", "boot", "broadcast", "content provider", "job", "launcher",
                    "launcher (recents)", "other", "push", "service", "start activity"};
            int r = s.getReason();
            return " · reason: " + (r >= 0 && r < reasons.length ? reasons[r] : String.valueOf(r));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * After App.onCreate, off the main thread: how the earlier runs ended (Android 11+), then the setup as it is now.
     * Exit records come from the system and are only written once each.
     */
    public static void startup(Context c) {
        logExits(c);
        snapshot(c, "setup at start");
    }

    private static void logExits(Context c) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            SharedPreferences sp = c.getSharedPreferences("trace", Context.MODE_PRIVATE);
            long seen = sp.getLong("exit_seen", 0), newest = seen;
            ActivityManager am = c.getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(null, 0, 20);
            for (int k = l.size() - 1; k >= 0; k--) { // oldest first
                ApplicationExitInfo x = l.get(k);
                if (x.getTimestamp() <= seen) continue;
                newest = Math.max(newest, x.getTimestamp());
                i("APP", "earlier run ended at " + STAMP.format(new Date(x.getTimestamp())) + " · pid " + x.getPid()
                        + " · " + exitReason(x.getReason()) + " · importance " + x.getImportance()
                        + (x.getDescription() == null ? "" : " · " + x.getDescription()));
            }
            sp.edit().putLong("exit_seen", newest).apply();
        } catch (Exception e) {
            e("APP", "could not read exit reasons", e);
        }
    }

    private static String exitReason(int r) {
        String[] names = {"unknown", "exit itself", "signaled", "low memory", "crash", "native crash", "ANR",
                "initialization failure", "permission change", "excessive resource usage", "user requested",
                "user stopped", "dependency died", "other", "freezer", "package state change", "package updated"};
        return r >= 0 && r < names.length ? names[r] : "reason " + r;
    }

    /** The setup and the phone's limits on the app: everything that decides whether an SMS can reach it. */
    public static void snapshot(Context c, String title) {
        StringBuilder s = new StringBuilder(title);
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            s.append("\napp ").append(pi.versionName).append(" (").append(Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode).append(")")
                    .append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
                    .append(" · ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                    .append(" · times are ").append(java.util.TimeZone.getDefault().getID());
        } catch (Exception ignored) {}
        Prefs p = Prefs.of(c);
        s.append("\nforwarding ").append(p.enabled() ? "on" : "OFF")
                .append(" · deposits only ").append(p.depositOnly())
                .append(" · attempts ").append(p.maxAttempts()).append(" · timeout ").append(p.timeoutSec()).append("s");
        s.append("\npermissions: RECEIVE_SMS ").append(granted(c, Manifest.permission.RECEIVE_SMS))
                .append(" · READ_SMS ").append(granted(c, Manifest.permission.READ_SMS));
        if (Build.VERSION.SDK_INT >= 33) s.append(" · notifications ").append(granted(c, Manifest.permission.POST_NOTIFICATIONS));
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null) s.append("\nbattery: optimization ignored ").append(pm.isIgnoringBatteryOptimizations(c.getPackageName()))
                .append(" · power saver ").append(pm.isPowerSaveMode())
                .append(" · idle ").append(pm.isDeviceIdleMode());
        if (Build.VERSION.SDK_INT >= 28) {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            UsageStatsManager us = c.getSystemService(UsageStatsManager.class);
            if (am != null) s.append(" · background restricted ").append(am.isBackgroundRestricted());
            if (us != null) s.append(" · standby bucket ").append(bucket(us.getAppStandbyBucket()));
        }
        s.append("\ndefault SMS app: ").append(Telephony.Sms.getDefaultSmsPackage(c));
        s.append(" · online ").append(Forwarder.online(c));
        try {
            Db db = Db.get(c);
            for (Db.Bank b : db.banks())
                if (!b.senderList().isEmpty()) s.append("\nbank ").append(b.name).append(": ").append(String.join(", ", b.senderList()));
            for (Db.Dest d : db.dests())
                s.append("\ndestination ").append(d.name).append(d.enabled ? "" : " (off)").append(": ")
                        .append(d.telegram() ? "telegram" : d.method()).append(" ").append(host(d.url));
            s.append("\nwaiting to send: ").append(db.countPending());
        } catch (Exception ex) {
            s.append("\ndatabase: ").append(ex);
        }
        i("SETUP", s.toString());
    }

    private static String bucket(int b) {
        switch (b) {
            case 5: return "exempted";
            case 10: return "active";
            case 20: return "working set";
            case 30: return "frequent";
            case 40: return "rare";
            case 45: return "restricted";
            default: return String.valueOf(b);
        }
    }

    private static String granted(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED ? "yes" : "NO";
    }

    /** Only the host of a destination address: the path of a Telegram address holds the bot token. */
    public static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "(no host)" : h;
        } catch (Exception e) {
            return "(bad address)";
        }
    }

    // ---------- export ----------

    /** Every kept day, oldest first, after the setup as it is now. */
    public static void export(Context c, OutputStream out) throws Exception {
        snapshot(c, "setup at export");
        File[] files;
        synchronized (Trace.class) {
            files = dir == null ? null : dir.listFiles();
        }
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        byte[] buf = new byte[8192];
        for (File f : files) {
            out.write(("===== " + f.getName() + " =====\n").getBytes(StandardCharsets.UTF_8));
            try (InputStream in = new FileInputStream(f)) {
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
        }
    }
}
