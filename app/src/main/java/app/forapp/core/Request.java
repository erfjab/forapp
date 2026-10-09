package app.forapp.core;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one delivery sends, built from the destination's own settings: method, address, headers and body.
 * Templates use {{name}} placeholders; each value is encoded for where it lands (JSON literal, form field, URL, raw).
 * A destination with nothing customised sends exactly what ForApp always sent: JSON by POST with X-API-Key.
 */
public final class Request {
    public static final String[] METHODS = {"POST", "PUT", "PATCH", "GET"};
    public static final String JSON = "json", FORM = "form", TEXT = "text", NONE = "none";
    public static final String[] TYPES = {JSON, FORM, TEXT, NONE};
    public static final String DEFAULT_HEADERS = "X-API-Key: {{api_key}}\nIdempotency-Key: {{id}}";

    /** Every placeholder, with what it holds (shown in the editor). */
    public static final String[][] VARS = {
            {"amount", "مبلغ به عدد"}, {"code", "کد سه‌رقمی تطبیق"}, {"unit", "rial یا toman"},
            {"body", "متن کامل پیامک"}, {"sender", "شماره‌ی فرستنده"}, {"bank", "نام بانک"},
            {"is_deposit", "واریز است یا نه"}, {"received_at", "زمان دریافت (ISO)"}, {"received_at_ms", "زمان دریافت (میلی‌ثانیه)"},
            {"id", "شناسه‌ی یکتای پیامک"}, {"attempt", "شماره‌ی تلاش"}, {"test", "آزمایشی است یا نه"},
            {"device", "مدل گوشی"}, {"api_key", "کلید API این مقصد"},
    };

    public String method, url, contentType;
    public final List<String[]> headers = new ArrayList<>();
    public String body; // null when the request has no body

    public static String defaultBody(String type) {
        switch (type) {
            case FORM: return "id={{id}}&amount={{amount}}&code={{code}}&unit={{unit}}&sender={{sender}}&bank={{bank}}"
                    + "&body={{body}}&received_at={{received_at}}&test={{test}}";
            case TEXT: return "{{body}}";
            case NONE: return "";
            default: return "{\n  \"id\": {{id}},\n  \"test\": {{test}},\n  \"sender\": {{sender}},\n  \"bank\": {{bank}},\n"
                    + "  \"body\": {{body}},\n  \"amount\": {{amount}},\n  \"code\": {{code}},\n  \"unit\": {{unit}},\n"
                    + "  \"is_deposit\": {{is_deposit}},\n  \"received_at\": {{received_at}},\n  \"received_at_ms\": {{received_at_ms}},\n"
                    + "  \"attempt\": {{attempt}},\n  \"device\": {{device}}\n}";
        }
    }

    public static Map<String, Object> vars(Db.Dest d, Db.Msg m, int attempt, boolean test) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.uid);
        v.put("test", test);
        v.put("sender", m.sender);
        v.put("bank", m.bank);
        v.put("body", m.body);
        v.put("amount", m.amount);
        v.put("code", m.amount == null ? null : SmsParser.code(m.amount));
        v.put("unit", m.unit);
        v.put("is_deposit", m.deposit);
        v.put("received_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date(m.at)));
        v.put("received_at_ms", m.at);
        v.put("attempt", attempt);
        v.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        v.put("api_key", d.apiKey);
        return v;
    }

    /** A made-up deposit for previews and test sends. */
    public static Db.Msg sample(boolean test) {
        Db.Msg m = new Db.Msg();
        m.uid = (test ? "test-" : "") + java.util.UUID.randomUUID();
        m.sender = test ? "ForApp" : "+989000000000";
        m.bank = test ? "آزمایشی" : "ملت";
        m.body = test ? "پیامک آزمایشی ForApp\nواریز: 1,000,123 ریال" : "بانک ملت\nواریز:1,000,123\nمانده:15,200,000";
        m.amount = 1_000_123L;
        m.unit = "rial";
        m.deposit = true;
        m.at = System.currentTimeMillis();
        return m;
    }

    public static Request build(Db.Dest d, Map<String, Object> v) {
        Request r = new Request();
        r.method = d.method();
        String type = "GET".equals(r.method) ? NONE : d.bodyType();
        r.url = fill(d.url.trim(), v, Enc.URL);
        boolean ownType = false;
        for (String line : d.headers().split("\n")) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            String name = line.substring(0, i).trim(), value = fill(line.substring(i + 1).trim(), v, Enc.RAW);
            if (name.isEmpty()) continue;
            if (name.equalsIgnoreCase("Content-Type")) ownType = true;
            r.headers.add(new String[]{name, value});
        }
        if (!NONE.equals(type)) {
            r.body = fill(d.bodyTpl(), v, JSON.equals(type) ? Enc.JSON : FORM.equals(type) ? Enc.FORM : Enc.RAW);
            r.contentType = JSON.equals(type) ? "application/json; charset=utf-8"
                    : FORM.equals(type) ? "application/x-www-form-urlencoded; charset=utf-8" : "text/plain; charset=utf-8";
            if (!ownType) r.headers.add(0, new String[]{"Content-Type", r.contentType});
        }
        return r;
    }

    private enum Enc { JSON, FORM, URL, RAW }
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*\\}\\}");

    static String fill(String tpl, Map<String, Object> v, Enc enc) {
        Matcher mt = VAR.matcher(tpl);
        StringBuffer out = new StringBuffer();
        while (mt.find()) {
            String key = mt.group(1);
            String rep = v.containsKey(key) ? encode(v.get(key), enc) : mt.group(0);
            mt.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        mt.appendTail(out);
        return out.toString();
    }

    private static String encode(Object o, Enc enc) {
        switch (enc) {
            case JSON:
                if (o == null) return "null";
                if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
                return JSONObject.quote(String.valueOf(o));
            case FORM:
            case URL:
                if (o == null) return "";
                try {
                    return URLEncoder.encode(String.valueOf(o), "UTF-8").replace("+", enc == Enc.URL ? "%20" : "+");
                } catch (java.io.UnsupportedEncodingException e) {
                    throw new AssertionError(e); // UTF-8 always exists
                }
            default:
                return o == null ? "" : String.valueOf(o);
        }
    }

    /** Problem with the body as JSON, or null when it parses (only checked for JSON bodies). */
    public String jsonError() {
        if (body == null || contentType == null || !contentType.startsWith("application/json")) return null;
        try {
            org.json.JSONTokener t = new org.json.JSONTokener(body);
            t.nextValue();
            if (t.more() && t.nextClean() != 0) return "بعد از JSON چیز اضافه‌ای آمده";
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** The request as text, like an HTTP log. The API key is shortened unless {@code showKey}. */
    public String describe(String apiKey, boolean showKey) {
        StringBuilder b = new StringBuilder(method).append(' ').append(url).append('\n');
        for (String[] h : headers) {
            String value = h[1];
            if (!showKey && apiKey != null && apiKey.length() > 0) value = value.replace(apiKey, mask(apiKey));
            b.append(h[0]).append(": ").append(value).append('\n');
        }
        if (body != null) b.append('\n').append(body);
        return b.toString().trim();
    }

    private static String mask(String key) {
        return key.length() <= 4 ? "••••" : "••••" + key.substring(key.length() - 4);
    }
}
