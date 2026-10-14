package com.classcontrol.ui.app;

import com.classcontrol.ui.api.LabService;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

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
        setMinimumSize(new Dimension(1024, 700));

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildTopBar(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildDetailPanel(), BorderLayout.EAST);
        root.add(buildEventLog(), BorderLayout.SOUTH);

        setContentPane(root);
        pack();
        setLocationRelativeTo(null);

        labService.addListener(seatMapPanel);
        labService.addListener(eventLogPanel);
        seatMapPanel.setPcs(labService.listPcs());
    }

    private JPanel buildTopBar() {
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBorder(BorderFactory.createTitledBorder("Status"));

        JCheckBox liveView = new JCheckBox("Live view");
        JLabel connection = new JLabel("Mode: " + labService.getClass().getSimpleName());

        liveView.addActionListener(e -> labService.setLiveView(liveView.isSelected(), 2000));

        topBar.add(liveView, BorderLayout.WEST);
        topBar.add(connection, BorderLayout.EAST);
        actionBar.setBorder(BorderFactory.createTitledBorder("Actions"));
        topBar.add(actionBar, BorderLayout.CENTER);
        return topBar;
    }

    private JPanel buildCenter() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createTitledBorder("Seat map"));
        seatMapPanel.setPreferredSize(new Dimension(600, 400));
        center.add(seatMapPanel, BorderLayout.CENTER);
        return center;
    }

    private JPanel buildDetailPanel() {
        JPanel detail = new JPanel(new BorderLayout());
        detail.setBorder(BorderFactory.createTitledBorder("Detail"));
        detail.setPreferredSize(new Dimension(220, 0));
        detail.add(detailPanel, BorderLayout.NORTH);
        return detail;
    }

    private void updateDetailPanel(java.util.Set<String> selectedIds) {
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

    private JPanel buildEventLog() {
        JPanel log = new JPanel(new BorderLayout());
        log.setBorder(BorderFactory.createTitledBorder("Event log"));
        log.setPreferredSize(new Dimension(0, 120));
        log.add(eventLogPanel, BorderLayout.CENTER);
        return log;
    }
}
