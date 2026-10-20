package app.forapp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

/** On a real Android network stack: one pass through a destination's proxies, in order, as {@link Forwarder} sends. */
@RunWith(AndroidJUnit4.class)
public class ProxyPassTest {
    private final Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static Proxies.Proxy proxy(String type, int port, String user, String pass) {
        Proxies.Proxy p = new Proxies.Proxy();
        p.type = type;
        p.host = "127.0.0.1";
        p.port = port;
        p.user = user;
        p.pass = pass;
        return p;
    }

    private static Db.Dest dest(boolean on, boolean direct, Proxies.Proxy... ps) {
        Db.Dest d = new Db.Dest();
        d.name = "test";
        d.proxyOn = on;
        d.proxyDirect = direct;
        d.proxies = new ArrayList<>(Arrays.asList(ps));
        return d;
    }

    private static Request post(String url) {
        Request q = new Request();
        q.method = "POST";
        q.url = url;
        q.body = "{\"amount\":1000123}";
        q.headers.add(new String[]{"Content-Type", "application/json"});
        return q;
    }

    @Test public void firstDeadSecondCarries() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.Socks5 sp = new ProxyServers.Socks5("user", "s3cret")) {
            Forwarder.Pass p = Forwarder.send(c, dest(true, false,
                    proxy(Proxies.HTTP, ProxyServers.deadPort(), null, null),
                    proxy(Proxies.SOCKS5, sp.port(), "user", "s3cret")), post(o.url()), 5);
            assertTrue(p.result.error, p.result.ok());
            assertEquals("پروکسی ۲ · SOCKS5", p.via);
            String[] lines = p.trail.split("\n");
            assertEquals(2, lines.length);
            assertTrue(lines[0], lines[0].contains("\tfail\t"));
            assertTrue(lines[1], lines[1].contains("\tok\t"));
            assertEquals("{\"amount\":1000123}", o.lastBody);
            assertFalse(p.allProxiesFailed);
        }
    }

    @Test public void httpProxyLogin() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.HttpProxy hp = new ProxyServers.HttpProxy("u", "p@ss")) {
            Forwarder.Pass p = Forwarder.send(c, dest(true, false, proxy(Proxies.HTTP, hp.port(), "u", "p@ss")), post(o.url()), 5);
            assertTrue(p.result.error, p.result.ok());
            assertEquals("پروکسی ۱ · HTTP", p.via);
        }
    }

    @Test public void wrongSocksPasswordFailsThePass() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.Socks5 sp = new ProxyServers.Socks5("user", "s3cret")) {
            Forwarder.Pass p = Forwarder.send(c, dest(true, false, proxy(Proxies.SOCKS5, sp.port(), "user", "nope")), post(o.url()), 5);
            assertTrue(p.allProxiesFailed);
            assertEquals(0, o.connections.get());
        }
    }

    @Test public void everyProxyDeadIsOneFailedPass() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200)) {
            Forwarder.Pass p = Forwarder.send(c, dest(true, false,
                    proxy(Proxies.SOCKS5, ProxyServers.deadPort(), null, null),
                    proxy(Proxies.HTTP, ProxyServers.deadPort(), null, null)), post(o.url()), 3);
            assertTrue(p.allProxiesFailed);
            assertEquals(0, p.result.code);
            assertNull(p.via);
            assertEquals(2, p.trail.split("\n").length);
            assertEquals(0, o.connections.get());
        }
    }

    @Test public void directAtTheEndWhenAllowed() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200)) {
            Forwarder.Pass p = Forwarder.send(c, dest(true, true, proxy(Proxies.SOCKS5, ProxyServers.deadPort(), null, null)), post(o.url()), 3);
            assertTrue(p.result.ok());
            assertEquals("مستقیم", p.via);
            assertFalse(p.allProxiesFailed);
        }
    }

    @Test public void serverAnswerEndsThePass() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(401);
             ProxyServers.Socks5 first = new ProxyServers.Socks5(null, null);
             ProxyServers.Socks5 second = new ProxyServers.Socks5(null, null)) {
            Forwarder.Pass p = Forwarder.send(c, dest(true, false,
                    proxy(Proxies.SOCKS5, first.port(), null, null), proxy(Proxies.SOCKS5, second.port(), null, null)), post(o.url()), 5);
            assertEquals(401, p.result.code);
            assertEquals("پروکسی ۱ · SOCKS5", p.via);
            assertEquals(0, second.connections.get());
        }
    }

    @Test public void proxiesOffSendsDirect() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.Socks5 sp = new ProxyServers.Socks5(null, null)) {
            Forwarder.Pass p = Forwarder.send(c, dest(false, false, proxy(Proxies.SOCKS5, sp.port(), null, null)), post(o.url()), 5);
            assertTrue(p.result.ok());
            assertNull(p.via);
            assertNull(p.trail);
            assertEquals(0, sp.connections.get());
        }
    }

    @Test public void probeThroughSocksWithLogin() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.Socks5 sp = new ProxyServers.Socks5("user", "s3cret")) {
            Proxies.Probe ok = Proxies.probe(proxy(Proxies.SOCKS5, sp.port(), "user", "s3cret"), o.url(), 5);
            assertTrue(ok.error, ok.ok);
            assertFalse(Proxies.probe(proxy(Proxies.SOCKS5, sp.port(), "user", "nope"), o.url(), 5).ok);
        }
    }
}
