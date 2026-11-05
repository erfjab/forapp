package app.forapp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class FiltersTest {
    private static final TimeZone TEHRAN = TimeZone.getTimeZone("Asia/Tehran");

    /** 2026-11-07 is a Saturday. */
    private static long at(int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TEHRAN);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    @Test public void noFiltersTakeEverything() {
        Db.Dest d = new Db.Dest();
        assertFalse(Filters.any(d));
        assertNull(Filters.reject(d, 5L, at(7, 3, 0), TEHRAN));
        assertNull(Filters.reject(d, null, at(13, 23, 59), TEHRAN));
        assertNull(Filters.summary(d));
    }

    @Test public void switchesOffKeepValuesButTakeEverything() {
        Db.Dest d = new Db.Dest();
        d.minAmount = 1_000_000L;
        d.hourFrom = 8 * 60;
        d.hourTo = 9 * 60;
        assertNull(Filters.reject(d, 10L, at(7, 3, 0), TEHRAN));
        d.amountOn = true;
        assertNotNull(Filters.reject(d, 10L, at(7, 3, 0), TEHRAN));
    }

    @Test public void amountRangeIsInclusive() {
        Db.Dest d = new Db.Dest();
        d.amountOn = true;
        d.minAmount = 1_000_000L;
        d.maxAmount = 50_000_000L;
        long t = at(7, 12, 0);
        assertNull(Filters.reject(d, 1_000_000L, t, TEHRAN));
        assertNull(Filters.reject(d, 50_000_000L, t, TEHRAN));
        assertEquals("کمتر از حداقل مبلغ این مقصد (۱ میلیون ریال)", Filters.reject(d, 999_999L, t, TEHRAN));
        assertEquals("بیشتر از حداکثر مبلغ این مقصد (۵۰ میلیون ریال)", Filters.reject(d, 50_000_001L, t, TEHRAN));
        assertNotNull(Filters.reject(d, null, t, TEHRAN));
    }

    @Test public void onlyMinOrOnlyMax() {
        Db.Dest d = new Db.Dest();
        d.amountOn = true;
        d.maxAmount = 500_000L;
        assertNull(Filters.reject(d, 1L, at(7, 12, 0), TEHRAN));
        assertNotNull(Filters.reject(d, 500_001L, at(7, 12, 0), TEHRAN));
        assertEquals("تا ۵۰۰ هزار ریال", Filters.amountText(null, 500_000L));
        assertEquals("از ۱ میلیون ریال به بالا", Filters.amountText(1_000_000L, null));
        assertEquals("از ۱ میلیون تا ۵۰ میلیون ریال", Filters.amountText(1_000_000L, 50_000_000L));
    }

    @Test public void amountSwitchOnWithNoLimitsIsNoFilter() {
        Db.Dest d = new Db.Dest();
        d.amountOn = true;
        assertFalse(Filters.amountActive(d));
        assertNull(Filters.reject(d, null, at(7, 12, 0), TEHRAN));
    }

    @Test public void hoursSameDayAndOvernight() {
        assertTrue(Filters.inHours(8 * 60, 8 * 60, 22 * 60));
        assertTrue(Filters.inHours(22 * 60 - 1, 8 * 60, 22 * 60));
        assertFalse(Filters.inHours(22 * 60, 8 * 60, 22 * 60));
        assertFalse(Filters.inHours(7 * 60 + 59, 8 * 60, 22 * 60));
        assertTrue(Filters.inHours(23 * 60, 22 * 60, 6 * 60));
        assertTrue(Filters.inHours(0, 22 * 60, 6 * 60));
        assertTrue(Filters.inHours(5 * 60 + 59, 22 * 60, 6 * 60));
        assertFalse(Filters.inHours(6 * 60, 22 * 60, 6 * 60));
        assertFalse(Filters.inHours(12 * 60, 22 * 60, 6 * 60));
        assertTrue(Filters.inHours(12 * 60, 5 * 60, 5 * 60));
    }

    @Test public void hoursFilterUsesReceiveTime() {
        Db.Dest d = new Db.Dest();
        d.hoursOn = true;
        d.hourFrom = 8 * 60;
        d.hourTo = 22 * 60;
        assertNull(Filters.reject(d, 1L, at(7, 8, 0), TEHRAN));
        assertEquals("بیرون از ساعت ۸ تا ۲۲ این مقصد", Filters.reject(d, 1L, at(7, 22, 30), TEHRAN));
        d.hourFrom = 8 * 60 + 30;
        assertEquals("بیرون از ساعت ۸:۳۰ تا ۲۲ این مقصد", Filters.reject(d, 1L, at(7, 8, 0), TEHRAN));
    }

    @Test public void daysStartOnSaturday() {
        assertEquals(0, Filters.dayIndex(at(7, 12, 0), TEHRAN));  // Saturday
        assertEquals(1, Filters.dayIndex(at(8, 12, 0), TEHRAN));  // Sunday
        assertEquals(6, Filters.dayIndex(at(13, 12, 0), TEHRAN)); // Friday
        Db.Dest d = new Db.Dest();
        d.days = Filters.ALL_DAYS & ~(1 << 6); // no Friday
        assertNull(Filters.reject(d, 1L, at(12, 12, 0), TEHRAN));
        assertEquals("جمعه جزو روزهای این مقصد نیست", Filters.reject(d, 1L, at(13, 12, 0), TEHRAN));
    }

    @Test public void daysText() {
        assertNull(Filters.daysText(Filters.ALL_DAYS));
        assertEquals("هیچ روزی", Filters.daysText(0));
        assertEquals("شنبه تا پنج‌شنبه", Filters.daysText(0x3F));
        assertEquals("شنبه، دوشنبه و چهارشنبه", Filters.daysText(1 | 4 | 16));
        assertEquals("شنبه و یکشنبه", Filters.daysText(1 | 2));
        assertEquals("جمعه", Filters.daysText(1 << 6));
    }

    @Test public void money() {
        assertEquals("۱ میلیون", Filters.money(1_000_000));
        assertEquals("۲٫۵ میلیون", Filters.money(2_500_000));
        assertEquals("۱٬۲۳۴٬۵۶۷", Filters.money(1_234_567));
        assertEquals("۵۰۰", Filters.money(500));
        assertEquals("۳ میلیارد", Filters.money(3_000_000_000L));
    }

    @Test public void summary() {
        Db.Dest d = new Db.Dest();
        d.amountOn = true;
        d.minAmount = 1_000_000L;
        d.hoursOn = true;
        d.days = 0x3F;
        assertEquals("از ۱ میلیون ریال به بالا · ساعت ۸ تا ۲۲ · شنبه تا پنج‌شنبه", Filters.summary(d));
    }
}
