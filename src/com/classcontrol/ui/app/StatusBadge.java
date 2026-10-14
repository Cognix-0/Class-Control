package com.classcontrol.ui.app;

import com.classcontrol.ui.api.Status;

import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

public class StatusBadge extends JLabel {

    private Color fg = Theme.STATUS_ONLINE;
    private Color tint = Theme.statusTint(Status.ONLINE);

    public StatusBadge() {
        super("", SwingConstants.CENTER);
        setFont(Theme.FONT_SMALL_BOLD);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 9, 3, 9));
        setOpaque(false);
    }

    public void setStatus(Status status, boolean locked) {
        fg = Theme.statusColor(status);
        tint = Theme.statusTint(status);
        setForeground(fg);
        setText(status.name() + (locked ? " • LOCKED" : ""));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(tint);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
