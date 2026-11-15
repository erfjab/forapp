package app.forapp.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ExportTest {
    private static final TimeZone TEHRAN = TimeZone.getTimeZone("Asia/Tehran");

    private static long at(int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TEHRAN);
        c.clear();
        c.set(2026, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static Report.Row row(long id, long at, String bank, Boolean deposit, long amount, String status) {
        Report.Row r = new Report.Row();
        r.id = id;
        r.at = at;
        r.bank = bank;
        r.sender = "+98920123";
        r.deposit = deposit;
        r.amount = amount;
        r.status = status;
        r.body = "واریز " + amount + "\nموجودی: ۱۰";
        r.dests = "حسابداری، تلگرام";
        return r;
    }

    /** 1 Aban 1405 (2026-10-23) to 25 Aban (2026-11-16), as the report screen builds it. */
    private static Report sample() {
        List<Report.Row> l = new ArrayList<>();
        l.add(row(1, at(11, 11, 8, 55), "ملت", true, 6_300_000, Report.SENT));
        l.add(row(2, at(11, 12, 13, 40), "بلو", true, 1_200_000, Report.IGNORED));
        l.add(row(3, at(11, 15, 9, 12), "ملی", false, 2_000_000, Report.FILTERED));
        Report.Row failed = row(4, at(11, 15, 19, 48), "ملت", true, 45_000_000, Report.FAILED);
        failed.failedDests = "حسابداری";
        Report.Delivery d = new Report.Delivery();
        d.dest = "حسابداری";
        d.status = Report.FAILED;
        d.code = 502;
        d.attempts = 5;
        d.error = "HTTP 502, \"bad gateway\"";
        failed.deliveries = new ArrayList<>(List.of(d));
        l.add(failed);
        l.add(row(5, at(11, 16, 14, 32), "سامان", true, 12_500_000, Report.SENT));
        return new Report(at(10, 23, 0, 0), at(11, 17, 0, 0), TEHRAN, l);
    }

    @Test public void jalaliRoundTripsEveryDayForYears() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2015, Calendar.JANUARY, 1);
        for (int i = 0; i < 365 * 20; i++) {
            int[] g = {c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)};
            int[] j = Fa.toJalali(g[0], g[1], g[2]);
            assertArrayEquals("day " + g[0] + "-" + g[1] + "-" + g[2], g, Fa.toGregorian(j[0], j[1], j[2]));
            assertTrue(j[2] <= Fa.monthLength(j[0], j[1]));
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    @Test public void knownDatesAndMonthLengths() {
        assertArrayEquals(new int[]{2026, 11, 16}, Fa.toGregorian(1405, 8, 25));
        assertArrayEquals(new int[]{2026, 3, 21}, Fa.toGregorian(1405, 1, 1));
        assertEquals(30, Fa.monthLength(1403, 12)); // 1403 is a leap year
        assertEquals(29, Fa.monthLength(1404, 12));
        assertEquals(31, Fa.monthLength(1405, 6));
        assertEquals(30, Fa.monthLength(1405, 7));
        assertEquals(at(11, 16, 0, 0), Fa.jalaliDayStart(1405, 8, 25, TEHRAN));
    }

    @Test public void summaryLeavesIgnoredAndWithdrawalsOut() {
        Report r = sample();
        assertEquals(25, r.days());
        assertEquals(3, r.deposits);
        assertEquals(63_800_000, r.total);
        assertEquals(2, r.sent);
        assertEquals(1, r.failed);
        assertEquals(1, r.ignored);
        assertEquals(1, r.filtered);
        assertEquals("ملت", r.banks.get(0).bank);
        assertEquals(51_300_000, r.banks.get(0).sum);
        assertEquals(25, r.slots.length);
        assertEquals(12_500_000, r.slots[24]);
        assertEquals(45_000_000, r.badSlots[23]);
        assertEquals("forapp-report-1405-08-01_1405-08-25.csv", r.fileName("csv"));
        assertEquals("۱ آبان تا ۲۵ آبان ۱۴۰۵", r.label());
    }

    @Test public void oneDayIsHourly() {
        List<Report.Row> l = new ArrayList<>();
        l.add(row(1, at(11, 16, 14, 32), "سامان", true, 100, Report.SENT));
        Report r = new Report(at(11, 16, 0, 0), at(11, 17, 0, 0), TEHRAN, l);
        assertEquals(1, r.days());
        assertEquals(24, r.slots.length);
        assertEquals(100, r.slots[14]);
        assertEquals("forapp-report-1405-08-25.xlsx", r.fileName("xlsx"));
    }

    @Test public void csvQuotingAndFormulaGuard() {
        assertEquals("plain", Export.csvCell("plain"));
        assertEquals("\"a,b\"", Export.csvCell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Export.csvCell("say \"hi\""));
        assertEquals("\"line\nbreak\"", Export.csvCell("line\nbreak"));
        assertEquals("'+98920", Export.csvCell("+98920"));
        assertEquals("'=SUM(A1)", Export.csvCell("=SUM(A1)"));
        assertEquals("", Export.csvCell(null));
    }

    @Test public void csvFile() throws Exception {
        Export.Options o = new Export.Options();
        o.body = true;
        String s = write(Export.CSV, o);
        assertTrue(s.startsWith("﻿id,date_jalali,"));
        // Bodies keep their line break inside quotes; only rows end in \r\n.
        assertEquals("header + 5 rows", 6, s.split("\r\n").length);
        assertTrue(s.contains("4,1405/08/24,19:48,2026-11-15T19:48:00+03:30,ملت,'+98920123,deposit,45000000,failed,"));
    }

    @Test public void jsonEscapesAndHoldsEveryRow() throws Exception {
        Export.Options o = new Export.Options();
        o.deliveries = true;
        o.filters.put("bank", "all");
        o.filters.put("include_ignored", true);
        String s = write(Export.JSON, o);
        assertTrue(s.contains("\"app\": \"forapp-report\""));
        assertTrue(s.contains("\"deposit_total_rial\": 63800000"));
        assertTrue(s.contains("\"error\": \"HTTP 502, \\\"bad gateway\\\"\""));
        assertTrue(s.contains("\"include_ignored\": true"));
        assertEquals(5, count(s, "\"amount_rial\""));
        assertEquals(count(s, "{"), count(s, "}"));
        assertEquals(count(s, "["), count(s, "]"));
        assertEquals("\"a\\nb\\u0001\"", Export.jsonString("a\nb\u0001"));
    }

    @Test public void textFile() throws Exception {
        String s = write(Export.TXT, new Export.Options());
        assertTrue(s.startsWith("گزارش فوراپ\n"));
        assertTrue(s.contains("۶۳٬۸۰۰٬۰۰۰ ریال (۳ واریز)"));
        assertTrue(s.contains("ناموفق: حسابداری"));
        assertFalse("body only when asked", s.contains("موجودی"));
    }

    @Test public void xlsxIsAWorkbookWithItsSheets() throws Exception {
        Export.Options o = new Export.Options();
        o.deliveries = true;
        Map<String, String> parts = unzip(writeBytes(Export.XLSX, o));
        assertTrue(parts.containsKey("[Content_Types].xml"));
        assertTrue(parts.containsKey("_rels/.rels"));
        assertTrue(parts.containsKey("xl/styles.xml"));
        String book = parts.get("xl/workbook.xml");
        assertTrue(book.contains("name=\"پیامک‌ها\""));
        assertTrue(book.contains("name=\"خلاصه\""));
        assertTrue(book.contains("name=\"بانک‌ها\""));
        assertTrue(book.contains("name=\"ارسال‌ها\""));
        for (int i = 1; i <= 4; i++) {
            String sh = parts.get("xl/worksheets/sheet" + i + ".xml");
            assertTrue("sheet " + i, sh != null && sh.contains("rightToLeft=\"1\""));
            assertTrue(parts.get("[Content_Types].xml").contains("/xl/worksheets/sheet" + i + ".xml"));
        }
        String tx = parts.get("xl/worksheets/sheet1.xml");
        assertTrue("amount is a grouped number", tx.contains("<c r=\"G5\" s=\"2\"><v>45000000</v></c>"));
        assertTrue(tx.contains("state=\"frozen\""));
        assertFalse("no body column unless asked", tx.contains("موجودی"));
    }

    @Test public void xmlDropsControlCharacters() {
        assertEquals("a&amp;b&lt;c&gt;\"", Export.xml("a&b<c>\u0001\"").replace("&quot;", "\""));
        assertEquals("A", Export.column(0));
        assertEquals("Z", Export.column(25));
        assertEquals("AA", Export.column(26));
    }

    // ---------- helpers ----------

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }

    private static byte[] writeBytes(String fmt, Export.Options o) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Export.write(fmt, sample(), o, at(11, 16, 14, 33), out);
        return out.toByteArray();
    }

    private static String write(String fmt, Export.Options o) throws Exception {
        return new String(writeBytes(fmt, o), StandardCharsets.UTF_8);
    }

    private static Map<String, String> unzip(byte[] b) throws Exception {
        Map<String, String> m = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[4096];
                for (int n; (n = z.read(tmp)) > 0; ) buf.write(tmp, 0, n);
                m.put(e.getName(), buf.toString("UTF-8"));
            }
        }
        return m;
    }
}
