package app.forapp.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Sample texts in the shapes Iranian banks commonly use. Add real ones from your own bank when a format is missed. */
public class SmsParserTest {

    private static SmsParser.Result p(String s) {
        return SmsParser.parse(s);
    }

    @Test public void multiLineDeposit() {
        SmsParser.Result r = p("بانک ملت\nواریز:2,500,487\nحساب:9812*\nمانده:15,200,000\n1405/07/16-14:20");
        assertEquals(Long.valueOf(2_500_487), r.amount);
        assertEquals(Boolean.TRUE, r.deposit);
        assertEquals("487", SmsParser.code(r.amount));
    }

    @Test public void persianDigitsAndSeparators() {
        SmsParser.Result r = p("واریز به حساب ۰۱۲۳۴۵۶۷۸۹\nمبلغ: ۲٬۵۰۰٬۴۸۷ ریال\nمانده: ۱۰٬۰۰۰٬۰۰۰\n۱۴۰۵/۰۷/۱۶ ۱۴:۲۰");
        assertEquals(Long.valueOf(2_500_487), r.amount);
        assertEquals("rial", r.unit);
        assertEquals(Boolean.TRUE, r.deposit);
    }

    @Test public void singleLineWithAccountAndBalance() {
        SmsParser.Result r = p("واریز به حساب 849-800-1234567-1 مبلغ 890,132 ریال 07/16_14:08 مانده 12,000,000");
        assertEquals(Long.valueOf(890_132), r.amount);
        assertEquals(Boolean.TRUE, r.deposit);
    }

    @Test public void plusSignDeposit() {
        SmsParser.Result r = p("حساب 1234\n+1,200,056\n1405/07/16\nمانده 3,000,000");
        assertEquals(Long.valueOf(1_200_056), r.amount);
        assertEquals(Boolean.TRUE, r.deposit);
    }

    @Test public void minusSignWithdrawal() {
        SmsParser.Result r = p("حساب 1234\n450,000-\n1405/07/16\nمانده 3,000,000");
        assertEquals(Long.valueOf(450_000), r.amount);
        assertEquals(Boolean.FALSE, r.deposit);
    }

    @Test public void purchaseIsWithdrawal() {
        SmsParser.Result r = p("بانک سامان\nخرید: 350,000 ریال\nکارت 6037****1234\nمانده: 1,000,000");
        assertEquals(Long.valueOf(350_000), r.amount);
        assertEquals(Boolean.FALSE, r.deposit);
    }

    @Test public void tomanUnit() {
        SmsParser.Result r = p("مبلغ 250,719 تومان به حساب شما واریز شد");
        assertEquals(Long.valueOf(250_719), r.amount);
        assertEquals("toman", r.unit);
        assertEquals(Boolean.TRUE, r.deposit);
    }

    @Test public void maskedCardIsNotAmount() {
        SmsParser.Result r = p("انتقال به حساب شما\nاز کارت 6037-9912-3456-7890\nمبلغ 1,500,029");
        assertEquals(Long.valueOf(1_500_029), r.amount);
        assertEquals(Boolean.TRUE, r.deposit);
    }

    @Test public void oneTimePasswordIsNotDeposit() {
        SmsParser.Result r = p("رمز پویا: 583921\nمعتبر تا 2 دقیقه");
        assertTrue(r.deposit == null || !r.deposit);
    }

    @Test public void codeKeepsLeadingZeros() {
        assertEquals("056", SmsParser.code(1_200_056L));
        assertNull(SmsParser.code(null));
    }

    @Test public void senderVariantsMatch() {
        assertTrue(SmsParser.senderMatches("+98 9000 1234", "009890001234"));
        assertTrue(SmsParser.senderMatches("+989000 1234", "090001234"));
        assertTrue(SmsParser.senderMatches("BankMellat", "bankmellat"));
        assertTrue(SmsParser.senderMatches("۳۰۰۰۱۲", "300012"));
        assertFalse(SmsParser.senderMatches("300012", "3000123"));
        assertFalse(SmsParser.senderMatches("", "300012"));
    }

    @Test public void jalaliDates() {
        assertArrayEquals(new int[]{1405, 7, 16}, Fa.toJalali(2026, 10, 8));
        assertArrayEquals(new int[]{1403, 1, 1}, Fa.toJalali(2024, 3, 20));
        assertArrayEquals(new int[]{1402, 12, 29}, Fa.toJalali(2024, 3, 19));
    }

    @Test public void persianNumbers() {
        assertEquals("۲٬۵۰۰٬۴۸۷", Fa.group(2_500_487));
        assertEquals("۴۸۷", Fa.group(487));
        assertEquals("123", Fa.ascii("۱۲۳"));
    }

    @Test public void backoffGrowsAndCaps() {
        assertEquals(15_000, Forwarder.backoffMs(1));
        assertEquals(30_000, Forwarder.backoffMs(2));
        assertEquals(30 * 60_000, Forwarder.backoffMs(20));
    }
}
