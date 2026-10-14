# Instructor Console UI — Intent

## What we're building

A standalone instructor-facing console UI for ClassControl: a screen a lab
instructor glances at from the front of a room with 30-40 PCs, lets them see
which machines are online, select some or all of them, and fire off an action
(message, lock, block a site, etc.) with low risk of hitting the wrong machine.

The UI is built and demoed against a **mock** data source first
(`--mock` launch mode, 32 fake PCs). It does not depend on the real backend
(`Teacher.java`) to exist or compile correctly while the UI is being built.

## Why

- The real backend (`Teacher.java` / `Agent.java`) is a working but minimal
  TCP server + Swing status window. There is no instructor console yet beyond
  whatever ad-hoc UI exists inside `Teacher.java`.
- Decoupling the UI from the backend via a small interface (`LabService`)
  means UI work can proceed in parallel with backend changes, and the backend
  can evolve without breaking the UI as long as the interface is honored.

## Adjustment from the original spec

The original spec assumed a package layout (`com.classcontrol.agent`,
`com.classcontrol.common`, `com.classcontrol.controller`, `AgentRegistry`,
`DiscoveryListener`, UDP multicast discovery) that does not exist in this
repo. The actual backend is:

- `Teacher.java`: a TCP **server** on port 5757. Agents connect *in*. Students
  list is whatever `Teacher.java` currently tracks as connected sockets.
- `Agent.java`: a TCP **client** that dials out to the teacher's IP and
  reconnects every 3s.
- Protocol: line-based UTF-8, `COMMAND\targ`, commands are
  `MSG`, `URL`, `LOCK`, `UNLOCK`, `SCREEN`, `SCREENOFF`, `BLOCK`, `UNBLOCK`.
  There is no `SHUTDOWN`, `RESTART`, `LOGOFF`, or `PROCESSES` command, and no
  UDP discovery — discovery is "agent connects to teacher," not "controller
  finds PCs on the network."

The `LabService` contract and `Command` enum are adjusted to match the real
protocol (see `docs/ui-plan.md`). `addManual(ip, port)` is dropped from the
contract because in the real architecture the controller never dials a PC —
PCs dial in. It's replaced with nothing; `MockLabService` just seeds its own
fake roster.

## Who uses it

A lab instructor, mid-lecture, glancing at the console between teaching.
Must be readable from a distance, fast to act on, and hard to misfire against
the wrong machines (destructive actions need a confirm step when more than
one PC or "all" is targeted).

## Constraints carried over from the spec

- Swing only. Plain Swing, no FlatLaf — the project's own `CLAUDE.md` says
  "no new dependencies unless needed," so we default to plain Swing and only
  reach for FlatLaf later if the plain look is genuinely a usability problem.
- UI code imports only `com.classcontrol.ui.api`, never the backend files
  directly.
- `MockLabService`: fake PCs, generated thumbnails, random online/offline
  flicker, delayed ACKs, occasional simulated errors.
- A `RealLabService` stub exists with TODOs, wired to the *actual* protocol
  shape, but is not required to fully work yet.
