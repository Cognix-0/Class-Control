package com.classcontrol.ui.app;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

public class RoundedButton extends JButton {

    private final Color bg;
    private final Color bgHover;
    private final Color outline;

    public RoundedButton(String text, Color bg, Color bgHover, Color fg) {
        this(text, bg, bgHover, fg, null);
    }

    public RoundedButton(String text, Color bg, Color bgHover, Color fg, Color outline) {
        super(text);
        this.bg = bg;
        this.bgHover = bgHover;
        this.outline = outline;
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(false);
        setForeground(fg);
        setFont(Theme.FONT_BASE);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 14, 8, 14));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color fill = isEnabled() ? (getModel().isRollover() ? bgHover : bg) : disabled(bg);
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
        if (outline != null) {
            g2.setColor(isEnabled() ? outline : disabled(outline));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
        }
        g2.dispose();
        super.paintComponent(g);
    }

    private Color disabled(Color c) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 90);
    }
}
