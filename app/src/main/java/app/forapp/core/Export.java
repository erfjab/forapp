package app.forapp.core;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a {@link Report} as plain text, JSON, CSV or Excel (.xlsx). Pure Java and no library:
 * the .xlsx is a zip of a few small XML files, written by hand.
 */
public final class Export {
    private Export() {}

    public static final String TXT = "txt", JSON = "json", CSV = "csv", XLSX = "xlsx";
    public static final String[] FORMATS = {XLSX, CSV, JSON, TXT};

    public static final class Options {
        public boolean summary = true, deliveries, body;
        /** For people: "بانک: همه · نوع: فقط واریز". */
        public String filterText = "";
        /** For programs: {"banks": "all", "type": "deposit", ...}. */
        public final Map<String, Object> filters = new LinkedHashMap<>();
    }

    public static String mime(String fmt) {
        switch (fmt) {
            case XLSX: return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case CSV: return "text/csv";
            case JSON: return "application/json";
            default: return "text/plain";
        }
    }

    public static void write(String fmt, Report r, Options o, long now, OutputStream out) throws IOException {
        switch (fmt) {
            case XLSX: xlsx(r, o, now, out); break;
            case CSV: csv(r, o, out); break;
            case JSON: json(r, o, now, out); break;
            default: txt(r, o, now, out);
        }
    }

    // ---------- shared ----------

    private static String jdate(Report r, long ms) {
        int[] j = Fa.jalali(ms, r.tz);
        return String.format(Locale.US, "%04d/%02d/%02d", j[0], j[1], j[2]);
    }

