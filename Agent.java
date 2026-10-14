import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import javax.imageio.ImageIO;
import java.util.regex.Pattern;

/** Student agent. Run as Administrator (hosts file): java Agent.java <teacherIP> <token> */
public class Agent {
    static final Path HOSTS = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
            "System32", "drivers", "etc", "hosts");
    static final String BEGIN = "# CLASSROOM-BEGIN", END = "# CLASSROOM-END";
    static java.util.List<JFrame> locks = new java.util.ArrayList<>();
    static javax.swing.Timer lockTimer;
    static JFrame view;
    static JLabel viewLabel;

    static JLabel status = new JLabel("Starting..."), info = new JLabel();
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
            JPanel top = new JPanel(new GridLayout(0, 1));
            top.add(status);
            top.add(info);
            f.add(top, BorderLayout.NORTH);
            f.add(new JScrollPane(logArea), BorderLayout.CENTER);
            f.setSize(420, 300);
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setVisible(true);
        });
        SwingUtilities.invokeLater(() -> info.setText("  Teacher: " + host + ":" + port + "   (this PC: " + me + ")"));
        log("Agent started, teacher=" + host + ":" + port);
        while (true) { // reconnect forever so a teacher restart doesn't strand the PC
            setStatus("Connecting to " + host + "...", Color.GRAY);
            try (Socket s = new Socket(host, port)) {
                setStatus("Connected to teacher (" + host + ")", new Color(0, 130, 0));
                log("Connected as " + me);
                final String mine = s.getLocalAddress().getHostAddress();
                SwingUtilities.invokeLater(() -> info.setText("  Teacher: " + host + ":" + port + "   (this PC: " + me + ", " + mine + ")"));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                out.println(token + "\t" + me);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                for (String line; (line = in.readLine()) != null; ) {
                    if (!line.startsWith("SCREEN")) log("Teacher: " + line.replace("\t", " "));
                    handle(line.split("\t", 2));
                }
                setStatus("Disconnected - wrong password?", Color.RED);
                log("Teacher closed the connection (wrong password?)");
            } catch (IOException e) {
                setStatus("Cannot reach teacher - retrying", Color.RED);
                log("Cannot reach " + host + ":" + port + " - " + e.getMessage());
            }
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
                case "BLOCK" -> { block(arg); policy(arg, false); }
                case "ALLOW" -> { block(""); policy(arg, true); }
                case "UNBLOCK" -> { block(""); policy("", false); }
            }
        } catch (Exception e) { log("Error: " + e); }
    }

    /** Covers every monitor and keeps grabbing focus back, so Alt+Tab / Win key only flash. Task Manager is disabled while locked. */
    static void lock(String msg) {
        if (!locks.isEmpty()) return;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            JFrame f = new JFrame(d.getDefaultConfiguration());
            f.setUndecorated(true);
            f.setAlwaysOnTop(true);
            f.getContentPane().setBackground(Color.BLACK);
            JLabel l = new JLabel(msg, SwingConstants.CENTER);
            l.setForeground(Color.WHITE);
            l.setFont(l.getFont().deriveFont(36f));
            f.add(l);
            d.setFullScreenWindow(f);
            locks.add(f);
        }
        lockTimer = new javax.swing.Timer(200, e -> { for (JFrame f : locks) { f.toFront(); f.requestFocus(); } });
        lockTimer.start();
        run("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System", "/v", "DisableTaskMgr", "/t", "REG_DWORD", "/d", "1", "/f");
        // shortcut: Ctrl+Alt+Del (Lock/Sign out) still works; a low-level keyboard hook (JNA) or kiosk mode would close it
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
        run("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System", "/v", "DisableTaskMgr", "/f");
    }

    static void run(String... cmd) {
        try { new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(); } catch (Exception e) { log("Error: " + e); }
    }

    /** Chrome and Edge policy: blocklist, or (allowOnly) block everything except the listed sites. Browsers apply it live. */
    static void policy(String sites, boolean allowOnly) {
        for (String b : new String[]{"Google\\Chrome", "Microsoft\\Edge"}) {
            String root = "HKLM\\SOFTWARE\\Policies\\" + b;
            run("reg", "delete", root + "\\URLBlocklist", "/f");
            run("reg", "delete", root + "\\URLAllowlist", "/f");
            if (sites.isBlank()) continue;
            if (allowOnly) run("reg", "add", root + "\\URLBlocklist", "/v", "1", "/t", "REG_SZ", "/d", "*", "/f");
            int i = 1;
            for (String d : sites.split("[,\\s]+"))
                if (d.matches("[A-Za-z0-9.-]+")) // trust boundary: no junk into the registry
                    run("reg", "add", root + (allowOnly ? "\\URLAllowlist" : "\\URLBlocklist"), "/v", "" + i++, "/t", "REG_SZ", "/d", d, "/f");
        }
        run("ipconfig", "/flushdns");
        // shortcut: Chrome/Edge only; Firefox and other apps are covered just by the hosts-file blocklist
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
