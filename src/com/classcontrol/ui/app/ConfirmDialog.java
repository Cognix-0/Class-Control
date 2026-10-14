package com.classcontrol.ui.app;

import com.classcontrol.ui.api.Command;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.Set;

public class ConfirmDialog {

    private static final Set<Command> DESTRUCTIVE = Set.of(Command.LOCK, Command.SCREENOFF, Command.BLOCK);

    private ConfirmDialog() {}

    public static boolean needsConfirmation(Command cmd, int selectionCount) {
        return DESTRUCTIVE.contains(cmd) && selectionCount > 1;
    }

    public static boolean confirm(Component parent, Command cmd, int selectionCount) {
        String message = "This will " + cmd.name() + " " + selectionCount + " PCs. Continue?";
        int result = JOptionPane.showConfirmDialog(
                parent, message, "Confirm action", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return result == JOptionPane.YES_OPTION;
    }
}
