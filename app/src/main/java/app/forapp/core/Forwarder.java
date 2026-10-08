package app.forapp.core;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sends stored SMS to webhooks.
 *
 * Two paths share one queue in {@link Db}: the SMS receiver sends new messages immediately (fast path),
 * and {@link SendJob} picks up whatever is left when the network is back (reliable path).
 * Every request carries an Idempotency-Key, so a retry never creates a second payment on the server.
 */
public final class Forwarder {
    private Forwarder() {}

    private static final int JOB_ID = 1;
    private static final String CHANNEL = "failures";
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private static Handler main;
    private static Context appCtx; // set once in App.onCreate (watchNetwork), used to redraw the home-screen widget
    private static final java.util.concurrent.ExecutorService widgetIo = java.util.concurrent.Executors.newSingleThreadExecutor();

    // ---------- UI refresh ----------

    public static void listen(Runnable r) { listeners.add(r); }
    public static void unlisten(Runnable r) { listeners.remove(r); }

    public static synchronized void changed() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        main.post(() -> { for (Runnable r : listeners) r.run(); });
        if (appCtx != null) widgetIo.execute(() -> app.forapp.ui.Widget.refresh(appCtx));
    }

    // ---------- sending ----------

    public static final class Result {
        public final int code;      // HTTP status; 0 when the server could not be reached, -1 when the address itself is wrong
        public final String error;  // null on success
        public final long ms;

        Result(int code, String error, long ms) {
            this.code = code;
            this.error = error;
            this.ms = ms;
        }

        public boolean ok() { return code >= 200 && code < 300; }

        /** Server errors, rate limits and network problems are retried; other 4xx mean the request itself is wrong. */
        public boolean retryable() {
            return code == 0 || code == 408 || code == 425 || code == 429 || code >= 500;
        }
    }

    /** Fast path: send freshly stored messages right now, in parallel, within the receiver's time budget. */
    public static void sendNow(Context c, List<Long> msgIds, long budgetMs) {
        Db db = Db.get(c);
        List<Db.Delivery> due = db.claimDue(System.currentTimeMillis(), 20, msgIds);
        if (!due.isEmpty()) {
            CountDownLatch done = new CountDownLatch(due.size());
            for (Db.Delivery d : due) {
                new Thread(() -> {
                    try { deliver(c, d); } finally { done.countDown(); }
                }, "forapp-send").start();
            }
            try { done.await(budgetMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        }
        schedule(c);
        changed();
    }

    /** Reliable path, run by {@link SendJob}: drain everything that is due. */
    public static void sendDue(Context c, long deadline) {
        Db db = Db.get(c);
        while (System.currentTimeMillis() < deadline) {
            List<Db.Delivery> due = db.claimDue(System.currentTimeMillis(), 10, null);
            if (due.isEmpty()) break;
            for (Db.Delivery d : due) deliver(c, d);
            changed();
            if (!online(c)) break;
        }
        cleanup(c);
        schedule(c);
        changed();
    }

    private static void deliver(Context c, Db.Delivery d) {
        Db db = Db.get(c);
        Prefs prefs = Prefs.of(c);
        Db.Dest dest = db.dest(d.destId);
        Db.Msg m = db.message(d.msgId);
        if (dest == null || m == null) {
            db.finishDelivery(d.id, Db.FAILED, d.attempts, 0, null, "مقصد حذف شده");
            return;
        }
        Result r = post(dest, payload(c, m, d.attempts + 1, false), prefs.timeoutSec());
        if (r.code > 0) db.setDestLast(dest.id, r.code, r.ms);
        long now = System.currentTimeMillis();
        if (r.ok()) {
            db.finishDelivery(d.id, Db.SENT, d.attempts + 1, now, r.code, null);
        } else if (r.code == 0) {
            // No answer at all: no internet, VPN off or the address is filtered. That is not the server's fault,
            // so no attempt is used up; retry every minute, and right away when the network changes (watchNetwork).
            db.finishDelivery(d.id, Db.PENDING, d.attempts, online(c) ? now + UNREACHABLE_RETRY_MS : now, null, reason(c, r));
        } else if (r.retryable() && d.attempts + 1 < prefs.maxAttempts()) {
            db.finishDelivery(d.id, Db.PENDING, d.attempts + 1, now + backoffMs(d.attempts + 1), r.code > 0 ? r.code : null, r.error);
        } else {
            db.finishDelivery(d.id, Db.FAILED, d.attempts + 1, now, r.code > 0 ? r.code : null, r.error);
            notifyFailure(c, dest, m, r);
        }
    }

    /** How often a delivery that could not reach its server at all is tried again. */
    public static final long UNREACHABLE_RETRY_MS = 60_000L;

    /** Error text for the log; "no connection" problems read differently from server errors. */
    private static String reason(Context c, Result r) {
        if (r.code != 0) return r.error;
        if (!online(c)) return "بدون اینترنت";
        return "بدون اتصال به مقصد" + (r.error == null ? "" : " · " + r.error);
    }

    /**
     * Manual "send now" from the details sheet: one try right away, outside the automatic schedule.
     * A failure leaves the delivery's status, attempts and next try as they were. Returns null if it is being sent already.
     */
    public static Result sendOne(Context c, long deliveryId) {
        Db db = Db.get(c);
        Db.Delivery d = db.claimOne(deliveryId, System.currentTimeMillis());
        if (d == null) return null;
        Db.Dest dest = db.dest(d.destId);
        Db.Msg m = db.message(d.msgId);
        Result r;
        if (dest == null || m == null) {
            r = new Result(0, "مقصد حذف شده", 0);
            db.finishDelivery(d.id, Db.FAILED, d.attempts, 0, null, r.error);
        } else {
            r = post(dest, payload(c, m, d.attempts + 1, false), Prefs.of(c).timeoutSec());
            if (r.code > 0) db.setDestLast(dest.id, r.code, r.ms);
            if (r.ok()) db.finishDelivery(d.id, Db.SENT, d.attempts + 1, System.currentTimeMillis(), r.code, null);
            else db.finishDelivery(d.id, d.status == Db.SENDING ? Db.PENDING : d.status, d.attempts, d.nextAt,
                    r.code > 0 ? r.code : null, reason(c, r));
        }
        schedule(c);
        changed();
        return r;
    }

    /** When the phone gets a new default network (VPN turned on, Wi-Fi back), retry what could not reach its server. */
    public static void watchNetwork(Context c) {
        appCtx = c.getApplicationContext();
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        Context app = c.getApplicationContext();
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                new Thread(() -> {
                    if (Db.get(app).wakeUnreachable(System.currentTimeMillis()) > 0) {
                        schedule(app);
                        changed();
                    }
                }, "forapp-net").start();
            }
        });
    }

    /** 15s, 30s, 1m, 2m … capped at 30 minutes. */
    public static long backoffMs(int attempt) {
        long ms = 15_000L << Math.min(attempt - 1, 10);
        return Math.min(ms, 30 * 60_000L);
    }

    public static JSONObject payload(Context c, Db.Msg m, int attempt, boolean test) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", m.uid);
            o.put("test", test);
            o.put("sender", m.sender);
            o.put("bank", m.bank == null ? JSONObject.NULL : m.bank);
            o.put("body", m.body);
            o.put("amount", m.amount == null ? JSONObject.NULL : m.amount);
            o.put("code", m.amount == null ? JSONObject.NULL : SmsParser.code(m.amount));
            o.put("unit", m.unit == null ? JSONObject.NULL : m.unit);
            o.put("is_deposit", m.deposit == null ? JSONObject.NULL : m.deposit);
            o.put("received_at", iso(m.at));
            o.put("received_at_ms", m.at);
            o.put("attempt", attempt);
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        } catch (JSONException ignored) {}
        return o;
    }

    public static Result post(Db.Dest d, JSONObject body, int timeoutSec) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(d.url.trim()).openConnection();
            h.setConnectTimeout(Math.min(timeoutSec, 8) * 1000);
            h.setReadTimeout(timeoutSec * 1000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setUseCaches(false);
            h.setInstanceFollowRedirects(false);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            h.setRequestProperty("Accept", "application/json");
            h.setRequestProperty("User-Agent", "ForApp/1.0 (Android " + Build.VERSION.RELEASE + ")");
            h.setRequestProperty("X-API-Key", d.apiKey);
            h.setRequestProperty("Idempotency-Key", body.optString("id"));
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            h.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = h.getOutputStream()) { out.write(bytes); }
            int code = h.getResponseCode();
            String err = null;
            if (code < 200 || code >= 300) err = "HTTP " + code + snippet(h);
            else drain(h);
            return new Result(code, err, System.currentTimeMillis() - t0);
        } catch (java.net.SocketTimeoutException e) {
            return new Result(0, "مهلت پاسخ سرور تمام شد", System.currentTimeMillis() - t0);
        } catch (java.net.UnknownHostException e) {
            return new Result(0, "آدرس سرور پیدا نشد", System.currentTimeMillis() - t0);
        } catch (java.net.MalformedURLException | ClassCastException e) { // ClassCast: not an http(s) address
            return new Result(-1, "آدرس وبهوک درست نیست", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return new Result(0, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    System.currentTimeMillis() - t0);
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /** Sends a clearly marked test payload; used by the "ارسال آزمایشی" buttons. */
    public static Result test(Context c, Db.Dest d) {
        Db.Msg m = new Db.Msg();
        m.uid = "test-" + java.util.UUID.randomUUID();
        m.sender = "ForApp";
        m.bank = "آزمایشی";
        m.body = "پیامک آزمایشی ForApp\nواریز: 1,000,123 ریال";
        m.amount = 1_000_123L;
        m.unit = "rial";
        m.deposit = true;
        m.at = System.currentTimeMillis();
        Result r = post(d, payload(c, m, 1, true), Prefs.of(c).timeoutSec());
        if (d.id > 0 && r.code > 0) Db.get(c).setDestLast(d.id, r.code, r.ms);
        return r;
    }

    private static String snippet(HttpURLConnection h) {
        try (InputStream in = h.getErrorStream() != null ? h.getErrorStream() : h.getInputStream()) {
            if (in == null) return "";
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n = in.read(buf);
            if (n > 0) b.write(buf, 0, n);
            String s = b.toString("UTF-8").trim().replace('\n', ' ');
            return s.isEmpty() ? "" : " · " + (s.length() > 160 ? s.substring(0, 160) + "…" : s);
        } catch (Exception e) {
            return "";
        }
    }

    private static void drain(HttpURLConnection h) {
        try (InputStream in = h.getInputStream()) {
            byte[] buf = new byte[512];
            while (in.read(buf) > 0) { /* let the connection be reused */ }
        } catch (Exception ignored) {}
    }

    private static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(ms));
    }

    public static boolean online(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return true;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ---------- scheduling ----------

    /** Makes sure the background job runs when the next delivery is due. No job is kept when the queue is empty. */
    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        long next = Db.get(c).nextDueAt();
        if (next < 0) {
            js.cancel(JOB_ID);
            return;
        }
        long delay = Math.max(0, next - System.currentTimeMillis());
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, SendJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true);
        if (delay > 0) b.setMinimumLatency(delay);
        try {
            if (delay == 0 && Build.VERSION.SDK_INT >= 31) b.setExpedited(true);
            js.schedule(b.build());
        } catch (Exception e) {
            // Expedited quota used up: fall back to a regular job.
            b = new JobInfo.Builder(JOB_ID, new ComponentName(c, SendJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true);
            if (delay > 0) b.setMinimumLatency(delay);
            js.schedule(b.build());
        }
    }

    /** Drops log entries older than the retention setting, at most once a day. */
    public static void cleanup(Context c) {
        Prefs p = Prefs.of(c);
        long now = System.currentTimeMillis();
        if (now - p.lastCleanup() < 24 * 3600_000L) return;
        Db.get(c).deleteOlderThan(now - p.retentionDays() * 24 * 3600_000L);
        p.lastCleanup(now);
    }

    // ---------- notifications ----------

    public static void createChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "ارسال ناموفق", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("وقتی یک پیامک بعد از همه‌ی تلاش‌ها به سرور نرسید");
        nm.createNotificationChannel(ch);
    }

    private static void notifyFailure(Context c, Db.Dest d, Db.Msg m, Result r) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String amount = m.amount == null ? "" : Fa.group(m.amount) + " · ";
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(app.forapp.R.drawable.ic_notify)
                .setContentTitle("به " + d.name + " نرسید")
                .setContentText(amount + (m.bank == null ? m.sender : m.bank) + " · " + (r.error == null ? "" : r.error))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (m.id % Integer.MAX_VALUE), n);
    }

    /** For the main screen: what is wrong with the setup right now, most important first. */
    public static List<String> problems(Context c) {
        List<String> l = new ArrayList<>();
        if (c.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) l.add("sms");
        if (!Prefs.of(c).enabled()) l.add("off");
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(c.getPackageName())) l.add("battery");
        Db db = Db.get(c);
        boolean anyDest = false;
        for (Db.Dest d : db.dests()) if (d.enabled) anyDest = true;
        if (!anyDest) l.add("dest");
        boolean anySender = false;
        for (Db.Bank b : db.banks()) if (!b.senderList().isEmpty()) anySender = true;
        if (!anySender) l.add("bank");
        return l;
    }
}
