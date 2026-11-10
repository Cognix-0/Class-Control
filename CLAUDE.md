# Class Control

Classroom control system for a computer lab (networking project). A lecturer controls what students see on lab PCs: send messages and links, lock screens, block websites, share the lecturer's screen. Quizzes and an installer are planned.

- Repo: https://github.com/Cognix-0/Class-Control.git
- Language: Java (17+) for the server and student agent (no external dependencies); Electron + React for the instructor UI. Java must be installed on the instructor PC.
- Target OS: Windows lab PCs (the teacher may also run on macOS during development)

## Files

- `desktop/`: instructor desktop app (Electron + React + three.js). The TCP server on port 5757 is Java (`desktop/backend/LabServer.java`), started by Electron as a child process; the React UI shows the lab as a 3D room (blocks of desks, like the agentrix seating page) or a 2D grid.
  - `backend/LabServer.java` the server: `ServerSocket` accept loop, a reader thread and a writer thread (bounded queue) per agent, handshake timeout, line cap, frame dropping on slow links. Controlled over stdin/stdout tab-separated lines (format in its header comment).
  - `electron/javaLab.js` bridge to it (same interface as `lab.js`); `electron/lab.js` is the older JavaScript server, used when `CC_BACKEND=node`.
  - `electron/demo.js` simulated PCs, `electron/layout.js` saved room layout, `src/` UI.
- `Agent.java`: student agent with a small status window (Java 17+, single file so `java Agent.java` works). Must run as Administrator on Windows. Reconnects every 3 s. Shows a red banner while the instructor views or controls the PC. Closing its window needs the class password. Nested classes: `WebFilter` (loopback HTTP/HTTPS proxy), `Enforce` (system proxy + firewall rules for known browsers), `Kiosk` (keyboard hook + policies while locked), `Board` (whiteboard window).
- `AgentTest.java`: checks for the agent's platform-independent parts (beacon parsing, filter rules, live proxy, whiteboard strokes).

## Run

```
cd desktop
npm install
npm start          # build UI + open the app ("Try demo" = 18 simulated PCs, no agents needed)
npm test           # protocol (JS + Java server) + seating tests
javac -d /tmp/out backend/*.java && java -ea -cp /tmp/out LabServerTest   # Java server protocol test
npm run dist       # installer for this OS (release/)
java Agent.java                                # on each student PC: finds the instructor by itself (UDP beacon), asks for the class password once
java Agent.java <teacherIP|auto> <token> [port]   # explicit form
javac -d /tmp/a Agent.java AgentTest.java && java -ea -Djava.awt.headless=true -cp /tmp/a AgentTest
```

The agent remembers the accepted password in `~/.classcontrol-agent.properties`. Discovery needs UDP broadcast on port 5758 (not available on networks that isolate clients: then pass the IP).
If the folder path contains `:`, npm can't find tools on PATH, so the scripts call them by file path.
Launching Electron from a VS Code extension shell needs `ELECTRON_RUN_AS_NODE` unset.

## Protocol

Line-based UTF-8 over TCP. The agent's first line is `<token>\t<computerName>`; a wrong token gets the connection dropped. Teacher commands are `COMMAND\targ`: `MSG`, `URL` (http/https only), `LOCK`, `UNLOCK`, `SCREEN` (base64 JPEG, ~2 frames/s), `SCREENOFF`, `BLOCK` (comma-separated domains), `ALLOW` (only these domains reachable), `UNBLOCK`, `WB`, `THUMB` (agent replies `THUMB\t<320px JPEG>`).
Remote control: `VIEW\t<fps>` (agent streams `FRAME\t<1280px JPEG>`), `VIEWOFF`, `MOUSE\t<move|down|up|wheel>,<x 0..1>,<y 0..1>,<button|notches>`, `KEY\t<down|up>,<Java VK code>`, `TYPE\t<text>` (pasted).

Discovery: the server broadcasts `CCLASS1\t<tcpPort>\t<name>` over UDP to port 5758 every 2 s; agents listen, connect to the sender's address and send the password as usual.
Whiteboard: `WB\tbeg,<rrggbb>,<width>,<x>,<y>` / `add,<x>,<y>,...` / `end` / `clear` / `off`; all numbers are fractions (0..1) of a 16:9 board. The server validates each line, keeps the strokes and replays them to PCs that join later.
State: the server remembers each PC's LOCK and web rules by host name and re-sends them when that PC reconnects; `endClass` (app closed) sends WB off, SCREENOFF, UNBLOCK and UNLOCK to everyone.

## Known limits

- Website blocking (BLOCK / ALLOW): every browser request goes through the agent's local proxy; Windows points the system proxy at it and firewall rules stop Chrome, Edge, Firefox, Brave, Opera, Vivaldi and IE from connecting directly. Not literally 100%: portable or renamed browsers, VPNs, another network (phone hotspot) and a student with admin rights can bypass it. Per-site rules apply to hostnames only (no URL paths over HTTPS).
- If the agent is killed while rules are active, browsers stay blocked until the agent starts again (it cleans up on start; the server re-applies the class rules on connect).
- Lock: Ctrl+Alt+Del cannot be blocked by any program (policies hide Task Manager, Lock, Sign out, Shut down while locked). The keyboard hook runs in a hidden PowerShell helper; endpoint protection may flag it.
- The Windows-only parts (Kiosk hook, firewall rules, proxy settings) have not been run on a real Windows PC yet; only their Java logic is tested.
- The agent can be closed by a student; it needs a protected service for real lab use.
- Token is sent in plain text (no TLS).
- Screen share and remote view are JPEG snapshots, no audio.
- Remote control can't send Ctrl+Alt+Del.

## Rules

- Keep it simple: plain Java, no new dependencies unless needed.
- Never add Claude as a co-author or contributor in git: no `Co-Authored-By` lines, no "Generated with Claude Code" text in commits or PRs.
