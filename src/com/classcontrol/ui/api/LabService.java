package com.classcontrol.ui.api;

import java.util.Collection;
import java.util.List;

public interface LabService {
    List<PcView> listPcs();
    void send(Collection<String> pcIds, Command cmd, String arg);
    void setLiveView(boolean on, int intervalMs);
    void addListener(LabListener l);
}
