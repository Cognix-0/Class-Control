package com.classcontrol.ui.app;

import com.classcontrol.ui.api.PcView;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;

public class PcTile extends JPanel {

    private static final int THUMB_W = 128;
    private static final int THUMB_H = 86;

    private final String pcId;
    private final JLabel thumbLabel = new JLabel();
    private final JLabel nameLabel = new JLabel();
    private final StatusBadge statusBadge = new StatusBadge();
    private boolean selected;
    private boolean hovered;

    public PcTile(PcView pc, BiConsumer<String, MouseEvent> onClick) {
        this.pcId = pc.id();
        setOpaque(false);
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setPreferredSize(new Dimension(THUMB_W + 24, THUMB_H + 66));

        nameLabel.setFont(Theme.FONT_SECTION);
        nameLabel.setForeground(Theme.TEXT_PRIMARY);

        javax.swing.Box south = javax.swing.Box.createVerticalBox();
        south.add(nameLabel);
        south.add(javax.swing.Box.createVerticalStrut(5));
        statusBadge.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        south.add(statusBadge);

        add(thumbLabel, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        update(pc);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onClick.accept(pcId, e);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                repaint();
            }
        });
    }

    public String getPcId() {
        return pcId;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        repaint();
    }

    public boolean isSelected() {
        return selected;
    }

    public void update(PcView pc) {
        Image scaled = pc.thumbnail().getScaledInstance(THUMB_W, THUMB_H, Image.SCALE_SMOOTH);
        ImageIcon icon = new ImageIcon(scaled);
        thumbLabel.setIcon(ImageUtil.roundedIcon(icon, 8));
        nameLabel.setText(pc.host());
        statusBadge.setStatus(pc.status(), pc.locked());
        setToolTipText(pc.host() + " - " + pc.ip() + " - " + pc.status());
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth() - 1, h = getHeight() - 4;
        if (hovered || selected) {
            g2.setColor(new Color(0x10, 0x10, 0x20, 26));
            g2.fillRoundRect(0, 3, w, h, 14, 14);
        }
        Color fill = selected ? Theme.ACCENT_TINT : (hovered ? Theme.NEUTRAL_BUTTON_HOVER : Theme.CARD_BG);
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, w, h, 14, 14);
        g2.setStroke(new BasicStroke(selected ? 2f : 1f));
        g2.setColor(selected ? Theme.ACCENT : Theme.BORDER);
        g2.drawRoundRect(0, 0, w, h, 14, 14);
        g2.dispose();
        super.paintComponent(g);
    }
}
