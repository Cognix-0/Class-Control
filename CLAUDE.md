# Class Control

Classroom control system for a computer lab (networking project). A lecturer controls what students see on lab PCs: send messages and links, lock screens, block websites, share the lecturer's screen. Quizzes and an installer are planned.

- Repo: https://github.com/Cognix-0/Class-Control.git
- Language: Java (17+) for the server and student agent (no external dependencies); Electron + React for the instructor UI. Java must be installed on the instructor PC.
- Target OS: Windows lab PCs (the teacher may also run on macOS during development)

## Files

- `desktop/`: instructor desktop app (Electron + React + three.js). The TCP server on port 5757 is Java (`desktop/backend/LabServer.java`), started by Electron as a child process; the React UI shows the lab as a 3D room (blocks of desks, like the agentrix seating page) or a 2D grid.
  - `backend/LabServer.java` the server: `SSLServerSocket` (TLS 1.3) accept loop with the handshake in each connection's own thread, a reader thread and a writer thread (bounded queue) per agent, handshake timeout, line cap, frame dropping on slow links. Controlled over stdin/stdout tab-separated lines (format in its header comment).
  - `electron/javaLab.js` bridge to it (same interface as `lab.js`); `electron/lab.js` is the older JavaScript server, used when `CC_BACKEND=node`.
  - `electron/demo.js` simulated PCs, `electron/layout.js` saved room layout, `src/` UI.
  - The lecturer's TLS keystore lives in `<userData>/instructor.p12` (`LabServer.Identity`, made by keytool); the start screen shows its fingerprint with a Copy button and asks for the master password on every start. `electron/pinnedTls.js` is the Node side of the same pinning, used by the co-instructor join, the demo and the tests.
  - Student passwords live in `<userData>/students.properties` (salted PBKDF2). Trust on first use: a reg no's first login sets its password; the instructor can reset it from the PC's info card.
- Student app (same `desktop/` package, second Electron entry): `electron/student.js` + `electron/studentPreload.js`, UI in `src/student/` (`student.html`). Student signs in with reg no + password, messages the instructor, and asks to share their screen with the class (instructor must allow). It runs `Agent.java --app` as a child process and drives it over stdin/stdout. Instructor IP (empty = auto-find), port, class password and instructor key come from `electron/studentDefaults.json` (set these before building for a lab) and can be changed in the app's Settings (`<appData>/Class Control Student/student-settings.json`); if `instructorKey` is left empty, the student app pins it on first successful sign-in (TOFU) and saves it to Settings.
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
npm run student    # student app (needs Java; uses ../Agent.java)
npm run dist:student   # student installer (release-student/, bundles Agent.java)
java Agent.java                                # on each student PC: finds the instructor by itself (UDP beacon); needs instructorKey in ~/.classcontrol-agent.properties
java Agent.java <teacherIP|auto> <token> [port] [instructorKey]   # explicit form (auto needs the key)
javac -d /tmp/a Agent.java AgentTest.java && java -ea -Djava.awt.headless=true -cp /tmp/a AgentTest
```

The agent remembers the accepted password and the pinned instructor key in `~/.classcontrol-agent.properties`. Discovery needs UDP broadcast on port 5758 (not available on networks that isolate clients: then pass the IP).
If the folder path contains `:`, npm can't find tools on PATH, so the scripts call them by file path.
Launching Electron from a VS Code extension shell needs `ELECTRON_RUN_AS_NODE` unset.

## Protocol

Line-based UTF-8 over **TLS 1.3** (nothing older is enabled, on either side). The lecturer's self-signed certificate is their identity: the agent trusts one certificate only, the one whose public key fingerprint matches the pinned instructor key (`Agent.Pinned`, an `X509TrustManager`, never a trust-all one). A different certificate, a missing chain, no pinned key or any error fails the handshake, so nothing - class password, login, chat, screen frames - is ever sent to an unverified PC; the agent then reports `This is not your instructor's PC` and ignores that IP for a minute. The fingerprint is SHA-256 of the certificate's SubjectPublicKeyInfo, 64 hex characters; a shorter key the lecturer read out is accepted as a prefix (at least 16 hex characters). After the handshake the agent sends `<token>\t<computerName>`; a wrong token gets the connection dropped. Teacher commands are `COMMAND\targ`: `MSG`, `URL` (http/https only), `LOCK`, `UNLOCK`, `SCREEN` (base64 JPEG, ~2 frames/s), `SCREENOFF`, `BLOCK` (comma-separated domains), `ALLOW` (only these domains reachable), `UNBLOCK`, `WB`, `THUMB` (agent replies `THUMB\t<320px JPEG>`).
Remote control: `VIEW\t<fps>` (agent streams `FRAME\t<1280px JPEG>`), `VIEWOFF`, `MOUSE\t<move|down|up|wheel>,<x 0..1>,<y 0..1>,<button|notches>`, `KEY\t<down|up>,<Java VK code>`, `TYPE\t<text>` (pasted).

