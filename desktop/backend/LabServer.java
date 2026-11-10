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
import javax.imageio.ImageIO;

/**
 * Instructor-side TCP server. A student agent connects in and sends "token\tcomputerName", then receives
 * "COMMAND\targ" lines. Agents may reply "THUMB\t<b64 jpeg>" and "FRAME\t<b64 jpeg>".
 *
 * One thread per agent reads; one writer thread per agent drains a bounded queue, so a slow student can
 * never block the instructor or the other students.
 *
 * Run as a process (the desktop app does this): java LabServer.java
 *   stdin  (tab separated): START token port [name] | SEND ids cmd arg | REMOTE id cmd arg | WB stroke | LIVE 1|0 | STOP
 *   stdout (tab separated): STARTED port | STARTFAIL msg | ADDED id host ip | REMOVED id
 *                           STATUS id status locked lastSeen | THUMB id b64 | FRAME id b64 | SHARE ids | LOG level msg
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

    static final class Client {
        final String id, host, ip;
        final Socket sock;
        final BlockingQueue<String> out = new LinkedBlockingQueue<>(256);
        volatile long lastSeen = System.currentTimeMillis();
        volatile boolean locked;
        volatile String status = "online";

        Client(String id, String host, String ip, Socket sock) { this.id = id; this.host = host; this.ip = ip; this.sock = sock; }

        boolean send(String line) { return out.offer(line); }
    }

    private final String token;
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

    public LabServer(String token, int port, Consumer<String> sink) throws IOException {
        this.token = token;
        this.sink = sink;
        this.server = new ServerSocket(port);
        pool.execute(this::acceptLoop);
        log("info", "Class started on port " + port() + ". Waiting for student PCs…");
    }

    public int port() { return server.getLocalPort(); }

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
            s.setSoTimeout(HANDSHAKE_MS);      // handshake deadline; cleared once authenticated
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String hello = readLine(in);
            String[] p = hello == null ? new String[0] : hello.split("\t", 2);
            if (p.length < 2 || !p[0].equals(token)) { log("warn", "Rejected " + ip + " (wrong password)"); return; }
            s.setSoTimeout(0);
            String host = clean(p[1]).trim();
            c = new Client(id, host.isEmpty() ? "student" : host.substring(0, Math.min(host.length(), 64)), ip, s);
            startWriter(c);
            synchronized (board) { // register and replay under one lock so the late joiner sees every stroke exactly once
                clients.put(id, c);
                if (boardOpen) for (String stroke : board) c.send("WB\t" + stroke);
            }
            emit("ADDED", id, c.host, ip);
            log("info", c.host + " connected");
            restorePolicy(c);
            for (String line; (line = readLine(in)) != null; ) onLine(c, line);
        } catch (IOException ignored) {
            // peer reset or handshake timeout: same cleanup as a normal disconnect
        } finally {
            if (c != null) {
                clients.remove(id);
                if (sharing.remove(id)) emitShare();
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

    private void startWriter(Client c) {
        pool.execute(() -> {
            try {
                Writer w = new BufferedWriter(new OutputStreamWriter(c.sock.getOutputStream(), StandardCharsets.UTF_8));
                while (true) {
                    w.write(c.out.take());
                    w.write('\n');
                    w.flush();
                }
            } catch (IOException | InterruptedException e) {
                try { c.sock.close(); } catch (IOException ignored) { } // wakes the reader, which cleans up
            }
        });
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

    /** Announces this instructor on the LAN every 2 s so student agents can find it without typing an IP. */
    public void startBeacon(String name) throws SocketException {
        beaconSocket = new DatagramSocket();
        beaconSocket.setBroadcast(true);
        byte[] pkt = beaconPacket(port(), name);
        beacon = timer.scheduleAtFixedRate(() -> {
            for (InetAddress to : broadcastAddresses()) {
                try { beaconSocket.send(new DatagramPacket(pkt, pkt.length, to, DISCOVERY_PORT)); } catch (IOException ignored) { }
            }
        }, 0, 2, TimeUnit.SECONDS);
        log("info", "Student PCs on this network will find the class automatically");
    }

    static byte[] beaconPacket(int tcpPort, String name) {
        return ("CCLASS1\t" + tcpPort + "\t" + clean(name)).getBytes(StandardCharsets.UTF_8);
    }

    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> all = new ArrayList<>();
        try {
            all.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress ia : n.getInterfaceAddresses()) if (ia.getBroadcast() != null) all.add(ia.getBroadcast());
            }
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

    private void log(String level, String msg) { emit("LOG", level, msg); }

    private void emit(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) sb.append(i > 0 ? "\t" : "").append(clean(fields[i]));
        sink.accept(sb.toString());
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
        sharing.clear();
        timer.shutdownNow();
        pool.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        Consumer<String> sink = l -> { synchronized (out) { out.println(l); out.flush(); } };
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        LabServer lab = null;
        for (String line; (line = in.readLine()) != null; ) {
            String[] p = line.split("\t", 4);
            try {
                switch (p[0]) {
                    case "START" -> {
                        try {
                            lab = new LabServer(p[1], Integer.parseInt(p[2]), sink);
                            lab.startBeacon(p.length > 3 && !p[3].isBlank() ? p[3] : "Class Control");
                            sink.accept("STARTED\t" + lab.port());
                        } catch (BindException e) {
                            sink.accept("STARTFAIL\tPort " + p[2] + " is already in use (is another console running?)");
                        } catch (IOException e) {
                            sink.accept("STARTFAIL\t" + clean(String.valueOf(e.getMessage())));
                        }
                    }
                    case "SEND" -> lab.send(p[1].isEmpty() ? List.of() : List.of(p[1].split(",")), p[2], p.length > 3 ? p[3] : "");
                    case "REMOTE" -> lab.remote(p[1], p[2], p.length > 3 ? p[3] : "");
                    case "WB" -> lab.whiteboard(p[1]);
                    case "LIVE" -> lab.setLive(p[1].equals("1"), 2000);
                    case "STOP" -> { if (lab != null) { lab.endClass(); lab.close(); } return; }
                    default -> sink.accept("LOG\terror\tUnknown control line " + clean(p[0]));
                }
            } catch (RuntimeException e) {
                sink.accept("LOG\terror\t" + clean(String.valueOf(e)));
            }
        }
        if (lab != null) { lab.endClass(); lab.close(); } // stdin closed: the desktop app is gone
    }
}
