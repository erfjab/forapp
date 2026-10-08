package app.forapp.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wakes up only when an SMS arrives; there is no service running in the background.
 * SMS from senders that are not set up as a bank are ignored and never stored.
 */
public final class SmsReceiver extends BroadcastReceiver {

    /** The system allows about 10 seconds; leave headroom for the database work. */
    private static final long SEND_BUDGET_MS = 8_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        Context c = context.getApplicationContext();
        if (!Prefs.of(c).enabled()) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;

        // A long SMS arrives in parts; join them per sender.
        Map<String, StringBuilder> bodies = new LinkedHashMap<>();
        Map<String, Long> times = new LinkedHashMap<>();
        for (SmsMessage p : parts) {
            if (p == null) continue;
            String from = p.getDisplayOriginatingAddress();
            if (from == null) from = "";
            StringBuilder b = bodies.get(from);
            if (b == null) {
                bodies.put(from, b = new StringBuilder());
                times.put(from, p.getTimestampMillis());
            }
            String t = p.getDisplayMessageBody();
            if (t != null) b.append(t);
        }

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                List<Long> ids = new ArrayList<>();
                for (Map.Entry<String, StringBuilder> e : bodies.entrySet()) {
                    long id = store(c, e.getKey(), e.getValue().toString(), times.get(e.getKey()));
                    if (id > 0) ids.add(id);
                }
                if (!ids.isEmpty()) Forwarder.sendNow(c, ids, SEND_BUDGET_MS);
            } catch (Exception ignored) {
                // Never crash inside the SMS broadcast; the job retries anything that was stored.
                Forwarder.schedule(c);
            } finally {
                pending.finish();
            }
        }, "forapp-sms").start();
    }

    /** Stores one SMS with its deliveries. Returns the row id, or -1 when it is ignored or a duplicate. */
    static long store(Context c, String sender, String body, long sentAt) {
        Db db = Db.get(c);
        Db.Bank bank = db.bankForSender(sender);
        if (bank == null) return -1;
        SmsParser.Result p = SmsParser.parse(body);
        Db.Msg m = new Db.Msg();
        m.uid = UUID.randomUUID().toString();
        m.fp = fingerprint(sender, body, sentAt);
        m.sender = sender;
        m.bankId = bank.id;
        m.bank = bank.name;
        m.body = body;
        m.amount = p.amount;
        m.unit = p.unit;
        m.deposit = p.deposit;
        m.at = System.currentTimeMillis();
        m.skipped = Prefs.of(c).depositOnly() && !Boolean.TRUE.equals(p.deposit);
        long id = db.insertMessage(m, db.destsForBank(bank.id));
        if (id > 0 && m.skipped) Forwarder.changed();
        return m.skipped ? -1 : id;
    }

    private static String fingerprint(String sender, String body, long sentAt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((sender + "\n" + sentAt + "\n" + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 16; i++) s.append(String.format("%02x", h[i]));
            return s.toString();
        } catch (Exception e) {
            return sender + sentAt + body.hashCode();
        }
    }
}
