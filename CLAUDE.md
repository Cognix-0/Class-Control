# Class Control

Classroom control system for a computer lab (networking project). A lecturer controls what students see on lab PCs: send messages and links, lock screens, block websites, share the lecturer's screen. Quizzes and an installer are planned.

- Repo: https://github.com/Cognix-0/Class-Control.git
- Language: Java (17+), Swing GUI, no external dependencies
- Target OS: Windows lab PCs (the teacher may also run on macOS during development)

## Files

- `Teacher.java`: lecturer console. TCP server on port 5757 (optional 2nd arg overrides). Lists connected students, sends commands to selected students (none selected = all).
- `Agent.java`: student agent with a small status window. Connects out to the teacher, reconnects every 3 s, runs commands. Must run as Administrator on Windows (edits the hosts file).

## Run

```
java Teacher.java <token> [port]
java Agent.java <teacherIP> <token> [port]
```

The agent asks for the teacher IP and password in dialogs when started with no arguments.

## Protocol

Line-based UTF-8 over TCP. The agent's first line is `<token>\t<computerName>`; a wrong token gets the connection dropped. Teacher commands are `COMMAND\targ`: `MSG`, `URL` (http/https only), `LOCK`, `UNLOCK`, `SCREEN` (base64 JPEG, ~2 frames/s), `SCREENOFF`, `BLOCK` (comma-separated domains), `UNBLOCK`.

## Known limits

- Website blocking is a blocklist via the Windows hosts file, not an allowlist.
- Lock screen can be bypassed with Ctrl+Alt+Del / Task Manager.
- The agent can be closed by a student; it needs a protected service for real lab use.
- Token is sent in plain text (no TLS).
- Screen share is JPEG snapshots, no audio.

## Rules

- Keep it simple: plain Java, no new dependencies unless needed.
- Never add Claude as a co-author or contributor in git: no `Co-Authored-By` lines, no "Generated with Claude Code" text in commits or PRs.
