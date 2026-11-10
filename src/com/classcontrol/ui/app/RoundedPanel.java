package com.classcontrol.ui.app;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

public class RoundedPanel extends JPanel {

    private final int radius;
    private Color borderColor = Theme.BORDER;
    private boolean shadow = true;

    public RoundedPanel(LayoutManager layout, int radius) {
        super(layout);
        this.radius = radius;
        setOpaque(false);
    }

    public void setBorderColor(Color color) {
        this.borderColor = color;
        repaint();
    }

    public void setShadowEnabled(boolean enabled) {
        this.shadow = enabled;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        if (shadow) {
            g2.setColor(new Color(0x10, 0x10, 0x20, 20));
            g2.fillRoundRect(0, 3, w - 1, h - 4, radius, radius);
        }
        g2.setColor(getBackground());
        g2.fillRoundRect(0, 0, w - 1, h - 4, radius, radius);
        g2.setColor(borderColor);
        g2.drawRoundRect(0, 0, w - 1, h - 4, radius, radius);
        g2.dispose();
        super.paintComponent(g);
    }
}
