import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import javax.imageio.ImageIO;
import java.util.regex.Pattern;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Student agent (Java 17+). Run as Administrator on Windows (hosts file, firewall, policies):
 *   java Agent.java                       finds the instructor on the LAN by itself (asks for the class password once)
 *   java Agent.java <teacherIP|auto> <token> [port]
 *   java Agent.java --app                 driven by the student desktop app over stdin/stdout (no window, no dialogs):
 *     stdin : CONNECT host|auto port token regNo password | CHAT text | SHARE 1|0 | LOGOUT      (tab separated)
 *     stdout: STATE searching|connecting|loggedin|badlogin|rejected|offline|loggedout detail
 *             MSG text | VIEWED 1|0 | SHARING 1|0|deny | LOG text
 */
public class Agent {
    static final Path HOSTS = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
            "System32", "drivers", "etc", "hosts");
    static final String BEGIN = "# CLASSROOM-BEGIN", END = "# CLASSROOM-END";
    static final int DISCOVERY_PORT = 5758;   // UDP: the instructor announces itself here
    static final int FILTER_PORT = 5760;      // loopback web filter (falls back to a free port)
    static final Path CONFIG = Path.of(System.getProperty("user.home"), ".classcontrol-agent.properties");
    static final java.util.List<JFrame> locks = new ArrayList<>();
    static javax.swing.Timer lockTimer;
    static JFrame view;
    static volatile String token;
    static JLabel viewLabel;
    static volatile PrintWriter toTeacher;
    static volatile int viewFps;          // > 0 while the instructor's remote view is open
    static volatile boolean controlShown; // banner already says "controlling"
    static Thread viewer;
    static Robot robot;
    static JWindow banner;
    static JLabel bannerLabel;
    static volatile boolean app;           // --app: the student desktop app is our UI
    static volatile boolean presenting;    // instructor approved this student's screen for the class
    static volatile String loginReply;     // "ok" / "fail\t<why>" from the instructor, per connection
    static volatile String[] settings;     // --app: {host, port, token, regNo, password}; null = logged out
    static volatile Socket current;
    static final Object wake = new Object();

    static JLabel status = new JLabel("Starting...");
    static JTextArea logArea = new JTextArea();

    static void log(String m) {
        if (app) { emit("LOG", m); return; }
        System.out.println(m);
        SwingUtilities.invokeLater(() -> logArea.append(java.time.LocalTime.now().withNano(0) + "  " + m + "\n"));
    }

    static void setStatus(String s, Color c) {
        SwingUtilities.invokeLater(() -> { status.setText(s); status.setForeground(c); });
    }

    /** One tab-separated line to the student app (--app only). */
    static void emit(String... f) {
        if (!app) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length; i++) sb.append(i > 0 ? "\t" : "").append(f[i].replaceAll("[\\t\\r\\n]+", " "));
        synchronized (System.out) { System.out.println(sb); System.out.flush(); }
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("--app")) { runApp(); return; }
        String host = a.length > 0 ? a[0] : "auto";
        token = a.length > 1 ? a[1] : savedToken();
        if (token == null && !host.equals("auto")) token = askPassword("Class password (ask your instructor):"); // auto: the instructor may share it
        if (token != null && token.isEmpty()) return;
        int fixedPort = a.length > 2 ? Integer.parseInt(a[2]) : 5757;
        String me = System.getenv().getOrDefault("COMPUTERNAME", "student");
        Runtime.getRuntime().addShutdownHook(new Thread(Agent::releaseAll));
        releaseAll(); // a crashed agent may have left rules behind; the instructor's server re-applies the real ones on connect
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Classroom Control - Student (" + me + ")");
            status.setFont(status.getFont().deriveFont(Font.BOLD, 16f));
            status.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            logArea.setEditable(false);
            f.add(status, BorderLayout.NORTH);
            f.add(new JScrollPane(logArea), BorderLayout.CENTER);
            f.setSize(420, 300);
            f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // students can't just close it
            f.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    if (token == null || token.equals(askPassword("Enter the class password to close the agent:"))) System.exit(0);
                }
            });
            f.setVisible(true);
        });
        log("Agent started, " + (host.equals("auto") ? "looking for the instructor on this network" : "teacher=" + host + ":" + fixedPort));
        Map<String, Long> skip = new HashMap<>(); // instructors that rejected our password, ignored for a minute
        int rejects = 0;
        while (true) { // reconnect forever so a teacher restart doesn't strand the PC
            String h = host;
            int port = fixedPort;
            String who = host;
            if (host.equals("auto")) {
                setStatus("Looking for your instructor...", Color.GRAY);
                String[] found = discover(5000, skip);
                if (found == null) continue;
                h = found[0];
                port = Integer.parseInt(found[1]);
                who = found[2] + " (" + h + ")";
                if (!found[3].isEmpty()) token = found[3]; // the instructor shares the class password on this network
                else if (token == null && (token = askPassword("Class password (ask your instructor):")) == null) return;
            }
            setStatus("Connecting to " + who + "...", Color.GRAY);
            long connectedAt = System.currentTimeMillis();
            try (Socket s = new Socket(h, port)) {
                setStatus("Connected to " + who, new Color(0, 130, 0));
                new Thread(() -> { // still connected after 3 s: the password was accepted, remember it
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                    if (!s.isClosed()) saveToken(token);
                }).start();
                session(s, who, me, null);
            } catch (IOException e) {
                setStatus("Cannot reach teacher - retrying", Color.RED);
                log("Cannot reach " + who + " - " + e.getMessage());
            }
            ended();
            if (System.currentTimeMillis() - connectedAt < 3000) { // dropped straight away: almost always a wrong password
                skip.put(h, System.currentTimeMillis() + 60_000);
                setStatus("Rejected - wrong password?", Color.RED);
                if (++rejects >= 3) {
                    rejects = 0;
                    try { Files.deleteIfExists(CONFIG); } catch (IOException ignored) { }
                    String t = askPassword("The instructor rejected the password. Enter the class password again:");
                    if (t == null) return;
                    token = t;
                }
            } else rejects = 0;
            Thread.sleep(3000);
        }
    }

    /** Handshake (plus the student's login line in --app mode), then obey the teacher until the connection ends. */
    static void session(Socket s, String who, String me, String login) throws IOException {
        s.setKeepAlive(true);
        current = s;
        log("Connected to " + who + " as " + me);
        PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
        out.println(token + "\t" + me);
        if (login != null) out.println(login);
        toTeacher = out;
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        for (String line; (line = in.readLine()) != null; ) {
            if (!line.matches("(SCREEN|THUMB|MOUSE|KEY|VIEW|WB|LOGIN)\\b.*")) log("Teacher: " + line.replace("\t", " "));
            handle(line.split("\t", 2));
        }
        log("Teacher closed the connection");
    }

    /** Teacher gone: never leave the "being viewed" stream, banner, shared screen or presentation running. */
    static void ended() {
        toTeacher = null;
        current = null;
        if (presenting) { presenting = false; emit("SHARING", "0"); }
        stopView();
        SwingUtilities.invokeLater(() -> { hideView(); Board.apply("off"); });
    }

    // ---- --app mode: the student desktop app logs in and drives us over stdin ----

    static void runApp() throws Exception {
        app = true;
        String me = System.getenv().getOrDefault("COMPUTERNAME", InetAddress.getLocalHost().getHostName());
        Runtime.getRuntime().addShutdownHook(new Thread(Agent::releaseAll));
        releaseAll();
        Thread loop = new Thread(() -> appLoop(me), "connection");
        loop.setDaemon(true);
        loop.start();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            String[] p = line.split("\t", -1);
            String arg = p.length > 1 ? p[1] : "";
            switch (p[0]) {
                case "CONNECT" -> { if (p.length >= 6) restart(Arrays.copyOfRange(p, 1, 6)); }
                case "LOGOUT" -> {
                    PrintWriter out = toTeacher;
                    if (out != null) out.println("LOGOUT");
                    restart(null);
                    SwingUtilities.invokeLater(Agent::unlock); // logged out: never leave the PC locked or filtered
                    clearWeb();
                    emit("STATE", "loggedout", "");
                }
                case "CHAT" -> { PrintWriter out = toTeacher; if (out != null && !arg.isBlank()) out.println("CHAT\t" + arg); }
                case "SHARE" -> {
                    PrintWriter out = toTeacher;
                    if (out != null) out.println("SHARE\t" + (arg.equals("1") ? "req" : "0"));
                    if (!arg.equals("1") && presenting) { presenting = false; emit("SHARING", "0"); }
                }
                default -> log("Unknown app command " + p[0]);
            }
        }
        System.exit(0); // the student app closed: the shutdown hook releases everything
    }

    /** New settings (or null = log out): drop the current connection and let the loop start over at once. */
    static void restart(String[] st) {
        synchronized (wake) { settings = st; wake.notifyAll(); }
        Socket s = current;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    static void pause(long ms) {
        synchronized (wake) { try { wake.wait(ms); } catch (InterruptedException ignored) { } }
    }

    static void appLoop(String me) {
        while (true) {
            String[] st = settings;
            if (st == null) { pause(60_000); continue; }
            String h = st[0].isBlank() ? "auto" : st[0].trim(), who = h;
            int port;
            try { port = Integer.parseInt(st[1].trim()); } catch (NumberFormatException e) { port = 5757; }
            token = st[2];
            if (h.equals("auto")) {
                emit("STATE", "searching", "Looking for your instructor on this network...");
                String[] found = discover(4000, new HashMap<>());
                if (found == null) {
                    if (st == settings) emit("STATE", "searching", "No instructor found on this network yet. If it stays like this, enter the instructor's IP in Settings.");
                    continue;
                }
                h = found[0];
                port = Integer.parseInt(found[1]);
                who = found[2] + " (" + h + ")";
                if (!found[3].isEmpty()) token = found[3]; // shared by the instructor: students never type it
            }
            if (st != settings) continue;
            if (token.isEmpty()) {
                emit("STATE", "rejected", "Your instructor didn't share the class password on this network. Enter it in Settings.");
                synchronized (wake) { if (settings == st) settings = null; }
                continue;
            }
            emit("STATE", "connecting", "Connecting to " + who + "...");
            loginReply = null;
            boolean reached = false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(h, port), 5000);
                reached = true;
                session(s, who, me, "LOGIN\t" + st[3] + "\t" + st[4]);
            } catch (IOException e) {
                if (!reached && st == settings) emit("STATE", "offline", "Cannot reach " + who + " (" + e.getMessage() + "). Retrying...");
            }
            ended();
            if (st != settings) continue; // settings changed or logged out: start again right away
            String r = loginReply;
            if (reached && r == null) { // dropped without an answer: the class password was wrong
                emit("STATE", "rejected", "The instructor rejected the class password. Check Settings.");
                synchronized (wake) { if (settings == st) settings = null; }
                continue;
            }
            if (r != null && r.startsWith("fail")) { synchronized (wake) { if (settings == st) settings = null; } continue; }
            if (r != null) emit("STATE", "offline", "Lost the connection to " + who + ". Reconnecting...");
            pause(3000);
        }
    }

    // ---- discovery (UDP beacon from the instructor, see LabServer.startBeacon) ----

    /** Waits up to timeoutMs for a beacon from an instructor we haven't been rejected by: {ip, tcpPort, name}, or null. */
    static String[] discover(int timeoutMs, Map<String, Long> skip) {
        try (DatagramSocket ds = new DatagramSocket(null)) {
            ds.setReuseAddress(true);
            ds.bind(new InetSocketAddress(DISCOVERY_PORT));
            byte[] buf = new byte[512];
            for (long end = System.currentTimeMillis() + timeoutMs; System.currentTimeMillis() < end; ) {
                ds.setSoTimeout((int) Math.max(1, end - System.currentTimeMillis()));
                DatagramPacket pk = new DatagramPacket(buf, buf.length);
                ds.receive(pk);
                String[] b = parseBeacon(new String(pk.getData(), 0, pk.getLength(), StandardCharsets.UTF_8), pk.getAddress().getHostAddress());
                if (b != null && skip.getOrDefault(b[0], 0L) < System.currentTimeMillis()) return b;
            }
        } catch (SocketTimeoutException e) {
            // nobody announced themselves yet
        } catch (IOException e) {
            log("Discovery problem: " + e.getMessage());
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
        }
        return null;
    }

    /** "CCLASS1\t<tcpPort>\t<name>[\t<token>]" -> {from, port, name, token or ""}; anything else (other broadcasts, junk) -> null. */
    static String[] parseBeacon(String msg, String from) {
        String[] p = msg.split("\t", 4);
        if (p.length < 3 || !p[0].equals("CCLASS1")) return null;
        try {
            int port = Integer.parseInt(p[1].trim());
            return port < 1 || port > 65535 ? null : new String[]{from, "" + port, p[2].trim(), p.length > 3 ? p[3] : ""};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String savedToken() {
        try (InputStream in = Files.newInputStream(CONFIG)) {
            Properties pr = new Properties();
            pr.load(in);
            return pr.getProperty("token");
        } catch (IOException e) {
            return null;
        }
    }

    // shortcut: the saved class password sits in the user's profile in plain text; a lab rollout should pre-seed a locked-down file
    static void saveToken(String t) {
        try (OutputStream out = Files.newOutputStream(CONFIG)) {
            Properties pr = new Properties();
            pr.setProperty("token", t);
            pr.store(out, "Class Control agent");
        } catch (IOException ignored) { }
    }

    static String askPassword(String prompt) {
        JPasswordField pf = new JPasswordField(20);
        int r = JOptionPane.showConfirmDialog(null, new Object[]{prompt, pf}, "Class Control", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return r == JOptionPane.OK_OPTION ? new String(pf.getPassword()) : null;
    }

    static void handle(String[] p) {
        String arg = p.length > 1 ? p[1] : "";
        try {
            switch (p[0]) {
                case "MSG" -> {
                    if (app) emit("MSG", arg);
                    else new Thread(() -> JOptionPane.showMessageDialog(null, arg, "Teacher", JOptionPane.INFORMATION_MESSAGE)).start();
                }
                case "LOGIN" -> {
                    loginReply = arg;
                    if (arg.equals("ok")) emit("STATE", "loggedin", "Connected to your instructor");
                    else emit("STATE", "badlogin", arg.startsWith("fail\t") ? arg.substring(5) : "Login failed");
                }
                case "PRESENT" -> {
                    presenting = arg.equals("1");
                    emit("SHARING", arg.equals("deny") ? "deny" : presenting ? "1" : "0");
                    if (presenting) startStreamer();
                }
                case "URL" -> { if (arg.matches("(?i)https?://.+")) Desktop.getDesktop().browse(URI.create(arg)); } // http(s) only: never run arbitrary schemes
                case "LOCK" -> SwingUtilities.invokeLater(() -> lock(arg));
                case "SCREEN" -> { var img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(arg))); SwingUtilities.invokeLater(() -> show(img)); }
                case "SCREENOFF" -> SwingUtilities.invokeLater(Agent::hideView);
                case "UNLOCK" -> SwingUtilities.invokeLater(Agent::unlock);
                case "BLOCK" -> setWeb("BLOCK", arg);
                case "ALLOW" -> setWeb("ALLOW", arg);
                case "UNBLOCK" -> setWeb(null, "");
                case "WB" -> SwingUtilities.invokeLater(() -> Board.apply(arg));
                case "THUMB" -> toTeacher.println("THUMB\t" + capture(320));
                case "VIEW" -> startView(Integer.parseInt(arg));
                case "VIEWOFF" -> stopView();
                case "MOUSE" -> mouse(arg.split(","));
                case "KEY" -> key(arg.split(","));
                case "TYPE" -> type(arg);
            }
        } catch (Exception e) { log("Error: " + e); }
    }

    /** JPEG of this screen, scaled to the given width (320 = live view tile, 1280 = remote view). */
    static String capture(int w) throws Exception {
        var shot = robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        int h = Math.max(1, shot.getHeight() * w / shot.getWidth());
        var small = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(shot, 0, 0, w, h, null);
        g.dispose();
        var jpg = new ByteArrayOutputStream();
        ImageIO.write(small, "jpg", jpg);
        return Base64.getEncoder().encodeToString(jpg.toByteArray());
    }

    static synchronized Robot robot() throws AWTException {
        if (robot == null) robot = new Robot();
        return robot;
    }

    /** Remote view: stream frames to the instructor until VIEWOFF or disconnect. Student sees a banner. */
    static synchronized void startView(int fps) {
        viewFps = Math.max(1, Math.min(fps, 15));
        if (!controlShown) setBanner("Your instructor is viewing this screen");
        emit("VIEWED", "1");
        startStreamer();
    }

    /** One capture thread for the remote view (FRAME, viewFps) and for presenting to the class (PFRAME, 2/s). */
    static synchronized void startStreamer() {
        if (viewer != null) return;
        viewer = new Thread(() -> {
            long lastPresent = 0;
            try {
                while (viewFps > 0 || presenting) {
                    long t = System.currentTimeMillis();
                    PrintWriter out = toTeacher;
                    if (out != null && viewFps > 0) out.println("FRAME\t" + capture(1280));
                    if (out != null && presenting && t - lastPresent >= 500) { out.println("PFRAME\t" + capture(1024)); lastPresent = t; }
                    int fps = viewFps > 0 ? viewFps : 2;
                    Thread.sleep(Math.max(10, 1000 / fps - (System.currentTimeMillis() - t)));
                }
                synchronized (Agent.class) { viewer = null; if (viewFps > 0 || presenting) startStreamer(); } // asked again while we were stopping
            } catch (Exception e) { // capture failed (macOS Screen Recording permission?): stop instead of retrying forever
                log("Screen capture error: " + e);
                synchronized (Agent.class) { viewer = null; viewFps = 0; }
                if (presenting) { presenting = false; emit("SHARING", "0"); PrintWriter out = toTeacher; if (out != null) out.println("SHARE\t0"); }
            }
        }, "screen-stream");
        viewer.setDaemon(true);
        viewer.start();
    }

    static synchronized void stopView() {
        if (viewFps > 0) emit("VIEWED", "0");
        viewFps = 0;
        controlShown = false;
        setBanner(null);
    }

    /** MOUSE move|down|up|wheel,x,y,n with x/y as 0..1 of the screen. */
    static void mouse(String[] p) throws Exception {
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
        Robot r = robot();
        r.mouseMove((int) Math.round(Double.parseDouble(p[1]) * (d.width - 1)), (int) Math.round(Double.parseDouble(p[2]) * (d.height - 1)));
        int n = Integer.parseInt(p[3]);
        switch (p[0]) {
            case "down" -> r.mousePress(InputEvent.getMaskForButton(n));
            case "up" -> r.mouseRelease(InputEvent.getMaskForButton(n));
            case "wheel" -> r.mouseWheel(n);
        }
        controlled();
    }

    /** KEY down|up,javaVK */
    static void key(String[] p) throws Exception {
        int vk = Integer.parseInt(p[1]);
        if (p[0].equals("down")) robot().keyPress(vk); else robot().keyRelease(vk);
        controlled();
    }

    /** TYPE text: paste it where the cursor is. */
    static void type(String text) throws Exception {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        int mod = System.getProperty("os.name").startsWith("Mac") ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        Robot r = robot();
        r.keyPress(mod); r.keyPress(KeyEvent.VK_V); r.keyRelease(KeyEvent.VK_V); r.keyRelease(mod);
        controlled();
    }

    static void controlled() {
        if (controlShown) return;
        controlShown = true;
        log("Instructor took control of this PC");
        setBanner("Your instructor is controlling this PC");
    }

    /** Always-on-top red strip at the top of the screen; null hides it. */
    static void setBanner(String text) {
        SwingUtilities.invokeLater(() -> {
            if (text == null) { if (banner != null) { banner.dispose(); banner = null; } return; }
            if (banner == null) {
                banner = new JWindow();
                banner.setAlwaysOnTop(true);
                banner.setFocusableWindowState(false);
                bannerLabel = new JLabel("", SwingConstants.CENTER);
                bannerLabel.setOpaque(true);
                bannerLabel.setBackground(new Color(200, 40, 40));
                bannerLabel.setForeground(Color.WHITE);
                bannerLabel.setFont(bannerLabel.getFont().deriveFont(Font.BOLD, 14f));
                bannerLabel.setBorder(BorderFactory.createEmptyBorder(6, 18, 6, 18));
                banner.add(bannerLabel);
            }
            bannerLabel.setText("\u25CF  " + text);
            banner.pack();
            banner.setLocation((Toolkit.getDefaultToolkit().getScreenSize().width - banner.getWidth()) / 2, 0);
            banner.setVisible(true);
        });
    }

    /** Black screen on every monitor that keeps grabbing focus back, plus the keyboard/policy lockdown (Kiosk). */
    static void lock(String msg) {
        if (!locks.isEmpty()) return;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            JFrame f = new JFrame(d.getDefaultConfiguration());
            f.setUndecorated(true);
            f.setAlwaysOnTop(true);
            f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override public void windowIconified(WindowEvent e) { f.setExtendedState(Frame.NORMAL); }
            });
            f.getContentPane().setBackground(Color.BLACK);
            JLabel l = new JLabel(msg, SwingConstants.CENTER);
            l.setForeground(Color.WHITE);
            l.setFont(l.getFont().deriveFont(36f));
            f.add(l);
            d.setFullScreenWindow(f);
            locks.add(f);
        }
        lockTimer = new javax.swing.Timer(200, e -> {
            for (JFrame f : locks) {
                if ((f.getExtendedState() & Frame.ICONIFIED) != 0) f.setExtendedState(Frame.NORMAL); // minimized by a gesture
                f.toFront();
                f.requestFocus();
            }
            Board.toFront(); // a whiteboard shown to a locked class stays above the lock
        });
        lockTimer.start();
        new Thread(Kiosk::on, "kiosk-on").start();
    }

    static void show(java.awt.image.BufferedImage img) {
        if (view == null) {
            view = new JFrame();
            view.setUndecorated(true);
            view.setAlwaysOnTop(true);
            view.getContentPane().setBackground(Color.BLACK);
            viewLabel = new JLabel((Icon) null, SwingConstants.CENTER);
            view.add(viewLabel);
            GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().setFullScreenWindow(view);
        }
        Dimension d = view.getSize();
        double k = Math.min((double) d.width / img.getWidth(), (double) d.height / img.getHeight());
        viewLabel.setIcon(new ImageIcon(img.getScaledInstance((int) (img.getWidth() * k), (int) (img.getHeight() * k), Image.SCALE_FAST)));
    }

    static void hideView() {
        if (view != null) { view.dispose(); view = null; }
    }

    static void unlock() {
        if (lockTimer != null) lockTimer.stop();
        for (JFrame f : locks) f.dispose();
        locks.clear();
        new Thread(Kiosk::off, "kiosk-off").start();
    }

    /** Take every restriction off this PC (unlock, web rules, whiteboard). Safe to call any time. */
    static void releaseAll() {
        Kiosk.off();
        clearWeb();
    }

    /** Rewrites our marked block in the hosts file; empty list removes it. */
    static void block(String sites) throws IOException {
        String old = Files.readString(HOSTS, StandardCharsets.UTF_8);
        String clean = old.replaceAll("(?s)" + Pattern.quote(BEGIN) + ".*?" + Pattern.quote(END) + "\\R?", "");
        StringBuilder sb = new StringBuilder(clean);
        if (!sites.isBlank()) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append("\r\n");
            sb.append(BEGIN).append("\r\n");
            for (String d : sites.split("[,\\s]+")) {
                if (!d.matches("[A-Za-z0-9.-]+")) continue; // trust boundary: no junk into hosts
                sb.append("0.0.0.0 ").append(d).append("\r\n").append("0.0.0.0 www.").append(d).append("\r\n");
            }
            sb.append(END).append("\r\n");
        }
        Files.writeString(HOSTS, sb.toString(), StandardCharsets.UTF_8);
    }

    // ---- web filtering ----

    static WebFilter filter;

    /** mode BLOCK (listed sites denied), ALLOW (only listed sites reachable) or null (everything open). */
    static synchronized void setWeb(String mode, String sites) {
        clearWeb();
        if (mode == null) { log("Web access: open"); return; }
        java.util.List<String> domains = new ArrayList<>();
        for (String d : sites.split("[,\\s]+")) if (d.matches("[A-Za-z0-9.-]+")) domains.add(d.toLowerCase(Locale.ROOT)); // trust boundary
        if (domains.isEmpty()) { log("No valid sites in: " + sites); return; }
        try {
            try { filter = new WebFilter(mode.equals("ALLOW"), domains, FILTER_PORT); }
            catch (BindException busy) { filter = new WebFilter(mode.equals("ALLOW"), domains, 0); }
            Enforce.on(filter.port());
            if (mode.equals("BLOCK")) { try { block(String.join(",", domains)); } catch (IOException e) { log("Hosts file not changed: " + e.getMessage()); } }
            log("Web access: " + (mode.equals("ALLOW") ? "only " : "blocked ") + String.join(", ", domains));
        } catch (IOException e) {
            log("Web filter could not start: " + e.getMessage());
        }
    }

    static synchronized void clearWeb() {
        if (filter != null) { filter.close(); filter = null; }
        Enforce.off();
        try { if (Files.exists(HOSTS)) block(""); } catch (IOException ignored) { }
    }

    static int run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1; }
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    static String runOut(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(20, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Local HTTP/HTTPS forward proxy on 127.0.0.1. Browsers are pointed at it (Enforce), so every request reaches
     * us with its destination host: CONNECT host:443 for HTTPS, absolute-URI requests for HTTP. Each connection
     * carries exactly one request/tunnel, so every request is checked.
     */
    static final class WebFilter implements Closeable {
        final boolean allowOnly;
        final java.util.List<String> domains;
        final Set<String> blockedIps = ConcurrentHashMap.newKeySet(); // block mode: closes "https://<ip of the site>"
        final ServerSocket server;
        final ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "web-filter"); t.setDaemon(true); return t; });

        WebFilter(boolean allowOnly, java.util.List<String> domains, int port) throws IOException {
            this.allowOnly = allowOnly;
            this.domains = List.copyOf(domains);
            this.server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress()); // loopback only: not an open proxy
            if (!allowOnly) pool.execute(this::resolveBlocked);
            pool.execute(this::acceptLoop);
        }

        int port() { return server.getLocalPort(); }

        /** Is `host` reachable under the current rules? Subdomains follow their parent domain. */
        boolean allowed(String host) {
            String h = host.toLowerCase(Locale.ROOT);
            if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
            if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
            if (h.contains(":") || h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return !allowOnly && !blockedIps.contains(h); // raw IPs can't be matched to a domain
            boolean listed = false;
            for (String d : domains) if (h.equals(d) || h.endsWith("." + d)) { listed = true; break; }
            return allowOnly == listed;
        }

        private void resolveBlocked() {
            for (String d : domains) {
                for (String n : new String[]{d, "www." + d}) {
                    try { for (InetAddress ia : InetAddress.getAllByName(n)) blockedIps.add(ia.getHostAddress().toLowerCase(Locale.ROOT)); }
                    catch (UnknownHostException ignored) { }
                }
            }
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket c = server.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void handle(Socket c) {
            try (c) {
                c.setSoTimeout(15_000); // request head must arrive promptly
                OutputStream out = c.getOutputStream();
                byte[] head = readHead(c.getInputStream());
                if (head == null) { reply(out, 400); return; }
                String text = new String(head, StandardCharsets.ISO_8859_1);
                String[] lines = text.split("\r\n");
                String[] rl = lines[0].split(" ");
                if (rl.length != 3) { reply(out, 400); return; }
                String method = rl[0], version = rl[2], host, path = null;
                int port;
                if (method.equalsIgnoreCase("CONNECT")) {
                    int i = rl[1].lastIndexOf(':');
                    if (i <= 0) { reply(out, 400); return; }
                    host = rl[1].substring(0, i);
                    port = Integer.parseInt(rl[1].substring(i + 1));
                } else {
                    URI u = URI.create(rl[1]);
                    if (!"http".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) { reply(out, 400); return; }
                    host = u.getHost();
                    port = u.getPort() < 0 ? 80 : u.getPort();
                    path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
                }
                if (port < 1 || port > 65535) { reply(out, 400); return; }
                if (!allowed(host)) { log("Blocked " + host); reply(out, 403); return; }
                try (Socket up = new Socket()) {
                    try { up.connect(new InetSocketAddress(host, port), 10_000); } catch (IOException e) { reply(out, 502); return; }
                    if (path == null) {
                        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                    } else { // plain HTTP: send the request on in origin form, one request per connection
                        StringBuilder req = new StringBuilder(method).append(' ').append(path).append(' ').append(version).append("\r\n");
                        for (int i = 1; i < lines.length; i++) {
                            String n = lines[i].toLowerCase(Locale.ROOT);
                            if (lines[i].isEmpty() || n.startsWith("proxy-") || n.startsWith("connection:") || n.startsWith("keep-alive:")) continue;
                            req.append(lines[i]).append("\r\n");
                        }
                        req.append("Connection: close\r\n\r\n");
                        up.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
                    } // shortcut: plain-HTTP Upgrade (ws://) is not supported, wss:// goes through CONNECT and works
                    c.setSoTimeout(0);
                    pipe(c, up);
                }
            } catch (IOException | RuntimeException ignored) {
                // bad request or peer reset: the connection just ends
            }
        }

        /** Request head up to and including the blank line, read byte by byte so no body bytes are consumed. */
        static byte[] readHead(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int state = 0; // progress through \r\n\r\n
            for (int ch; (ch = in.read()) != -1; ) {
                b.write(ch);
                if (b.size() > 16 * 1024) return null;
                state = (ch == '\r' && (state == 0 || state == 2)) || (ch == '\n' && (state == 1 || state == 3)) ? state + 1 : (ch == '\r' ? 1 : 0);
                if (state == 4) return b.toByteArray();
            }
            return null;
        }

        private static void reply(OutputStream out, int code) throws IOException {
            String reason = code == 403 ? "Forbidden" : code == 400 ? "Bad Request" : "Bad Gateway";
            String msg = code == 403 ? "This site is blocked by your instructor." : "The request could not be completed.";
            byte[] body = ("<html><body style='font-family:sans-serif;text-align:center;margin-top:20vh'><h1>" + reason + "</h1><p>" + msg + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        }

        /** Copy both directions until one side ends, give the other 15 s to finish, then close both. */
        private void pipe(Socket a, Socket b) {
            CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> copy(a, b), pool);
            CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> copy(b, a), pool);
            try {
                CompletableFuture.anyOf(f1, f2).join();
                CompletableFuture.allOf(f1, f2).get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) { }
            try { a.close(); } catch (IOException ignored) { }
            try { b.close(); } catch (IOException ignored) { }
        }

        private static void copy(Socket from, Socket to) {
            byte[] buf = new byte[16 * 1024];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                for (int n; (n = in.read(buf)) != -1; ) { out.write(buf, 0, n); out.flush(); }
                to.shutdownOutput();
            } catch (IOException ignored) { }
        }

        @Override public void close() {
            try { server.close(); } catch (IOException ignored) { }
            pool.shutdownNow();
        }
    }

    /**
     * Windows enforcement for the web filter: points the system proxy at it and stops known browsers from connecting
     * directly (loopback to the proxy is not filtered by Windows Firewall). Original proxy settings are saved to a file so
     * they survive an agent crash and can be restored on the next start.
     */
    static final class Enforce {
        static final boolean WIN = System.getProperty("os.name").startsWith("Windows");
        static final String INET = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
        static final Path BACKUP = Path.of(System.getProperty("user.home"), ".classcontrol-proxy-backup.properties");
        static final String[] KEYS = {"ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL", "AutoDetect"};

        static synchronized void on(int port) {
            if (!WIN) return; // dev machines: the filter runs but nothing points at it
            if (!Files.exists(BACKUP)) saveOriginals();
            run("reg", "add", INET, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f");
            run("reg", "add", INET, "/v", "ProxyServer", "/t", "REG_SZ", "/d", "127.0.0.1:" + port, "/f");
            run("reg", "add", INET, "/v", "ProxyOverride", "/t", "REG_SZ", "/d", "localhost;127.0.0.1", "/f");
            run("reg", "add", INET, "/v", "AutoDetect", "/t", "REG_DWORD", "/d", "0", "/f");
            run("reg", "delete", INET, "/v", "AutoConfigURL", "/f");
            StringBuilder ps = new StringBuilder();
            for (Path exe : browsers()) {
                ps.append("New-NetFirewallRule -DisplayName 'ClassControl-").append(exe.getFileName()).append("-").append(Integer.toHexString(exe.toString().hashCode()))
                  .append("' -Direction Outbound -Action Block -Profile Any -Program '").append(exe.toString().replace("'", "''")).append("' | Out-Null; ");
            }
            if (ps.length() > 0) run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps.toString());
        }

        static synchronized void off() {
            if (!WIN) return;
            run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-NetFirewallRule -DisplayName 'ClassControl-*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule");
            if (!Files.exists(BACKUP)) return;
            Properties pr = new Properties();
            try (InputStream in = Files.newInputStream(BACKUP)) { pr.load(in); } catch (IOException e) { return; }
            for (String k : KEYS) {
                String v = pr.getProperty(k, "absent");
                if (v.equals("absent")) run("reg", "delete", INET, "/v", k, "/f");
                else { String[] tv = v.split("\\|", 2); run("reg", "add", INET, "/v", k, "/t", tv[0], "/d", tv[1], "/f"); }
            }
            try { Files.deleteIfExists(BACKUP); } catch (IOException ignored) { }
        }

        static void saveOriginals() {
            Properties pr = new Properties();
            for (String k : KEYS) {
                String v = "absent";
                for (String line : runOut("reg", "query", INET, "/v", k).split("\\R")) {
                    String[] f = line.trim().split("\\s{2,}", 3);
                    if (f.length == 3 && f[0].equals(k)) v = f[1] + "|" + f[2];
                }
                pr.setProperty(k, v);
            }
            try (OutputStream out = Files.newOutputStream(BACKUP)) { pr.store(out, "proxy settings before Class Control"); } catch (IOException ignored) { }
        }

        /** Installed browsers worth blocking. shortcut: portable or renamed browsers are not found, use AppLocker for those. */
        static java.util.List<Path> browsers() {
            String[] roots = {System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")};
            String[] rel = {"Google\\Chrome\\Application\\chrome.exe", "Microsoft\\Edge\\Application\\msedge.exe", "Mozilla Firefox\\firefox.exe",
                    "BraveSoftware\\Brave-Browser\\Application\\brave.exe", "Vivaldi\\Application\\vivaldi.exe", "Opera\\opera.exe",
                    "Opera\\launcher.exe", "Programs\\Opera\\opera.exe", "Programs\\Opera\\launcher.exe", "Internet Explorer\\iexplore.exe",
                    "Chromium\\Application\\chrome.exe"};
            java.util.List<Path> found = new ArrayList<>();
            for (String root : roots) {
                if (root == null) continue;
                for (String r : rel) { Path p = Path.of(root, r); if (Files.exists(p) && !found.contains(p)) found.add(p); }
            }
            return found;
        }
    }

    // ---- lock-down while the screen is locked ----

    /**
     * Stops a locked student leaving the lock screen: a hidden PowerShell helper runs a low-level keyboard hook that
     * swallows the Windows key (Start, Win+Tab, Win+Ctrl+arrows = switching desktops), Alt+Tab, Alt+Esc, Alt+F4,
     * Ctrl+Esc and Ctrl+Shift+Esc (also when injected, which is how touchpad gestures arrive); policies hide Task Manager, Lock, Sign out and Shut down. Ctrl+Alt+Del itself
     * cannot be blocked by any program. Touchpad gestures and touchscreen edge swipes are turned off too (the student's own
     * touchpad settings are saved and put back). The helper only blocks keys, it never records them.
     */
    static final class Kiosk {
        static final boolean WIN = System.getProperty("os.name").startsWith("Windows");
        static final String SYS = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System";
        static final String EXP = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\Explorer";
        static final String[][] POLICIES = {{SYS, "DisableTaskMgr"}, {SYS, "DisableLockWorkstation"}, {SYS, "DisableChangePassword"},
                {EXP, "NoLogoff"}, {EXP, "NoClose"}};
        // shortcut: Windows may only pick up touchpad changes once its touchpad settings reload; the keyboard hook is the main block
        static final String PAD = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\PrecisionTouchPad";
        static final String[] PAD_VALUES = {"ThreeFingerSlideEnabled", "FourFingerSlideEnabled", "ThreeFingerTapEnabled", "FourFingerTapEnabled"};
        static final String EDGE = "HKLM\\SOFTWARE\\Policies\\Microsoft\\Windows\\EdgeUI"; // touchscreen edge swipes
        static final Path PAD_SAVED = Path.of(System.getProperty("user.home"), ".classcontrol-touchpad.properties"); // the student's own settings
        static Process hook;

        /** The DWORD value named in "reg query" output, as a decimal string, or null if it isn't there. */
        static String regDword(String out, String name) {
            for (String line : out.split("\\R")) {
                String[] f = line.trim().split("\\s+");
                if (f.length == 3 && f[0].equalsIgnoreCase(name) && f[1].equals("REG_DWORD") && f[2].startsWith("0x")) {
                    try { return String.valueOf(Long.parseLong(f[2].substring(2), 16)); } catch (NumberFormatException e) { return null; }
                }
            }
            return null;
        }

        /** Turns touchpad gestures off, first saving the student's values (once, so a second lock never saves our zeros). */
        static void padOff() {
            if (!Files.exists(PAD_SAVED)) {
                Properties old = new Properties();
                for (String v : PAD_VALUES) { String d = regDword(runOut("reg", "query", PAD, "/v", v), v); if (d != null) old.setProperty(v, d); }
                try (OutputStream o = Files.newOutputStream(PAD_SAVED)) { old.store(o, "Class Control: touchpad settings to restore after a lock"); }
                catch (IOException e) { log("Touchpad gestures left on: " + e.getMessage()); return; }
            }
            for (String v : PAD_VALUES) run("reg", "add", PAD, "/v", v, "/t", "REG_DWORD", "/d", "0", "/f");
        }

        /** Puts back exactly what padOff saved (values that didn't exist are removed again). */
        static void padRestore() {
            if (!Files.exists(PAD_SAVED)) return;
            Properties old = new Properties();
            try (InputStream i = Files.newInputStream(PAD_SAVED)) { old.load(i); } catch (IOException e) { log("Touchpad settings not restored: " + e.getMessage()); return; }
            for (String v : PAD_VALUES) {
                String d = old.getProperty(v);
                if (d != null) run("reg", "add", PAD, "/v", v, "/t", "REG_DWORD", "/d", d, "/f");
                else run("reg", "delete", PAD, "/v", v, "/f");
            }
            try { Files.delete(PAD_SAVED); } catch (IOException ignored) { }
        }

        static final String HOOK_PS1 = """
                param([int]$ParentPid)
                $src = @'
                using System;
                using System.Diagnostics;
                using System.Runtime.InteropServices;
                using System.Windows.Forms;
                public static class KioskHook {
                    delegate IntPtr Proc(int code, IntPtr w, IntPtr l);
                    static Proc proc = Callback;   // keep a reference so the garbage collector never frees the callback
                    static IntPtr hook;
                    [DllImport("user32.dll")] static extern IntPtr SetWindowsHookEx(int id, Proc fn, IntPtr module, uint thread);
                    [DllImport("user32.dll")] static extern bool UnhookWindowsHookEx(IntPtr h);
                    [DllImport("user32.dll")] static extern IntPtr CallNextHookEx(IntPtr h, int code, IntPtr w, IntPtr l);
                    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int vk);
                    [DllImport("kernel32.dll")] static extern IntPtr GetModuleHandle(string name);
                    static IntPtr Callback(int code, IntPtr w, IntPtr l) {
                        if (code >= 0) {
                            int vk = Marshal.ReadInt32(l);                    // KBDLLHOOKSTRUCT.vkCode
                            int flags = Marshal.ReadInt32(l, 8);              // KBDLLHOOKSTRUCT.flags
                            bool alt = (flags & 0x20) != 0;
                            bool ctrl = (GetAsyncKeyState(0x11) & 0x8000) != 0;
                            bool shift = (GetAsyncKeyState(0x10) & 0x8000) != 0;
                            bool block = vk == 0x5B || vk == 0x5C                                  // Windows keys
                                || (alt && (vk == 0x09 || vk == 0x1B || vk == 0x73))               // Alt+Tab, Alt+Esc, Alt+F4
                                || (ctrl && vk == 0x1B)                                            // Ctrl+Esc, Ctrl+Shift+Esc
                                || (ctrl && shift && vk == 0x1B);
                            if (block) return (IntPtr)1;                     // injected keys too: touchpad gestures send them
                        }
                        return CallNextHookEx(hook, code, w, l);
                    }
                    public static void Run(int parentPid) {
                        hook = SetWindowsHookEx(13, proc, GetModuleHandle(Process.GetCurrentProcess().MainModule.ModuleName), 0);
                        System.Windows.Forms.Timer t = new System.Windows.Forms.Timer();
                        t.Interval = 1000;
                        t.Tick += delegate { try { Process.GetProcessById(parentPid); } catch (ArgumentException) { Application.Exit(); } };
                        t.Start();                                                                 // agent gone: stop blocking keys
                        Application.Run();
                        UnhookWindowsHookEx(hook);
                    }
                }
                '@
                Add-Type -ReferencedAssemblies System.Windows.Forms -TypeDefinition $src
                [KioskHook]::Run($ParentPid)
                """;

        static synchronized void on() {
            if (!WIN) return;
            for (String[] p : POLICIES) run("reg", "add", p[0], "/v", p[1], "/t", "REG_DWORD", "/d", "1", "/f");
            run("reg", "add", EDGE, "/v", "AllowEdgeSwipe", "/t", "REG_DWORD", "/d", "0", "/f");
            padOff();
            if (hook != null && hook.isAlive()) return;
            try {
                Path script = Files.createTempFile("cc-kiosk", ".ps1");
                script.toFile().deleteOnExit();
                Files.writeString(script, HOOK_PS1, StandardCharsets.UTF_8);
                hook = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString(), String.valueOf(ProcessHandle.current().pid()))
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) {
                log("Keyboard lock could not start: " + e.getMessage());
            }
        }

        static synchronized void off() {
            if (hook != null) { hook.destroyForcibly(); hook = null; }
            if (!WIN) return;
            for (String[] p : POLICIES) run("reg", "delete", p[0], "/v", p[1], "/f");
            run("reg", "delete", EDGE, "/v", "AllowEdgeSwipe", "/f");
            padRestore();
        }
    }

    // ---- whiteboard ----

    /**
     * The instructor's whiteboard, fullscreen and read-only. Strokes arrive as WB lines (beg/add/end/clear/off) with
     * coordinates and widths as fractions of a 16:9 board, so it looks the same on every screen size.
     */
    @SuppressWarnings("serial")
    static final class Board extends JPanel {
        record Line(Color color, double width, java.util.List<double[]> pts) { }

        static JFrame frame;
        static Board board;
        final java.util.List<Line> lines = new ArrayList<>();
        Line current;
        int points;

        static void apply(String arg) { // call on the Swing thread
            if (arg.equals("off")) {
                if (frame != null) { frame.dispose(); frame = null; board = null; }
                return;
            }
            if (frame == null) {
                board = new Board();
                board.setBackground(new Color(30, 32, 36));
                frame = new JFrame();
                frame.setUndecorated(true);
                frame.setAlwaysOnTop(true);
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                frame.add(board);
                GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().setFullScreenWindow(frame);
            }
            board.update(arg);
        }

        static void toFront() { if (frame != null) frame.toFront(); }

        void update(String arg) {
            String[] p = arg.split(",");
            try {
                switch (p[0]) {
                    case "beg" -> {
                        current = new Line(new Color(Integer.parseInt(p[1], 16)), Math.min(0.1, Double.parseDouble(p[2])), new ArrayList<>());
                        lines.add(current);
                        add(p[3], p[4]);
                    }
                    case "add" -> { if (current != null) for (int i = 1; i + 1 < p.length; i += 2) add(p[i], p[i + 1]); }
                    case "end" -> current = null;
                    case "clear" -> { lines.clear(); current = null; points = 0; }
                    default -> { }
                }
            } catch (NumberFormatException ignored) { } // the server already validates; a bad line just draws nothing
            repaint();
        }

        private void add(String x, String y) {
            if (points++ > 200_000) return; // cap memory
            current.pts().add(new double[]{Math.max(0, Math.min(1, Double.parseDouble(x))), Math.max(0, Math.min(1, Double.parseDouble(y)))});
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int bw = Math.min(getWidth(), getHeight() * 16 / 9), bh = bw * 9 / 16;
            int ox = (getWidth() - bw) / 2, oy = (getHeight() - bh) / 2;
            g.setColor(Color.WHITE);
            g.fillRect(ox, oy, bw, bh);
            g.setClip(ox, oy, bw, bh);
            for (Line l : lines) {
                g.setColor(l.color());
                g.setStroke(new BasicStroke((float) Math.max(1, l.width() * bw), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
                double[] a = l.pts().get(0);
                path.moveTo(ox + a[0] * bw, oy + a[1] * bh);
                if (l.pts().size() == 1) path.lineTo(ox + a[0] * bw + 0.01, oy + a[1] * bh); // a tap draws a dot
                for (double[] q : l.pts()) path.lineTo(ox + q[0] * bw, oy + q[1] * bh);
                g.draw(path);
            }
        }
    }
}