Students (agent `--app`, sent after the handshake): `LOGIN\t<regNo>\t<password>` (server replies `LOGIN\tok` or `LOGIN\tfail\t<why>` and drops the connection), `LOGOUT`, `CHAT\t<text>` (max 1000 chars), `SHARE\treq|0` (ask to present / stop). The instructor answers with `PRESENT\t1|0|deny`; while approved the agent sends `PFRAME\t<1024px JPEG>` ~2/s and the server relays it to every other PC as `SCREEN` (only the approved presenter's frames are relayed; the instructor's own screen share ends a presentation and vice versa).
Agent `--app` stdio: stdin `CONNECT host|auto port token regNo password [instructorKey]`, `CHAT text`, `SHARE 1|0`, `LOGOUT`; stdout `STATE searching|connecting|loggedin|badlogin|rejected|offline|loggedout detail`, `MSG text`, `VIEWED 1|0`, `SHARING 1|0|deny`, `LOG text`.
LabServer extra stdin lines: `ROSTER file`, `PRESENT id 1|0|deny`, `FORGET regNo`; stdout `STUDENT id regNo`, `CHAT id text`, `SHAREREQ id 1|0`, `PRESENTER id`.
`START token port name share keyFile masterPw`: the key file is required (a class is never served in the clear) and the server replies `KEY\t<fingerprint>` after `STARTED`. `<userData>/instructor.p12` is a PKCS12 keystore holding a self-signed EC P-256 certificate (CN=Class Control Instructor, 10 years), created on the lecturer's first class by `keytool` from the running Java installation (`java.home/bin/keytool`, password fed on stdin, never as an argument; if keytool is missing the class fails rather than falling back to plaintext). The keystore password is the lecturer's master password and is never stored, so a wrong one fails to open it and the class does not start (`STARTFAIL Wrong master password`). The fingerprint is public, so the server also writes it to `<userData>/instructor-fingerprint.txt`, which is what the start screen shows before any class runs. Students pin it in `electron/studentDefaults.json` (`instructorKey`, editable in the student app's Settings; if left empty in the student app, the first successful sign-in pins and saves it automatically) or, for the CLI agent, in `~/.classcontrol-agent.properties` (`instructorKey=`) or as a 4th argument. Without a pinned key the CLI agent does not connect at all, auto-discovered or manual IP (`Ask your lecturer for the instructor key`). The demo gets its own throwaway `demo.p12` and its simulated PCs pin it too. `electron/lab.js` (`CC_BACKEND=node`) refuses to start: it has no certificate.

Discovery: the server broadcasts `CCLASS1\t<tcpPort>\t<name>[\t<token>]` over UDP to port 5758 every 2 s; agents listen, connect to the sender's address and send the password as usual. It stays plain UDP and says no more than "a class may be here": what makes the connection safe is the pinned certificate, checked during the TLS handshake. The token is included when the instructor leaves "Student apps on this network join on their own" ticked (start screen, default on): then students with an empty IP in Settings only type their reg no and password. The demo sends no beacon.
Whiteboard: `WB\tbeg,<rrggbb>,<width>,<x>,<y>` / `add,<x>,<y>,...` / `end` / `clear` / `off`; all numbers are fractions (0..1) of a 16:9 board. The server validates each line, keeps the strokes and replays them to PCs that join later.
State: the server remembers each PC's LOCK and web rules by host name and re-sends them when that PC reconnects; `endClass` (app closed) sends WB off, SCREENOFF, UNBLOCK and UNLOCK to everyone.

Co-instructors: one class server, many instructors. A second instructor app sees running classes (their UDP beacons) on its start screen and joins one over the same TCP port with `#INSTRUCTOR\t<name>`; the host gets an Allow / Decline prompt (`COJOIN` / `COACCEPT`, auto-declined after 2 minutes). Once allowed it receives `JOINED`, a snapshot of the class as events, then every live event, and may send the host's control lines except `FORGET` / `COACCEPT` / `START` / `STOP`. Students keep one connection, so two instructors never fight over a PC. Starting a second class while one is visible warns first, because each student PC joins only one class.

## Known limits

- Website blocking (BLOCK / ALLOW): every browser request goes through the agent's local proxy; Windows points the system proxy at it and firewall rules stop Chrome, Edge, Firefox, Brave, Opera, Vivaldi and IE from connecting directly. Not literally 100%: portable or renamed browsers, VPNs, another network (phone hotspot) and a student with admin rights can bypass it. Per-site rules apply to hostnames only (no URL paths over HTTPS).
- If the agent is killed while rules are active, browsers stay blocked until the agent starts again (it cleans up on start; the server re-applies the class rules on connect).
- Lock: Ctrl+Alt+Del cannot be blocked by any program (policies hide Task Manager, Lock, Sign out, Shut down while locked). The keyboard hook runs in a hidden PowerShell helper; endpoint protection may flag it. It also blocks injected Win/Alt+Tab shortcuts (touchpad gestures send those), so remote control can't send them to a locked PC. Touchpad 3/4-finger gestures and touchscreen edge swipes are turned off while locked (the student's touchpad settings are saved in `~/.classcontrol-touchpad.properties` and put back); Windows may not apply the touchpad change until its settings reload.
- The lock is released when the student logs out or the instructor disconnects (unplugging the network gets a student out until it reconnects); the server re-sends LOCK when the PC reconnects.
- The Windows-only parts (Kiosk hook, firewall rules, proxy settings) have not been run on a real Windows PC yet; only their Java logic is tested.
- The agent can be closed by a student; it needs a protected service for real lab use.
- With auto-join on, the class password is broadcast on the LAN in the clear (UDP discovery is not encrypted), so anyone on that network can learn it and connect as a PC; they still have to reach the instructor's own certificate, and student logins still need each student's own password.
- The instructor key is only as good as the fingerprint students were given: a student who pastes a classmate's key pins the wrong PC. Read the whole key out in class or pre-seed `studentDefaults.json`; a 16-character prefix is accepted but easier to fake.
- A lost master password cannot be recovered: delete `<userData>/instructor.p12`, start a class to make a new certificate, and every student PC has to be given the new fingerprint.
- The certificate is self-signed and pinned, so it is never checked against a CA or a host name, and nothing warns when it expires (10 years).
- Student passwords are trust-on-first-use: whoever first logs in with a reg no sets its password (no roster import yet).
- Quitting the student app stops its agent (same as closing the agent); a student who logs out is no longer listed by name.
- Screen share and remote view are JPEG snapshots, no audio.
- Remote control can't send Ctrl+Alt+Del.
- Co-instructors: each instructor's whiteboard canvas shows only their own strokes (one whiteboard user at a time); remote-view frames go to every instructor.

## Rules

- Keep it simple: plain Java, no new dependencies unless needed.
- Never add Claude as a co-author or contributor in git: no `Co-Authored-By` lines, no "Generated with Claude Code" text in commits or PRs.
