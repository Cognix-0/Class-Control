package com.classcontrol.ui.app;

import com.classcontrol.ui.api.LabService;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Set;

public class ConsoleFrame extends JFrame {

    private final LabService labService;
    private final SeatMapPanel seatMapPanel = new SeatMapPanel();
    private final ActionBar actionBar;
    private final DetailPanel detailPanel = new DetailPanel();
    private final EventLogPanel eventLogPanel = new EventLogPanel();

    public ConsoleFrame(LabService labService) {
        super("ClassControl - Instructor Console");
        this.labService = labService;
        this.actionBar = new ActionBar(labService, seatMapPanel::getSelectedIds);
        seatMapPanel.setSelectionListener(ids -> {
            actionBar.updateEnabled();
            updateDetailPanel(ids);
        });

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 760));

        JPanel root = new JPanel(new BorderLayout());
        root.setPreferredSize(new Dimension(1180, 780));
        root.setBackground(Theme.PAGE_BG);
        root.add(buildAppBar(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(12, 12));
        body.setBackground(Theme.PAGE_BG);
        body.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        body.add(buildActionsCard(), BorderLayout.NORTH);
        body.add(buildCenter(), BorderLayout.CENTER);
        body.add(buildDetailCard(), BorderLayout.EAST);
        body.add(buildEventLogCard(), BorderLayout.SOUTH);

        root.add(body, BorderLayout.CENTER);

        setContentPane(root);
        pack();
        setLocationRelativeTo(null);

        labService.addListener(seatMapPanel);
        labService.addListener(eventLogPanel);
        seatMapPanel.setPcs(labService.listPcs());
    }

    private JPanel buildAppBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.APP_BAR_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JLabel title = new JLabel("ClassControl");
        title.setFont(Theme.FONT_TITLE);
        title.setForeground(Theme.APP_BAR_FG);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        right.setOpaque(false);

        JCheckBox liveView = new JCheckBox("Live view");
        liveView.setOpaque(false);
        liveView.setForeground(Theme.APP_BAR_FG);
        liveView.setFont(Theme.FONT_BASE);
        liveView.addActionListener(e -> labService.setLiveView(liveView.isSelected(), 2000));

        JLabel mode = new JLabel(labService.getClass().getSimpleName());
        mode.setFont(Theme.FONT_SMALL);
        mode.setForeground(new java.awt.Color(0xB8, 0xC2, 0xD6));
        mode.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        mode.setOpaque(true);
        mode.setBackground(new java.awt.Color(0xFF, 0xFF, 0xFF, 25));

        right.add(liveView);
        right.add(mode);

        bar.add(title, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildActionsCard() {
        return sectionCard("Actions", actionBar);
    }

    private JPanel buildCenter() {
        return sectionCard("Seat map", seatMapPanel);
    }

    private JPanel buildDetailCard() {
        JPanel card = sectionCard("Detail", detailPanel);
        card.setPreferredSize(new Dimension(260, 0));
        return card;
    }

    private JPanel buildEventLogCard() {
        JPanel card = sectionCard("Event log", eventLogPanel);
        card.setPreferredSize(new Dimension(0, 150));
        return card;
    }

    private JPanel sectionCard(String title, JComponent content) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 10), 12);
        card.setBackground(Theme.CARD_BG);
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel header = new JLabel(title);
        header.setFont(Theme.FONT_SECTION);
        header.setForeground(Theme.TEXT_PRIMARY);

        card.add(header, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private void updateDetailPanel(Set<String> selectedIds) {
        if (selectedIds.isEmpty()) {
            detailPanel.showPlaceholder();
        } else if (selectedIds.size() == 1) {
            String id = selectedIds.iterator().next();
            var pc = seatMapPanel.getPc(id);
            if (pc != null) {
                detailPanel.showPc(pc);
            } else {
                detailPanel.showPlaceholder();
            }
        } else {
            detailPanel.showMultiple(selectedIds.size());
        }
    }
}
