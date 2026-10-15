package app.forapp.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small user settings. Everything else is in {@link Db}. */
public final class Prefs {
    private final SharedPreferences p;

    private Prefs(Context c) {
        p = c.getApplicationContext().getSharedPreferences("forapp", Context.MODE_PRIVATE);
    }

    public static Prefs of(Context c) {
        return new Prefs(c);
    }

    public boolean enabled() { return p.getBoolean("enabled", true); }
    public void enabled(boolean v) {
        p.edit().putBoolean("enabled", v).apply();
        Trace.i("SETTINGS", "forwarding turned " + (v ? "on" : "off"));
    }

    /** Forward only SMS recognised as deposits (withdrawals, one-time passwords and ads stay on the phone). */
    public boolean depositOnly() { return p.getBoolean("deposit_only", true); }

    /** The first-launch introduction and setup were seen (finished or skipped). */
    public boolean onboarded() { return p.getBoolean("onboarded", false); }
    public void onboarded(boolean v) { p.edit().putBoolean("onboarded", v).apply(); }
    public void depositOnly(boolean v) {
        p.edit().putBoolean("deposit_only", v).apply();
        Trace.i("SETTINGS", "deposits only: " + v);
    }

    public int maxAttempts() { return p.getInt("max_attempts", 10); }
    public void maxAttempts(int v) { p.edit().putInt("max_attempts", v).apply(); }

    public int timeoutSec() { return p.getInt("timeout_sec", 10); }
    public void timeoutSec(int v) { p.edit().putInt("timeout_sec", v).apply(); }

    public int retentionDays() { return p.getInt("retention_days", 90); }
    public void retentionDays(int v) { p.edit().putInt("retention_days", v).apply(); }

    public long lastCleanup() { return p.getLong("last_cleanup", 0); }
    public void lastCleanup(long v) { p.edit().putLong("last_cleanup", v).apply(); }

    private static final int UNKNOWN_MAX = 8;
    private static final Object UNKNOWN_LOCK = new Object();

    /**
     * Senders not set up as any bank that sent an SMS with an amount in it, newest first: {address, time ms}.
     * Only the sender is kept, never the text.
     */
    public List<String[]> unknownSenders() {
        List<String[]> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("unknown_senders", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(new String[]{o.getString("a"), String.valueOf(o.getLong("t"))});
            }
        } catch (Exception ignored) {}
        return l;
    }

    public void noteUnknownSender(String addr, long at) {
        synchronized (UNKNOWN_LOCK) {
            List<String[]> l = without(addr);
            l.add(0, new String[]{addr, String.valueOf(at)});
            saveUnknown(l.subList(0, Math.min(l.size(), UNKNOWN_MAX)));
        }
    }

    public void forgetUnknownSender(String addr) {
        synchronized (UNKNOWN_LOCK) {
            saveUnknown(without(addr));
        }
    }

    private List<String[]> without(String addr) {
        List<String[]> l = unknownSenders();
        String key = SmsParser.canonicalSender(addr);
        l.removeIf(e -> SmsParser.canonicalSender(e[0]).equals(key));
        return l;
    }

    private void saveUnknown(List<String[]> l) {
        JSONArray a = new JSONArray();
        try {
            for (String[] e : l) a.put(new JSONObject().put("a", e[0]).put("t", Long.parseLong(e[1])));
        } catch (Exception ignored) {}
        p.edit().putString("unknown_senders", a.toString()).apply();
    }
}
