package app.forapp.core;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Proxies a destination sends through: HTTP (CONNECT for https) or SOCKS5, each with an optional user and password.
 * Parsing is pure Java so it can be unit tested; credentials reach the platform through one process-wide {@link Authenticator}.
 */
public final class Proxies {
    private Proxies() {}

    public static final String HTTP = "http", SOCKS5 = "socks5";

    public static final class Proxy {
        public long id;
        public String type = SOCKS5, host = "", user, pass;
        public int port;
        // The last test or send through it; lastOk null means never tried.
        public Boolean lastOk;
        public long lastMs, lastAt;
        public String lastError;

        public boolean socks() { return SOCKS5.equals(type); }
        public boolean auth() { return user != null && !user.isEmpty(); }
        public String typeLabel() { return socks() ? "SOCKS5" : "HTTP"; }

        /** host:port, with the user in front when there is one. */
        public String label() { return (auth() ? user + "@" : "") + hostPort(); }

        public String hostPort() { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }

        /** The Proxy-Authorization value for an HTTP proxy's login. */
        public String basicAuth() {
            String cred = (user == null ? "" : user) + ":" + (pass == null ? "" : pass);
            return "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
        }

        /** The address form, as pasted or exported: socks5://user:pass@host:port. */
        public String url() {
            String cred = auth() ? enc(user) + (pass == null || pass.isEmpty() ? "" : ":" + enc(pass)) + "@" : "";
            return type + "://" + cred + hostPort();
        }

        java.net.Proxy java() {
            if (auth()) CREDS.put(key(host, port), new PasswordAuthentication(user, pass == null ? new char[0] : pass.toCharArray()));
            return new java.net.Proxy(socks() ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(host, port));
        }

        public Proxy copy() {
            Proxy p = new Proxy();
            p.id = id; p.type = type; p.host = host; p.port = port; p.user = user; p.pass = pass;
            p.lastOk = lastOk; p.lastMs = lastMs; p.lastAt = lastAt; p.lastError = lastError;
            return p;
        }
    }

