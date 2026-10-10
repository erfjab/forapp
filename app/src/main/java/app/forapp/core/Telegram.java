package app.forapp.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A destination that is a Telegram chat: the bot (token in the destination's API key) sends each deposit to a chat ID.
 * It is built as an ordinary {@link Request} to the Bot API's sendMessage, so retries, the log, "send now" and the
 * request viewer work exactly as for a webhook.
 */
public final class Telegram {
    private Telegram() {}

    public static final String KIND = "telegram";
    /** The official Bot API; a destination can point at its own Bot API server or relay instead. */
    public static final String API = "https://api.telegram.org";
    public static final String MARKDOWN = "markdown", PLAIN = "plain";

    public static final String DEFAULT_TEMPLATE = "💰 **واریز {{amount_text}} {{unit_fa}}**\n"
            + "کد تطبیق: `{{code}}`\n"
            + "{{bank}} · {{date}} ساعت {{time}}\n"
            + "\n"
            + "```\n{{body}}\n```";

    /** The formatting the editor explains; the same marks as typing in Telegram itself. */
    public static final String[][] MARKS = {
            {"**متن**", "پررنگ"}, {"__متن__", "کج"}, {"~~متن~~", "خط‌خورده"}, {"||متن||", "پنهان"},
            {"`متن`", "تک‌فاصله"}, {"```متن```", "بلوک کد"},
    };

    static Request build(Db.Dest d, Map<String, Object> v, boolean test) {
        Request r = new Request();
        r.method = "POST";
        r.url = api(d.url) + "/bot" + d.apiKey.trim() + "/sendMessage";
        r.contentType = "application/json; charset=utf-8";
        r.headers.add(new String[]{"Content-Type", r.contentType});
        boolean md = !PLAIN.equals(d.parseMode());
        String text = text(d.template(), v, md);
        if (test) text = (md ? "🧪 <b>پیام آزمایشی فوراپ</b>" : "🧪 پیام آزمایشی فوراپ") + "\n\n" + text;
        try {
            JSONObject o = new JSONObject();
            o.put("chat_id", d.chatId == null ? "" : d.chatId.trim());
            o.put("text", text);
            if (md) o.put("parse_mode", "HTML");
            o.put("link_preview_options", new JSONObject().put("is_disabled", true));
            r.body = o.toString(2).replace("\\/", "/"); // org.json escapes every slash; Telegram does not need it
        } catch (JSONException e) {
            throw new AssertionError(e); // only strings and booleans go in
        }
        return r;
    }

    static String api(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.isEmpty() ? API : u;
    }

    /**
     * The message text: the template's marks become Telegram HTML first, then variables are filled in escaped,
     * so a "*" or "<" inside an SMS can never break the formatting. Plain mode sends the text as it is.
     */
    public static String text(String tpl, Map<String, Object> v, boolean markdown) {
        if (!markdown) return Request.fill(tpl, v, Request.Enc.RAW);
        return Request.fill(toHtml(tpl), v, Request.Enc.HTML);
    }

    private static final Pattern PRE = Pattern.compile("```\\n?(.*?)\\n?```", Pattern.DOTALL);
    private static final Pattern CODE = Pattern.compile("`([^`\\n]+)`");
    private static final String[][] INLINE = {
            {"\\*\\*(.+?)\\*\\*", "b"}, {"__(.+?)__", "i"}, {"~~(.+?)~~", "s"}, {"\\|\\|(.+?)\\|\\|", "tg-spoiler"},
    };

    /** Telegram-style marks to Telegram's HTML. Text inside code keeps its marks literally. */
    public static String toHtml(String tpl) {
        String s = escape(tpl == null ? "" : tpl);
        List<String> kept = new ArrayList<>();
        s = keep(PRE, s, "pre", kept);
        s = keep(CODE, s, "code", kept);
        for (String[] m : INLINE) s = s.replaceAll(m[0], "<" + m[1] + ">$1</" + m[1] + ">");
        for (int i = 0; i < kept.size(); i++) s = s.replace("\u0000" + i + "\u0000", kept.get(i));
        return s;
    }

    /** Replaces each match with a numbered stand-in so later marks do not touch it. */
    private static String keep(Pattern p, String s, String tag, List<String> kept) {
        Matcher m = p.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            kept.add("<" + tag + ">" + m.group(1) + "</" + tag + ">");
            m.appendReplacement(out, Matcher.quoteReplacement("\u0000" + (kept.size() - 1) + "\u0000"));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** "123456789:AAH…" — the shape of a token from @BotFather. */
    public static boolean validToken(String t) {
        return t != null && t.trim().matches("\\d{5,}:[A-Za-z0-9_-]{20,}");
    }

    /**
     * Chats that recently messaged the bot (or added it), newest first, as {chat ID, name, kind}. Reads getUpdates
     * without an offset, so nothing is marked as read for any other program using the same bot.
     */
    public static List<String[]> recentChats(String api, String token, int timeoutSec) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(api(api) + "/bot" + token.trim() + "/getUpdates?limit=100").openConnection();
        try {
            h.setConnectTimeout(Math.min(timeoutSec, 8) * 1000);
            h.setReadTimeout(timeoutSec * 1000);
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; in != null && (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            JSONObject o = new JSONObject(buf.toString("UTF-8"));
            if (!o.optBoolean("ok")) {
                if (code == 401 || code == 404) throw new Exception("توکن بات درست نیست");
                if (code == 409) throw new Exception("این بات وبهوک دارد و getUpdates کار نمی‌کند؛ چت آیدی را دستی بنویسید");
                throw new Exception(o.optString("description", "HTTP " + code));
            }
            LinkedHashMap<String, String[]> chats = new LinkedHashMap<>();
            JSONArray res = o.getJSONArray("result");
            for (int i = res.length() - 1; i >= 0; i--) {
                JSONObject u = res.getJSONObject(i);
                for (String k : new String[]{"message", "edited_message", "channel_post", "my_chat_member"}) {
                    JSONObject c = u.optJSONObject(k) == null ? null : u.optJSONObject(k).optJSONObject("chat");
                    if (c == null) continue;
                    String id = String.valueOf(c.optLong("id"));
                    if (chats.containsKey(id)) continue;
                    String name = c.optString("title", (c.optString("first_name") + " " + c.optString("last_name")).trim());
                    if (name.isEmpty() && !c.optString("username").isEmpty()) name = "@" + c.optString("username");
                    String type = c.optString("type");
                    chats.put(id, new String[]{id, name, "channel".equals(type) ? "کانال" : "private".equals(type) ? "شخصی" : "گروه"});
                }
            }
            return new ArrayList<>(chats.values());
        } finally {
            h.disconnect();
        }
    }
}
