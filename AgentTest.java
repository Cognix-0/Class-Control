import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Checks for the platform-independent parts of Agent.java: beacon parsing, web filter (rules and a live proxy), whiteboard strokes.
 *  Run: javac -d out Agent.java AgentTest.java && java -ea -Djava.awt.headless=true -cp out AgentTest */
public class AgentTest {
    static void eq(Object want, Object got) {
        if (!java.util.Objects.equals(want, got)) throw new AssertionError("expected <" + want + "> but got <" + got + ">");
    }

    /** Sends raw bytes to the proxy and returns everything it answers until it closes. */
    static String ask(int proxyPort, String request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", proxyPort)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A keystore with a fresh self-signed certificate, made the way LabServer.Identity does: keytool, password on stdin. */
    static String[] keystore(Path dir, String pw) throws Exception {
        Path file = dir.resolve("instructor.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().startsWith("windows") ? "keytool.exe" : "keytool");
        Process proc = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "instructor", "-keyalg", "EC",
                "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "3650",
                "-dname", "CN=Class Control Instructor", "-keystore", file.toString(), "-storetype", "PKCS12")
                .redirectErrorStream(true).start();
        try (Writer w = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8)) { w.write(pw + "\n" + pw + "\n"); }
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!proc.waitFor(60, TimeUnit.SECONDS) || proc.exitValue() != 0) throw new IllegalStateException("keytool failed: " + out);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) { ks.load(in, pw.toCharArray()); }
        Certificate cert = ks.getCertificate("instructor");
        return new String[]{file.toString(), Agent.fpOf(cert.getPublicKey().getEncoded())};
    }

    /** A TLS 1.3 server holding that keystore, like a real class. */
    static SSLServerSocket server(String file, String pw) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(file))) { ks.load(in, pw.toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), null, null);
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ss.setEnabledProtocols(new String[]{"TLSv1.3"});
        return ss;
    }

    /** Accepts one connection and reports the first line the agent sent (null = it sent nothing at all). */
    static Future<String> firstLine(SSLServerSocket ss) {
        CompletableFuture<String> line = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept()) {
                s.setSoTimeout(5000);
                line.complete(new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine());
            } catch (Exception e) {
                line.complete(null);
            }
        });
        t.setDaemon(true);
        t.start();
        return line;
    }

    /** The line that reached the server, or null if none did within a few seconds (the agent never connected). */
    static String within(Future<String> f) {
        try { return f.get(3, TimeUnit.SECONDS); } catch (Exception e) { return null; }
    }

    /** True when the agent would not talk to that port: it sent nothing and gave up. */
    static boolean refused(int port) {
        try (Socket s = Agent.connect("127.0.0.1", port, 5000)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- only the lecturer's own PC gets the class password (TLS 1.3 with a pinned certificate) ----
        Path lecturer = Files.createTempDirectory("cclect"), classmate = Files.createTempDirectory("ccmate");
        String[] real = keystore(lecturer, "lecturerpw");
        String[] rogue = keystore(classmate, "classmatepw");   // a student's laptop running the instructor app
        String pin = real[1];
        eq(64, pin.length());
        eq(pin, Agent.fp(pin.toUpperCase().replaceAll("(.{4})(?!$)", "$1-"))); // the lecturer reads it out in groups and capitals: same key

        Agent.pinnedKey = pin;
        try (SSLServerSocket ss = server(real[0], "lecturerpw")) {
            Future<String> sent = firstLine(ss);
            try (SSLSocket s = Agent.connect("127.0.0.1", ss.getLocalPort(), 5000)) {  // the real instructor
                eq("TLSv1.3", s.getSession().getProtocol());
                new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true).println("pw\tPC01");
            }
            eq("pw\tPC01", within(sent));
        }
        try (SSLServerSocket ss = server(rogue[0], "classmatepw")) {
            Future<String> sent = firstLine(ss);
            eq(true, refused(ss.getLocalPort()));                 // the handshake fails on the certificate
            eq(null, within(sent));                                 // no class password, no login, nothing
        }
        Agent.pinnedKey = Agent.fp(pin.substring(0, 16));         // a short key the lecturer read out: still only their certificate
        try (SSLServerSocket ss = server(real[0], "lecturerpw")) {
            Future<String> sent = firstLine(ss);
            try (SSLSocket s = Agent.connect("127.0.0.1", ss.getLocalPort(), 5000)) {
                new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true).println("pw\tPC02");
            }
            eq("pw\tPC02", within(sent));
        }
        try (SSLServerSocket ss = server(rogue[0], "classmatepw")) {
            eq(true, refused(ss.getLocalPort()));
        }

        // ---- no pinned key: strict connect refuses, while TOFU connect (--app first sign-in) captures and pins the key ----
        Agent.pinnedKey = "";
        eq("Ask your lecturer for the instructor key", Agent.keyBlock());
        try (SSLServerSocket ss = server(real[0], "lecturerpw")) {
            Future<String> sent = firstLine(ss);
            eq(true, refused(ss.getLocalPort()));                 // even the right server is refused without a pin in strict mode
            eq(null, within(sent));
        }
        try (SSLServerSocket ss = server(real[0], "lecturerpw")) {
            Future<String> sent = firstLine(ss);
            try (SSLSocket s = Agent.connect("127.0.0.1", ss.getLocalPort(), 5000, true)) {
                eq("TLSv1.3", s.getSession().getProtocol());
                eq(pin, Agent.sessionKey);
                new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true).println("pw\tPC03");
            }
            eq("pw\tPC03", within(sent));
        }
        Agent.pinnedKey = Agent.sessionKey;
        try (SSLServerSocket ss = server(rogue[0], "classmatepw")) {
            eq(true, refused(ss.getLocalPort()));                 // once pinned by TOFU, a rogue server is refused
        }
        Agent.pinnedKey = Agent.fp(pin);
        eq(null, Agent.keyBlock());
        eq("", Agent.fp(null));
        eq("", Agent.fp("1a2b-3c4d"));                            // half a key is no key

        for (Path dir : List.of(lecturer, classmate)) {
            Files.deleteIfExists(dir.resolve("instructor.p12"));
            Files.delete(dir);
        }

        // ---- beacon ----
        eq("10.0.0.5:5757:Room 1:", String.join(":", Agent.parseBeacon("CCLASS1\t5757\tRoom 1", "10.0.0.5")));
        eq("10.0.0.5:5757:Room 1:pw\tx", String.join(":", Agent.parseBeacon("CCLASS1\t5757\tRoom 1\tpw\tx", "10.0.0.5"))); // shared password, kept whole
        eq(null, Agent.parseBeacon("HELLO\t5757\tx", "10.0.0.5"));
        eq(null, Agent.parseBeacon("CCLASS1\t0\tx", "10.0.0.5"));
        eq(null, Agent.parseBeacon("CCLASS1\t99999\tx", "10.0.0.5"));
        eq(null, Agent.parseBeacon("CCLASS1\tabc\tx", "10.0.0.5"));
        eq(null, Agent.parseBeacon("CCLASS1", "10.0.0.5"));

        // ---- touchpad settings saved before a lock ----
        String q = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\PrecisionTouchPad\r\n    ThreeFingerSlideEnabled    REG_DWORD    0x1\r\n\r\n";
        eq("1", Agent.Kiosk.regDword(q, "ThreeFingerSlideEnabled"));
        eq("1", Agent.Kiosk.regDword(q, "threefingerslideenabled"));
        eq(null, Agent.Kiosk.regDword(q, "FourFingerSlideEnabled"));
        eq(null, Agent.Kiosk.regDword("ERROR: The system was unable to find the specified registry key or value.", "ThreeFingerSlideEnabled"));
        eq("255", Agent.Kiosk.regDword("    X    REG_DWORD    0xff", "X"));

        // ---- filter rules ----
        try (Agent.WebFilter block = new Agent.WebFilter(false, List.of("facebook.com"), 0);
             Agent.WebFilter only = new Agent.WebFilter(true, List.of("school.edu"), 0)) {
            eq(false, block.allowed("facebook.com"));
            eq(false, block.allowed("WWW.Facebook.com"));
            eq(false, block.allowed("m.facebook.com."));       // subdomain, upper case, trailing dot
            eq(true, block.allowed("notfacebook.com"));        // a different domain that merely ends the same
            eq(true, block.allowed("facebook.com.evil.org") );
            eq(true, only.allowed("school.edu"));
            eq(true, only.allowed("lms.school.edu"));
            eq(false, only.allowed("google.com"));
            eq(false, only.allowed("1.2.3.4"));                // allow-only: raw IPs are never reachable
            eq(false, only.allowed("[::1]"));
        }

        // ---- live proxy against local servers ----
        HttpServer web = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        web.createContext("/", ex -> {
            byte[] body = ("hello " + ex.getRequestURI() + " conn=" + ex.getRequestHeaders().getFirst("Connection") + " proxy=" + ex.getRequestHeaders().getFirst("Proxy-Connection")).getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        web.start();
        int webPort = web.getAddress().getPort();
        ServerSocket echo = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread echoThread = new Thread(() -> {
            try (Socket c = echo.accept()) { c.getOutputStream().write(c.getInputStream().readNBytes(4)); } catch (IOException ignored) { }
        });
        echoThread.setDaemon(true);
        echoThread.start();

        try (Agent.WebFilter open = new Agent.WebFilter(false, List.of("example.com"), 0);
             Agent.WebFilter denyLocal = new Agent.WebFilter(false, List.of("localhost"), 0);
             Agent.WebFilter onlyExample = new Agent.WebFilter(true, List.of("example.com"), 0)) {
            String get = "GET http://localhost:" + webPort + "/a?b=1 HTTP/1.1\r\nHost: localhost:" + webPort + "\r\nProxy-Connection: keep-alive\r\n\r\n";
            String ok = ask(open.port(), get);
            if (!ok.startsWith("HTTP/1.1 200") || !ok.contains("hello /a?b=1") || !ok.contains("conn=close") || !ok.contains("proxy=null"))
                throw new AssertionError("plain HTTP through the proxy failed: " + ok);
            if (!ask(denyLocal.port(), get).startsWith("HTTP/1.1 403")) throw new AssertionError("blocked site was not refused");
            if (!ask(onlyExample.port(), get).startsWith("HTTP/1.1 403")) throw new AssertionError("allow-only let an unlisted site through");

            // HTTPS-style tunnel
            try (Socket s = new Socket("127.0.0.1", open.port())) {
                s.setSoTimeout(5000);
                s.getOutputStream().write(("CONNECT localhost:" + echo.getLocalPort() + " HTTP/1.1\r\nHost: x\r\n\r\n").getBytes());
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                eq("HTTP/1.1 200 Connection Established", r.readLine());
                r.readLine();
                s.getOutputStream().write("ping".getBytes());
                char[] buf = new char[4];
                int n = 0;
                while (n < 4) n += r.read(buf, n, 4 - n);
                eq("ping", new String(buf));
            }
            if (!ask(denyLocal.port(), "CONNECT localhost:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403")) throw new AssertionError("blocked tunnel was not refused");

            // typing the site's IP address must not get round a block
            for (long end = System.currentTimeMillis() + 5000; denyLocal.blockedIps.isEmpty() && System.currentTimeMillis() < end; ) Thread.sleep(20);
            String viaIp = "GET http://127.0.0.1:" + webPort + "/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n";
            if (!ask(denyLocal.port(), viaIp).startsWith("HTTP/1.1 403")) throw new AssertionError("IP address bypassed the block");
            if (!ask(open.port(), "garbage\r\n\r\n").startsWith("HTTP/1.1 400")) throw new AssertionError("malformed request not rejected");
        }
        web.stop(0);
        echo.close();

        // ---- whiteboard ----
        Agent.Board b = new Agent.Board();
        b.update("beg,ff0000,0.0050,0.1000,0.2000");
        b.update("add,0.2000,0.3000,0.3000,0.4000");
        b.update("end");
        b.update("beg,000000,0.0100,0.5,0.5");
        b.update("add,2,2");                                // out of range: clamped, not trusted
        eq(2, b.lines.size());
        eq(3, b.lines.get(0).pts().size());
        eq(1.0, b.lines.get(1).pts().get(1)[0]);
        b.update("add,not,numbers");                        // garbage draws nothing and does not throw
        b.update("clear");
        eq(0, b.lines.size());
        System.out.println("AgentTest: all checks passed");
    }
}
