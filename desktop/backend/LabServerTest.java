import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

    /** Next sink event starting with `type`; other events are skipped. */
    static String event(BlockingQueue<String> q, String type) throws Exception {
        for (long end = System.currentTimeMillis() + 5000; System.currentTimeMillis() < end; ) {
            String e = q.poll(100, TimeUnit.MILLISECONDS);
            if (e != null && e.startsWith(type + "\t")) return e;
        }
        throw new AssertionError("no " + type + " event");
    }

    static final class Agent {
        final Socket s;
        final BufferedReader in;
        final PrintWriter out;

        Agent(int port, String hello) throws IOException {
            s = new Socket("127.0.0.1", port);
            s.setSoTimeout(5000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(hello);
        }

        String next() throws IOException { return in.readLine(); }
    }

    public static void main(String[] args) throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        try (LabServer lab = new LabServer("pw", 0, events::add)) {
            int port = lab.port();

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
            eq("CCLASS1\t5757\tRoom 1", new String(LabServer.beaconPacket(5757, "Room\t1"), StandardCharsets.UTF_8)); // a tab in the name must not break the format

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

            // ---- end of class releases everyone ----
            lab.endClass();
            eq("WB\toff", clean.next());
            eq("SCREENOFF\t", clean.next());
            eq("UNBLOCK\t", clean.next());
            eq("UNLOCK\t", clean.next());
        }
        System.out.println("LabServerTest: all checks passed");
    }
}
