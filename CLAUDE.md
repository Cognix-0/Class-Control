# Class Control

Classroom control system for a computer lab (networking project). A lecturer controls what students see on lab PCs: send messages and links, lock screens, block websites, share the lecturer's screen. Quizzes and an installer are planned.

- Repo: https://github.com/Cognix-0/Class-Control.git
- Language: Java (17+), Swing GUI, no external dependencies
- Target OS: Windows lab PCs (the teacher may also run on macOS during development)

## Files

- `desktop/`: instructor desktop app (Electron + React + three.js). The Electron main process is the TCP server on port 5757; the React UI shows the lab as a 3D room (blocks of desks, like the agentrix seating page) or a 2D grid.
  - `electron/lab.js` server + protocol, `electron/demo.js` simulated PCs, `electron/layout.js` saved room layout, `src/` UI.
- `Agent.java`: student agent with a small status window. Connects out to the teacher, reconnects every 3 s, runs commands. Must run as Administrator on Windows (edits the hosts file). Shows a red banner while the instructor views or controls the PC.

## Run

```
cd desktop
npm install
npm start          # build UI + open the app ("Try demo" = 18 simulated PCs, no agents needed)
npm test           # protocol + seating tests
npm run dist       # installer for this OS (release/)
java Agent.java <teacherIP> <token> [port]   # on each student PC
```

The agent asks for the teacher IP and password in dialogs when started with no arguments.
If the folder path contains `:`, npm can't find tools on PATH, so the scripts call them by file path.
Launching Electron from a VS Code extension shell needs `ELECTRON_RUN_AS_NODE` unset.

## Protocol

Line-based UTF-8 over TCP. The agent's first line is `<token>\t<computerName>`; a wrong token gets the connection dropped. Teacher commands are `COMMAND\targ`: `MSG`, `URL` (http/https only), `LOCK`, `UNLOCK`, `SCREEN` (base64 JPEG, ~2 frames/s), `SCREENOFF`, `BLOCK` (comma-separated domains), `UNBLOCK`, `THUMB` (agent replies `THUMB\t<320px JPEG>`).
Remote control: `VIEW\t<fps>` (agent streams `FRAME\t<1280px JPEG>`), `VIEWOFF`, `MOUSE\t<move|down|up|wheel>,<x 0..1>,<y 0..1>,<button|notches>`, `KEY\t<down|up>,<Java VK code>`, `TYPE\t<text>` (pasted).

## Known limits

- Website blocking is a blocklist via the Windows hosts file, not an allowlist.
- Lock screen can be bypassed with Ctrl+Alt+Del / Task Manager.
- The agent can be closed by a student; it needs a protected service for real lab use.
- Token is sent in plain text (no TLS).
- Screen share and remote view are JPEG snapshots, no audio.
- Remote control can't send Ctrl+Alt+Del.

## Rules

- Keep it simple: plain Java, no new dependencies unless needed.
- Never add Claude as a co-author or contributor in git: no `Co-Authored-By` lines, no "Generated with Claude Code" text in commits or PRs.
