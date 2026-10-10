package app.forapp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class TelegramTest {

    @Test public void marksBecomeTelegramHtml() {
        assertEquals("<b>واریز</b> <i>کج</i> <s>خط</s> <tg-spoiler>پنهان</tg-spoiler> <code>437</code>",
                Telegram.toHtml("**واریز** __کج__ ~~خط~~ ||پنهان|| `437`"));
        assertEquals("<pre>a\nb</pre>", Telegram.toHtml("```\na\nb\n```"));
    }

    @Test public void quoteLinesBecomeOneBlockquote() {
        assertEquals("a\n<blockquote>b\nc</blockquote>\nd", Telegram.toHtml("a\n> b\n>c\nd"));
        Map<String, Object> v = new HashMap<>();
        v.put("body", "بانک ملت\nواریز:1,000");
        assertEquals("<blockquote>بانک ملت\nواریز:1,000</blockquote>", Telegram.text("> {{body}}", v, true));
    }

    @Test public void marksInsideCodeStayLiteral() {
        assertEquals("<code>**not bold**</code>", Telegram.toHtml("`**not bold**`"));
        assertEquals("<pre>__x__</pre>", Telegram.toHtml("```__x__```"));
    }

    @Test public void htmlInTheTemplateIsEscaped() {
        assertEquals("a &lt;b&gt; &amp; c", Telegram.toHtml("a <b> & c"));
        assertEquals("single ** star stays", Telegram.toHtml("single ** star stays"));
    }

    @Test public void smsTextCanNotBreakFormatting() {
        Map<String, Object> v = new HashMap<>();
        v.put("body", "**fake** <a href=x>link</a> & `x`");
        v.put("code", "437");
        String t = Telegram.text("**{{code}}**\n{{body}}", v, true);
        assertEquals("<b>437</b>\n**fake** &lt;a href=x&gt;link&lt;/a&gt; &amp; `x`", t);
    }

    @Test public void plainModeSendsTextAsIs() {
        Map<String, Object> v = new HashMap<>();
        v.put("body", "<b>&</b>");
        assertEquals("**x** <b>&</b>", Telegram.text("**x** {{body}}", v, false));
    }

    @Test public void tokenShape() {
        assertTrue(Telegram.validToken("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"));
        assertFalse(Telegram.validToken("123456789"));
        assertFalse(Telegram.validToken("abc:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"));
    }

    @Test public void apiServerDefaultsAndTrims() {
        assertEquals(Telegram.API, Telegram.api(""));
        assertEquals("https://tg.example.com", Telegram.api("https://tg.example.com/"));
    }
}
