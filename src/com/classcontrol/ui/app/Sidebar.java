package com.classcontrol.ui.app;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Consumer;

/** Vertical icon rail on the left edge of the console, in the spirit of SchoolMaster's nav dock. */
public class Sidebar extends JPanel {

    private static final Font ICON_FONT = new Font("Segoe UI Emoji", Font.PLAIN, 17);

    public Sidebar(Consumer<Boolean> onLiveToggle, Consumer<Boolean> onLogToggle, Runnable onInfo, Runnable onExit) {
        setPreferredSize(new Dimension(68, 0));
        setBackground(Theme.SIDEBAR_BG);
        setLayout(new BorderLayout());

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(18, 0, 10, 0));
        top.add(logo());
        top.add(Box.createVerticalStrut(24));
        top.add(actionButton("🖥", "Seat map", true, null));
        top.add(Box.createVerticalStrut(10));
        top.add(toggleButton("📡", "Live view", false, onLiveToggle));
        top.add(Box.createVerticalStrut(10));
        top.add(toggleButton("🗒", "Event log", true, onLogToggle));

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(10, 0, 18, 0));
        bottom.add(actionButton("⚙", "Mode info", false, onInfo));
        bottom.add(Box.createVerticalStrut(10));
        bottom.add(actionButton("⏻", "Exit", false, onExit));

        add(top, BorderLayout.NORTH);
        add(bottom, BorderLayout.SOUTH);
    }

    private JComponent logo() {
        JPanel mark = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, Theme.ACCENT, getWidth(), getHeight(), Theme.ACCENT_2);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.setColor(Color.WHITE);
                g2.setFont(Theme.FONT_TITLE.deriveFont(Font.BOLD, 16f));
                FontMetrics fm = g2.getFontMetrics();
                String s = "C";
                int tx = (getWidth() - fm.stringWidth(s)) / 2;
                int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(s, tx, ty);
                g2.dispose();
            }
        };
        mark.setOpaque(false);
        mark.setPreferredSize(new Dimension(36, 36));
        mark.setMaximumSize(new Dimension(36, 36));
        mark.setAlignmentX(Component.CENTER_ALIGNMENT);
        return mark;
    }

    private JComponent actionButton(String glyph, String tooltip, boolean highlighted, Runnable onClick) {
        JButton btn = new JButton(glyph) {
            @Override
            protected void paintComponent(Graphics g) {
                if (highlighted) {
                    paintRailBackground(g, Theme.SIDEBAR_ACTIVE_BG);
                } else if (getModel().isRollover()) {
                    paintRailBackground(g, Theme.SIDEBAR_BG_HOVER);
                }
                super.paintComponent(g);
            }
        };
        styleRailButton(btn, tooltip);
        if (onClick != null) {
            btn.addActionListener(e -> onClick.run());
        }
        return btn;
    }

    private JComponent toggleButton(String glyph, String tooltip, boolean initiallySelected, Consumer<Boolean> onToggle) {
        JToggleButton btn = new JToggleButton(glyph) {
            @Override
            protected void paintComponent(Graphics g) {
                if (isSelected()) {
                    paintRailBackground(g, Theme.SIDEBAR_ACTIVE_BG);
                } else if (getModel().isRollover()) {
                    paintRailBackground(g, Theme.SIDEBAR_BG_HOVER);
                }
                super.paintComponent(g);
            }
        };
        btn.setSelected(initiallySelected);
        styleRailButton(btn, tooltip);
        btn.addActionListener(e -> onToggle.accept(btn.isSelected()));
        return btn;
    }

    private void paintRailBackground(Graphics g, Color fill) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(fill);
        g2.fillRoundRect(4, 2, 40, 36, 12, 12);
        g2.dispose();
    }

    private void styleRailButton(javax.swing.AbstractButton btn, String tooltip) {
        btn.setFont(ICON_FONT);
        btn.setForeground(Theme.SIDEBAR_ICON_ACTIVE);
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setOpaque(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
        btn.setMaximumSize(new Dimension(48, 40));
        btn.setPreferredSize(new Dimension(48, 40));
        btn.setToolTipText(tooltip);
    }
}
