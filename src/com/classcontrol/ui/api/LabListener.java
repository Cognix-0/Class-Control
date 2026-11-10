package com.classcontrol.ui.api;

public interface LabListener {
    void pcAdded(PcView pc);
    void pcUpdated(PcView pc);
    void pcRemoved(String pcId);
    void logEvent(String message);
}
