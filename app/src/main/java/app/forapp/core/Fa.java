package app.forapp.core;

import java.util.Calendar;
import java.util.TimeZone;

/** Persian digits, number grouping and the Jalali (Shamsi) calendar. Pure Java so it can be unit tested. */
public final class Fa {
    private Fa() {}

    private static final char[] FA_DIGITS = {'۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'};
    public static final String[] MONTHS = {"فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"};
    /** Indexed by Calendar.DAY_OF_WEEK (1 = Sunday). */
    private static final String[] WEEKDAYS = {"", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه", "شنبه"};

    /** Replace ASCII digits with Persian digits. */
    public static String d(Object o) {
        String s = String.valueOf(o);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= '0' && c <= '9' ? FA_DIGITS[c - '0'] : c);
        }
        return b.toString();
    }

    /** Replace Persian and Arabic-Indic digits with ASCII digits. */
    public static String ascii(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '۰' && c <= '۹') b.append((char) ('0' + (c - '۰')));
            else if (c >= '٠' && c <= '٩') b.append((char) ('0' + (c - '٠')));
            else b.append(c);
        }
        return b.toString();
    }

    /** 2500487 → "۲٬۵۰۰٬۴۸۷". */
    public static String group(long n) {
        String s = Long.toString(Math.abs(n));
        StringBuilder b = new StringBuilder();
        int lead = s.length() % 3;
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (i - lead) % 3 == 0) b.append('٬');
            b.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + d(b.toString());
    }

    /** Gregorian → Jalali {year, month(1-12), day}. */
    public static int[] toJalali(int gy, int gm, int gd) {
        int[] gdm = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
        int gy2 = gm > 2 ? gy + 1 : gy;
        long days = 355666 + 365L * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1];
        long jy = -1595 + 33 * (days / 12053);
        days %= 12053;
        jy += 4 * (days / 1461);
        days %= 1461;
        if (days > 365) {
            jy += (days - 1) / 365;
            days = (days - 1) % 365;
        }
        int jm, jd;
        if (days < 186) {
            jm = 1 + (int) (days / 31);
            jd = 1 + (int) (days % 31);
        } else {
            jm = 7 + (int) ((days - 186) / 30);
            jd = 1 + (int) ((days - 186) % 30);
        }
        return new int[]{(int) jy, jm, jd};
    }

    /** Jalali → Gregorian {year, month(1-12), day}; the inverse of {@link #toJalali}. */
    public static int[] toGregorian(int jy, int jm, int jd) {
        jy += 1595;
        long days = -355668 + 365L * jy + (jy / 33) * 8L + ((jy % 33) + 3) / 4 + jd + (jm < 7 ? (jm - 1) * 31 : (jm - 7) * 30 + 186);
        long gy = 400 * (days / 146097);
        days %= 146097;
        if (days > 36524) {
            gy += 100 * (--days / 36524);
            days %= 36524;
            if (days >= 365) days++;
        }
        gy += 4 * (days / 1461);
        days %= 1461;
        if (days > 365) {
            gy += (days - 1) / 365;
            days = (days - 1) % 365;
        }
        long gd = days + 1;
        boolean leap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0;
        int[] len = {31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        int gm = 0;
        while (gm < 11 && gd > len[gm]) gd -= len[gm++];
        return new int[]{(int) gy, gm + 1, (int) gd};
    }

    /** Days in a Jalali month: 31 for the first six, 30 for the next five, 29 or 30 for Esfand. */
    public static int monthLength(int jy, int jm) {
        if (jm <= 6) return 31;
        if (jm <= 11) return 30;
        int[] g = toGregorian(jy, 12, 30);
        int[] back = toJalali(g[0], g[1], g[2]);
        return back[1] == 12 && back[2] == 30 ? 30 : 29;
    }

    /** Local midnight at the start of a Jalali day. */
    public static long jalaliDayStart(int jy, int jm, int jd, TimeZone tz) {
        int[] g = toGregorian(jy, jm, jd);
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(g[0], g[1] - 1, g[2], 0, 0, 0);
        return c.getTimeInMillis();
    }

    /** "۲۵ آبان ۱۴۰۵", without the year when {@code year} is false. */
    public static String date(long ms, TimeZone tz, boolean year) {
        int[] j = jalali(ms, tz);
        return d(j[2]) + " " + MONTHS[j[1] - 1] + (year ? " " + d(j[0]) : "");
    }

    public static int[] jalali(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return toJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** "پنج‌شنبه ۱۶ مهر" (year added when it differs from the current one). */
    public static String dayLabel(long ms, long nowMs, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        int[] j = jalali(ms, tz);
        int[] now = jalali(nowMs, tz);
        String s = WEEKDAYS[c.get(Calendar.DAY_OF_WEEK)] + " " + d(j[2]) + " " + MONTHS[j[1] - 1];
        if (j[0] != now[0]) s += " " + d(j[0]);
        return s;
    }

    /** Local-midnight key of a timestamp, used to group the log by day. */
    public static long dayStart(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** "۱۴:۲۰". */
    public static String time(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return d(String.format(java.util.Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
    }

    /** "۱۴۰۵/۰۷/۱۶ ۱۴:۲۰:۰۵". */
    public static String full(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        int[] j = jalali(ms, tz);
        return d(String.format(java.util.Locale.US, "%04d/%02d/%02d %02d:%02d:%02d", j[0], j[1], j[2],
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)));
    }

    /** "۲ دقیقه پیش". */
    public static String ago(long ms, long nowMs) {
        long s = Math.max(0, (nowMs - ms) / 1000);
        if (s < 60) return "همین الان";
        if (s < 3600) return d(s / 60) + " دقیقه پیش";
        if (s < 86400) return d(s / 3600) + " ساعت پیش";
        return d(s / 86400) + " روز پیش";
    }
}
