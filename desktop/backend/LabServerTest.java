import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

/** Protocol self-check: a fake agent talks to the real LabServer over TCP.
 *  Run: javac -d out backend/*.java && java -cp out LabServerTest */
public class LabServerTest {
    static void eq(Object want, Object got) {
        if (!java.util.Objects.equals(want, got)) throw new AssertionError("expected <" + want + "> but got <" + got + ">");
    }

    static void throwsIae(Runnable r) {
        try { r.run(); } catch (IllegalArgumentException e) { return; }
        throw new AssertionError("expected IllegalArgumentException");
    }

    interface Risky { void run() throws Exception; }

    /** Pins one certificate by its public key, the way Agent.Pinned does. */
    record Pinned(String want) implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String t) throws CertificateException { throw new CertificateException("client"); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String t) throws CertificateException {
            if (!LabServer.Identity.fingerprint(chain[0].getPublicKey().getEncoded()).startsWith(want)) throw new CertificateException("not pinned");
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    /** A TLS 1.3 socket that only trusts `pin`: what every student PC and co-instructor uses. */
    static SSLSocket tls(int port, String pin) throws IOException, GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, new TrustManager[]{new Pinned(pin)}, null);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port);
        s.setEnabledProtocols(new String[]{"TLSv1.3"});
        s.setSoTimeout(5000);
        s.startHandshake();
        return s;
    }

    /** Why `r` failed, so a test can check the message the lecturer would see. */
    static String reason(Risky r) {
        try { r.run(); } catch (Exception e) { return e.getMessage(); }
        throw new AssertionError("expected it to fail");
    }

    /** Next sink event starting with `type`; other events are skipped. */
    static String event(BlockingQueue<String> q, String type) throws Exception {
        for (long end = System.currentTimeMillis() + 5000; System.currentTimeMillis() < end; ) {
            String e = q.poll(100, TimeUnit.MILLISECONDS);
            if (e != null && e.startsWith(type + "\t")) return e;
        }
        throw new AssertionError("no " + type + " event");
    }

    /** Reads lines from a socket until one starts with `prefix`. */
    static String until(Agent a, String prefix) throws IOException {
        for (String l; (l = a.next()) != null; ) if (l.startsWith(prefix)) return l;
        throw new AssertionError("connection closed before " + prefix);
    }

    /** Next sink event containing `text`. */
    static String eventWith(BlockingQueue<String> q, String text) throws Exception {
        for (long end = System.currentTimeMillis() + 5000; System.currentTimeMillis() < end; ) {
            String e = q.poll(100, TimeUnit.MILLISECONDS);
            if (e != null && e.contains(text)) return e;
        }
        throw new AssertionError("no event with " + text);
    }

    static final class Agent {
        final Socket s;
        final BufferedReader in;
        final PrintWriter out;

        Agent(int port, String hello) throws Exception {
            s = tls(port, PIN);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(hello);
        }

        String next() throws IOException { return in.readLine(); }
    }

    static String PIN;   // the certificate this test run pins, set once the keystore is open

    public static void main(String[] args) throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        Path dir = Files.createTempDirectory("cckey");
        Path keystore = dir.resolve("instructor.p12");
        LabServer.Identity me = LabServer.Identity.load(keystore, "lecturerpw");   // keytool makes the certificate on the first class
        PIN = me.fingerprint;
        eq(64, PIN.length());                                                      // SHA-256 of the public key, in hex
        eq(me.fingerprint, LabServer.Identity.load(keystore, "lecturerpw").fingerprint); // same certificate next class
        eq("Wrong master password", reason(() -> LabServer.Identity.load(keystore, "guessguess")));
        eq("The master password must be at least 8 characters", reason(() -> LabServer.Identity.load(keystore, "short")));

        try (LabServer lab = new LabServer("pw", 0, events::add, me)) {
            int port = lab.port();
            eq(List.of("TLSv1.3"), lab.protocols());                               // nothing older, ever

            Agent bad = new Agent(port, "nope\tX");
            eq(null, bad.next());                      // wrong password: dropped

            Agent a = new Agent(port, "pw\tPC01");
            String[] added = event(events, "ADDED").split("\t");
            eq("PC01", added[2]);
            String id = added[1];

            lab.send(List.of(id), "MSG", "hi\nthere");
            eq("MSG\thi there", a.next());
            lab.send(List.of(id), "LOCK", "");
            eq("LOCK\tEyes on the instructor", a.next());
            eq("STATUS\t" + id + "\tlocked\t1", event(events, "STATUS").substring(0, ("STATUS\t" + id + "\tlocked\t1").length()));
            eq(1, lab.send(List.of(), "UNLOCK", ""));  // empty list = everyone
            eq("UNLOCK\t", a.next());
            eq(0, lab.send(List.of("9.9.9.9:1"), "MSG", "x")); // unknown PC: nobody gets it
            throwsIae(() -> lab.send(List.of(id), "RM", "-rf"));

            lab.setLive(true, 5000);
            eq("THUMB\t", a.next());
            lab.setLive(false, 5000);
            a.out.println("THUMB\tQUJD");
            eq("THUMB\t" + id + "\tQUJD", event(events, "THUMB"));
            a.out.println("THUMB\tnot base64!");        // garbage must not become a thumbnail
            a.out.println("FRAME\tRkZG");
            eq("FRAME\t" + id + "\tRkZG", event(events, "FRAME"));

            lab.remote(id, "VIEW", "5");
            eq("VIEW\t5", a.next());
            lab.remote(id, "MOUSE", "down,0.25,1,1");
            eq("MOUSE\tdown,0.25,1,1", a.next());
            lab.remote(id, "KEY", "up,65");
            eq("KEY\tup,65", a.next());
            lab.remote(id, "TYPE", "a\nb");
            eq("TYPE\ta b", a.next());
            throwsIae(() -> lab.remote(id, "MOUSE", "down,2,0,1"));
            throwsIae(() -> lab.remote(id, "MOUSE", "down,0.5,0.5,1\nLOCK"));
            throwsIae(() -> lab.remote(id, "KEY", "up,x"));

            a.s.close();
            eq("REMOVED\t" + id, event(events, "REMOVED"));

            // ---- discovery beacon ----
            eq("CCLASS1\t5757\tRoom 1", new String(LabServer.beaconPacket(5757, "Room\t1", null), StandardCharsets.UTF_8)); // a tab in the name must not break the format
            eq("CCLASS1\t5757\tRoom 1\tpw", new String(LabServer.beaconPacket(5757, "Room 1", "pw"), StandardCharsets.UTF_8)); // shared class password

            // ---- whiteboard: validated, relayed, replayed to late joiners ----
            Agent w1 = new Agent(port, "pw\tPC02");
            String w1id = event(events, "ADDED").split("\t")[1];
            String beg = "beg,ff0000,0.0050,0.1000,0.2000", add = "add,0.2000,0.3000,0.3000,0.4000";
            lab.whiteboard(beg);
            lab.whiteboard(add);
            lab.whiteboard("end");
            eq("WB\t" + beg, w1.next());
            eq("WB\t" + add, w1.next());
            eq("WB\tend", w1.next());
            throwsIae(() -> lab.whiteboard("beg,zzzzzz,0.01,0.1,0.1"));          // not a colour
            throwsIae(() -> lab.whiteboard("beg,ff0000,0.01,2,0.1"));            // outside the board
            throwsIae(() -> lab.whiteboard("end\nLOCK\tx"));                     // line injection
            throwsIae(() -> lab.whiteboard("add" + ",0.5,0.5".repeat(201)));     // too many points in one message
            Agent late = new Agent(port, "pw\tPC03");                            // joins while the board is open
            eq("WB\t" + beg, late.next());
            eq("WB\t" + add, late.next());
            eq("WB\tend", late.next());
            lab.whiteboard("clear");
            eq("WB\tclear", w1.next());
            eq("WB\tclear", late.next());
            lab.whiteboard("off");
            eq("WB\toff", w1.next());
            eq("WB\toff", late.next());
            late.s.close();

            // ---- lock and web rules come back when a PC reconnects ----
            lab.send(List.of(w1id), "LOCK", "Back soon");
            eq("LOCK\tBack soon", w1.next());
            lab.send(List.of(w1id), "BLOCK", "facebook.com");
            eq("BLOCK\tfacebook.com", w1.next());
            lab.send(List.of(w1id), "ALLOW", "school.edu");                       // replaces the block
            eq("ALLOW\tschool.edu", w1.next());
            w1.s.close();
            event(events, "REMOVED");
            Agent back = new Agent(port, "pw\tPC02");
            eq("LOCK\tBack soon", back.next());
            eq("ALLOW\tschool.edu", back.next());
            lab.send(List.of(), "UNLOCK", "");
            lab.send(List.of(), "UNBLOCK", "");
            eq("UNLOCK\t", back.next());
            eq("UNBLOCK\t", back.next());
            back.s.close();
            event(events, "REMOVED");
            events.clear();                                                       // drop stale events so we wait for this PC's own ADDED
            Agent clean = new Agent(port, "pw\tPC02");                           // nothing left to restore
            event(events, "ADDED");
            lab.send(List.of(), "MSG", "x");
            eq("MSG\tx", clean.next());

            // ---- student login (trust on first use), chat, presenting to the class ----
            events.clear();
            Agent st = new Agent(port, "pw\tPC10");
            String stId = event(events, "ADDED").split("\t")[1];
            st.out.println("LOGIN\tIT23001\tsecret");
            eq("LOGIN\tok", st.next());
            eq("STUDENT\t" + stId + "\tIT23001", event(events, "STUDENT"));
            st.out.println("CHAT\thelp\tplease");
            eq("CHAT\t" + stId + "\thelp please", event(events, "CHAT"));
            st.out.println("PFRAME\tUFJF");                                    // not approved: must not reach the class
            st.out.println("SHARE\treq");
            eq("SHAREREQ\t" + stId + "\t1", event(events, "SHAREREQ"));
            lab.present(stId, "1");
            eq("PRESENT\t1", st.next());
            eq("PRESENTER\t" + stId, event(events, "PRESENTER"));
            st.out.println("PFRAME\tQUJD");
            eq("SCREEN\tQUJD", clean.next());                                  // the first frame the class gets is the approved one
            st.out.println("SHARE\t0");                                        // student stops
            eq("PRESENT\t0", st.next());
            eq("SCREENOFF\t", clean.next());
            st.out.println("SHARE\treq");
            event(events, "SHAREREQ");
            lab.present(stId, "deny");
            eq("PRESENT\tdeny", st.next());
            throwsIae(() -> lab.present(stId, "maybe"));

            Agent wrong = new Agent(port, "pw\tPC11");
            wrong.out.println("LOGIN\tit23001\tguess");                       // reg no is case-insensitive
            eq("LOGIN\tfail\tWrong registration number or password", wrong.next());
            eq(null, wrong.next());                                            // dropped after a failed login
            Agent junk = new Agent(port, "pw\tPC12");
            junk.out.println("LOGIN\tIT 23\tx");
            eq("LOGIN\tfail\tEnter a valid registration number", junk.next());
            eq(true, lab.forget("IT23001"));
            Agent reset = new Agent(port, "pw\tPC13");
            reset.out.println("LOGIN\tIT23001\tnewpass");                    // forgotten: the next login sets a new password
            eq("LOGIN\tok", reset.next());
            reset.s.close();
            st.s.close();

            java.nio.file.Path file = java.nio.file.Files.createTempFile("roster", ".properties");
            new LabServer.Roster(file).check("IT1", "a");                      // saved to disk, read back by a new roster
            eq(null, new LabServer.Roster(file).check("IT1", "a"));
            eq("Wrong registration number or password", new LabServer.Roster(file).check("IT1", "b"));
            java.nio.file.Files.delete(file);

            // ---- co-instructor: a second instructor app joins this class instead of starting its own ----
            events.clear();
            Agent kid = new Agent(port, "pw\tPC09");
            String kidId = event(events, "ADDED").split("\t")[1];
            Agent co2 = new Agent(port, "#INSTRUCTOR\tMr Silva");                 // a join request the host declines
            String id2 = event(events, "COJOIN").split("\t")[1];
            co2.out.println("SEND\t\tMSG\tsneaky");                             // ignored: never approved
            co2.out.println("LIVE\t1");
            Thread.sleep(200);
            lab.coAccept(id2, false);
            eq("COREJECT\tThe instructor who started the class declined", co2.next());
            eq(null, co2.next());                                                  // and the connection is closed
            event(events, "COGONE");
            Agent co = new Agent(port, "#INSTRUCTOR\tMs Perera");
            String[] join = event(events, "COJOIN").split("\t");
            eq("Ms Perera", join[2]);
            lab.control("COACCEPT\t" + join[1] + "\t1", false);
            eq("JOINED\t" + port, co.next());
            boolean sawKid = false;                                                // snapshot: the class as it is now
            String l;
            while (!(l = co.next()).startsWith("LIVESTATE")) if (l.equals("ADDED\t" + kidId + "\tPC09\t127.0.0.1")) sawKid = true;
            eq(true, sawKid);
            eq("LIVESTATE\t0", l);                                                // the declined request's LIVE 1 never ran
            co.out.println("SEND\t" + kidId + "\tMSG\thello from co");          // co-instructor commands reach students
            eq("MSG\thello from co", kid.next());                                 // and the earlier "sneaky" never did
            eventWith(events, "Ms Perera: MSG");                                   // the host's activity log says who did it
            lab.send(List.of(kidId), "LOCK", "");                                  // host actions show up on the co-instructor too
            eq("LOCK\tEyes on the instructor", kid.next());
            eq("STATUS\t" + kidId + "\tlocked\t1", until(co, "STATUS\t" + kidId).substring(0, ("STATUS\t" + kidId + "\tlocked\t1").length()));
            co.out.println("FORGET\tIT23001");                                    // host-only
            eq("LOG\terror\tOnly the instructor who started the class can do that", until(co, "LOG\terror"));
            co.out.println("LIVE\t1");                                            // shared switch: everyone sees the new state
            eventWith(events, "LIVESTATE\t1");
            co.out.println("LIVE\t0");
            eventWith(events, "LIVESTATE\t0");
            co.s.close();
            event(events, "COGONE");
            lab.send(List.of(kidId), "UNLOCK", "");
            kid.s.close();

            // ---- only a client with the right certificate gets in ----
            Path rogueDir = Files.createTempDirectory("ccrogue");   // a second lecturer's (or a student's) certificate
            String other = LabServer.Identity.load(rogueDir.resolve("instructor.p12"), "someoneelse").fingerprint;
            eq(false, other.equals(PIN));
            Exception wrongCert = null;
            try { tls(port, other); } catch (Exception e) { wrongCert = e; }       // a classmate's certificate pin never matches ours
            eq(true, wrongCert instanceof SSLHandshakeException);

            events.clear();
            try (Socket plain = new Socket("127.0.0.1", port)) {                   // a plaintext client gets nowhere
                plain.setSoTimeout(2000);
                plain.getOutputStream().write("pw\tPC99\n".getBytes(StandardCharsets.UTF_8));
                plain.getOutputStream().flush();
                try { plain.getInputStream().read(); } catch (IOException expected) { } // it gets a TLS alert or a closed socket, never a class
            }
            for (long end = System.currentTimeMillis() + 700; System.currentTimeMillis() < end; ) {
                String e = events.poll(100, TimeUnit.MILLISECONDS);
                if (e != null && e.startsWith("ADDED")) throw new AssertionError("a plaintext client was let into the class");
            }
            events.clear();
            Agent afterJunk = new Agent(port, "pw\tPC21");                        // and the class is still running
            String junkId = event(events, "ADDED").split("\t")[1];
            afterJunk.out.println("LOGIN\tIT24001\toverTls");                    // a student login, over TLS
            eq("LOGIN\tok", afterJunk.next());
            eq("STUDENT\t" + junkId + "\tIT24001", event(events, "STUDENT"));
            eq("TLSv1.3", ((SSLSocket) afterJunk.s).getSession().getProtocol());
            afterJunk.s.close();
            event(events, "REMOVED");
            for (Path f : List.of(rogueDir.resolve("instructor.p12"), rogueDir.resolve(LabServer.Identity.FINGERPRINT_FILE))) Files.deleteIfExists(f);
            Files.delete(rogueDir);

            // ---- end of class releases everyone ----
            lab.endClass();
            eq("WB\toff", until(clean, "WB\t"));                               // skip thumbnail polls from the live-view check above
            eq("SCREENOFF\t", clean.next());
            eq("UNBLOCK\t", clean.next());
            eq("UNLOCK\t", clean.next());
        }
        for (Path f : List.of(keystore, dir.resolve(LabServer.Identity.FINGERPRINT_FILE))) Files.deleteIfExists(f);
        Files.delete(dir);
        System.out.println("LabServerTest: all checks passed");
    }
}
