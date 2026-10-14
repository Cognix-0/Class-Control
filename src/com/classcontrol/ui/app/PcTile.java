package com.classcontrol.ui.app;

import com.classcontrol.ui.api.PcView;
import com.classcontrol.ui.api.Status;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;

public class PcTile extends JPanel {

    private static final int THUMB_W = 120;
    private static final int THUMB_H = 90;

    private final String pcId;
    private final JLabel thumbLabel = new JLabel();
    private final JLabel nameLabel = new JLabel("", SwingConstants.CENTER);
    private boolean selected;

    public PcTile(PcView pc, BiConsumer<String, MouseEvent> onClick) {
        this.pcId = pc.id();
        setLayout(new BorderLayout(2, 2));
        setPreferredSize(new Dimension(THUMB_W + 10, THUMB_H + 34));

        thumbLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(thumbLabel, BorderLayout.CENTER);
        add(nameLabel, BorderLayout.SOUTH);

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
        Image scaled = pc.thumbnail().getScaledInstance(THUMB_W, THUMB_H, Image.SCALE_SMOOTH);
        thumbLabel.setIcon(new ImageIcon(scaled));
        nameLabel.setText(pc.host() + (pc.locked() ? " 🔒" : ""));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected ? Color.BLUE : statusColor(pc.status()), selected ? 3 : 2),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)
        ));
        setToolTipText(pc.host() + " - " + pc.ip() + " - " + pc.status());
    }

    private Color statusColor(Status status) {
        return switch (status) {
            case ONLINE -> new Color(60, 160, 60);
            case OFFLINE -> Color.GRAY;
            case LOCKED -> new Color(200, 140, 0);
            case NOT_RESPONDING -> new Color(190, 50, 50);
        };
    }
}
