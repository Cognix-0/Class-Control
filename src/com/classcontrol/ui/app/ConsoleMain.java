package com.classcontrol.ui.app;

import com.classcontrol.ui.api.LabService;
import com.classcontrol.ui.mock.MockLabService;
import com.classcontrol.ui.real.RealLabService;

import javax.swing.SwingUtilities;
import java.util.Arrays;

public class ConsoleMain {

    public static void main(String[] args) {
        boolean mock = Arrays.asList(args).contains("--mock");
        LabService labService = mock ? new MockLabService() : new RealLabService();

        SwingUtilities.invokeLater(() -> {
            ConsoleFrame frame = new ConsoleFrame(labService);
            frame.setVisible(true);
        });
    }
}
