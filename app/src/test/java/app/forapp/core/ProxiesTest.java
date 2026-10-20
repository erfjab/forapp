package app.forapp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ProxiesTest {
    @Test public void socksWithLogin() {
        Proxies.Proxy p = Proxies.parse("socks5://user:pa%40ss@185.120.44.9:1080");
        assertEquals(Proxies.SOCKS5, p.type);
        assertEquals("185.120.44.9", p.host);
        assertEquals(1080, p.port);
        assertEquals("user", p.user);
        assertEquals("pa@ss", p.pass);
        assertTrue(p.auth());
        assertEquals("user@185.120.44.9:1080", p.label());
    }

    @Test public void bareHostPortIsHttp() {
        Proxies.Proxy p = Proxies.parse(" proxy.mydomain.ir:3128 ");
        assertEquals(Proxies.HTTP, p.type);
        assertEquals("proxy.mydomain.ir", p.host);
        assertEquals(3128, p.port);
        assertNull(p.user);
        assertFalse(p.auth());
    }

    @Test public void socksAliases() {
        assertEquals(Proxies.SOCKS5, Proxies.parse("socks://1.2.3.4:1080").type);
        assertEquals(Proxies.SOCKS5, Proxies.parse("SOCKS5H://1.2.3.4:1080").type);
        assertEquals(Proxies.HTTP, Proxies.parse("https://1.2.3.4:8443").type);
    }

    @Test public void ipv6() {
        Proxies.Proxy p = Proxies.parse("socks5://[2001:db8::1]:1080");
        assertEquals("2001:db8::1", p.host);
        assertEquals("[2001:db8::1]:1080", p.hostPort());
    }

    @Test public void urlRoundTrip() {
        Proxies.Proxy p = Proxies.parse("socks5://us%20er:p%3Aw@h.example:9050");
        Proxies.Proxy q = Proxies.parse(p.url());
        assertEquals(p.type, q.type);
        assertEquals(p.host, q.host);
        assertEquals(p.port, q.port);
        assertEquals("us er", q.user);
        assertEquals("p:w", q.pass);
    }

    @Test public void missingPortOrBadType() {
        for (String s : new String[]{"1.2.3.4", "socks5://1.2.3.4", "ftp://1.2.3.4:21", "", "socks5://:1080", "1.2.3.4:99999"}) {
            try {
                Proxies.parse(s);
                fail("should not parse: " + s);
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    @Test public void checkFields() {
        assertNull(Proxies.check("1.2.3.4", "1080"));
        assertNull(Proxies.check("proxy.example.com", "8080"));
        assertTrue(Proxies.check("", "1080") != null);
        assertTrue(Proxies.check("1.2.3.4", "") != null);
        assertTrue(Proxies.check("1.2.3.4", "70000") != null);
        assertTrue(Proxies.check("پروکسی", "1080") != null);
    }
}
