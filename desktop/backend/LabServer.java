import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.imageio.ImageIO;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;

/**
 * Instructor-side TLS 1.3 server. The certificate in the lecturer's keystore is their identity (see Identity):
 * a student agent only completes the handshake with a certificate whose public key matches the fingerprint it
 * pinned, so a classmate's laptop running this app cannot pose as the lecturer, and nothing on the wire is
 * readable. After the handshake the agent sends "token\tcomputerName" and receives "COMMAND\targ" lines.
 * Agents may reply "THUMB\t<b64 jpeg>" and "FRAME\t<b64 jpeg>".
 * The student app's agent also sends "LOGIN\treg\tpw", "LOGOUT", "CHAT\ttext", "SHARE\treq|0" and,
 * once the instructor approved it (PRESENT 1), "PFRAME\t<b64 jpeg>", which is shown to the whole class.
 *
 * One thread per agent reads; one writer thread per agent drains a bounded queue, so a slow student can
 * never block the instructor or the other students.
 *
 * Run as a process (the desktop app does this): java LabServer.java
 *   stdin  (tab separated): ROSTER file | START token port name share keyFile masterPw (name = announce on the LAN,
 *                           share 1 = beacon carries the token, keyFile+masterPw = the lecturer's keystore) | SEND ids cmd arg | REMOTE id cmd arg | WB stroke
 *                           LIVE 1|0 | PRESENT id 1|0|deny | FORGET regNo | STOP
 *                           COACCEPT id 1|0 (host only: answer a co-instructor's join request)
 *   stdout (tab separated): STARTED port | KEY fingerprint | STARTFAIL msg | ADDED id host ip | REMOVED id
 *                           STATUS id status locked lastSeen | THUMB id b64 | FRAME id b64 | SHARE ids | LOG level msg
 *                           STUDENT id regNo | CHAT id text | SHAREREQ id 1|0 | PRESENTER id | LIVESTATE 1|0
 *                           COJOIN id name ip | COGONE id (host only)
 *
 * Co-instructors: a second instructor app connects to the same TCP port and sends "#INSTRUCTOR\tname". Once the
 * host approves it (COACCEPT), it gets "JOINED", a snapshot of the class as events, then every live event; it may
 * send the same control lines as the host except FORGET / COACCEPT / START / STOP. One class server, many
 * instructors: students keep a single connection, so two instructors never fight over a PC.
 */
public final class LabServer implements AutoCloseable {
    static final int MAX_LINE = 8 * 1024 * 1024;   // trust boundary: a client can't make us buffer forever
    static final int HANDSHAKE_MS = 10_000;
    static final Set<String> BROADCAST = Set.of("MSG", "URL", "LOCK", "UNLOCK", "BLOCK", "ALLOW", "UNBLOCK", "SCREEN", "SCREENOFF");
    static final int DISCOVERY_PORT = 5758;        // UDP: students listen here for the instructor's beacon
    static final String COORD = "(?:0(?:\\.\\d{1,4})?|1(?:\\.0{1,4})?)"; // 0..1, the whiteboard is resolution independent
    static final Pattern WHITEBOARD = Pattern.compile("beg,[0-9a-f]{6},0(?:\\.\\d{1,4})?," + COORD + "," + COORD
            + "|add(?:," + COORD + "," + COORD + "){1,200}|end|clear|off");
    static final int BOARD_MAX = 50_000;
    static final Map<String, Pattern> REMOTE = Map.of(
            "VIEW", Pattern.compile("\\d{1,2}"),
            "VIEWOFF", Pattern.compile(""),
            "MOUSE", Pattern.compile("(move|down|up|wheel),(0(\\.\\d+)?|1(\\.0+)?),(0(\\.\\d+)?|1(\\.0+)?),-?\\d{1,3}"),
            "KEY", Pattern.compile("(down|up),\\d{1,5}"));
    static final Pattern REG_NO = Pattern.compile("[A-Za-z0-9/._-]{1,32}");
    static final int CHAT_MAX = 1000;

    static final class Client {
        final String id, host, ip;
        final Socket sock;
        final BlockingQueue<String> out = new LinkedBlockingQueue<>(256);
        volatile long lastSeen = System.currentTimeMillis();
        volatile boolean locked;
        volatile String status = "online";
        volatile String student = "";      // reg no of the student logged in on this PC
        volatile boolean shareRequest;      // student asked to present their screen

