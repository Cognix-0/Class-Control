package com.classcontrol.ui.app;

import com.classcontrol.ui.api.LabListener;
import com.classcontrol.ui.api.PcView;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.text.SimpleDateFormat;
import java.util.Date;

public class EventLogPanel extends JPanel implements LabListener {

    private final JTextArea textArea = new JTextArea();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public EventLogPanel() {
        setOpaque(false);
        setLayout(new BorderLayout());
        textArea.setEditable(false);
        textArea.setRows(5);
        textArea.setFont(Theme.FONT_MONO);
        textArea.setForeground(Theme.TEXT_PRIMARY);
        textArea.setBackground(Theme.CARD_BG);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        add(scrollPane, BorderLayout.CENTER);
    }

    public void append(String message) {
        textArea.append("[" + timeFormat.format(new Date()) + "] " + message + "\n");
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    @Override
    public void pcAdded(PcView pc) {}

    @Override
    public void pcUpdated(PcView pc) {}

    @Override
    public void pcRemoved(String pcId) {}

    @Override
    public void logEvent(String message) {
        SwingUtilities.invokeLater(() -> append(message));
    }
}
