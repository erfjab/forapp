package app.forapp.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * What the report screen and its export files show: the SMS of a time range, filtered, with a summary.
 * Pure Java so it can be unit tested; {@link Db#reportRows} fills it.
 */
public final class Report {

    /** Which SMS: deposits only, deposits and withdrawals, or every SMS the app kept. */
    public static final int TYPE_DEPOSIT = 0, TYPE_BOTH = 1, TYPE_ALL = 2;
    public static final String[] TYPE_LABELS = {"فقط واریز", "واریز و برداشت", "همه‌ی پیامک‌ها"};

    public static final String SENT = "sent", FAILED = "failed", PENDING = "pending", FILTERED = "filtered",
            IGNORED = "ignored", SKIPPED = "skipped";

    public static final class Delivery {
        public String dest, status, error;
        public Integer code;
        public int attempts;
    }

    public static final class Row {
        public long id, at;
        public String bank = "", sender = "", body = "", dests = "", failedDests = "", status = SKIPPED;
        public Long amount;
        public Boolean deposit;
        public List<Delivery> deliveries;

        public boolean isDeposit() { return Boolean.TRUE.equals(deposit); }
        /** A deposit that counts toward the totals: ignored ones are listed but not added up. */
        public boolean counts() { return isDeposit() && !IGNORED.equals(status); }
        public String type() { return deposit == null ? "unknown" : deposit ? "deposit" : "withdrawal"; }
    }

    public static final class BankTotal {
        public final String bank;
        public int count;
        public long sum;
        BankTotal(String bank) { this.bank = bank; }
    }

    // ---------- the range ----------

    public final long from, to; // [from, to): local midnights
    public final TimeZone tz;
    public final List<Row> rows;

    // ---------- the summary, from rows ----------

    public int deposits, sent, failed, pending, filtered, ignored, skipped;
    public long total;
    /** Banks by deposit total, largest first. */
    public final List<BankTotal> banks = new ArrayList<>();
    /** Deposit totals per day of the range, or per hour when it is one day; the failed part separately. */
    public final long[] slots, badSlots;

    /** Local midnight of each day of the range, then {@code to}: DST-safe day boundaries. */
    private final long[] mids;

    public Report(long from, long to, TimeZone tz, List<Row> rows) {
        this.from = from;
        this.to = to;
        this.tz = tz;
        this.rows = rows;
        List<Long> m = new ArrayList<>();
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(from);
        while (cal.getTimeInMillis() < to && m.size() < 4000) {
            m.add(cal.getTimeInMillis());
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        mids = new long[Math.max(1, m.size())];
        for (int i = 0; i < m.size(); i++) mids[i] = m.get(i);
        int days = days();
        slots = new long[days == 1 ? 24 : days];
        badSlots = new long[slots.length];
        Map<String, BankTotal> byBank = new LinkedHashMap<>();
        Calendar c = Calendar.getInstance(tz);
        for (Row r : rows) {
            switch (r.status) {
                case SENT: sent++; break;
                case FAILED: failed++; break;
                case PENDING: pending++; break;
                case FILTERED: filtered++; break;
                case IGNORED: ignored++; break;
                default: skipped++;
            }
            if (!r.counts()) continue;
            deposits++;
            long a = r.amount == null ? 0 : r.amount;
            total += a;
            String name = r.bank == null || r.bank.isEmpty() ? "نامشخص" : r.bank;
            BankTotal b = byBank.get(name);
            if (b == null) byBank.put(name, b = new BankTotal(name));
            b.count++;
            b.sum += a;
            int slot;
            if (slots.length == 24 && days == 1) {
                c.setTimeInMillis(r.at);
                slot = c.get(Calendar.HOUR_OF_DAY);
            } else {
                slot = dayIndex(r.at);
            }
            if (slot < 0 || slot >= slots.length) continue;
            slots[slot] += a;
            if (FAILED.equals(r.status)) badSlots[slot] += a;
        }
        banks.addAll(byBank.values());
        banks.sort((x, y) -> Long.compare(y.sum, x.sum));
    }

    /** Calendar days in the range (counts midnights, not 24-hour blocks). */
    public int days() {
        return mids.length;
    }

    /** Which day of the range a time falls on; -1 before it. */
    private int dayIndex(long ms) {
        int i = java.util.Arrays.binarySearch(mids, ms);
        return i >= 0 ? i : -i - 2;
    }

    /** Day of the month (Jalali) for each daily slot, for the chart's labels. */
    public int[] slotDays() {
        int[] d = new int[mids.length];
        for (int i = 0; i < d.length; i++) d[i] = Fa.jalali(mids[i], tz)[2];
        return d;
    }

    /** "۱ آبان تا ۲۵ آبان ۱۴۰۵", or one day. */
    public String label() {
        long last = to - 1;
        if (Fa.dayStart(from, tz) == Fa.dayStart(last, tz)) return Fa.date(from, tz, true);
        boolean sameYear = Fa.jalali(from, tz)[0] == Fa.jalali(last, tz)[0];
        return Fa.date(from, tz, !sameYear) + " تا " + Fa.date(last, tz, true);
    }

    /** "forapp-report-1405-08-01_1405-08-25.xlsx". */
    public String fileName(String ext) {
        String a = isoJalali(from), b = isoJalali(to - 1);
        return "forapp-report-" + (a.equals(b) ? a : a + "_" + b) + "." + ext;
    }

    private String isoJalali(long ms) {
        int[] j = Fa.jalali(ms, tz);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", j[0], j[1], j[2]);
    }

    public static String statusLabel(String s) {
        switch (s) {
            case SENT: return "ارسال‌شده";
            case FAILED: return "ناموفق";
            case PENDING: return "در صف";
            case FILTERED: return "کنار گذاشته";
            case IGNORED: return "نادیده";
            default: return "فرستاده نشد";
        }
    }

    public static String typeLabel(Boolean deposit) {
        return deposit == null ? "نامشخص" : deposit ? "واریز" : "برداشت";
    }

    /** One status for an SMS from its deliveries; failures win over waiting, waiting over sent. */
    public static String status(Db.Msg m) {
        if (m.ignored()) return IGNORED;
        if (m.failed > 0) return FAILED;
        if (m.pending > 0) return PENDING;
        if (m.sent > 0) return SENT;
        if (m.total > 0 && m.filtered == m.total) return FILTERED;
        return SKIPPED;
    }
}
