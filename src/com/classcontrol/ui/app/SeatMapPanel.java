package com.classcontrol.ui.app;

import com.classcontrol.ui.api.LabListener;
import com.classcontrol.ui.api.PcView;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class SeatMapPanel extends JPanel implements LabListener {

    private static final String CARD_GRID = "grid";
    private static final String CARD_EMPTY = "empty";

    private final Map<String, PcTile> tiles = new LinkedHashMap<>();
    private final Map<String, PcView> latestPcs = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final JPanel grid = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);

    private Consumer<Set<String>> selectionListener = ids -> {};

    public SeatMapPanel() {
        setLayout(new BorderLayout());
        setBackground(Theme.PAGE_BG);

        grid.setBackground(Theme.PAGE_BG);
        JScrollPane scrollPane = new JScrollPane(grid);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(Theme.PAGE_BG);
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);

        JLabel emptyState = new JLabel("No PCs connected", SwingConstants.CENTER);
        emptyState.setFont(Theme.FONT_BASE);
        emptyState.setForeground(Theme.TEXT_SECONDARY);
        JPanel emptyPanel = new JPanel(new BorderLayout());
        emptyPanel.setBackground(Theme.PAGE_BG);
        emptyPanel.add(emptyState, BorderLayout.CENTER);

        cards.add(scrollPane, CARD_GRID);
        cards.add(emptyPanel, CARD_EMPTY);
        add(cards, BorderLayout.CENTER);
        updateEmptyState();
    }

    public void setSelectionListener(Consumer<Set<String>> listener) {
        this.selectionListener = listener;
    }

    public void setPcs(Iterable<PcView> pcs) {
        for (PcView pc : pcs) {
            addOrUpdateTile(pc);
        }
        updateEmptyState();
    }

    public Set<String> getSelectedIds() {
        return new LinkedHashSet<>(selected);
    }

    public PcView getPc(String pcId) {
        return latestPcs.get(pcId);
    }

    public void clearSelection() {
        for (String id : selected) {
            PcTile tile = tiles.get(id);
            if (tile != null) {
                tile.setSelected(false);
            }
        }
        selected.clear();
        selectionListener.accept(getSelectedIds());
    }

    public void selectAll() {
        selected.clear();
        selected.addAll(tiles.keySet());
        for (PcTile tile : tiles.values()) {
            tile.setSelected(true);
        }
        selectionListener.accept(getSelectedIds());
    }

    private void addOrUpdateTile(PcView pc) {
        latestPcs.put(pc.id(), pc);
        PcTile tile = tiles.get(pc.id());
        if (tile == null) {
            tile = new PcTile(pc, this::onTileClicked);
            tiles.put(pc.id(), tile);
            grid.add(tile);
        } else {
            tile.update(pc);
        }
    }

    private void onTileClicked(String pcId, MouseEvent e) {
        boolean multiSelect = e.isControlDown() || e.isShiftDown();
        if (!multiSelect) {
            clearSelectionSilently();
        }
        if (selected.contains(pcId) && multiSelect) {
            selected.remove(pcId);
            tiles.get(pcId).setSelected(false);
        } else {
            selected.add(pcId);
            tiles.get(pcId).setSelected(true);
        }
        selectionListener.accept(getSelectedIds());
    }

    private void clearSelectionSilently() {
        for (String id : selected) {
            PcTile tile = tiles.get(id);
            if (tile != null) {
                tile.setSelected(false);
            }
        }
        selected.clear();
    }

    private void updateEmptyState() {
        cardLayout.show(cards, tiles.isEmpty() ? CARD_EMPTY : CARD_GRID);
    }

    @Override
    public void pcAdded(PcView pc) {
        SwingUtilities.invokeLater(() -> {
            addOrUpdateTile(pc);
            updateEmptyState();
            revalidate();
            repaint();
        });
    }

    @Override
    public void pcUpdated(PcView pc) {
        SwingUtilities.invokeLater(() -> {
            addOrUpdateTile(pc);
            repaint();
        });
    }

    @Override
    public void pcRemoved(String pcId) {
        SwingUtilities.invokeLater(() -> {
            PcTile tile = tiles.remove(pcId);
            if (tile != null) {
                grid.remove(tile);
                selected.remove(pcId);
                updateEmptyState();
                revalidate();
                repaint();
            }
        });
    }

    @Override
    public void logEvent(String message) {
        // SeatMapPanel does not display the log; EventLogPanel handles this.
    }
}
