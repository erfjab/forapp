package app.forapp.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Which transactions a destination takes: an amount range, hours of the day and days of the week.
 * A transaction that does not fit gets a "filtered" delivery with the reason, and can still be sent by hand.
 * Pure Java so it can be unit tested.
 */
public final class Filters {
    private Filters() {}

    /** Day bits start at Saturday: bit 0 = Saturday … bit 6 = Friday. */
    public static final int ALL_DAYS = 0x7F;
    public static final String[] DAY_SHORT = {"ش", "ی", "د", "س", "چ", "پ", "ج"};
    public static final String[] DAY_NAMES = {"شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه"};
    /** Hours used when the hours filter is turned on for the first time: 08:00 to 22:00. */
    public static final int DEFAULT_FROM = 8 * 60, DEFAULT_TO = 22 * 60;

    /** Saturday-first index (0–6) of the day {@code ms} falls on. */
    public static int dayIndex(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return c.get(Calendar.DAY_OF_WEEK) % 7; // Saturday (7) → 0, Sunday (1) → 1 … Friday (6) → 6
    }

    /** Minute of the day (0–1439). */
    public static int minuteOfDay(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** Whether minute {@code m} is inside [from, to); a range past midnight (22 → 6) wraps, and from == to is the whole day. */
    public static boolean inHours(int m, int from, int to) {
        if (from == to) return true;
        if (from < to) return m >= from && m < to;
        return m >= from || m < to;
    }

    public static boolean amountActive(Db.Dest d) {
        return d.amountOn && (d.minAmount != null || d.maxAmount != null);
    }

    public static boolean hoursActive(Db.Dest d) {
        return d.hoursOn && d.hourFrom != d.hourTo;
    }

    public static boolean daysActive(Db.Dest d) {
        return (d.days & ALL_DAYS) != ALL_DAYS;
    }

    public static boolean any(Db.Dest d) {
        return amountActive(d) || hoursActive(d) || daysActive(d);
    }

    /** Why this destination does not take a transaction of {@code amount} received at {@code at}, or null when it does. */
    public static String reject(Db.Dest d, Long amount, long at, TimeZone tz) {
        if (amountActive(d)) {
            if (amount == null) return "مبلغ در پیامک پیدا نشد و این مقصد محدودیت مبلغ دارد";
            if (d.minAmount != null && amount < d.minAmount) return "کمتر از حداقل مبلغ این مقصد (" + money(d.minAmount) + " ریال)";
            if (d.maxAmount != null && amount > d.maxAmount) return "بیشتر از حداکثر مبلغ این مقصد (" + money(d.maxAmount) + " ریال)";
        }
        if (daysActive(d)) {
            int day = dayIndex(at, tz);
            if ((d.days & (1 << day)) == 0) return DAY_NAMES[day] + " جزو روزهای این مقصد نیست";
        }
        if (hoursActive(d) && !inHours(minuteOfDay(at, tz), d.hourFrom, d.hourTo))
            return "بیرون از ساعت " + hours(d.hourFrom, d.hourTo) + " این مقصد";
        return null;
    }

    // ---------- words ----------

    /** 1000000 → "۱ میلیون", 2500000 → "۲٫۵ میلیون", 1234567 → "۱٬۲۳۴٬۵۶۷". */
    public static String money(long n) {
        long[] steps = {1_000_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L};
        String[] names = {"هزار میلیارد", "میلیارد", "میلیون", "هزار"};
        for (int i = 0; i < steps.length; i++) {
            long s = steps[i];
            if (n < s) continue;
            if (n % s == 0) return Fa.group(n / s) + " " + names[i];
            if ((n * 10) % s == 0 && n / s < 1000) return Fa.d(n / s) + "٫" + Fa.d(n % s * 10 / s) + " " + names[i];
            break;
        }
        return Fa.group(n);
    }

    /** "۸:۰۰" style clock, with the minutes always shown. */
    public static String clock(int minute) {
        int m = ((minute % 1440) + 1440) % 1440;
        return Fa.d(String.format(Locale.US, "%02d:%02d", m / 60, m % 60));
    }

    /** "۸ تا ۲۲", or "۸:۳۰ تا ۲۲" when minutes are set. */
    public static String hours(int from, int to) {
        return shortClock(from) + " تا " + shortClock(to);
    }

    private static String shortClock(int minute) {
        int m = ((minute % 1440) + 1440) % 1440;
        return m % 60 == 0 ? Fa.d(m / 60) : Fa.d(String.format(Locale.US, "%d:%02d", m / 60, m % 60));
    }

    /** "از ۱ میلیون ریال به بالا", "تا ۵۰ میلیون ریال", "از ۱ میلیون تا ۵۰ میلیون ریال", or null for any amount. */
    public static String amountText(Long min, Long max) {
        if (min != null && max != null) return "از " + money(min) + " تا " + money(max) + " ریال";
        if (min != null) return "از " + money(min) + " ریال به بالا";
        if (max != null) return "تا " + money(max) + " ریال";
        return null;
    }

    /** "شنبه تا پنج‌شنبه", "شنبه، دوشنبه و چهارشنبه", "هیچ روزی", or null for every day. */
    public static String daysText(int days) {
        days &= ALL_DAYS;
        if (days == ALL_DAYS) return null;
        if (days == 0) return "هیچ روزی";
        List<String> runs = new ArrayList<>();
        int i = 0;
        while (i < 7) {
            if ((days & (1 << i)) == 0) { i++; continue; }
            int j = i;
            while (j + 1 < 7 && (days & (1 << (j + 1))) != 0) j++;
            if (j - i >= 2) runs.add(DAY_NAMES[i] + " تا " + DAY_NAMES[j]);
            else for (int k = i; k <= j; k++) runs.add(DAY_NAMES[k]);
            i = j + 1;
        }
        if (runs.size() == 1) return runs.get(0);
        return String.join("، ", runs.subList(0, runs.size() - 1)) + " و " + runs.get(runs.size() - 1);
    }

    /** One line with every active filter, like "از ۱ میلیون ریال به بالا · ساعت ۸ تا ۲۲ · شنبه تا پنج‌شنبه"; null when none. */
    public static String summary(Db.Dest d) {
        List<String> parts = new ArrayList<>();
        if (amountActive(d)) parts.add(amountText(d.minAmount, d.maxAmount));
        if (hoursActive(d)) parts.add("ساعت " + hours(d.hourFrom, d.hourTo));
        String days = daysText(d.days);
        if (days != null) parts.add(days);
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }
}
