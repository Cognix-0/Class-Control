# Instructor Console UI — Plan

## Package layout (new, additive — nothing under the existing flat
`Teacher.java` / `Agent.java` is touched)

```
src/com/classcontrol/ui/
  api/
    LabService.java      interface
    PcView.java           record
    Status.java            enum
    Command.java            enum
    LabListener.java      interface
  mock/
    MockLabService.java
  real/
    RealLabService.java   stub, TODOs, adapts Teacher.java's socket list
  app/
    ConsoleMain.java       entry point, parses --mock
    ConsoleFrame.java       main window, 6 layout areas
    SeatMapPanel.java       PC grid / seat map
    PcTile.java              single PC tile (thumbnail, status, selection)
    DetailPanel.java         selected-PC detail view
    ActionBar.java           command buttons
    ConfirmDialog.java       destructive-action confirmation
    EventLogPanel.java       scrolling log of events/acks
```

`src/` stays the compile root so `java src/com/classcontrol/ui/app/ConsoleMain.java`
(or a `build.sh` that javac's the `ui` tree) works standalone, independent of
whether `Teacher.java`/`Agent.java` compile.

## LabService contract (adjusted from spec)

```java
package com.classcontrol.ui.api;

public interface LabService {
    List<PcView> listPcs();
    void send(Collection<String> pcIds, Command cmd, String arg); // arg nullable
    void setLiveView(boolean on, int intervalMs);
    void addListener(LabListener l);
}

public record PcView(String id, String host, String ip, Status status,
                      long lastSeenMs, BufferedImage thumbnail, boolean locked) {}

public enum Status { ONLINE, OFFLINE, LOCKED, NOT_RESPONDING }

// Matches Teacher.java's real COMMAND\targ protocol, not the spec's imagined one.
public enum Command { MSG, URL, LOCK, UNLOCK, SCREEN, SCREENOFF, BLOCK, UNBLOCK }

public interface LabListener {
    void pcAdded(PcView pc);
    void pcUpdated(PcView pc);
    void pcRemoved(String pcId);
    void logEvent(String message);
}
```

Changes from the original spec, and why:

- Dropped `addManual(ip, port)` — the real architecture has agents dialing
  into the teacher; the controller never dials out to a PC, so there's
  nothing for this method to do against the real backend.
- Dropped `user` field from `PcView` — the protocol's handshake only sends
  `<token>\t<computerName>`, no logged-in username.
- `Command` enum values match the real protocol (`MSG`, `URL`, `LOCK`,
  `UNLOCK`, `SCREEN`, `SCREENOFF`, `BLOCK`, `UNBLOCK`) instead of the spec's
  invented set (`MESSAGE`, `SNAPSHOT`, `SHUTDOWN`, `RESTART`, `LOGOFF`,
  `PROCESSES`), none of which exist on the wire.
- Dropped `processList` from `LabListener` — there's no process-list feature
  in the protocol.
- `send()` takes an explicit nullable `arg` string instead of bundling it
  into `Command`, since `URL`/`MSG`/`BLOCK`/`UNBLOCK` all need a string
  payload and `LOCK`/`UNLOCK`/`SCREEN`/`SCREENOFF` don't.

## 6 layout areas (ConsoleFrame)

1. Top bar: live-view toggle, connection/server status, instructor identity.
2. Seat map (center): grid of `PcTile`s, click to select/multi-select.
3. Action bar: buttons for MSG / URL / LOCK / UNLOCK / SCREEN / BLOCK /
   UNBLOCK, disabled appropriately when nothing is selected.
4. Detail panel (right): selected PC's thumbnail, status, last-seen, host.
5. Event log (bottom): scrolling feed of sent commands and ACKs/errors.
6. Empty state: shown in the seat map area when `listPcs()` is empty.

## Order of work (small commits)

1. `ui: add LabService contract and PcView model`
2. `ui: add MockLabService with 32 fake PCs`
3. `ui: add ConsoleMain entry point and --mock flag`
4. `ui: add ConsoleFrame shell with 6 layout areas (empty state only)`
5. `ui: add SeatMapPanel and PcTile with selection`
6. `ui: add ActionBar and wire send() to selected PCs`
7. `ui: add ConfirmDialog for destructive multi/all-select actions`
8. `ui: add DetailPanel for single-selection view`
9. `ui: add EventLogPanel wired to LabListener.logEvent`
10. `ui: add RealLabService stub with TODOs`

Each step is checked by: it compiles, `--mock` launches without exceptions,
and the specific area added is visibly present/functional before moving to
the next commit.

## Verification

- Build: a `build.sh` that javac's everything under `src/com/classcontrol/ui/`
  into `out/`, then `java -cp out com.classcontrol.ui.app.ConsoleMain --mock`.
- Manual check after each commit: launch `--mock`, confirm no exceptions,
  confirm the new layout area renders.
- Final pass: screenshot main screen, a multi-selection, the destructive
  confirm dialog, the detail panel, and the empty state (0 PCs). Run
  design-critique and accessibility-review skills against those screenshots.

## Self-assessment

**1. What could this break?**
Nothing in the existing backend — this is an additive new package tree.
The only shared surface is the `src/` compile root, so a bad classpath setup
could make the plain `javac Teacher.java` style build pick up the new files
by accident; the plan avoids that by keeping `ui/` self-contained with its
own `build.sh` rather than changing how `Teacher.java`/`Agent.java` are built.

**2. Alternatives rejected, and why**
- *JavaFX instead of Swing*: rejected — `Teacher.java`/`Agent.java` are
  already Swing, and introducing a second GUI toolkit for just the console
  adds a dependency and a second mental model for no clear payoff at this
  scale (30-40 tiles, simple controls).
- *FlatLaf*: rejected for now, even though the spec allows it, because the
  project's own `CLAUDE.md` says to avoid new dependencies unless needed.
  Plain Swing can be restyled later if the default look proves to be a real
  usability problem in the verify phase.
- *Matching the spec's literal `AgentRegistry`-based `RealLabService`*:
  rejected — that class doesn't exist. `RealLabService` instead wraps
  whatever `Teacher.java` currently exposes (its connected-sockets list),
  with TODOs where `Teacher.java` would need small additions (e.g. exposing
  its student list and a hook to send a line to a given socket) to make the
  adapter real instead of stubbed.

**3. Least sure about**
- Whether `Teacher.java` as it stands exposes enough (a gettable list of
  connected students, a way to push a command line to one) for
  `RealLabService` to be more than a stub without backend changes — likely
  it will need at least a getter added to `Teacher.java`, which is explicitly
  out of scope ("do not touch backend") until you say otherwise.
- Exact seat-map layout algorithm (fixed grid vs. flow layout) for 32-40
  tiles at varying window sizes — will prototype in commit 5 and adjust if it
  looks bad at a projector's typical aspect ratio.
