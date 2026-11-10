package com.classcontrol.ui.real;

import com.classcontrol.ui.api.Command;
import com.classcontrol.ui.api.LabListener;
import com.classcontrol.ui.api.LabService;
import com.classcontrol.ui.api.PcView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stub adapter over Teacher.java's TCP server.
 * TODO: wrap Teacher's connected-student list once it exposes one (currently
 * it is a private field inside Teacher.java's own Swing window).
 * TODO: wrap a way to push a "COMMAND\targ" line to one or more selected
 * student sockets (currently Teacher.java only has an internal "send to
 * selected rows" button handler, not a public method).
 */
public class RealLabService implements LabService {

    private final List<LabListener> listeners = new ArrayList<>();

    @Override
    public List<PcView> listPcs() {
        return List.of();
    }

    @Override
    public void send(Collection<String> pcIds, Command cmd, String arg) {
        // TODO: send "cmd\targ" to each selected student's socket via Teacher.java.
    }

    @Override
    public void setLiveView(boolean on, int intervalMs) {
        // TODO: toggle periodic SCREEN / SCREENOFF commands once wired to Teacher.java.
    }

    @Override
    public void addListener(LabListener l) {
        listeners.add(l);
    }
}
