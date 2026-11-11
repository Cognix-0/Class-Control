import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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

    public static void main(String[] args) throws Exception {
        // ---- beacon ----
        eq("10.0.0.5:5757:Room 1:", String.join(":", Agent.parseBeacon("CCLASS1\t5757\tRoom 1", "10.0.0.5")));
        eq("10.0.0.5:5757:Room 1:pw\tx", String.join(":", Agent.parseBeacon("CCLASS1\t5757\tRoom 1\tpw\tx", "10.0.0.5"))); // shared password, kept whole
        eq(null, Agent.parseBeacon("HELLO\t5757\tx", "10.0.0.5"));
        eq(null, Agent.parseBeacon("CCLASS1\t0\tx", "10.0.0.5"));
        eq(null, Agent.parseBeacon("CCLASS1\t99999\tx", "10.0.0.5"));
        eq(null, Agent.parseBeacon("CCLASS1\tabc\tx", "10.0.0.5"));
        eq(null, Agent.parseBeacon("CCLASS1", "10.0.0.5"));

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
