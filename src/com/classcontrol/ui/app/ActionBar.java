package com.classcontrol.ui.app;

import com.classcontrol.ui.api.Command;
import com.classcontrol.ui.api.LabService;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class ActionBar extends JPanel {

    private final LabService labService;
    private final Supplier<Set<String>> selectionSupplier;
    private final List<JButton> selectionRequiredButtons = new java.util.ArrayList<>();

    public ActionBar(LabService labService, Supplier<Set<String>> selectionSupplier) {
        this.labService = labService;
        this.selectionSupplier = selectionSupplier;

        addActionButton("Message", Command.MSG, true);
        addActionButton("Open URL", Command.URL, true);
        addActionButton("Lock", Command.LOCK, false);
        addActionButton("Unlock", Command.UNLOCK, false);
        addActionButton("Screen On", Command.SCREEN, false);
        addActionButton("Screen Off", Command.SCREENOFF, false);
        addActionButton("Block site", Command.BLOCK, true);
        addActionButton("Unblock site", Command.UNBLOCK, true);

        updateEnabled();
    }

    private void addActionButton(String label, Command cmd, boolean needsArg) {
        JButton button = new JButton(label);
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
