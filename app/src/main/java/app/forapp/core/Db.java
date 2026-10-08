package app.forapp.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** All app state lives in one small SQLite file. No ORM, no extra library. */
public final class Db extends SQLiteOpenHelper {

    public static final int PENDING = 0, SENT = 1, FAILED = 2, SENDING = 3;
    /** A delivery stuck in SENDING longer than this (process killed mid-request) is retried. */
    public static final long STALE_SENDING_MS = 2 * 60 * 1000;

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "forapp.db", null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE banks(id INTEGER PRIMARY KEY, name TEXT NOT NULL, senders TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE dests(id INTEGER PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, api_key TEXT NOT NULL DEFAULT '',"
                + " enabled INTEGER NOT NULL DEFAULT 1, all_banks INTEGER NOT NULL DEFAULT 1, last_code INTEGER, last_ms INTEGER, last_at INTEGER)");
        db.execSQL("CREATE TABLE dest_banks(dest_id INTEGER NOT NULL, bank_id INTEGER NOT NULL, PRIMARY KEY(dest_id, bank_id))");
        db.execSQL("CREATE TABLE msgs(id INTEGER PRIMARY KEY, uid TEXT NOT NULL, fp TEXT NOT NULL UNIQUE, sender TEXT NOT NULL,"
                + " bank_id INTEGER, bank TEXT, body TEXT NOT NULL, amount INTEGER, unit TEXT, deposit INTEGER,"
                + " skipped INTEGER NOT NULL DEFAULT 0, at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX msgs_at ON msgs(at)");
        db.execSQL("CREATE TABLE dels(id INTEGER PRIMARY KEY, msg_id INTEGER NOT NULL, dest_id INTEGER NOT NULL, dest TEXT NOT NULL,"
                + " status INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0,"
                + " code INTEGER, error TEXT, updated_at INTEGER NOT NULL DEFAULT 0, UNIQUE(msg_id, dest_id))");
        db.execSQL("CREATE INDEX dels_due ON dels(status, next_at)");
        String[] seed = {"ملت", "ملی", "صادرات", "تجارت", "سپه", "پاسارگاد", "سامان", "پارسیان", "رسالت", "بلوبانک",
                "مسکن", "کشاورزی", "رفاه", "اقتصاد نوین", "شهر", "آینده"};
        for (String n : seed) {
            ContentValues v = new ContentValues();
            v.put("name", n);
            db.insert("banks", null, v);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

    // ---------- models ----------

    public static final class Bank {
        public long id;
        public String name = "";
        public String senders = "";

        public List<String> senderList() {
            List<String> l = new ArrayList<>();
            for (String s : senders.split("[,\\n،]")) if (!s.trim().isEmpty()) l.add(s.trim());
            return l;
        }
    }

    public static final class Dest {
        public long id;
        public String name = "", url = "", apiKey = "";
        public boolean enabled = true, allBanks = true;
        public Integer lastCode;
        public long lastMs, lastAt;
    }

    public static final class Msg {
        public long id;
        public String uid, fp, sender, bank, body, unit;
        public Long bankId, amount;
        public Boolean deposit;
        public boolean skipped;
        public long at;
        // aggregated delivery state, filled by log()
        public int total, sent, failed, pending;
        public String dests = "";
    }

    public static final class Delivery {
        public long id, msgId, destId;
        public String dest, error;
        public int status, attempts;
        public Integer code;
        public long nextAt, updatedAt;
    }

    // ---------- banks ----------

    public List<Bank> banks() {
        List<Bank> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, name, senders FROM banks ORDER BY senders = '', id", null)) {
            while (c.moveToNext()) {
                Bank b = new Bank();
                b.id = c.getLong(0);
                b.name = c.getString(1);
                b.senders = c.getString(2);
                l.add(b);
            }
        }
        return l;
    }

    public long saveBank(Bank b) {
        ContentValues v = new ContentValues();
        v.put("name", b.name);
        v.put("senders", b.senders);
        if (b.id == 0) return b.id = getWritableDatabase().insert("banks", null, v);
        getWritableDatabase().update("banks", v, "id=?", args(b.id));
        return b.id;
    }

    public void deleteBank(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("banks", "id=?", args(id));
        db.delete("dest_banks", "bank_id=?", args(id));
    }

    public Bank bankForSender(String sender) {
        for (Bank b : banks())
            for (String s : b.senderList())
                if (SmsParser.senderMatches(s, sender)) return b;
        return null;
    }

    // ---------- destinations ----------

    public List<Dest> dests() {
        List<Dest> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, name, url, api_key, enabled, all_banks, last_code, last_ms, last_at FROM dests ORDER BY id", null)) {
            while (c.moveToNext()) l.add(readDest(c));
        }
        return l;
    }

