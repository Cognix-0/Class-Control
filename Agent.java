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

/** Student agent. Run as Administrator (hosts file): java Agent.java <teacherIP> <token> */
public class Agent {
    static final Path HOSTS = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
            "System32", "drivers", "etc", "hosts");
    static final String BEGIN = "# CLASSROOM-BEGIN", END = "# CLASSROOM-END";
    static JFrame lock, view;
    static JLabel viewLabel;
    static volatile PrintWriter toTeacher;
    static volatile int viewFps;          // > 0 while the instructor's remote view is open
    static volatile boolean controlShown; // banner already says "controlling"
    static Thread viewer;
    static Robot robot;
    static JWindow banner;
    static JLabel bannerLabel;

    static JLabel status = new JLabel("Starting...");
    static JTextArea logArea = new JTextArea();

    static void log(String m) {
        System.out.println(m);
        SwingUtilities.invokeLater(() -> logArea.append(java.time.LocalTime.now().withNano(0) + "  " + m + "\n"));
    }

    static void setStatus(String s, Color c) {
        SwingUtilities.invokeLater(() -> { status.setText(s); status.setForeground(c); });
    }

    public static void main(String[] a) throws Exception {
        String host = a.length > 0 ? a[0] : JOptionPane.showInputDialog("Teacher PC address (IP):");
        String token = a.length > 1 ? a[1] : JOptionPane.showInputDialog("Class password:");
        if (host == null || token == null) return;
        int port = a.length > 2 ? Integer.parseInt(a[2]) : 5757;
        String me = System.getenv().getOrDefault("COMPUTERNAME", "student");
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Classroom Control - Student (" + me + ")");
            status.setFont(status.getFont().deriveFont(Font.BOLD, 16f));
            status.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            logArea.setEditable(false);
            f.add(status, BorderLayout.NORTH);
            f.add(new JScrollPane(logArea), BorderLayout.CENTER);
            f.setSize(420, 300);
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setVisible(true);
        });
        log("Agent started, teacher=" + host + ":" + port);
        while (true) { // reconnect forever so a teacher restart doesn't strand the PC
            setStatus("Connecting to " + host + "...", Color.GRAY);
            try (Socket s = new Socket(host, port)) {
                setStatus("Connected to teacher (" + host + ")", new Color(0, 130, 0));
                log("Connected as " + me);
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                out.println(token + "\t" + me);
                toTeacher = out;
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                for (String line; (line = in.readLine()) != null; ) {
                    if (!line.matches("(SCREEN|THUMB|MOUSE|KEY|VIEW)\\b.*")) log("Teacher: " + line.replace("\t", " "));
                    handle(line.split("\t", 2));
                }
                setStatus("Disconnected - wrong password?", Color.RED);
                log("Teacher closed the connection (wrong password?)");
            } catch (IOException e) {
                setStatus("Cannot reach teacher - retrying", Color.RED);
                log("Cannot reach " + host + ":" + port + " - " + e.getMessage());
            }
            toTeacher = null;
            stopView(); // teacher gone: never leave the "being viewed" stream or banner running
            Thread.sleep(3000);
        }
    }

    static void handle(String[] p) {
        String arg = p.length > 1 ? p[1] : "";
        try {
            switch (p[0]) {
                case "MSG" -> new Thread(() -> JOptionPane.showMessageDialog(null, arg, "Teacher", JOptionPane.INFORMATION_MESSAGE)).start();
                case "URL" -> { if (arg.matches("(?i)https?://.+")) Desktop.getDesktop().browse(URI.create(arg)); } // http(s) only: never run arbitrary schemes
                case "LOCK" -> SwingUtilities.invokeLater(() -> lock(arg));
                case "SCREEN" -> { var img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(arg))); SwingUtilities.invokeLater(() -> show(img)); }
                case "SCREENOFF" -> SwingUtilities.invokeLater(Agent::hideView);
                case "UNLOCK" -> SwingUtilities.invokeLater(Agent::unlock);
                case "BLOCK" -> block(arg);
                case "UNBLOCK" -> block("");
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
        if (viewer != null) return;
        viewer = new Thread(() -> {
            try {
                while (viewFps > 0) {
                    long t = System.currentTimeMillis();
                    PrintWriter out = toTeacher;
                    if (out != null) out.println("FRAME\t" + capture(1280));
                    Thread.sleep(Math.max(10, 1000 / Math.max(1, viewFps) - (System.currentTimeMillis() - t)));
                }
            } catch (Exception e) { log("Remote view error: " + e); }
            finally { synchronized (Agent.class) { viewer = null; } }
        }, "remote-view");
        viewer.setDaemon(true);
        viewer.start();
    }

    static synchronized void stopView() {
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

    static void lock(String msg) {
        if (lock != null) return;
        lock = new JFrame();
        lock.setUndecorated(true);
        lock.setAlwaysOnTop(true);
        lock.getContentPane().setBackground(Color.BLACK);
        JLabel l = new JLabel(msg, SwingConstants.CENTER);
        l.setForeground(Color.WHITE);
        l.setFont(l.getFont().deriveFont(36f));
        lock.add(l);
        GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().setFullScreenWindow(lock);
        // shortcut: Ctrl+Alt+Del / Task Manager can still escape the lock; harden with a kiosk policy if needed
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
        if (lock != null) { lock.dispose(); lock = null; }
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
}