        Client(String id, String host, String ip, Socket sock) { this.id = id; this.host = host; this.ip = ip; this.sock = sock; }

        boolean send(String line) { return out.offer(line); }
    }

    /** A second instructor app sharing this class (see the class comment). */
    static final class Instructor {
        final String id, name, ip;
        final Socket sock;
        final BlockingQueue<String> out = new LinkedBlockingQueue<>(4096);
        volatile boolean approved;

        Instructor(String id, String name, String ip, Socket sock) { this.id = id; this.name = name; this.ip = ip; this.sock = sock; }
    }

    private final String token;
    private final Identity identity;   // the lecturer's certificate: what students pin
    private final ServerSocket server;
    private final Consumer<String> sink;
    private final ExecutorService pool = Executors.newCachedThreadPool(LabServer::daemon);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(LabServer::daemon);
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Set<String> sharing = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean sharingRunning = new AtomicBoolean();
    private ScheduledFuture<?> live, beacon;
    private DatagramSocket beaconSocket;
    private final List<String> board = new ArrayList<>(); // strokes of the open whiteboard (also the lock for board state)
    private boolean boardOpen;
    // host -> LOCK / WEB state, re-applied when that PC reconnects (agent restarted, Wi-Fi dropped)
    private final Map<String, Map<String, String>> policy = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile Roster roster = new Roster(null);
    private volatile Client presenter;     // student whose screen the class sees (approved by the instructor)
    private final Map<String, Instructor> instructors = new ConcurrentHashMap<>();
    private final Object fanout = new Object();                   // events reach the host and every co-instructor in one order
    private final ThreadLocal<String> actor = new ThreadLocal<>(); // co-instructor whose command is running, for the activity log
    private volatile boolean liveOn;

    public LabServer(String token, int port, Consumer<String> sink, Identity identity) throws IOException {
        if (identity == null) throw new IllegalArgumentException("A class needs the instructor's certificate"); // never serve a lab in the clear
        this.token = token;
        this.identity = identity;
        this.sink = sink;
        SSLServerSocket ssl = (SSLServerSocket) identity.ssl.getServerSocketFactory().createServerSocket(port);
        ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
        this.server = ssl;
        pool.execute(this::acceptLoop);
        log("info", "Class started on port " + port() + ". Waiting for student PCs…");
    }

    public int port() { return server.getLocalPort(); }

    /** What this class's socket will speak; the tests pin it to TLS 1.3 alone. */
    public List<String> protocols() { return List.of(((SSLServerSocket) server).getEnabledProtocols()); }