    public Dest dest(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, name, url, api_key, enabled, all_banks, last_code, last_ms, last_at FROM dests WHERE id=?", args(id))) {
            return c.moveToFirst() ? readDest(c) : null;
        }
    }

    private static Dest readDest(Cursor c) {
        Dest d = new Dest();
        d.id = c.getLong(0);
        d.name = c.getString(1);
        d.url = c.getString(2);
        d.apiKey = c.getString(3);
        d.enabled = c.getInt(4) == 1;
        d.allBanks = c.getInt(5) == 1;
        d.lastCode = c.isNull(6) ? null : c.getInt(6);
        d.lastMs = c.isNull(7) ? 0 : c.getLong(7);
        d.lastAt = c.isNull(8) ? 0 : c.getLong(8);
        return d;
    }

    public Set<Long> destBanks(long destId) {
        Set<Long> s = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT bank_id FROM dest_banks WHERE dest_id=?", args(destId))) {
            while (c.moveToNext()) s.add(c.getLong(0));
        }
        return s;
    }

    public long saveDest(Dest d, Set<Long> bankIds) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("name", d.name);
            v.put("url", d.url);
            v.put("api_key", d.apiKey);
            v.put("enabled", d.enabled ? 1 : 0);
            v.put("all_banks", d.allBanks ? 1 : 0);
            if (d.id == 0) d.id = db.insert("dests", null, v);
            else db.update("dests", v, "id=?", args(d.id));
            db.delete("dest_banks", "dest_id=?", args(d.id));
            for (long b : bankIds) {
                ContentValues bv = new ContentValues();
                bv.put("dest_id", d.id);
                bv.put("bank_id", b);
                db.insert("dest_banks", null, bv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return d.id;
    }

    public void setDestEnabled(long id, boolean on) {
        ContentValues v = new ContentValues();
        v.put("enabled", on ? 1 : 0);
        getWritableDatabase().update("dests", v, "id=?", args(id));
    }

    public void deleteDest(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("dests", "id=?", args(id));
        db.delete("dest_banks", "dest_id=?", args(id));
        db.delete("dels", "dest_id=? AND status IN (0,3)", args(id));
    }

    public void setDestLast(long id, int code, long ms) {
        ContentValues v = new ContentValues();
        v.put("last_code", code);
        v.put("last_ms", ms);
        v.put("last_at", System.currentTimeMillis());
        getWritableDatabase().update("dests", v, "id=?", args(id));
    }

    /** Enabled destinations that take SMS from this bank. */
    public List<Dest> destsForBank(long bankId) {
        List<Dest> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, url, api_key, enabled, all_banks, last_code, last_ms, last_at FROM dests WHERE enabled=1"
                        + " AND (all_banks=1 OR id IN (SELECT dest_id FROM dest_banks WHERE bank_id=?)) ORDER BY id", args(bankId))) {
            while (c.moveToNext()) l.add(readDest(c));
        }
        return l;
    }

    // ---------- messages and deliveries ----------

    /** Stores the SMS and one delivery per destination. Returns -1 when the same SMS was already stored. */
    public long insertMessage(Msg m, List<Dest> to) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("uid", m.uid);
            v.put("fp", m.fp);
            v.put("sender", m.sender);
            if (m.bankId != null) v.put("bank_id", m.bankId);
            v.put("bank", m.bank);
            v.put("body", m.body);
            if (m.amount != null) v.put("amount", m.amount);
            v.put("unit", m.unit);
            if (m.deposit != null) v.put("deposit", m.deposit ? 1 : 0);
            v.put("skipped", m.skipped ? 1 : 0);
            v.put("at", m.at);
            long id = db.insertWithOnConflict("msgs", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            if (id == -1) return -1;
            m.id = id;
            if (!m.skipped) addDeliveries(db, id, to);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    private static void addDeliveries(SQLiteDatabase db, long msgId, List<Dest> to) {
        long now = System.currentTimeMillis();
        for (Dest d : to) {
            ContentValues v = new ContentValues();
            v.put("msg_id", msgId);
            v.put("dest_id", d.id);
            v.put("dest", d.name);
            v.put("status", PENDING);
            v.put("next_at", now);
            v.put("updated_at", now);
            db.insertWithOnConflict("dels", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    /** Sends a skipped SMS by hand, or re-sends failed deliveries of a message. */
    public void resend(Msg m) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (m.skipped) {
                ContentValues v = new ContentValues();
                v.put("skipped", 0);
                db.update("msgs", v, "id=?", args(m.id));
                addDeliveries(db, m.id, m.bankId == null ? new ArrayList<>() : destsForBank(m.bankId));
            }
            ContentValues v = new ContentValues();
            v.put("status", PENDING);
            v.put("attempts", 0);
            v.put("next_at", System.currentTimeMillis());
            db.update("dels", v, "msg_id=? AND status=?", new String[]{String.valueOf(m.id), String.valueOf(FAILED)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public Msg message(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(MSG_SELECT + " WHERE m.id=? GROUP BY m.id", args(id))) {
            return c.moveToFirst() ? readMsg(c) : null;
        }
    }

    private static final String MSG_SELECT = "SELECT m.id, m.uid, m.sender, m.bank_id, m.bank, m.body, m.amount, m.unit, m.deposit, m.skipped, m.at,"
            + " COUNT(d.id), SUM(d.status=1), SUM(d.status=2), SUM(d.status IN (0,3)), GROUP_CONCAT(d.dest, '، ')"
            + " FROM msgs m LEFT JOIN dels d ON d.msg_id = m.id";

    private static Msg readMsg(Cursor c) {
        Msg m = new Msg();
        m.id = c.getLong(0);
        m.uid = c.getString(1);
        m.sender = c.getString(2);
        m.bankId = c.isNull(3) ? null : c.getLong(3);
        m.bank = c.getString(4);
        m.body = c.getString(5);
        m.amount = c.isNull(6) ? null : c.getLong(6);
        m.unit = c.getString(7);
        m.deposit = c.isNull(8) ? null : c.getInt(8) == 1;
        m.skipped = c.getInt(9) == 1;
        m.at = c.getLong(10);
        m.total = c.getInt(11);
        m.sent = c.getInt(12);
        m.failed = c.getInt(13);
        m.pending = c.getInt(14);
        m.dests = c.isNull(15) ? "" : c.getString(15);
        return m;
    }

    /** Newest first. Optionally only messages that went (or are going) to one destination. */
    public List<Msg> log(long destId, int limit) {
        List<Msg> l = new ArrayList<>();
        String where = destId > 0 ? " WHERE m.id IN (SELECT msg_id FROM dels WHERE dest_id=" + destId + ")" : "";
        try (Cursor c = getReadableDatabase().rawQuery(MSG_SELECT + where + " GROUP BY m.id ORDER BY m.at DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) l.add(readMsg(c));
        }
        return l;
    }

    public List<Delivery> deliveries(long msgId) {
        List<Delivery> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, msg_id, dest_id, dest, status, attempts, next_at, code, error, updated_at FROM dels WHERE msg_id=? ORDER BY id", args(msgId))) {
            while (c.moveToNext()) l.add(readDel(c));
        }
        return l;
    }

    private static Delivery readDel(Cursor c) {
        Delivery d = new Delivery();
        d.id = c.getLong(0);
        d.msgId = c.getLong(1);
        d.destId = c.getLong(2);
        d.dest = c.getString(3);
        d.status = c.getInt(4);
        d.attempts = c.getInt(5);
        d.nextAt = c.getLong(6);
        d.code = c.isNull(7) ? null : c.getInt(7);
        d.error = c.getString(8);
        d.updatedAt = c.getLong(9);
        return d;
    }

    /**
     * Atomically takes up to {@code limit} deliveries that are due (or stuck mid-send) and marks them SENDING,
     * so the SMS receiver and the background job never send the same delivery twice at once.
     * {@code onlyMsgIds} restricts the claim to freshly received messages (the fast path).
     */
    public synchronized List<Delivery> claimDue(long now, int limit, List<Long> onlyMsgIds) {
        SQLiteDatabase db = getWritableDatabase();
        List<Delivery> l = new ArrayList<>();
        db.beginTransaction();
        try {
            String filter = "";
            if (onlyMsgIds != null) {
                StringBuilder b = new StringBuilder();
                for (Long id : onlyMsgIds) b.append(b.length() > 0 ? "," : "").append(id);
                filter = " AND msg_id IN (" + b + ")";
            }
            try (Cursor c = db.rawQuery("SELECT id, msg_id, dest_id, dest, status, attempts, next_at, code, error, updated_at FROM dels"
                    + " WHERE ((status=0 AND next_at<=?) OR (status=3 AND updated_at<?))" + filter + " ORDER BY next_at LIMIT " + limit,
                    new String[]{String.valueOf(now), String.valueOf(now - STALE_SENDING_MS)})) {
                while (c.moveToNext()) l.add(readDel(c));
            }
            for (Delivery d : l) {
                ContentValues v = new ContentValues();
                v.put("status", SENDING);
                v.put("updated_at", now);
                db.update("dels", v, "id=?", args(d.id));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return l;
    }

    public void finishDelivery(long id, int status, int attempts, long nextAt, Integer code, String error) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("attempts", attempts);
        v.put("next_at", nextAt);
        if (code == null) v.putNull("code");
        else v.put("code", code);
        v.put("error", error);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("dels", v, "id=?", args(id));
    }

    /** When the next delivery becomes due, or -1 if nothing is waiting. */
    public long nextDueAt() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT MIN(CASE status WHEN 0 THEN next_at ELSE updated_at + " + STALE_SENDING_MS + " END) FROM dels WHERE status IN (0,3)", null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : -1;
        }
    }

    public int countFailedSince(long since) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM dels WHERE status=2 AND updated_at>=?", args(since))) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int countPending() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM dels WHERE status IN (0,3)", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Deliveries to one destination since a time: {total, failed}. */
    public int[] destStats(long destId, long since) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), SUM(status=2) FROM dels d JOIN msgs m ON m.id=d.msg_id WHERE d.dest_id=? AND m.at>=?",
                new String[]{String.valueOf(destId), String.valueOf(since)})) {
            return c.moveToFirst() ? new int[]{c.getInt(0), c.getInt(1)} : new int[]{0, 0};
        }
    }

    public void deleteOlderThan(long ms) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM dels WHERE msg_id IN (SELECT id FROM msgs WHERE at<?) AND status IN (1,2)", new Object[]{ms});
            db.execSQL("DELETE FROM msgs WHERE at<? AND id NOT IN (SELECT msg_id FROM dels)", new Object[]{ms});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void clearLog() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("dels", "status IN (1,2)", null);
        db.execSQL("DELETE FROM msgs WHERE id NOT IN (SELECT msg_id FROM dels)");
    }

    private static String[] args(long id) {
        return new String[]{String.valueOf(id)};
    }
}
