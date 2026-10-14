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
    static JFrame lock, view;
    static JLabel viewLabel;

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
                case "BLOCK" -> block(arg);
                case "UNBLOCK" -> block("");
            }
        } catch (Exception e) { log("Error: " + e); }
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