    /**
     * Reads "socks5://user:pass@host:port", "http://host:port" or a bare "host:port" (HTTP).
     * Throws with a Persian message saying what is missing.
     */
    public static Proxy parse(String text) throws IllegalArgumentException {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("آدرس پروکسی خالی است");
        if (!s.contains("://")) s = "http://" + s;
        URI u;
        try {
            u = new URI(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("آدرس پروکسی درست نیست");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
        Proxy p = new Proxy();
        switch (scheme) {
            case "http": case "https": p.type = HTTP; break;
            case "socks": case "socks5": case "socks5h": p.type = SOCKS5; break;
            default: throw new IllegalArgumentException("نوع پروکسی باید http یا socks5 باشد");
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("آدرس سرور پروکسی را بنویسید");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        p.host = host;
        if (u.getPort() < 1 || u.getPort() > 65535) throw new IllegalArgumentException("پورت را بنویسید، مثل :1080");
        p.port = u.getPort();
        String info = u.getRawUserInfo();
        if (info != null && !info.isEmpty()) {
            int i = info.indexOf(':');
            p.user = dec(i < 0 ? info : info.substring(0, i));
            p.pass = i < 0 ? null : dec(info.substring(i + 1));
        }
        return p;
    }

    /** Checks a host and port typed into separate fields; null when fine, else what is wrong. */
    public static String check(String host, String port) {
        if (host == null || host.trim().isEmpty()) return "آدرس سرور پروکسی را بنویسید";
        if (!host.trim().matches("[A-Za-z0-9.:\\-\\[\\]_]+")) return "آدرس سرور فقط حروف لاتین، عدد، نقطه و خط تیره دارد";
        int p;
        try {
            p = Integer.parseInt(port == null ? "" : port.trim());
        } catch (NumberFormatException e) {
            return "پورت باید عدد باشد، مثل 1080";
        }
        return p < 1 || p > 65535 ? "پورت باید بین ۱ و ۶۵۵۳۵ باشد" : null;
    }

    private static String enc(String s) {
        StringBuilder b = new StringBuilder();
        for (byte c : s.getBytes(StandardCharsets.UTF_8)) {
            int ch = c & 0xff;
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || "-._~".indexOf(ch) >= 0) b.append((char) ch);
            else b.append('%').append(String.format(Locale.US, "%02X", ch));
        }
        return b.toString();
    }

    private static String dec(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------- credentials ----------

    private static final Map<String, PasswordAuthentication> CREDS = new ConcurrentHashMap<>();
    private static volatile boolean installed;

    private static String key(String host, int port) { return host.toLowerCase(Locale.US) + ":" + port; }

    /** Answers the platform's password requests (HTTP 407 from a proxy, SOCKS5 login) for proxies the app uses. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                String host = getRequestingHost();
                if (host == null && getRequestingSite() != null) host = getRequestingSite().getHostAddress();
                return host == null ? null : CREDS.get(key(host, getRequestingPort()));
            }
        });
    }

    // ---------- test ----------

    /** Outcome of one connection test: ok, how long it took, and what went wrong. */
    public static final class Probe {
        public final boolean ok;
        public final long ms;
        public final String error;

        Probe(boolean ok, long ms, String error) {
            this.ok = ok;
            this.ms = ms;
            this.error = error;
        }
    }

    /**
     * Opens a connection through the proxy to the destination's host and, for https, completes the TLS handshake.
     * Nothing is sent to the destination itself.
     */
    public static Probe probe(Proxy p, String url, int timeoutSec) {
        long t0 = System.currentTimeMillis();
        int to = Math.min(timeoutSec, 10) * 1000;
        Socket s = null;
        try {
            URL u = new URL(url);
            String host = u.getHost();
            boolean tls = "https".equalsIgnoreCase(u.getProtocol());
            int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
            if (p.socks()) {
                s = new Socket(p.java());
                s.setSoTimeout(to);
                s.connect(InetSocketAddress.createUnresolved(host, port), to);
            } else {
                s = new Socket();
                s.setSoTimeout(to);
                s.connect(new InetSocketAddress(p.host, p.port), to);
                connect(s, p, host, port);
            }
            if (tls) {
                SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, port, true);
                ss.setSoTimeout(to);
                ss.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss.getSession()))
                    return new Probe(false, System.currentTimeMillis() - t0, "گواهی امنیتی با آدرس مقصد نمی‌خواند");
                s = ss;
            }
            return new Probe(true, System.currentTimeMillis() - t0, null);
        } catch (java.net.SocketTimeoutException e) {
            return new Probe(false, System.currentTimeMillis() - t0, "timeout");
        } catch (java.net.ConnectException e) {
            return new Probe(false, System.currentTimeMillis() - t0, "connection refused");
        } catch (java.net.UnknownHostException e) {
            return new Probe(false, System.currentTimeMillis() - t0, "آدرس پیدا نشد: " + e.getMessage());
        } catch (Exception e) {
            return new Probe(false, System.currentTimeMillis() - t0, shortError(e));
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) {}
        }
    }

    /** A short reason for a failed connection, instead of the platform's long message. */
    static String shortError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        String l = m.toLowerCase(Locale.US);
        if (l.contains("refused")) return "connection refused";
        if (l.contains("timed out") || l.contains("timeout")) return "timeout";
        if (l.contains("unreachable")) return "network unreachable";
        if (l.contains("reset")) return "connection reset";
        if (l.contains("socks") && (l.contains("auth") || l.contains("password"))) return "نام کاربری یا رمز پروکسی درست نیست";
        if (m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }

    /** HTTP CONNECT through an HTTP proxy; throws unless the proxy answers 200. */
    private static void connect(Socket s, Proxy p, String host, int port) throws Exception {
        StringBuilder req = new StringBuilder("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n");
        if (p.auth()) req.append("Proxy-Authorization: ").append(p.basicAuth()).append("\r\n");
        req.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) { // byte by byte, so nothing past the headers is consumed
            head.append((char) c);
            if (head.length() > 8192) throw new Exception("پاسخ پروکسی درست نیست");
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        String status = head.toString().split("\r\n", 2)[0];
        String[] parts = status.split(" ");
        if (parts.length < 2 || !parts[1].equals("200")) {
            if (parts.length >= 2 && parts[1].equals("407")) throw new Exception("407 · نام کاربری یا رمز پروکسی درست نیست");
            throw new Exception(status.isEmpty() ? "پروکسی پاسخ نداد" : status);
        }
    }
}
