package com.classcontrol.ui.app;

import com.classcontrol.ui.api.PcView;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Image;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DetailPanel extends JPanel {

    private static final int THUMB_W = 192;
    private static final int THUMB_H = 128;

    private final JLabel thumbLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel hostLabel = new JLabel();
    private final JLabel ipLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JLabel lastSeenLabel = new JLabel();
    private final JLabel placeholder = new JLabel("<html>Select a PC<br>to see details</html>");

    public DetailPanel() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        for (JLabel field : new JLabel[]{hostLabel, ipLabel, statusLabel, lastSeenLabel}) {
            field.setFont(Theme.FONT_BASE);
            field.setForeground(Theme.TEXT_PRIMARY);
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        hostLabel.setFont(Theme.FONT_SECTION);
        placeholder.setFont(Theme.FONT_BASE);
        placeholder.setForeground(Theme.TEXT_SECONDARY);

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
        JLabel label = new JLabel(count + " PCs selected");
        label.setFont(Theme.FONT_SECTION);
        label.setForeground(Theme.TEXT_PRIMARY);
        add(label);
        revalidate();
        repaint();
    }

    public void showPc(PcView pc) {
        removeAll();
        Image scaled = pc.thumbnail().getScaledInstance(THUMB_W, THUMB_H, Image.SCALE_SMOOTH);
        thumbLabel.setIcon(new ImageIcon(scaled));
        thumbLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        hostLabel.setText(pc.host());
        ipLabel.setText(pc.ip());
        statusLabel.setForeground(Theme.statusColor(pc.status()));
        statusLabel.setText("● " + pc.status() + (pc.locked() ? "  🔒 locked" : ""));
        lastSeenLabel.setText("Last seen " + new SimpleDateFormat("HH:mm:ss").format(new Date(pc.lastSeenMs())));
        lastSeenLabel.setForeground(Theme.TEXT_SECONDARY);

        add(thumbLabel);
        add(javax.swing.Box.createVerticalStrut(10));
        add(hostLabel);
        add(ipLabel);
        add(javax.swing.Box.createVerticalStrut(6));
        add(statusLabel);
        add(lastSeenLabel);
        revalidate();
        repaint();
    }
}
