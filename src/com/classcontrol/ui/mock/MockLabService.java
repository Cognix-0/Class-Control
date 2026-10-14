package com.classcontrol.ui.mock;

import com.classcontrol.ui.api.Command;
import com.classcontrol.ui.api.LabListener;
import com.classcontrol.ui.api.LabService;
import com.classcontrol.ui.api.PcView;
import com.classcontrol.ui.api.Status;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MockLabService implements LabService {

    private static final int PC_COUNT = 32;
    private static final int COLS = 8;

    private final Map<String, PcView> pcs = new LinkedHashMap<>();
    private final List<LabListener> listeners = new ArrayList<>();
    private final Random random = new Random();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mock-lab-service");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> liveViewTask;

    public MockLabService() {
        seedPcs();
    }

    private void seedPcs() {
        for (int i = 1; i <= PC_COUNT; i++) {
            int row = (i - 1) / COLS + 1;
            int col = (i - 1) % COLS + 1;
            String id = String.format("LAB2-R%d-PC%02d", row, col);
            PcView pc = new PcView(
                    id,
                    id,
                    "10.0." + row + "." + (100 + col),
                    Status.ONLINE,
                    System.currentTimeMillis(),
                    generateThumbnail(id),
                    false
            );
            pcs.put(id, pc);
        }
    }

    private BufferedImage generateThumbnail(String label) {
        BufferedImage img = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float hue = random.nextFloat();
        g.setColor(Color.getHSBColor(hue, 0.35f, 0.85f));
        g.fillRect(0, 0, 160, 120);
        g.setColor(new Color(0, 0, 0, 60));
        for (int y = 0; y < 120; y += 8) {
            g.drawLine(0, y, 160, y);
        }
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.drawString(label, 10, 65);
        g.dispose();
        return img;
    }

    @Override
    public synchronized List<PcView> listPcs() {
        return new ArrayList<>(pcs.values());
    }

    @Override
    public synchronized void send(Collection<String> pcIds, Command cmd, String arg) {
        Collection<String> targets = (pcIds == null || pcIds.isEmpty()) ? pcs.keySet() : pcIds;
        for (String id : targets) {
            PcView pc = pcs.get(id);
            if (pc == null) {
                continue;
            }
            long delayMs = 200 + random.nextInt(600);
            scheduler.schedule(() -> applyCommand(id, cmd, arg), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void applyCommand(String id, Command cmd, String arg) {
        PcView pc = pcs.get(id);
        if (pc == null) {
            return;
        }
        boolean simulatedError = random.nextInt(20) == 0;
        if (simulatedError) {
            fireLog(id + ": " + cmd + " failed (no response)");
            return;
        }
        PcView updated = switch (cmd) {
            case LOCK -> withLocked(pc, true, Status.LOCKED);
            case UNLOCK -> withLocked(pc, false, Status.ONLINE);
            default -> pc;
        };
        pcs.put(id, updated);
        fireUpdated(updated);
        String argPart = arg == null || arg.isBlank() ? "" : " \"" + arg + "\"";
        fireLog(id + ": " + cmd + argPart + " ack");
    }

    private PcView withLocked(PcView pc, boolean locked, Status status) {
        return new PcView(pc.id(), pc.host(), pc.ip(), status, System.currentTimeMillis(), pc.thumbnail(), locked);
    }

    @Override
    public synchronized void setLiveView(boolean on, int intervalMs) {
        if (liveViewTask != null) {
            liveViewTask.cancel(false);
            liveViewTask = null;
        }
        if (on) {
            liveViewTask = scheduler.scheduleAtFixedRate(
                    this::flickerOnePc, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void flickerOnePc() {
        if (pcs.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(pcs.keySet());
        String id = ids.get(random.nextInt(ids.size()));
        PcView pc = pcs.get(id);
        if (pc.locked()) {
            return;
        }
        Status newStatus = switch (random.nextInt(10)) {
            case 0 -> Status.OFFLINE;
            case 1 -> Status.NOT_RESPONDING;
            default -> Status.ONLINE;
        };
        PcView updated = new PcView(pc.id(), pc.host(), pc.ip(), newStatus,
                System.currentTimeMillis(), pc.thumbnail(), pc.locked());
        pcs.put(id, updated);
        fireUpdated(updated);
    }

    @Override
    public synchronized void addListener(LabListener l) {
        listeners.add(l);
    }

    private void fireUpdated(PcView pc) {
        for (LabListener l : listeners) {
            l.pcUpdated(pc);
        }
    }

    private void fireLog(String message) {
        for (LabListener l : listeners) {
            l.logEvent(message);
        }
    }
}
