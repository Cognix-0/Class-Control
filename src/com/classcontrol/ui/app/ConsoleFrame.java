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
        setMinimumSize(new Dimension(1140, 780));

        JPanel root = new JPanel(new BorderLayout());
        root.setPreferredSize(new Dimension(1240, 820));
        root.setBackground(Theme.PAGE_BG);
        root.add(buildAppBar(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(16, 16));
        body.setBackground(Theme.PAGE_BG);
        body.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
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
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(16, 20, 16, 20)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);

        JLabel logoDot = new JLabel("●");
        logoDot.setFont(Theme.FONT_TITLE);
        logoDot.setForeground(Theme.ACCENT);

        JLabel title = new JLabel("ClassControl");
        title.setFont(Theme.FONT_TITLE);
        title.setForeground(Theme.APP_BAR_FG);

        left.add(logoDot);
        left.add(title);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        right.setOpaque(false);

        JCheckBox liveView = new JCheckBox("Live view");
        liveView.setOpaque(false);
        liveView.setForeground(Theme.TEXT_PRIMARY);
        liveView.setFont(Theme.FONT_BASE);
        liveView.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        liveView.addActionListener(e -> labService.setLiveView(liveView.isSelected(), 2000));

        JLabel mode = new JLabel(labService.getClass().getSimpleName());
        mode.setFont(Theme.FONT_SMALL_BOLD);
        mode.setForeground(Theme.TEXT_SECONDARY);
        mode.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_STRONG, 1, true),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        mode.setOpaque(false);

        right.add(liveView);
        right.add(mode);

        bar.add(left, BorderLayout.WEST);
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
        card.setPreferredSize(new Dimension(280, 0));
        return card;
    }

    private JPanel buildEventLogCard() {
        JPanel card = sectionCard("Event log", eventLogPanel);
        card.setPreferredSize(new Dimension(0, 160));
        return card;
    }

    private JPanel sectionCard(String title, JComponent content) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 12), 14);
        card.setBackground(Theme.CARD_BG);
        card.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

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