    private static String clock(Report r, long ms) {
        Calendar c = Calendar.getInstance(r.tz);
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static String iso(Report r, long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        f.setTimeZone(r.tz);
        return f.format(new Date(ms));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String deliveryText(Report.Delivery d) {
        StringBuilder b = new StringBuilder(nz(d.dest)).append(": ").append(Report.statusLabel(d.status));
        if (d.code != null) b.append("، HTTP ").append(d.code);
        if (d.attempts > 1) b.append("، ").append(d.attempts).append(" تلاش");
        if (d.error != null && !d.error.isEmpty() && !Report.SENT.equals(d.status)) b.append("، ").append(d.error);
        return b.toString();
    }

    // ---------- text ----------

    private static void txt(Report r, Options o, long now, OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        String nl = "\n", sep = " · ";
        w.write("گزارش فوراپ" + nl);
        w.write("بازه: " + r.label() + sep + "ساخته‌شده " + Fa.date(now, r.tz, true) + " ساعت " + Fa.time(now, r.tz) + nl);
        if (!o.filterText.isEmpty()) w.write(o.filterText + nl);
        if (o.summary) {
            w.write(nl + "جمع واریزها: " + Fa.group(r.total) + " ریال (" + Fa.d(r.deposits) + " واریز)" + nl);
            w.write(statusLine(r) + nl);
            if (!r.banks.isEmpty()) {
                w.write(nl + "به تفکیک بانک" + nl);
                for (Report.BankTotal b : r.banks)
                    w.write("  " + b.bank + sep + Fa.d(b.count) + " واریز" + sep + Fa.group(b.sum) + " ریال" + nl);
            }
        }
        w.write(nl + "پیامک‌ها (" + Fa.d(r.rows.size()) + ")" + nl);
        for (Report.Row x : r.rows) {
            String line = Fa.date(x.at, r.tz, false) + " " + Fa.time(x.at, r.tz) + sep + (x.bank.isEmpty() ? nz(x.sender) : x.bank)
                    + sep + Report.typeLabel(x.deposit) + sep + (x.amount == null ? "—" : Fa.group(x.amount)) + sep + Report.statusLabel(x.status);
            if (Report.FAILED.equals(x.status) && !x.failedDests.isEmpty()) line += ": " + x.failedDests;
            w.write(line + nl);
            if (o.deliveries && x.deliveries != null)
                for (Report.Delivery d : x.deliveries) w.write("    ← " + deliveryText(d) + nl);
            if (o.body && x.body != null) w.write("    " + x.body.replace("\r", "").replace("\n", "\n    ") + nl);
        }
        w.flush();
    }

    private static String statusLine(Report r) {
        List<String> p = new ArrayList<>();
        if (r.sent > 0) p.add("ارسال‌شده " + Fa.d(r.sent));
        if (r.failed > 0) p.add("ناموفق " + Fa.d(r.failed));
        if (r.pending > 0) p.add("در صف " + Fa.d(r.pending));
        if (r.filtered > 0) p.add("کنار گذاشته " + Fa.d(r.filtered));
        if (r.ignored > 0) p.add("نادیده " + Fa.d(r.ignored));
        if (r.skipped > 0) p.add("فرستاده نشد " + Fa.d(r.skipped));
        return p.isEmpty() ? "پیامکی نیست" : String.join(" · ", p);
    }

    // ---------- CSV ----------

    /** RFC 4180 quoting; text that a spreadsheet would run as a formula gets a leading apostrophe. */
    static String csvCell(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
            return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    private static void csv(Report r, Options o, OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write('﻿'); // so Excel on Windows reads it as UTF-8
        w.write("id,date_jalali,time,datetime_iso,bank,sender,type,amount_rial,status,destinations,failed");
        if (o.deliveries) w.write(",deliveries");
        if (o.body) w.write(",body");
        w.write("\r\n");
        for (Report.Row x : r.rows) {
            StringBuilder b = new StringBuilder();
            b.append(x.id).append(',').append(jdate(r, x.at)).append(',').append(clock(r, x.at)).append(',').append(iso(r, x.at)).append(',')
                    .append(csvCell(x.bank)).append(',').append(csvCell(x.sender)).append(',').append(x.type()).append(',')
                    .append(x.amount == null ? "" : x.amount).append(',').append(x.status).append(',')
                    .append(csvCell(x.dests)).append(',').append(csvCell(x.failedDests));
            if (o.deliveries) {
                List<String> d = new ArrayList<>();
                if (x.deliveries != null) for (Report.Delivery e : x.deliveries) d.add(deliveryText(e));
                b.append(',').append(csvCell(String.join(" | ", d)));
            }
            if (o.body) b.append(',').append(csvCell(x.body));
            w.write(b.append("\r\n").toString());
        }
        w.flush();
    }

    // ---------- JSON ----------

    static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return jsonString(v.toString());
    }

    private static void json(Report r, Options o, long now, OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write("{\n  \"app\": \"forapp-report\",\n  \"version\": 1,\n  \"exported_at\": " + now + ",\n");
        w.write("  \"range\": {\"from\": " + r.from + ", \"to\": " + (r.to - 1) + ", \"label\": " + jsonString(r.label()) + "},\n");
        w.write("  \"filters\": {");
        int i = 0;
        for (Map.Entry<String, Object> e : o.filters.entrySet())
            w.write((i++ > 0 ? ", " : "") + jsonString(e.getKey()) + ": " + jsonValue(e.getValue()));
        w.write("},\n");
        if (o.summary) {
            w.write("  \"summary\": {\"deposits\": " + r.deposits + ", \"deposit_total_rial\": " + r.total + ", \"sent\": " + r.sent
                    + ", \"failed\": " + r.failed + ", \"pending\": " + r.pending + ", \"filtered\": " + r.filtered
                    + ", \"ignored\": " + r.ignored + ", \"skipped\": " + r.skipped + ",\n    \"by_bank\": [");
            i = 0;
            for (Report.BankTotal b : r.banks)
                w.write((i++ > 0 ? ", " : "") + "{\"bank\": " + jsonString(b.bank) + ", \"count\": " + b.count + ", \"total_rial\": " + b.sum + "}");
            w.write("]},\n");
        }
        w.write("  \"transactions\": [");
        i = 0;
        for (Report.Row x : r.rows) {
            w.write((i++ > 0 ? "," : "") + "\n    {\"id\": " + x.id + ", \"at\": " + x.at + ", \"date_jalali\": " + jsonString(jdate(r, x.at))
                    + ", \"time\": " + jsonString(clock(r, x.at)) + ", \"bank\": " + jsonString(x.bank) + ", \"sender\": " + jsonString(x.sender)
                    + ", \"type\": " + jsonString(x.type()) + ", \"amount_rial\": " + jsonValue(x.amount) + ", \"status\": " + jsonString(x.status));
            if (o.deliveries) {
                w.write(", \"deliveries\": [");
                int k = 0;
                if (x.deliveries != null) for (Report.Delivery d : x.deliveries)
                    w.write((k++ > 0 ? ", " : "") + "{\"dest\": " + jsonString(d.dest) + ", \"status\": " + jsonString(d.status)
                            + ", \"code\": " + jsonValue(d.code) + ", \"attempts\": " + d.attempts + ", \"error\": " + jsonString(d.error) + "}");
                w.write("]");
            } else {
                w.write(", \"destinations\": " + jsonString(x.dests));
            }
            if (o.body) w.write(", \"body\": " + jsonString(x.body));
            w.write("}");
        }
        w.write(i > 0 ? "\n  ]\n}\n" : "]\n}\n");
        w.flush();
    }

    // ---------- XLSX ----------

    /** A rial amount: a number cell shown with thousands separators. */
    static final class Money {
        final long v;
        Money(long v) { this.v = v; }
    }

    static final class Sheet {
        final String name;
        final int[] widths;
        final List<Object[]> rows = new ArrayList<>();
        Sheet(String name, int[] widths, Object... header) {
            this.name = name;
            this.widths = widths;
            rows.add(header);
        }
        void add(Object... cells) { rows.add(cells); }
    }

    private static Object money(Long v) {
        return v == null ? null : new Money(v);
    }

    private static void xlsx(Report r, Options o, long now, OutputStream out) throws IOException {
        List<Sheet> sheets = new ArrayList<>();
        Sheet tx = o.body
                ? new Sheet("پیامک‌ها", new int[]{8, 12, 7, 12, 16, 9, 15, 12, 22, 18, 26, 60},
                "شناسه", "تاریخ", "ساعت", "بانک", "فرستنده", "نوع", "مبلغ (ریال)", "وضعیت", "مقصدها", "ناموفق", "زمان (ISO)", "متن پیامک")
                : new Sheet("پیامک‌ها", new int[]{8, 12, 7, 12, 16, 9, 15, 12, 22, 18, 26},
                "شناسه", "تاریخ", "ساعت", "بانک", "فرستنده", "نوع", "مبلغ (ریال)", "وضعیت", "مقصدها", "ناموفق", "زمان (ISO)");
        for (Report.Row x : r.rows) {
            Object[] c = {x.id, jdate(r, x.at), clock(r, x.at), x.bank, x.sender, Report.typeLabel(x.deposit), money(x.amount),
                    Report.statusLabel(x.status), x.dests, x.failedDests, iso(r, x.at), x.body};
            tx.add(o.body ? c : java.util.Arrays.copyOf(c, 11));
        }
        sheets.add(tx);
        if (o.summary) {
            Sheet s = new Sheet("خلاصه", new int[]{22, 34}, "", "");
            s.rows.clear();
            s.add("گزارش فوراپ", "");
            s.add("بازه", r.label());
            s.add("ساخته‌شده", Fa.full(now, r.tz));
            if (!o.filterText.isEmpty()) s.add("فیلتر", o.filterText);
            s.add("تعداد واریز", r.deposits);
            s.add("جمع واریز (ریال)", new Money(r.total));
            s.add("ارسال‌شده", r.sent);
            s.add("ناموفق", r.failed);
            s.add("در صف", r.pending);
            s.add("کنار گذاشته", r.filtered);
            s.add("نادیده", r.ignored);
            s.add("فرستاده نشد", r.skipped);
            sheets.add(s);
            Sheet b = new Sheet("بانک‌ها", new int[]{16, 10, 18}, "بانک", "تعداد واریز", "جمع (ریال)");
            for (Report.BankTotal t : r.banks) b.add(t.bank, t.count, new Money(t.sum));
            sheets.add(b);
        }
        if (o.deliveries) {
            Sheet d = new Sheet("ارسال‌ها", new int[]{10, 12, 7, 18, 12, 9, 7, 40},
                    "شناسه پیامک", "تاریخ", "ساعت", "مقصد", "وضعیت", "کد پاسخ", "تلاش", "خطا");
            for (Report.Row x : r.rows) if (x.deliveries != null) for (Report.Delivery e : x.deliveries)
                d.add(x.id, jdate(r, x.at), clock(r, x.at), e.dest, Report.statusLabel(e.status), e.code, e.attempts,
                        Report.SENT.equals(e.status) ? null : e.error);
            sheets.add(d);
        }

        ZipOutputStream z = new ZipOutputStream(out);
        StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        StringBuilder book = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView/></bookViews><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheets.size(); i++) {
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            book.append("<sheet name=\"").append(xml(sheets.get(i - 1).name)).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        rels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        entry(z, "[Content_Types].xml", types.append("</Types>").toString());
        entry(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        entry(z, "xl/workbook.xml", book.append("</sheets></workbook>").toString());
        entry(z, "xl/_rels/workbook.xml.rels", rels.toString());
        entry(z, "xl/styles.xml", STYLES);
        for (int i = 0; i < sheets.size(); i++) {
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet" + (i + 1) + ".xml"));
            Writer w = new OutputStreamWriter(z, StandardCharsets.UTF_8);
            sheet(w, sheets.get(i), i == 0 || sheets.get(i).name.equals("بانک‌ها") || sheets.get(i).name.equals("ارسال‌ها"));
            w.flush();
            z.closeEntry();
        }
        z.finish();
    }

    // Styles: 0 plain, 1 bold (header), 2 number with thousands separators (built-in format 3).
    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Tahoma\"/></font><font><b/><sz val=\"11\"/><name val=\"Tahoma\"/></font></fonts>"
            + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";

    private static void entry(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static void sheet(Writer w, Sheet s, boolean header) throws IOException {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\">");
        if (header) w.write("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>");
        w.write("</sheetView></sheetViews><cols>");
        for (int i = 0; i < s.widths.length; i++)
            w.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + s.widths[i] + "\" customWidth=\"1\"/>");
        w.write("</cols><sheetData>");
        for (int r = 0; r < s.rows.size(); r++) {
            Object[] row = s.rows.get(r);
            w.write("<row r=\"" + (r + 1) + "\">");
            for (int c = 0; c < row.length; c++) {
                Object v = row[c];
                if (v == null || "".equals(v)) continue;
                String ref = column(c) + (r + 1);
                boolean bold = (header && r == 0) || (!header && c == 0);
                if (v instanceof Money) w.write("<c r=\"" + ref + "\" s=\"2\"><v>" + ((Money) v).v + "</v></c>");
                else if (v instanceof Number) w.write("<c r=\"" + ref + "\"" + (bold ? " s=\"1\"" : "") + "><v>" + v + "</v></c>");
                else w.write("<c r=\"" + ref + "\" t=\"inlineStr\"" + (bold ? " s=\"1\"" : "") + "><is><t xml:space=\"preserve\">" + xml(v.toString()) + "</t></is></c>");
            }
            w.write("</row>");
        }
        w.write("</sheetData></worksheet>");
    }

    static String column(int i) {
        String s = "";
        for (i++; i > 0; i = (i - 1) / 26) s = (char) ('A' + (i - 1) % 26) + s;
        return s;
    }

    /** XML text, without the control characters XML 1.0 does not allow (an SMS may carry them). */
    static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        if (c != '￾' && c != '￿') b.append(c);
                    }
            }
        }
        return b.toString();
    }
}
