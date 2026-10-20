package app.forapp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Real connections through local proxies: the connection test and a request sent through each kind of proxy. */
public class ProxyNetworkTest {
    private static Proxies.Proxy proxy(String type, int port, String user, String pass) {
        Proxies.Proxy p = new Proxies.Proxy();
        p.type = type;
        p.host = "127.0.0.1";
        p.port = port;
        p.user = user;
        p.pass = pass;
        return p;
    }

    private static Request post(String url, String body) {
        Request q = new Request();
        q.method = "POST";
        q.url = url;
        q.body = body;
        q.headers.add(new String[]{"Content-Type", "application/json"});
        return q;
    }

    @Test public void probeThroughHttpConnect() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.HttpProxy hp = new ProxyServers.HttpProxy(null, null)) {
            Proxies.Probe r = Proxies.probe(proxy(Proxies.HTTP, hp.port(), null, null), o.url(), 5);
            assertTrue(r.error, r.ok);
            assertEquals(1, hp.connections.get());
        }
    }

    @Test public void probeThroughHttpConnectNeedsLogin() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.HttpProxy hp = new ProxyServers.HttpProxy("u", "p")) {
            assertTrue(Proxies.probe(proxy(Proxies.HTTP, hp.port(), "u", "p"), o.url(), 5).ok);
            Proxies.Probe bad = Proxies.probe(proxy(Proxies.HTTP, hp.port(), "u", "wrong"), o.url(), 5);
            assertFalse(bad.ok);
            assertTrue(bad.error, bad.error.contains("407"));
        }
    }

    @Test public void probeThroughSocks5() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.Socks5 sp = new ProxyServers.Socks5(null, null)) {
            Proxies.Probe r = Proxies.probe(proxy(Proxies.SOCKS5, sp.port(), null, null), o.url(), 5);
            assertTrue(r.error, r.ok);
            assertEquals(1, sp.connections.get());
        }
    }

    @Test public void probeDeadProxyFails() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200)) {
            Proxies.Probe r = Proxies.probe(proxy(Proxies.SOCKS5, ProxyServers.deadPort(), null, null), o.url(), 3);
            assertFalse(r.ok);
            assertFalse(r.error.isEmpty());
        }
    }

    @Test public void postThroughSocks5WithLogin() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.Socks5 sp = new ProxyServers.Socks5("user", "s3cret")) {
            Forwarder.Result r = Forwarder.post(post(o.url(), "{\"amount\":1000123}"), 5, proxy(Proxies.SOCKS5, sp.port(), "user", "s3cret"));
            assertTrue(r.error, r.ok());
            assertEquals("{\"amount\":1000123}", o.lastBody);
            assertTrue(sp.connections.get() >= 1);
        }
    }

    @Test public void postThroughHttpProxy() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200); ProxyServers.HttpProxy hp = new ProxyServers.HttpProxy(null, null)) {
            Forwarder.Result r = Forwarder.post(post(o.url(), "{\"a\":1}"), 5, proxy(Proxies.HTTP, hp.port(), null, null));
            assertTrue(r.error, r.ok());
            assertEquals("POST /hook HTTP/1.1", o.lastLine);
            assertEquals("{\"a\":1}", o.lastBody);
            assertTrue(hp.connections.get() >= 1);
        }
    }

    @Test public void postThroughDeadProxyGetsNoAnswer() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(200)) {
            Forwarder.Result r = Forwarder.post(post(o.url(), "{}"), 3, proxy(Proxies.HTTP, ProxyServers.deadPort(), null, null));
            assertEquals(0, r.code);
            assertEquals(0, o.connections.get());
        }
    }

    @Test public void serverErrorThroughProxyIsTheServersAnswer() throws Exception {
        try (ProxyServers.Origin o = new ProxyServers.Origin(401); ProxyServers.Socks5 sp = new ProxyServers.Socks5(null, null)) {
            Forwarder.Result r = Forwarder.post(post(o.url(), "{}"), 5, proxy(Proxies.SOCKS5, sp.port(), null, null));
            assertEquals(401, r.code);
            assertTrue(r.answered());
        }
    }
}
