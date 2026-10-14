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

    public ConsoleFrame(LabService labService) {
        super("ClassControl - Instructor Console");
        this.labService = labService;
        this.actionBar = new ActionBar(labService, seatMapPanel::getSelectedIds);
        seatMapPanel.setSelectionListener(ids -> actionBar.updateEnabled());

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
        JPanel detail = new JPanel();
        detail.setBorder(BorderFactory.createTitledBorder("Detail"));
        detail.setPreferredSize(new Dimension(220, 0));
        detail.add(new JLabel("(select a PC)"));
        return detail;
    }

    private JPanel buildEventLog() {
        JPanel log = new JPanel();
        log.setBorder(BorderFactory.createTitledBorder("Event log"));
        log.setPreferredSize(new Dimension(0, 120));
        log.add(new JLabel("(events appear here)"));
        return log;
    }
}
