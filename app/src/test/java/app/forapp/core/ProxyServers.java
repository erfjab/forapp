package app.forapp.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny local servers for proxy tests: an HTTP origin, an HTTP proxy (CONNECT and plain forwarding) and a SOCKS5 proxy,
 * each with an optional login. Test code only; kept the same in src/test and src/androidTest.
 */
final class ProxyServers {
    private ProxyServers() {}

    abstract static class Server implements Closeable {
        final ServerSocket ss;
        final AtomicInteger connections = new AtomicInteger();

        Server() throws Exception {
            ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket s = ss.accept();
                        connections.incrementAndGet();
                        new Thread(() -> {
                            try { handle(s); } catch (Exception ignored) {} finally { quietly(s); }
                        }).start();
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() { return ss.getLocalPort(); }

        abstract void handle(Socket s) throws Exception;

        @Override
        public void close() { quietly(ss); }
    }

    /** Answers every request with {@code status}; remembers the last request line and body. */
    static final class Origin extends Server {
        final int status;
        volatile String lastLine, lastBody;

        Origin(int status) throws Exception {
            super();
            this.status = status;
        }

        String url() { return "http://127.0.0.1:" + port() + "/hook"; }

        @Override
        void handle(Socket s) throws Exception {
            InputStream in = s.getInputStream();
            String head = readHead(in);
            lastLine = head.split("\r\n", 2)[0];
            int len = 0;
            for (String l : head.split("\r\n")) if (l.toLowerCase(Locale.US).startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
            byte[] body = new byte[len];
            new DataInputStream(in).readFully(body);
            lastBody = new String(body, StandardCharsets.UTF_8);
            byte[] out = "ok".getBytes(StandardCharsets.UTF_8);
            s.getOutputStream().write(("HTTP/1.1 " + status + " X\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().write(out);
            s.getOutputStream().flush();
        }
    }

    /** An HTTP proxy: CONNECT tunnels, and absolute-URI requests forwarded as they are. */
    static final class HttpProxy extends Server {
        final String user, pass;

        HttpProxy(String user, String pass) throws Exception {
            super();
            this.user = user;
            this.pass = pass;
        }

        @Override
        void handle(Socket s) throws Exception {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String head = readHead(in);
            String[] first = head.split("\r\n", 2)[0].split(" ");
            if (user != null) {
                String want = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                boolean ok = false;
                for (String l : head.split("\r\n"))
                    if (l.toLowerCase(Locale.US).startsWith("proxy-authorization:") && l.substring(20).trim().equals(want)) ok = true;
                if (!ok) {
                    out.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    return;
                }
            }
            if (first[0].equals("CONNECT")) {
                String[] hp = first[1].split(":");
                Socket t = new Socket(hp[0], Integer.parseInt(hp[1]));
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                pipe(s, t);
                return;
            }
            // GET http://host:port/path HTTP/1.1 → the same request with only the path, to host:port
            java.net.URL u = new java.net.URL(first[1]);
            Socket t = new Socket(u.getHost(), u.getPort() > 0 ? u.getPort() : 80);
            StringBuilder b = new StringBuilder(first[0] + " " + u.getFile() + " " + first[2] + "\r\n");
            String[] lines = head.split("\r\n");
            for (int i = 1; i < lines.length; i++)
                if (!lines[i].isEmpty() && !lines[i].toLowerCase(Locale.US).startsWith("proxy-")) b.append(lines[i]).append("\r\n");
            b.append("\r\n");
            t.getOutputStream().write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
            pipe(s, t);
        }
    }

    /** A SOCKS5 proxy (RFC 1928) with an optional username/password login (RFC 1929). */
    static final class Socks5 extends Server {
        final String user, pass;

        Socks5(String user, String pass) throws Exception {
            super();
            this.user = user;
            this.pass = pass;
        }

        @Override
        void handle(Socket s) throws Exception {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            if (in.readUnsignedByte() != 5) return;
            int n = in.readUnsignedByte();
            boolean hasLogin = false, hasNone = false;
            for (int i = 0; i < n; i++) {
                int m = in.readUnsignedByte();
                if (m == 2) hasLogin = true;
                if (m == 0) hasNone = true;
            }
            if (user != null) {
                if (!hasLogin) { out.write(new byte[]{5, (byte) 0xFF}); return; }
                out.write(new byte[]{5, 2});
                in.readUnsignedByte(); // sub-negotiation version
                byte[] u = new byte[in.readUnsignedByte()];
                in.readFully(u);
                byte[] p = new byte[in.readUnsignedByte()];
                in.readFully(p);
                boolean ok = user.equals(new String(u, StandardCharsets.UTF_8)) && pass.equals(new String(p, StandardCharsets.UTF_8));
                out.write(new byte[]{1, (byte) (ok ? 0 : 1)});
                if (!ok) return;
            } else {
                if (!hasNone) { out.write(new byte[]{5, (byte) 0xFF}); return; }
                out.write(new byte[]{5, 0});
            }
            in.readUnsignedByte(); // version
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte(); // reserved
            int atyp = in.readUnsignedByte();
            String host;
            if (atyp == 1) {
                byte[] a = new byte[4];
                in.readFully(a);
                host = InetAddress.getByAddress(a).getHostAddress();
            } else if (atyp == 3) {
                byte[] a = new byte[in.readUnsignedByte()];
                in.readFully(a);
                host = new String(a, StandardCharsets.US_ASCII);
            } else {
                byte[] a = new byte[16];
                in.readFully(a);
                host = InetAddress.getByAddress(a).getHostAddress();
            }
            int port = in.readUnsignedShort();
            if (cmd != 1) { out.write(new byte[]{5, 7, 0, 1, 0, 0, 0, 0, 0, 0}); return; }
            Socket t;
            try {
                t = new Socket(host, port);
            } catch (Exception e) {
                out.write(new byte[]{5, 5, 0, 1, 0, 0, 0, 0, 0, 0});
                return;
            }
            out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
            out.flush();
            pipe(s, t);
        }
    }

    /** A port nothing listens on. */
    static int deadPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, m = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            m = (c == '\r' || c == '\n') ? m + 1 : 0;
            if (m == 4) break;
        }
        return b.toString("ISO-8859-1");
    }

    /** Copies both ways until either side closes. */
    static void pipe(Socket a, Socket b) throws Exception {
        Thread up = new Thread(() -> copy(a, b));
        up.start();
        copy(b, a);
        up.join(10_000);
        quietly(b);
    }

    private static void copy(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                out.flush();
            }
            to.shutdownOutput();
        } catch (Exception ignored) {
        }
    }

    static void quietly(Closeable c) {
        try { c.close(); } catch (Exception ignored) {}
    }

    static void quietly(Socket s) {
        try { s.close(); } catch (Exception ignored) {}
    }

    static void quietly(ServerSocket s) {
        try { s.close(); } catch (Exception ignored) {}
    }
}
