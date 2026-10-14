package com.classcontrol.ui.app;

import com.classcontrol.ui.api.Command;
import com.classcontrol.ui.api.LabService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Dark "control dock" card of grouped commands, echoing the dark control panel of the SchoolMaster reference. */
public class ActionBar extends RoundedPanel {

    private final LabService labService;
    private final Supplier<Set<String>> selectionSupplier;
    private final List<JButton> selectionRequiredButtons = new ArrayList<>();
    private final JLabel hint = new JLabel();

    public ActionBar(LabService labService, Supplier<Set<String>> selectionSupplier) {
        super(new BorderLayout(0, 14), 18);
        this.labService = labService;
        this.selectionSupplier = selectionSupplier;
        setBackground(Theme.DOCK_BG);
        setBorderColor(Theme.DOCK_BG);
        setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        JLabel title = new JLabel("Control dock");
        title.setFont(Theme.FONT_SECTION.deriveFont(15f));
        title.setForeground(Color.WHITE);
        hint.setFont(Theme.FONT_SMALL);
        hint.setForeground(Theme.DOCK_TEXT_MUTED);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(title);
        titles.add(Box.createVerticalStrut(3));
        titles.add(hint);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titles, BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        JPanel row = new JPanel(new WrapLayout(FlowLayout.LEFT, 18, 0));
        row.setOpaque(false);
        row.add(group("Communicate",
                btn("💬  Message", Command.MSG, true, Style.ACCENT),
                btn("🔗  Open URL", Command.URL, true, Style.NEUTRAL)));
        row.add(divider());
        row.add(group("Screen",
                btn("🔒  Lock", Command.LOCK, false, Style.DANGER),
                btn("🔓  Unlock", Command.UNLOCK, false, Style.NEUTRAL),
                btn("🖥  Screen on", Command.SCREEN, false, Style.NEUTRAL),
                btn("⛔  Screen off", Command.SCREENOFF, false, Style.DANGER)));
        row.add(divider());
        row.add(group("Access",
                btn("🚫  Block site", Command.BLOCK, true, Style.DANGER),
                btn("✅  Unblock site", Command.UNBLOCK, true, Style.NEUTRAL)));

        add(row, BorderLayout.CENTER);
        updateEnabled();
    }

    private enum Style { ACCENT, NEUTRAL, DANGER }

    private JButton btn(String label, Command cmd, boolean needsArg, Style style) {
        JButton button = switch (style) {
            case ACCENT -> new RoundedButton(label, Theme.ACCENT, Theme.ACCENT_HOVER, Color.WHITE);
            case DANGER -> new RoundedButton(label, Theme.DANGER, Theme.DANGER_HOVER, Color.WHITE);
            case NEUTRAL -> new RoundedButton(label, Theme.DOCK_BUTTON_BG, Theme.DOCK_BUTTON_HOVER, Theme.DOCK_TEXT);
        };
        button.addActionListener(e -> onAction(cmd, needsArg));
        selectionRequiredButtons.add(button);
        return button;
    }

    private JPanel group(String label, JButton... buttons) {
        JPanel g = new JPanel();
        g.setOpaque(false);
        g.setLayout(new BoxLayout(g, BoxLayout.Y_AXIS));

        JLabel l = new JLabel(label.toUpperCase());
        l.setFont(Theme.FONT_SMALL_BOLD);
        l.setForeground(Theme.DOCK_TEXT_MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        g.add(l);
        g.add(Box.createVerticalStrut(6));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JButton b : buttons) {
            row.add(b);
        }
        g.add(row);
        return g;
    }

    private JPanel divider() {
        JPanel d = new JPanel();
        d.setBackground(Theme.DOCK_DIVIDER);
        d.setPreferredSize(new Dimension(1, 50));
        return d;
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
        Set<String> selection = selectionSupplier.get();
        boolean hasSelection = !selection.isEmpty();
        for (JButton button : selectionRequiredButtons) {
            button.setEnabled(hasSelection);
        }
        hint.setText(hasSelection
                ? selection.size() + " PC" + (selection.size() == 1 ? "" : "s") + " selected"
                : "Select PCs in the seat map to enable actions");
    }
}
