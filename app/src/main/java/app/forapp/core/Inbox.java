package app.forapp.core;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.List;

/**
 * Catches up on bank SMS that arrived while ForApp was not running.
 *
 * MIUI and others kill the app (low memory, lock-screen clean, swipe from recents) and, without "autostart",
 * do not wake it for the SMS broadcast either. The SMS still lands in the phone's inbox, so whenever ForApp runs
 * (app start, the send job, a periodic job) it reads the inbox from where it last looked and stores what it missed
 * through the normal path. Nothing is stored twice: the store's fingerprint and a same-text check catch repeats.
 */
public final class Inbox {
    private Inbox() {}

    static final int PERIODIC_JOB_ID = 2;
    /** Fresh SMS are the live receiver's; looking only at older ones keeps the two from racing. */
    private static final long SETTLE_MS = 60_000;
    /** An SMS already stored with the same text this close in time is the same SMS. */
    private static final long SAME_SMS_WINDOW_MS = 10 * 60_000;
    private static final int MAX_PER_RUN = 300;
    private static final Object LOCK = new Object();

    /** Stores bank SMS the app missed and returns the ids that have something to send. Call off the main thread. */
    public static List<Long> catchUp(Context c, String why) {
        List<Long> ids = new ArrayList<>();
        synchronized (LOCK) {
            Prefs p = Prefs.of(c);
            long now = System.currentTimeMillis(), seen = p.inboxSeen();
            if (seen == 0) {
                // First run with catch-up: start from now rather than replaying the whole inbox.
                p.inboxSeen(now);
                Trace.i("INBOX", "catch-up starts now; earlier SMS are not replayed");
                return ids;
            }
            if (!p.enabled()) {
                p.inboxSeen(now - SETTLE_MS); // what arrives while forwarding is off is not sent later
                return ids;
            }
            if (c.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                Trace.i("INBOX", "catch-up skipped (" + why + "): no permission to read SMS");
                return ids;
            }
            long until = now - SETTLE_MS;
            if (until <= seen) return ids;

            Db db = Db.get(c);
            int read = 0, fromBanks = 0, missed = 0;
            long last = seen;
            try (Cursor cur = c.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT},
                    Telephony.Sms.DATE + ">? AND " + Telephony.Sms.DATE + "<=?",
                    new String[]{String.valueOf(seen), String.valueOf(until)}, Telephony.Sms.DATE + " ASC")) {
                if (cur == null) {
                    Trace.i("INBOX", "catch-up (" + why + "): the inbox could not be read");
                    return ids;
                }
                while (cur.moveToNext() && read < MAX_PER_RUN) {
                    read++;
                    String from = cur.getString(0), body = cur.getString(1);
                    long date = cur.getLong(2), sent = cur.getLong(3);
                    last = Math.max(last, date);
                    if (from == null || body == null || db.bankForSender(from) == null) continue;
                    fromBanks++;
                    if (db.hasSameText(body, date, SAME_SMS_WINDOW_MS)) continue;
                    missed++;
                    Trace.i("INBOX", "missed SMS from " + from + " received " + Trace.time(date) + "; storing it now");
                    long id = SmsReceiver.store(c, from, body, sent > 0 ? sent : date, date);
                    if (id > 0) ids.add(id);
                }
                if (read < MAX_PER_RUN) last = Math.max(last, until);
            } catch (Exception e) {
                Trace.e("INBOX", "catch-up (" + why + ") failed", e);
                return ids;
            }
            p.inboxSeen(last);
            if (read > 0 || missed > 0)
                Trace.i("INBOX", "catch-up (" + why + "): " + read + " inbox SMS checked · " + fromBanks + " from banks · " + missed + " missed");
        }
        return ids;
    }

    /** Catch up, then send what was found (or leave it to the send job). */
    public static void catchUpAndSend(Context c, String why) {
        List<Long> ids = catchUp(c, why);
        if (!ids.isEmpty()) Forwarder.sendNow(c, ids, 20_000);
    }

    /** A job every 15 minutes (the shortest Android allows), so missed SMS are found even if nobody opens the app. */
    public static void schedulePeriodic(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(PERIODIC_JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(PERIODIC_JOB_ID, new ComponentName(c, SendJob.class))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build());
        Trace.i("INBOX", "periodic catch-up job scheduled every 15 minutes");
    }
}
