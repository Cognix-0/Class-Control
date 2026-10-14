package com.classcontrol.ui.app;

import com.classcontrol.ui.api.Command;
import com.classcontrol.ui.api.LabService;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class ActionBar extends JPanel {

    private final LabService labService;
    private final Supplier<Set<String>> selectionSupplier;
    private final List<JButton> selectionRequiredButtons = new ArrayList<>();

    public ActionBar(LabService labService, Supplier<Set<String>> selectionSupplier) {
        this.labService = labService;
        this.selectionSupplier = selectionSupplier;
        setOpaque(false);
        setLayout(new FlowLayout(FlowLayout.LEFT, 10, 6));

        addActionButton("💬  Message", Command.MSG, true, Style.ACCENT);
        addActionButton("🔗  Open URL", Command.URL, true, Style.NEUTRAL);
        addActionButton("🔒  Lock", Command.LOCK, false, Style.DANGER);
        addActionButton("🔓  Unlock", Command.UNLOCK, false, Style.NEUTRAL);
        addActionButton("🖥  Screen On", Command.SCREEN, false, Style.NEUTRAL);
        addActionButton("⛔  Screen Off", Command.SCREENOFF, false, Style.DANGER);
        addActionButton("🚫  Block site", Command.BLOCK, true, Style.DANGER);
        addActionButton("✅  Unblock site", Command.UNBLOCK, true, Style.NEUTRAL);

        updateEnabled();
    }

    private enum Style { ACCENT, NEUTRAL, DANGER }

    private void addActionButton(String label, Command cmd, boolean needsArg, Style style) {
        JButton button = switch (style) {
            case ACCENT -> new RoundedButton(label, Theme.ACCENT, Theme.ACCENT_HOVER, java.awt.Color.WHITE);
            case DANGER -> new RoundedButton(label, Theme.DANGER_TINT, Theme.DANGER, Theme.DANGER, Theme.DANGER);
            case NEUTRAL -> new RoundedButton(label, Theme.NEUTRAL_BUTTON, Theme.NEUTRAL_BUTTON_HOVER, Theme.TEXT_PRIMARY, Theme.BORDER_STRONG);
        };
        button.addActionListener(e -> onAction(cmd, needsArg));
        selectionRequiredButtons.add(button);
        add(button);
    }

    private void onAction(Command cmd, boolean needsArg) {
        Set<String> selection = selectionSupplier.get();
        if (selection.isEmpty()) {
            return;
        }
        if (ConfirmDialog.needsConfirmation(cmd, selection.size())
                && !ConfirmDialog.confirm(this, cmd, selection.size())) {
            return;
        }
        String arg = null;
        if (needsArg) {
            arg = JOptionPane.showInputDialog(this, cmd.name() + " - enter value:");
            if (arg == null) {
                return;
            }
        }
        labService.send(selection, cmd, arg);
    }

    public void updateEnabled() {
        boolean hasSelection = !selectionSupplier.get().isEmpty();
        for (JButton button : selectionRequiredButtons) {
            button.setEnabled(hasSelection);
        }
    }
}
