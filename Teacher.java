import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.List;
import javax.imageio.*;

/** Teacher console. Run: java Teacher.java <token> [port] */
public class Teacher {
    static int port = 5757;
    static final DefaultListModel<Student> students = new DefaultListModel<>();
    static String token;

    record Student(String name, Socket sock, PrintWriter out) {
        public String toString() { return name + " (" + sock.getInetAddress().getHostAddress() + ")"; }
    }

    public static void main(String[] a) throws Exception {
        token = a.length > 0 ? a[0] : "changeme";
        if (a.length > 1) port = Integer.parseInt(a[1]);
        new Thread(Teacher::accept, "accept").start();
        SwingUtilities.invokeLater(Teacher::gui);
    }

    static void accept() {
        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                Socket s = ss.accept();
                new Thread(() -> serve(s)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    static void serve(Socket s) {
        Student st = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
            String[] hello = in.readLine().split("\t", 2);
            if (hello.length < 2 || !hello[0].equals(token)) { s.close(); return; } // wrong token: drop
            st = new Student(hello[1], s, out);
            final Student added = st;
            SwingUtilities.invokeLater(() -> students.addElement(added));
            while (in.readLine() != null) { } // agent sends nothing else yet; blocks until it disconnects
        } catch (Exception ignored) {
        } finally {
            final Student gone = st;
            if (gone != null) SwingUtilities.invokeLater(() -> students.removeElement(gone));
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** Sends to selected students, or everyone if none selected. */
    static void send(JList<Student> list, String cmd, String arg) {
        send(targets(list), cmd, arg);
    }

    static List<Student> targets(JList<Student> list) {
        List<Student> to = list.getSelectedValuesList();
        return to.isEmpty() ? java.util.Collections.list(students.elements()) : to;
    }

    static void send(List<Student> to, String cmd, String arg) {
        for (Student s : to) s.out().println(cmd + "\t" + arg.replace("\n", " "));
    }

    static volatile boolean sharing;

    /** Streams screenshots (~2/s, 1024px wide JPEG) to the chosen students until stopped. */
    static void share(JList<Student> list) {
        if (sharing) return;
        // shortcut: targets are fixed when sharing starts; students who join later miss it, restart sharing
        List<Student> to = List.copyOf(targets(list));
        sharing = true;
        new Thread(() -> {
            try {
                Robot r = new Robot();
                Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                while (sharing) {
                    BufferedImage shot = r.createScreenCapture(screen);
                    int w = 1024, h = shot.getHeight() * w / shot.getWidth();
                    BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = small.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(shot, 0, 0, w, h, null);
                    g.dispose();
                    ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                    ImageIO.write(small, "jpg", jpg);
                    send(to, "SCREEN", Base64.getEncoder().encodeToString(jpg.toByteArray()));
                    Thread.sleep(500);
                }
            } catch (Exception e) { e.printStackTrace(); sharing = false; }
            send(to, "SCREENOFF", "");
        }, "share").start();
    }

    static void gui() {
        JFrame f = new JFrame("Classroom Control - Teacher");
        JList<Student> list = new JList<>(students);
        JPanel bar = new JPanel(new GridLayout(0, 1, 4, 4));
        bar.add(btn("Send message", () -> ask("Message:", list, "MSG")));
        bar.add(btn("Open link", () -> ask("URL (https://...):", list, "URL")));
        bar.add(btn("Lock screens", () -> send(list, "LOCK", "Eyes on the teacher")));
        bar.add(btn("Unlock screens", () -> send(list, "UNLOCK", "")));
        bar.add(btn("Share my screen", () -> share(list)));
        bar.add(btn("Stop sharing", () -> sharing = false));
        bar.add(btn("Block sites", () -> ask("Sites to block (comma separated):", list, "BLOCK")));
        bar.add(btn("Unblock all sites", () -> send(list, "UNBLOCK", "")));
        f.add(new JLabel("  Select students (none = all)"), BorderLayout.NORTH);
        f.add(new JScrollPane(list), BorderLayout.CENTER);
        f.add(bar, BorderLayout.EAST);
        f.setSize(560, 360);
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setVisible(true);
    }

    static void ask(String prompt, JList<Student> list, String cmd) {
        String v = JOptionPane.showInputDialog(prompt);
        if (v != null && !v.isBlank()) send(list, cmd, v.trim());
    }

    static JButton btn(String t, Runnable r) {
        JButton b = new JButton(t);
        b.addActionListener(e -> r.run());
        return b;
    }
}