    private static Thread daemon(Runnable r) { Thread t = new Thread(r); t.setDaemon(true); return t; }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                pool.execute(() -> serve(s));
            } catch (IOException e) {
                if (!closed) log("error", "Server error: " + e.getMessage());
                return;
            }
        }
    }

    private void serve(Socket s) {
        String ip = s.getInetAddress().getHostAddress();
        String id = ip + ":" + s.getPort(); // unique per connection, so two PCs with the same name don't collide
        Client c = null;
        try (s) {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);              // notice a student PC that vanished (lid closed, cable pulled)
            s.setSoTimeout(HANDSHAKE_MS);      // covers the TLS handshake and the first line; cleared once authenticated
            ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.3"});
            // The TLS handshake runs here, in this connection's own thread, so a client that stalls cannot hold up the accept loop.
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String hello = readLine(in);
            String[] p = hello == null ? new String[0] : hello.split("\t", 2);
            if (p.length > 0 && p[0].equals("#INSTRUCTOR")) { instructorSession(s, in, id, ip, p.length > 1 ? p[1] : ""); return; }
            if (p.length < 2 || !p[0].equals(token)) { log("warn", "Rejected " + ip + " (wrong password)"); return; }
            s.setSoTimeout(0);
            String host = clean(p[1]).trim();
            c = new Client(id, host.isEmpty() ? "student" : host.substring(0, Math.min(host.length(), 64)), ip, s);
            startWriter(c.sock, c.out);
            synchronized (board) { // register and replay under one lock so the late joiner sees every stroke exactly once
                clients.put(id, c);
                if (boardOpen) for (String stroke : board) c.send("WB\t" + stroke);
            }
            emit("ADDED", id, c.host, ip);
            log("info", c.host + " connected");
            restorePolicy(c);
            for (String line; (line = readLine(in)) != null; ) onLine(c, line);
        } catch (SSLException e) {
            log("warn", "Rejected " + ip + " (not a Class Control student app, or it does not have this PC's instructor key)");
        } catch (IOException ignored) {
            // peer reset or handshake timeout: same cleanup as a normal disconnect
        } finally {
            if (c != null) {
                clients.remove(id);
                if (sharing.remove(id)) emitShare();
                if (presenter == c) stopPresenting();
                emit("REMOVED", id);
                log("info", c.host + " disconnected");
            }
        }
    }

    private void restorePolicy(Client c) {
        Map<String, String> st = policy.get(c.host);
        if (st == null) return;
        if (st.containsKey("LOCK")) { c.send("LOCK\t" + st.get("LOCK")); c.locked = true; c.status = "locked"; emitStatus(c); }
        if (st.containsKey("WEB")) c.send(st.get("WEB"));
        if (!st.isEmpty()) log("info", c.host + " reconnected: lock and web rules restored");
    }

    private void startWriter(Socket sock, BlockingQueue<String> queue) {
        pool.execute(() -> {
            try {
                Writer w = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
                while (true) {
                    w.write(queue.take());
                    w.write('\n');
                    w.flush();
                }
            } catch (IOException | InterruptedException e) {
                try { sock.close(); } catch (IOException ignored) { } // wakes the reader, which cleans up
            }
        });
    }

    // ---- co-instructors ----

    /** Waits for the host's approval, then runs the co-instructor's commands until it leaves. */
    private void instructorSession(Socket s, BufferedReader in, String id, String ip, String rawName) throws IOException {
        String name = clean(rawName).trim();
        name = name.isEmpty() ? "Instructor" : name.substring(0, Math.min(name.length(), 64));
        Instructor ins = new Instructor(id, name, ip, s);
        s.setSoTimeout(0);
        instructors.put(id, ins);
        startWriter(s, ins.out);
        emitHost("COJOIN", id, name, ip);
        log("info", name + " (" + ip + ") asks to join this class as a co-instructor");
        timer.schedule(() -> { if (!ins.approved && instructors.containsKey(id)) coAccept(id, false); }, 2, TimeUnit.MINUTES); // nobody answered
        try {
            for (String line; (line = readLine(in)) != null; ) {
                if (!ins.approved) continue; // nothing counts before the host says yes
                actor.set(name);
                try { control(line, true); }
                catch (RuntimeException e) { ins.out.offer("LOG\terror\t" + clean(e.getMessage() == null ? e.toString() : e.getMessage())); }
                finally { actor.remove(); }
            }
        } finally {
            instructors.remove(id);
            emitHost("COGONE", id);
            if (ins.approved) log("info", name + " left the class");
        }
    }

    /** Host's answer to a join request. Approval sends a snapshot of the class, then live events. */
    public void coAccept(String id, boolean ok) {
        Instructor ins = instructors.get(id);
        if (ins == null || ins.approved) return;
        if (!ok) {
            instructors.remove(id);
            ins.out.offer("COREJECT\tThe instructor who started the class declined");
            timer.schedule(() -> { try { ins.sock.close(); } catch (IOException ignored) { } }, 500, TimeUnit.MILLISECONDS); // let the reply go out first
            emitHost("COGONE", id);
            log("info", "Declined co-instructor " + ins.name);
            return;
        }
        synchronized (fanout) { // nothing can be emitted between the snapshot and going live, so no event is missed
            ins.out.offer("JOINED\t" + port());
            for (String line : snapshot()) ins.out.offer(line);
            ins.approved = true;
        }
        log("info", ins.name + " joined as a co-instructor");
    }

    /** The class as event lines, for an instructor who joins late. Events are idempotent, so a duplicate is harmless. */
    List<String> snapshot() {
        List<String> lines = new ArrayList<>();
        for (Client c : clients.values()) {
            lines.add(line("ADDED", c.id, c.host, c.ip));
            if (!c.student.isEmpty()) lines.add(line("STUDENT", c.id, c.student));
            if (c.shareRequest) lines.add(line("SHAREREQ", c.id, "1"));
            lines.add(line("STATUS", c.id, c.status, c.locked ? "1" : "0", "" + c.lastSeen));
        }
        lines.add(line("SHARE", String.join(",", sharing)));
        Client p = presenter;
        lines.add(line("PRESENTER", p == null ? "" : p.id));
        lines.add(line("LIVESTATE", liveOn ? "1" : "0"));
        return lines;
    }

    /** One control line from the host (stdin) or a co-instructor (fromInstructor). */
    public void control(String line, boolean fromInstructor) {
        String[] p = line.split("\t", 4);
        switch (p[0]) {
            case "SEND" -> send(p[1].isEmpty() ? List.of() : List.of(p[1].split(",")), p[2], p.length > 3 ? p[3] : "");
            case "REMOTE" -> remote(p[1], p[2], p.length > 3 ? p[3] : "");
            case "WB" -> whiteboard(p[1]);
            case "LIVE" -> setLive(p[1].equals("1"), 2000);
            case "PRESENT" -> present(p[1], p.length > 2 ? p[2] : "0");
            case "FORGET", "COACCEPT" -> {
                if (fromInstructor) throw new IllegalArgumentException("Only the instructor who started the class can do that");
                if (p[0].equals("FORGET")) forget(p[1]); else coAccept(p[1], p.length > 2 && p[2].equals("1"));
            }
            default -> throw new IllegalArgumentException("Unknown control line " + clean(p[0]));
        }
    }

    /** readLine with a size cap; BufferedReader.readLine would buffer any amount. */
    static String readLine(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int ch; (ch = in.read()) != -1; ) {
            if (ch == '\n') return sb.toString();
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > MAX_LINE) throw new IOException("line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void onLine(Client c, String line) {
        int t = line.indexOf('\t');
        String kind = t < 0 ? line : line.substring(0, t);
        String data = t < 0 ? "" : line.substring(t + 1);
        if (kind.equals("FRAME")) { if (isBase64(data)) emit("FRAME", c.id, data); return; }
        if (kind.equals("PFRAME")) { if (presenter == c && isBase64(data)) relay(c, data); return; } // never relay an unapproved screen
        switch (kind) {
            case "LOGIN" -> login(c, data);
            case "LOGOUT" -> { if (!c.student.isEmpty()) { log("info", c.student + " logged out of " + c.host); c.student = ""; emit("STUDENT", c.id, ""); } }
            case "CHAT" -> {
                String text = clean(data).trim();
                if (!text.isEmpty()) emit("CHAT", c.id, text.substring(0, Math.min(text.length(), CHAT_MAX)));
            }
            case "SHARE" -> {
                if (data.equals("req") && presenter != c && !c.shareRequest) {
                    c.shareRequest = true;
                    emit("SHAREREQ", c.id, "1");
                    log("info", who(c) + " asks to share their screen with the class");
                } else if (data.equals("0")) {
                    if (presenter == c) stopPresenting();
                    else if (c.shareRequest) { c.shareRequest = false; emit("SHAREREQ", c.id, "0"); }
                }
            }
            default -> { }
        }
        c.lastSeen = System.currentTimeMillis();
        c.status = c.locked ? "locked" : "online";
        if (kind.equals("THUMB") && !data.isEmpty() && isBase64(data)) emit("THUMB", c.id, data);
        emitStatus(c);
    }

    static boolean isBase64(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!(ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '+' || ch == '/' || ch == '=')) return false;
        }
        return true;
    }

    private List<Client> targets(List<String> ids) {
        if (ids.isEmpty()) return new ArrayList<>(clients.values());
        List<Client> to = new ArrayList<>();
        for (String id : ids) { Client c = clients.get(id); if (c != null) to.add(c); }
        return to;
    }

    /** Broadcast-style command to the selected PCs (empty list = everyone). Returns how many got it. */
    public int send(List<String> ids, String cmd, String arg) {
        if (!BROADCAST.contains(cmd)) throw new IllegalArgumentException("Unknown command " + cmd);
        List<Client> to = targets(ids);
        if (to.isEmpty()) return 0;
        String a = (arg == null ? "" : arg).replaceAll("[\\r\\n]+", " ").trim();
        String n = to.size() + " PC" + (to.size() == 1 ? "" : "s");
        if (cmd.equals("SCREEN")) {
            stopPresenting(); // one screen on the class at a time
            for (Client c : to) sharing.add(c.id);
            shareLoop();
            emitShare();
            log("info", "Sharing your screen with " + n);
            return to.size();
        }
        if (cmd.equals("SCREENOFF")) {
            for (Client c : to) { sharing.remove(c.id); c.send("SCREENOFF\t"); }
            emitShare();
            log("info", "Stopped screen share on " + n);
            return to.size();
        }
        if (cmd.equals("LOCK") && a.isEmpty()) a = "Eyes on the instructor";
        for (Client c : to) {
            c.send(cmd + "\t" + a);
            Map<String, String> st = policy.computeIfAbsent(c.host, k -> new ConcurrentHashMap<>());
            switch (cmd) {
                case "LOCK" -> st.put("LOCK", a);
                case "UNLOCK" -> st.remove("LOCK");
                case "BLOCK", "ALLOW" -> st.put("WEB", cmd + "\t" + a);
                case "UNBLOCK" -> st.remove("WEB");
                default -> { }
            }
            if (cmd.equals("LOCK") || cmd.equals("UNLOCK")) {
                c.locked = cmd.equals("LOCK");
                c.status = c.locked ? "locked" : "online";
                emitStatus(c);
            }
        }
        log("info", cmd + (a.isEmpty() ? "" : " \"" + a + "\"") + " sent to " + n);
        return to.size();
    }

    /** Remote view / control of one PC. Validates everything that goes onto the wire. */
    public boolean remote(String id, String cmd, String arg) {
        String a = arg == null ? "" : arg;
        Client c = clients.get(id);
        if (cmd.equals("TYPE")) {
            String text = a.replaceAll("[\\r\\n]+", " ");
            return c != null && c.send("TYPE\t" + text.substring(0, Math.min(text.length(), 2000)));
        }
        Pattern ok = REMOTE.get(cmd);
        if (ok == null || !ok.matcher(a).matches()) throw new IllegalArgumentException("Bad remote command " + cmd + " " + a);
        if (cmd.equals("VIEW") && c != null) log("info", "Remote view opened on " + c.host);
        return c != null && c.send(cmd + "\t" + a);
    }

    private String who(Client c) { return c.student.isEmpty() ? c.host : c.student + " (" + c.host + ")"; }

    public void setRoster(Roster r) { roster = r; }

    public boolean forget(String regNo) {
        boolean had = roster.forget(regNo);
        log("info", had ? "Password of " + regNo + " reset: their next login sets a new one" : regNo + " has no saved password");
        return had;
    }

    private void login(Client c, String data) {
        String[] p = data.split("\t", 2);
        String reg = p[0].trim();
        String why = !REG_NO.matcher(reg).matches() ? "Enter a valid registration number"
                : p.length < 2 || p[1].isEmpty() ? "Enter your password" : roster.check(reg, p[1]);
        if (why != null) {
            c.send("LOGIN\tfail\t" + why);
            log("warn", "Login failed for " + clean(reg) + " on " + c.host + ": " + why);
            timer.schedule(() -> { try { c.sock.close(); } catch (IOException ignored) { } }, 500, TimeUnit.MILLISECONDS); // let the reply go out first
            return;
        }
        for (Client o : clients.values()) if (o != c && o.student.equalsIgnoreCase(reg)) log("warn", reg + " is also logged in on " + o.host);
        c.student = reg;
        c.send("LOGIN\tok");
        emit("STUDENT", c.id, reg);
        log("info", reg + " is on " + c.host);
    }

    /** Instructor's answer to a share request (or starts/stops a presentation on its own). */
    public synchronized boolean present(String id, String mode) {
        Client c = clients.get(id);
        switch (mode) {
            case "1" -> {
                if (c == null) return false;
                if (!sharing.isEmpty()) send(List.of(), "SCREENOFF", "");
                stopPresenting();
                c.shareRequest = false;
                emit("SHAREREQ", c.id, "0");
                presenter = c;
                c.send("PRESENT\t1");
                emit("PRESENTER", c.id);
                log("info", who(c) + " is presenting to the class");
            }
            case "deny" -> {
                if (c == null) return false;
                c.shareRequest = false;
                emit("SHAREREQ", c.id, "0");
                c.send("PRESENT\tdeny");
            }
            case "0" -> stopPresenting();
            default -> throw new IllegalArgumentException("Bad present mode " + mode);
        }
        return true;
    }

    private synchronized void stopPresenting() {
        Client p = presenter;
        if (p == null) return;
        presenter = null;
        p.send("PRESENT\t0");
        for (Client o : clients.values()) if (o != p) o.send("SCREENOFF\t");
        emit("PRESENTER", "");
        log("info", who(p) + " stopped presenting");
    }

    /** Presenter's frame to everyone else; slow links drop frames instead of queueing them (like shareLoop). */
    private void relay(Client from, String frame) {
        for (Client o : clients.values()) if (o != from && o.out.size() < 2) o.send("SCREEN\t" + frame);
        emit("FRAME", from.id, frame);
    }

    /**
     * The lecturer's TLS identity: a self-signed EC P-256 certificate in <userData>/instructor.p12, created by
     * keytool from this Java runtime on the first class. Students pin the certificate's public key fingerprint, so
     * a classmate's laptop running this app cannot pose as the lecturer, and TLS 1.3 also keeps the class password,
     * student logins, chat and screen frames off the wire in the clear.
     *
     * The keystore password is the lecturer's master password and is never stored: a wrong one fails to open the
     * keystore and the class does not start. It is fed to keytool over stdin, never as a command-line argument,
     * because anyone on the machine can read another process's arguments.
     */
    static final class Identity {
        static final String ALIAS = "instructor";
        static final String FINGERPRINT_FILE = "instructor-fingerprint.txt";
        final SSLContext ssl;
        final String fingerprint;   // SHA-256 of the certificate's SubjectPublicKeyInfo, 64 lower-case hex characters

        private Identity(SSLContext ssl, String fingerprint) { this.ssl = ssl; this.fingerprint = fingerprint; }

        /** Opens the lecturer's keystore with this master password, creating it on their first class. */
        static Identity load(Path file, String masterPw) throws IOException, GeneralSecurityException {
            if (masterPw == null || masterPw.length() < 8) throw new GeneralSecurityException("The master password must be at least 8 characters");
            if (!Files.exists(file)) create(file, masterPw);
            char[] pw = masterPw.toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(file)) {
                ks.load(in, pw);
            } catch (IOException e) {
                throw new GeneralSecurityException("Wrong master password"); // a bad password fails the keystore's integrity check
            }
            Certificate cert = ks.getCertificate(ALIAS);
            if (cert == null) throw new GeneralSecurityException("No instructor certificate in " + file.getFileName() + ". Delete it and start the class again to make a new one.");
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(kmf.getKeyManagers(), null, null);
            String fingerprint = fingerprint(cert.getPublicKey().getEncoded());
            // The fingerprint is public, so keep it in a plain file next to the keystore: the UI shows it before any class starts.
            try { Files.writeString(file.resolveSibling(FINGERPRINT_FILE), fingerprint + "\n"); } catch (IOException ignored) { }
            return new Identity(ctx, fingerprint);
        }

        private static void create(Path file, String masterPw) throws IOException, GeneralSecurityException {
            Path keytool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "keytool.exe" : "keytool");
            if (!Files.isExecutable(keytool)) throw new GeneralSecurityException("keytool is missing from this Java installation (" + keytool
                    + "). Install a full JDK 17 or newer and start the class again.");
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Process proc = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1",
                    "-sigalg", "SHA256withECDSA", "-validity", "3650", "-dname", "CN=Class Control Instructor",
                    "-keystore", file.toString(), "-storetype", "PKCS12").redirectErrorStream(true).start();
            try (Writer w = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write(masterPw + "\n" + masterPw + "\n"); // keystore password, then its confirmation
            }
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (!proc.waitFor(60, TimeUnit.SECONDS) || proc.exitValue() != 0) {
                    proc.destroyForcibly();
                    Files.deleteIfExists(file); // half-written keystore: start clean next time
                    throw new GeneralSecurityException("Could not create the instructor certificate: " + clean(out.trim()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while creating the instructor certificate");
            }
        }

        /** What the lecturer reads out and students pin: SHA-256 of the certificate's public key, 64 hex characters. */
        static String fingerprint(byte[] publicKeyInfo) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKeyInfo));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Student passwords, checked by the instructor (never by the student's own PC). Trust on first use:
     * a reg no's first login sets its password. Saved as salted PBKDF2 hashes; file == null keeps them in memory (demo).
     */
    static final class Roster {
        private final Path file;
        private final Properties hashes = new Properties();
        private static final SecureRandom RANDOM = new SecureRandom();

        Roster(Path file) {
            this.file = file;
            if (file != null && Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) { hashes.load(in); } catch (IOException ignored) { }
            }
        }

        /** null = accepted (and registered if new), else the reason to show the student. */
        synchronized String check(String reg, String pw) {
            String key = reg.toUpperCase(Locale.ROOT);
            String saved = hashes.getProperty(key);
            if (saved == null) {
                byte[] salt = new byte[16];
                RANDOM.nextBytes(salt);
                hashes.setProperty(key, b64(salt) + ":" + b64(hash(pw, salt)));
                save();
                return null;
            }
            String[] p = saved.split(":", 2);
            byte[] want = Base64.getDecoder().decode(p[1]);
            return MessageDigest.isEqual(want, hash(pw, Base64.getDecoder().decode(p[0]))) ? null : "Wrong registration number or password";
        }

        synchronized boolean forget(String reg) {
            boolean had = hashes.remove(reg.toUpperCase(Locale.ROOT)) != null;
            if (had) save();
            return had;
        }

        private void save() {
            if (file == null) return;
            try {
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (OutputStream out = Files.newOutputStream(tmp)) { hashes.store(out, "Class Control student passwords (salt:PBKDF2)"); }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not save student passwords", e);
            }
        }

        static byte[] hash(String pw, byte[] salt) {
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 60_000, 256)).getEncoded();
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    }

    /** Whiteboard stroke for every student. Validated, remembered for late joiners, then broadcast. */
    public void whiteboard(String arg) {
        if (arg == null || !WHITEBOARD.matcher(arg).matches()) throw new IllegalArgumentException("Bad whiteboard command");
        synchronized (board) {
            if (arg.equals("off")) { boardOpen = false; board.clear(); }
            else {
                boardOpen = true;
                if (arg.equals("clear")) board.clear();
                else if (board.size() < BOARD_MAX) board.add(arg);
            }
            for (Client c : clients.values()) c.send("WB\t" + arg);
        }
    }

    /** Announces this instructor on the LAN every 2 s so student agents can find it without typing an IP.
     *  Plain UDP, no authentication: it only says "a class may be here". What makes a connection safe is the
     *  pinned certificate, which the agent checks during the TLS handshake before it sends anything.
     *  shareToken: the beacon also carries the class password, so student apps on this network join without typing it. */
    public void startBeacon(String name, boolean shareToken) throws SocketException {
        beaconSocket = new DatagramSocket();
        beaconSocket.setBroadcast(true);
        byte[] pkt = beaconPacket(port(), name, shareToken ? token : null);
        beacon = timer.scheduleAtFixedRate(() -> {
            for (InetAddress to : broadcastAddresses()) {
                try { beaconSocket.send(new DatagramPacket(pkt, pkt.length, to, DISCOVERY_PORT)); } catch (IOException ignored) { }
            }
        }, 0, 2, TimeUnit.SECONDS);
        log("info", "Student PCs on this network will find the class automatically");
    }

    static byte[] beaconPacket(int tcpPort, String name, String token) {
        return ("CCLASS1\t" + tcpPort + "\t" + clean(name) + (token == null ? "" : "\t" + clean(token))).getBytes(StandardCharsets.UTF_8);
    }

    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> all = new ArrayList<>();
        try {
            all.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress ia : n.getInterfaceAddresses()) if (ia.getBroadcast() != null) all.add(ia.getBroadcast());
            }
            all.add(InetAddress.getLoopbackAddress()); // allows student app on the same machine to discover without LAN broadcast
        } catch (IOException ignored) { }
        return all;
    }

    /** Class over: release every student before the connections go away. */
    public void endClass() {
        for (Client c : clients.values()) { c.send("WB\toff"); c.send("SCREENOFF\t"); c.send("UNBLOCK\t"); c.send("UNLOCK\t"); }
        for (long end = System.currentTimeMillis() + 1000; System.currentTimeMillis() < end; ) {
            if (clients.values().stream().allMatch(c -> c.out.isEmpty())) break;
            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
        }
    }

    /** Polls every agent for a thumbnail; a PC that stops answering for 3 rounds is marked "stuck". */
    public void setLive(boolean on, long ms) {
        if (live != null) live.cancel(false);
        live = null;
        liveOn = on;
        emit("LIVESTATE", on ? "1" : "0"); // every instructor's switch shows the same thing
        log("info", on ? "Live view on" : "Live view off");
        if (!on) return;
        live = timer.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Client c : clients.values()) {
                c.send("THUMB\t");
                if (!c.locked && c.status.equals("online") && now - c.lastSeen > 3 * ms) { c.status = "stuck"; emitStatus(c); }
            }
        }, 0, ms, TimeUnit.MILLISECONDS);
    }

    /** Streams the instructor's screen (~2/s, 1024px JPEG) to everyone in `sharing`. */
    private void shareLoop() {
        if (!sharingRunning.compareAndSet(false, true)) return;
        pool.execute(() -> {
            try {
                Robot r = new Robot();
                Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                while (!sharing.isEmpty() && !closed) {
                    String frame = jpeg(r.createScreenCapture(screen), 1024);
                    for (String id : sharing) {
                        Client c = clients.get(id);
                        if (c != null && c.out.size() < 2) c.send("SCREEN\t" + frame); // slow link: drop the frame, don't queue it
                    }
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                log("error", "Screen share stopped: " + e.getMessage());
                sharing.clear();
            } finally {
                sharingRunning.set(false);
                emitShare();
            }
        });
    }

    static String jpeg(BufferedImage src, int w) throws IOException {
        int h = src.getHeight() * w / src.getWidth();
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(small, "jpg", b);
        return Base64.getEncoder().encodeToString(b.toByteArray());
    }

    private void emitStatus(Client c) { emit("STATUS", c.id, c.status, c.locked ? "1" : "0", "" + c.lastSeen); }

    private void emitShare() { emit("SHARE", String.join(",", sharing)); }

    private void log(String level, String msg) {
        String by = actor.get();
        emit("LOG", level, by == null ? msg : by + ": " + msg);
    }

    static String line(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) sb.append(i > 0 ? "\t" : "").append(clean(fields[i]));
        return sb.toString();
    }

    /** To the host app and every approved co-instructor. */
    private void emit(String... fields) {
        String l = line(fields);
        boolean image = fields[0].equals("THUMB") || fields[0].equals("FRAME");
        synchronized (fanout) {
            sink.accept(l);
            for (Instructor i : instructors.values()) {
                if (!i.approved) continue;
                if (image) { if (i.out.size() < 64) i.out.offer(l); continue; } // slow link: skip pictures, never state
                if (!i.out.offer(l)) { try { i.sock.close(); } catch (IOException ignored) { } } // hopelessly behind: drop it, it can rejoin
            }
        }
    }

    /** Only to the host app (join requests are the host's decision). */
    private void emitHost(String... fields) {
        synchronized (fanout) { sink.accept(line(fields)); }
    }

    /** Fields are tab separated and one per line, so no tabs or newlines inside a field. */
    static String clean(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\t' || ch == '\r' || ch == '\n') return s.replaceAll("[\\t\\r\\n]+", " ");
        }
        return s;
    }

    @Override
    public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        if (beaconSocket != null) beaconSocket.close();
        for (Client c : clients.values()) { try { c.sock.close(); } catch (IOException ignored) { } }
        for (Instructor i : instructors.values()) { try { i.sock.close(); } catch (IOException ignored) { } }
        sharing.clear();
        timer.shutdownNow();
        pool.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        Consumer<String> sink = l -> { synchronized (out) { out.println(l); out.flush(); } };
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        LabServer lab = null;
        Roster roster = new Roster(null);
        for (String line; (line = in.readLine()) != null; ) {
            String[] p = line.split("\t", 7);
            try {
                switch (p[0]) {
                    case "ROSTER" -> roster = new Roster(Path.of(p[1]));
                    case "START" -> {
                        try {
                            if (p.length < 7 || p[5].isBlank()) throw new GeneralSecurityException("This class has no instructor certificate");
                            Identity id = Identity.load(Path.of(p[5]), p[6]);
                            lab = new LabServer(p[1], Integer.parseInt(p[2]), sink, id);
                            lab.setRoster(roster);
                            if (p.length > 3 && !p[3].isBlank()) lab.startBeacon(p[3], p.length > 4 && p[4].equals("1")); // no name (demo): stay off the LAN
                            sink.accept("STARTED\t" + lab.port());
                            sink.accept("KEY\t" + id.fingerprint);
                        } catch (BindException e) {
                            sink.accept("STARTFAIL\tPort " + p[2] + " is already in use (is another console running?)");
                        } catch (GeneralSecurityException e) {
                            sink.accept("STARTFAIL\t" + clean(String.valueOf(e.getMessage())));
                        } catch (IOException e) {
                            sink.accept("STARTFAIL\t" + clean(String.valueOf(e.getMessage())));
                        }
                    }
                    case "STOP" -> { if (lab != null) { lab.endClass(); lab.close(); } return; }
                    default -> {
                        if (lab == null) throw new IllegalStateException("The class has not started");
                        lab.control(line, false);
                    }
                }
            } catch (RuntimeException e) {
                sink.accept("LOG\terror\t" + clean(String.valueOf(e)));
            }
        }
        if (lab != null) { lab.endClass(); lab.close(); } // stdin closed: the desktop app is gone
    }
}
