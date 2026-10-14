package com.classcontrol.ui.app;

import com.classcontrol.ui.api.LabListener;
import com.classcontrol.ui.api.LabService;
import com.classcontrol.ui.api.PcView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class ConsoleFrame extends JFrame {

    private final LabService labService;
    private final SeatMapPanel seatMapPanel = new SeatMapPanel();
    private final ActionBar actionBar;
    private final DetailPanel detailPanel = new DetailPanel();
    private final EventLogPanel eventLogPanel = new EventLogPanel();
    private final JLabel countBadge = new JLabel();
    private final AtomicInteger pcCount = new AtomicInteger();
    private RoundedPanel eventLogCard;

    public ConsoleFrame(LabService labService) {
        super("ClassControl - Instructor Console");
        this.labService = labService;
        this.actionBar = new ActionBar(labService, seatMapPanel::getSelectedIds);
        seatMapPanel.setSelectionListener(ids -> {
            actionBar.updateEnabled();
            updateDetailPanel(ids);
        });

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 800));

        JPanel root = new JPanel(new BorderLayout());
        root.setPreferredSize(new Dimension(1320, 840));
        root.setBackground(Theme.PAGE_BG);

        Sidebar sidebar = new Sidebar(
                live -> labService.setLiveView(live, 2000),
                this::setEventLogVisible,
                this::showModeInfo,
                this::confirmExit);
        root.add(sidebar, BorderLayout.WEST);

        JPanel shell = new JPanel(new BorderLayout());
        shell.setBackground(Theme.PAGE_BG);
        shell.add(buildAppBar(), BorderLayout.NORTH);
        shell.add(buildBody(), BorderLayout.CENTER);
        root.add(shell, BorderLayout.CENTER);

        setContentPane(root);
        pack();
        setLocationRelativeTo(null);

        labService.addListener(seatMapPanel);
        labService.addListener(eventLogPanel);
        labService.addListener(countListener());
        seatMapPanel.setPcs(labService.listPcs());
        pcCount.set(labService.listPcs().size());
        updateCountBadge();
    }

    private LabListener countListener() {
        return new LabListener() {
            @Override
            public void pcAdded(PcView pc) {
                pcCount.incrementAndGet();
                SwingUtilities.invokeLater(ConsoleFrame.this::updateCountBadge);
            }

            @Override
            public void pcUpdated(PcView pc) {}

            @Override
            public void pcRemoved(String pcId) {
                pcCount.decrementAndGet();
                SwingUtilities.invokeLater(ConsoleFrame.this::updateCountBadge);
            }

            @Override
            public void logEvent(String message) {}
        };
    }

    private void updateCountBadge() {
        int n = pcCount.get();
        countBadge.setText(n + " PC" + (n == 1 ? "" : "s") + " online");
    }

    private JPanel buildAppBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.APP_BAR_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(16, 22, 16, 22)));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("ClassControl");
        title.setFont(Theme.FONT_TITLE);
        title.setForeground(Theme.APP_BAR_FG);
        JLabel subtitle = new JLabel("Instructor Console");
        subtitle.setFont(Theme.FONT_SMALL);
        subtitle.setForeground(Theme.TEXT_SECONDARY);
        titles.add(title);
        titles.add(subtitle);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        countBadge.setFont(Theme.FONT_SMALL_BOLD);
        countBadge.setForeground(Theme.ACCENT);
        countBadge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.ACCENT_TINT, 1, true),
                BorderFactory.createEmptyBorder(5, 12, 5, 12)));

        JLabel mode = new JLabel(labService.getClass().getSimpleName());
        mode.setFont(Theme.FONT_SMALL_BOLD);
        mode.setForeground(Theme.TEXT_SECONDARY);
        mode.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_STRONG, 1, true),
                BorderFactory.createEmptyBorder(5, 12, 5, 12)));

        right.add(countBadge);
        right.add(mode);

        bar.add(titles, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setBackground(Theme.PAGE_BG);
        body.setBorder(BorderFactory.createEmptyBorder(16, 18, 18, 16));
        body.add(buildMainColumn(), BorderLayout.CENTER);
        body.add(buildSideColumn(), BorderLayout.EAST);
        return body;
    }

    private JPanel buildMainColumn() {
        JPanel col = new JPanel(new BorderLayout(0, 14));
        col.setOpaque(false);
        col.add(buildSeatMapSection(), BorderLayout.CENTER);
        col.add(actionBar, BorderLayout.SOUTH);
        return col;
    }

    private JPanel buildSeatMapSection() {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 14), 18);
        card.setBackground(Theme.CANVAS_BG);
        card.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));

        JLabel title = new JLabel("Seat map");
        title.setFont(Theme.FONT_SECTION.deriveFont(15f));
        title.setForeground(Theme.TEXT_PRIMARY);
        JLabel subtitle = new JLabel("Click a PC to select it, Ctrl/Shift-click for multiple");
        subtitle.setFont(Theme.FONT_SMALL);
        subtitle.setForeground(Theme.TEXT_SECONDARY);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(title);
        titles.add(Box.createVerticalStrut(2));
        titles.add(subtitle);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titles, BorderLayout.WEST);

        card.add(header, BorderLayout.NORTH);
        card.add(seatMapPanel, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildSideColumn() {
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setPreferredSize(new Dimension(300, 0));

        JPanel detailCard = sectionCard("Detail", detailPanel);
        detailCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailCard.setMaximumSize(new Dimension(300, 330));

        eventLogCard = sectionCard("Event log", eventLogPanel);
        eventLogCard.setAlignmentX(Component.LEFT_ALIGNMENT);

        col.add(detailCard);
        col.add(Box.createVerticalStrut(14));
        col.add(eventLogCard);
        return col;
    }

    private RoundedPanel sectionCard(String title, JComponent content) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 12), 14);
        card.setBackground(Theme.CARD_BG);
        card.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        JLabel header = new JLabel(title);
        header.setFont(Theme.FONT_SECTION);
        header.setForeground(Theme.TEXT_PRIMARY);

        card.add(header, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private void setEventLogVisible(boolean visible) {
        eventLogCard.setVisible(visible);
        eventLogCard.revalidate();
        eventLogCard.getParent().revalidate();
        eventLogCard.getParent().repaint();
    }

    private void showModeInfo() {
        JOptionPane.showMessageDialog(this,
                "Data source: " + labService.getClass().getSimpleName() + "\nPCs tracked: " + pcCount.get(),
                "Console info", JOptionPane.INFORMATION_MESSAGE);
    }

    private void confirmExit() {
        int result = JOptionPane.showConfirmDialog(this, "Close the instructor console?", "Exit",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            dispose();
            System.exit(0);
        }
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
