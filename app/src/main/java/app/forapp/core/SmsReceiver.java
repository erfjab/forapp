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
 * SMS from senders that are not set up as a bank are ignored and never stored; only the sender of one
 * that mentions an amount is remembered, so a bank's new number can be added.
 */
public final class SmsReceiver extends BroadcastReceiver {

    /** The system allows about 10 seconds; leave headroom for the database work. */
    private static final long SEND_BUDGET_MS = 8_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            Trace.i("SMS", "broadcast ignored: " + intent.getAction());
            return;
        }
        Context c = context.getApplicationContext();
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        int n = parts == null ? 0 : parts.length;
        if (!Prefs.of(c).enabled()) {
            Trace.i("SMS", "SMS broadcast with " + n + " part(s), not read: forwarding is off");
            return;
        }
        if (n == 0) {
            Trace.i("SMS", "SMS broadcast with no readable parts");
            return;
        }

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
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : times.entrySet())
            Trace.i("SMS", "received from " + e.getKey() + " · " + n + " part(s) · " + bodies.get(e.getKey()).length()
                    + " chars · sent " + (now - e.getValue()) / 1000 + "s ago by the network's clock");

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                List<Long> ids = new ArrayList<>();
                for (Map.Entry<String, StringBuilder> e : bodies.entrySet()) {
                    long id = store(c, e.getKey(), e.getValue().toString(), times.get(e.getKey()));
                    if (id > 0) ids.add(id);
                }
                if (!ids.isEmpty()) Forwarder.sendNow(c, ids, SEND_BUDGET_MS);
            } catch (Exception e) {
                // Never crash inside the SMS broadcast; the job retries anything that was stored.
                Trace.e("SMS", "handling failed; the background job will retry what was stored", e);
                Forwarder.schedule(c);
            } finally {
                pending.finish();
                Trace.i("SMS", "done with broadcast after " + (System.currentTimeMillis() - now) + "ms");
            }
        }, "forapp-sms").start();
    }

    /** Stores one SMS with its deliveries. Returns the row id, or -1 when it is ignored or a duplicate. */
    static long store(Context c, String sender, String body, long sentAt) {
        return store(c, sender, body, sentAt, System.currentTimeMillis());
    }

    /** Same, for an SMS found later in the inbox: {@code receivedAt} is when the phone actually got it. */
    static long store(Context c, String sender, String body, long sentAt, long receivedAt) {
        Db db = Db.get(c);
        Db.Bank bank = db.bankForSender(sender);
        SmsParser.Result p = SmsParser.parse(body);
        if (bank == null) {
            Trace.i("SMS", "from " + sender + ": not a bank sender, ignored (canonical \"" + SmsParser.canonicalSender(sender)
                    + "\") · amount found " + (p.amount != null));
            // Not stored, but if it mentions an amount it may be a bank's new number: show the sender on the banks screen.
            if (p.amount != null) {
                Prefs.of(c).noteUnknownSender(sender, System.currentTimeMillis());
                Forwarder.changed();
            }
            return -1;
        }
        Trace.i("SMS", "from " + sender + ": bank " + bank.name + " · parsed amount " + p.amount + " " + p.unit
                + " · " + (p.deposit == null ? "deposit/withdrawal unclear" : p.deposit ? "deposit" : "withdrawal") + "\ntext: " + body);
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
        m.at = receivedAt;
        m.skipped = Prefs.of(c).depositOnly() && !Boolean.TRUE.equals(p.deposit);
        List<Db.Dest> to = db.destsForBank(bank.id);
        long id = db.insertMessage(m, to);
        if (id < 0) Trace.i("SMS", "already stored (same sender, time and text), not stored again");
        else if (m.skipped) Trace.i("SMS", "stored as #" + id + ", not sent: not a deposit and \"deposits only\" is on");
        else Trace.i("SMS", "stored as #" + id + " · " + to.size() + " destination(s)" + (to.isEmpty() ? ", so nothing to send" : ""));
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
