package com.classcontrol.ui.app;

import com.classcontrol.ui.api.PcView;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.BoxLayout;
import java.awt.Image;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DetailPanel extends JPanel {

    private static final int THUMB_W = 180;
    private static final int THUMB_H = 135;

    private final JLabel thumbLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel hostLabel = new JLabel();
    private final JLabel ipLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JLabel lastSeenLabel = new JLabel();
    private final JLabel placeholder = new JLabel("(select a PC)");

    public DetailPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        showPlaceholder();
    }

    public void showPlaceholder() {
        removeAll();
        add(placeholder);
        revalidate();
        repaint();
    }

    public void showMultiple(int count) {
        removeAll();
        add(new JLabel(count + " PCs selected"));
        revalidate();
        repaint();
    }

    public void showPc(PcView pc) {
        removeAll();
        Image scaled = pc.thumbnail().getScaledInstance(THUMB_W, THUMB_H, Image.SCALE_SMOOTH);
        thumbLabel.setIcon(new ImageIcon(scaled));
        hostLabel.setText("Host: " + pc.host());
        ipLabel.setText("IP: " + pc.ip());
        statusLabel.setText("Status: " + pc.status() + (pc.locked() ? " (locked)" : ""));
        lastSeenLabel.setText("Last seen: " + new SimpleDateFormat("HH:mm:ss").format(new Date(pc.lastSeenMs())));

        add(thumbLabel);
        add(hostLabel);
        add(ipLabel);
        add(statusLabel);
        add(lastSeenLabel);
        revalidate();
        repaint();
    }
}
