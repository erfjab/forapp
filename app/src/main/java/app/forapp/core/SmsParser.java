package app.forapp.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the deposit amount out of an Iranian bank SMS and tells deposits from withdrawals.
 * Pure Java so it can be unit tested without a phone. The server still receives the raw text,
 * so a wrong guess here never loses information.
 */
public final class SmsParser {
    private SmsParser() {}

    public static final class Result {
        public Long amount;      // as written in the SMS (usually rial)
        public String unit;      // "rial", "toman" or null
        public Boolean deposit;  // true = deposit, false = withdrawal, null = could not tell
    }

    private static final Pattern NUM = Pattern.compile("\\d{1,3}(?:,\\d{3})+|\\d{4,}");
    private static final String[] DEPOSIT = {"واریز", "انتقال به حساب شما", "دریافت وجه", "بستانکار", "+"};
    private static final String[] WITHDRAW = {"برداشت", "خرید", "کسر", "پرداخت", "انتقال از حساب", "بدهکار"};

    /** Unify digits, separators and Arabic letter forms so one set of rules covers every bank. */
    public static String normalize(String s) {
        if (s == null) return "";
        s = Fa.ascii(s);
        return s.replace('٬', ',').replace('،', ',').replace('\'', ',')
                .replace('ي', 'ی').replace('ك', 'ک').replace('‏', ' ').replace('‎', ' ');
    }

    public static Result parse(String body) {
        Result r = new Result();
        String t = normalize(body);
        Matcher m = NUM.matcher(t);
        int bestScore = Integer.MIN_VALUE;
        int prevEnd = 0;
        long best = -1;
        int bestStart = -1, bestEnd = -1;
        while (m.find()) {
            int s = m.start(), e = m.end();
            char before = s > 0 ? t.charAt(s - 1) : ' ';
            char after = e < t.length() ? t.charAt(e) : ' ';
            String raw = m.group();
            int segStart = Math.max(prevEnd, lineStart(t, s));
            String pre = t.substring(Math.max(segStart, s - 25), s);
            String post = t.substring(e, Math.min(t.length(), e + 12)).trim();
            prevEnd = e;

            // Dates, times, card/account numbers and masked numbers are never amounts.
            if (before == '/' || after == '/' || before == ':' && isDigitAt(t, s - 2) || after == ':'
                    || before == '*' || after == '*' || before == '-' && isDigitAt(t, s - 2)
                    || after == '-' && isDigitAt(t, e + 1) || before == '_' || after == '_') continue;
            String digits = raw.replace(",", "");
            if (digits.length() > 12) continue;

            int score = 0;
            if (containsAny(pre, "مانده", "موجودی", "مانده:")) score -= 10;
            if (containsAny(pre, "حساب", "کارت", "شبا", "کد", "پیگیری", "مرجع") && !containsAny(pre, "مبلغ", "واریز")) score -= 4;
            if (pre.contains("مبلغ")) score += 5;
            if (pre.contains("واریز")) score += 4;
            if (pre.trim().endsWith("+") || after == '+') score += 4;
            if (pre.trim().endsWith("-") || after == '-') score += 3;
            if (post.startsWith("ریال") || post.startsWith("تومان") || post.startsWith("rial") || post.startsWith("IRR")) score += 2;
            if (raw.indexOf(',') >= 0) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = Long.parseLong(digits);
                bestStart = s;
                bestEnd = e;
            }
        }
        if (best >= 0 && bestScore >= 0) {
            r.amount = best;
            String post = t.substring(bestEnd, Math.min(t.length(), bestEnd + 12)).trim();
            if (post.startsWith("تومان")) r.unit = "toman";
            else if (post.startsWith("ریال") || post.toLowerCase(Locale.US).startsWith("rial") || post.startsWith("IRR")) r.unit = "rial";
            else if (t.contains("ریال")) r.unit = "rial";
            else if (t.contains("تومان")) r.unit = "toman";
        }
        r.deposit = depositOf(t, bestStart, bestEnd);
        return r;
    }

    private static Boolean depositOf(String t, int s, int e) {
        if (s >= 0) {
            char after = e < t.length() ? t.charAt(e) : ' ';
            String pre = t.substring(Math.max(0, s - 2), s).trim();
            if (after == '+' || pre.endsWith("+")) return true;
            if (after == '-' || pre.endsWith("-")) return false;
        }
        boolean dep = false, wd = false;
        for (String k : DEPOSIT) if (!k.equals("+") && t.contains(k)) dep = true;
        for (String k : WITHDRAW) if (t.contains(k)) wd = true;
        if (dep && !wd) return true;
        if (wd && !dep) return false;
        if (dep) {
            // Both words appear: trust the one closest before the amount.
            int ref = s >= 0 ? s : t.length();
            int d = lastIndexBefore(t, DEPOSIT, ref), w = lastIndexBefore(t, WITHDRAW, ref);
            if (d != w) return d > w;
        }
        return null;
    }

    /** Three-digit code the sales bot matches on: the last three digits of the amount. */
    public static String code(Long amount) {
        if (amount == null) return null;
        return String.format(Locale.US, "%03d", amount % 1000);
    }

    /**
     * Canonical form of an SMS sender so "+98 9000 1234", "009890001234" and "090001234"
     * compare equal. Alphanumeric senders are lower-cased.
     */
    public static String canonicalSender(String s) {
        String a = Fa.ascii(s == null ? "" : s).toLowerCase(Locale.US);
        StringBuilder b = new StringBuilder();
        boolean letters = false;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(c);
                if (!(c >= '0' && c <= '9')) letters = true;
            }
        }
        String r = b.toString();
        if (letters) return r;
        if (r.startsWith("00")) r = r.substring(2);
        if (r.startsWith("98") && r.length() >= 10) r = r.substring(2);
        while (r.startsWith("0")) r = r.substring(1);
        return r;
    }

    public static boolean senderMatches(String configured, String actual) {
        String c = canonicalSender(configured), a = canonicalSender(actual);
        return !c.isEmpty() && c.equals(a);
    }

    private static int lineStart(String t, int i) {
        int n = t.lastIndexOf('\n', i - 1);
        return n < 0 ? 0 : n + 1;
    }

    private static boolean isDigitAt(String t, int i) {
        return i >= 0 && i < t.length() && Character.isDigit(t.charAt(i));
    }

    private static boolean containsAny(String s, String... ks) {
        for (String k : ks) if (s.contains(k)) return true;
        return false;
    }

    private static int lastIndexBefore(String t, String[] ks, int ref) {
        int best = -1;
        for (String k : ks) {
            int i = t.lastIndexOf(k, ref);
            if (i > best) best = i;
        }
        return best;
    }
}
