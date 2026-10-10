package app.forapp.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings as one JSON file: options, banks with their senders, and destinations with their requests and API keys.
 * The log is never included.
 */
public final class Backup {
    private Backup() {}

    private static final String APP = "forapp-settings";
    private static final int VERSION = 1;

    /** What an import holds, read and checked before anything is replaced. */
    public static final class Data {
        final JSONObject prefs;
        public final List<Db.Bank> banks = new ArrayList<>();
        public final List<Db.Dest> dests = new ArrayList<>();
        final List<Set<String>> destBanks = new ArrayList<>();

        Data(JSONObject prefs) { this.prefs = prefs; }
    }

    public static String export(Context c) throws JSONException {
        Db db = Db.get(c);
        Prefs p = Prefs.of(c);
        JSONObject o = new JSONObject();
        o.put("app", APP);
        o.put("version", VERSION);
        o.put("exported_at", System.currentTimeMillis());

        JSONObject prefs = new JSONObject();
        prefs.put("enabled", p.enabled());
        prefs.put("deposit_only", p.depositOnly());
        prefs.put("max_attempts", p.maxAttempts());
        prefs.put("timeout_sec", p.timeoutSec());
        prefs.put("retention_days", p.retentionDays());
        o.put("prefs", prefs);

        JSONArray banks = new JSONArray();
        Map<Long, String> names = new HashMap<>();
        for (Db.Bank b : db.banks()) {
            names.put(b.id, b.name);
            JSONObject j = new JSONObject();
            j.put("name", b.name);
            j.put("senders", new JSONArray(b.senderList()));
            banks.put(j);
        }
        o.put("banks", banks);

        JSONArray dests = new JSONArray();
        for (Db.Dest d : db.dests()) {
            JSONObject j = new JSONObject();
            j.put("name", d.name);
            j.put("url", d.url);
            j.put("api_key", d.apiKey);
            j.put("enabled", d.enabled);
            j.put("all_banks", d.allBanks);
            JSONArray only = new JSONArray();
            for (long id : db.destBanks(d.id)) if (names.containsKey(id)) only.put(names.get(id));
            j.put("banks", only);
            // Absent means the default request.
            j.putOpt("method", d.method);
            j.putOpt("headers", d.headers);
            j.putOpt("body_type", d.bodyType);
            j.putOpt("body_tpl", d.bodyTpl);
            dests.put(j);
        }
        o.put("dests", dests);
        return o.toString(2);
    }

    /** Parses a file; throws with a Persian message the user can read when it is not a ForApp settings file. */
    public static Data read(String text) throws Exception {
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (JSONException e) {
            throw new Exception("این فایل تنظیمات فوراپ نیست");
        }
        if (!APP.equals(o.optString("app"))) throw new Exception("این فایل تنظیمات فوراپ نیست");
        if (o.optInt("version") > VERSION) throw new Exception("این فایل از نسخه‌ی تازه‌تر فوراپ است؛ اول برنامه را به‌روز کنید");

        Data data = new Data(o.optJSONObject("prefs"));
        JSONArray banks = o.optJSONArray("banks");
        for (int i = 0; banks != null && i < banks.length(); i++) {
            JSONObject j = banks.getJSONObject(i);
            Db.Bank b = new Db.Bank();
            b.name = j.optString("name").trim();
            if (b.name.isEmpty()) continue;
            List<String> senders = new ArrayList<>();
            JSONArray s = j.optJSONArray("senders");
            for (int k = 0; s != null && k < s.length(); k++) if (!s.optString(k).trim().isEmpty()) senders.add(s.optString(k).trim());
            b.senders = String.join(",", senders);
            data.banks.add(b);
        }
        JSONArray dests = o.optJSONArray("dests");
        for (int i = 0; dests != null && i < dests.length(); i++) {
            JSONObject j = dests.getJSONObject(i);
            Db.Dest d = new Db.Dest();
            d.name = j.optString("name").trim();
            d.url = j.optString("url").trim();
            if (d.url.isEmpty()) continue;
            if (d.name.isEmpty()) d.name = d.url;
            d.apiKey = j.optString("api_key");
            d.enabled = j.optBoolean("enabled", true);
            d.allBanks = j.optBoolean("all_banks", true);
            d.method = opt(j, "method");
            d.headers = opt(j, "headers");
            d.bodyType = opt(j, "body_type");
            d.bodyTpl = opt(j, "body_tpl");
            Set<String> only = new HashSet<>();
            JSONArray b = j.optJSONArray("banks");
            for (int k = 0; b != null && k < b.length(); k++) only.add(b.optString(k));
            data.dests.add(d);
            data.destBanks.add(only);
        }
        return data;
    }

    /** Replaces the current settings with the imported ones. The log stays; pending sends to destinations not in the file are dropped. */
    public static void apply(Context c, Data data) {
        Db.get(c).replaceSettings(data.banks, data.dests, data.destBanks);
        Prefs p = Prefs.of(c);
        JSONObject j = data.prefs;
        if (j != null) {
            if (j.has("enabled")) p.enabled(j.optBoolean("enabled", true));
            if (j.has("deposit_only")) p.depositOnly(j.optBoolean("deposit_only", true));
            if (j.has("max_attempts")) p.maxAttempts(j.optInt("max_attempts", p.maxAttempts()));
            if (j.has("timeout_sec")) p.timeoutSec(j.optInt("timeout_sec", p.timeoutSec()));
            if (j.has("retention_days")) p.retentionDays(j.optInt("retention_days", p.retentionDays()));
        }
        Forwarder.changed();
    }

    private static String opt(JSONObject j, String key) {
        return j.isNull(key) ? null : j.optString(key);
    }
}
