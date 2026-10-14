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
    private final JLabel nameLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
    private PcView pc;
    private boolean selected;

    public PcTile(PcView pc, BiConsumer<String, MouseEvent> onClick) {
        this.pcId = pc.id();
        setOpaque(false);
        setLayout(new BorderLayout(0, 2));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(THUMB_W + 20, THUMB_H + 56));

        nameLabel.setFont(Theme.FONT_SECTION);
        nameLabel.setForeground(Theme.TEXT_PRIMARY);
        statusLabel.setFont(Theme.FONT_SMALL);

        javax.swing.Box south = javax.swing.Box.createVerticalBox();
        south.add(nameLabel);
        south.add(statusLabel);

        add(thumbLabel, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        update(pc);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onClick.accept(pcId, e);
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
        this.pc = pc;
        Image scaled = pc.thumbnail().getScaledInstance(THUMB_W, THUMB_H, Image.SCALE_SMOOTH);
        thumbLabel.setIcon(new ImageIcon(scaled));
        nameLabel.setText(pc.host());
        Color statusColor = Theme.statusColor(pc.status());
        statusLabel.setForeground(statusColor);
        statusLabel.setText("● " + pc.status() + (pc.locked() ? "  🔒" : ""));
        setToolTipText(pc.host() + " - " + pc.ip() + " - " + pc.status());
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(selected ? new Color(0x2F, 0x6F, 0xED, 22) : Theme.CARD_BG);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g2.setStroke(new BasicStroke(selected ? 2f : 1f));
        g2.setColor(selected ? Theme.ACCENT : Theme.BORDER);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g2.dispose();
        super.paintComponent(g);
    }
}
