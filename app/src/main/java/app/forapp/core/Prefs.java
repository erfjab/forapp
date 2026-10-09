package app.forapp.core;

import android.content.Context;
import android.content.SharedPreferences;

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
    public void enabled(boolean v) { p.edit().putBoolean("enabled", v).apply(); }

    /** Forward only SMS recognised as deposits (withdrawals, one-time passwords and ads stay on the phone). */
    public boolean depositOnly() { return p.getBoolean("deposit_only", true); }

    /** The first-launch introduction and setup were seen (finished or skipped). */
    public boolean onboarded() { return p.getBoolean("onboarded", false); }
    public void onboarded(boolean v) { p.edit().putBoolean("onboarded", v).apply(); }
    public void depositOnly(boolean v) { p.edit().putBoolean("deposit_only", v).apply(); }

    public int maxAttempts() { return p.getInt("max_attempts", 10); }
    public void maxAttempts(int v) { p.edit().putInt("max_attempts", v).apply(); }

    public int timeoutSec() { return p.getInt("timeout_sec", 10); }
    public void timeoutSec(int v) { p.edit().putInt("timeout_sec", v).apply(); }

    public int retentionDays() { return p.getInt("retention_days", 90); }
    public void retentionDays(int v) { p.edit().putInt("retention_days", v).apply(); }

    public long lastCleanup() { return p.getLong("last_cleanup", 0); }
    public void lastCleanup(long v) { p.edit().putLong("last_cleanup", v).apply(); }
}
