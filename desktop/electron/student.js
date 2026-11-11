// Student app: login + messages + "share my screen with the class" UI on top of Agent.java (run with --app).
// The agent does the classroom work (lock, web filter, whiteboard, remote view/control); we drive it over stdin/stdout.
const { app, BrowserWindow, ipcMain, Notification, dialog } = require('electron');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const readline = require('node:readline');
const DEFAULTS = require('./studentDefaults.json'); // the lab admin sets the instructor's address and class password here before building

// Own data folder and single-instance lock, so it can run next to the instructor app on the same machine.
app.setPath('userData', path.join(app.getPath('appData'), 'Class Control Student'));
if (!app.requestSingleInstanceLock()) app.exit(0);

const AGENT = app.isPackaged ? path.join(process.resourcesPath, 'Agent.java') : path.join(__dirname, '..', '..', 'Agent.java');
const SETTINGS = () => path.join(app.getPath('userData'), 'student-settings.json');
const REG_NO = /^[A-Za-z0-9/._-]{1,32}$/;            // same rule as LabServer.REG_NO
const oneLine = (s) => String(s ?? '').replace(/[\t\r\n]+/g, ' ');

let win;
let agent = null;
let quitting = false;
let login = null;       // { regNo, password } while signed in, so new settings can reconnect at once
let loggedIn = false;

function loadSettings() {
  let saved = {};
  try { saved = JSON.parse(fs.readFileSync(SETTINGS(), 'utf8')); } catch { /* first run: defaults only */ }
  return { ...DEFAULTS, ...saved };
}

function saveSettings(s) {
  const port = Number(s.port);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Port must be a number from 1 to 65535');
  if (/[\s]/.test(String(s.host ?? ''))) throw new Error('The instructor address cannot contain spaces');
  if (/[\t\r\n]/.test(String(s.token ?? ''))) throw new Error('The class password cannot contain tabs or line breaks');
  const next = { host: String(s.host ?? '').trim(), port, token: String(s.token ?? ''), regNo: String(s.regNo ?? loadSettings().regNo ?? '') };
  const tmp = SETTINGS() + '.tmp';
  fs.mkdirSync(path.dirname(tmp), { recursive: true });
  fs.writeFileSync(tmp, JSON.stringify(next, null, 2));
  fs.renameSync(tmp, SETTINGS());
  return next;
}

const send = (e) => { if (win && !win.isDestroyed()) win.webContents.send('student:event', e); };

function startAgent() {
  agent = spawn('java', ['-Dapple.awt.UIElement=true', AGENT, '--app'], { stdio: ['pipe', 'pipe', 'pipe'] });
  const me = agent;
  me.stdin.on('error', () => {});
  me.stderr.on('data', (d) => console.error(String(d).trim()));
  me.once('error', (e) => send({ type: 'state', state: 'fatal', detail: e.code === 'ENOENT' ? 'Java was not found. Install Java 17 or newer, then restart the app.' : e.message }));
  me.once('exit', () => {
    if (quitting || agent !== me) return;
    agent = null;
    loggedIn = false;
    send({ type: 'state', state: 'offline', detail: 'The class agent stopped. Restarting it…' });
    setTimeout(() => { startAgent(); if (login) connect(); }, 2000);
  });
  readline.createInterface({ input: me.stdout }).on('line', (l) => {
    const p = l.split('\t');
    switch (p[0]) {
      case 'STATE':
        loggedIn = p[1] === 'loggedin';
        if (p[1] === 'badlogin' || p[1] === 'rejected') login = null;
        send({ type: 'state', state: p[1], detail: p[2] ?? '' });
        break;
      case 'MSG':
        send({ type: 'msg', text: p[1] ?? '', at: Date.now() });
        if (win && !win.isFocused()) {
          if (Notification.isSupported()) new Notification({ title: 'Message from your instructor', body: p[1] ?? '' }).show();
          win.flashFrame(true);
          win.show();
        }
        break;
      case 'SHARING': send({ type: 'sharing', value: p[1] }); break;
      case 'VIEWED': send({ type: 'viewed', on: p[1] === '1' }); break;
      case 'LOG': console.log(p[1]); break;
    }
  });
}

const write = (line) => { if (agent && !agent.stdin.destroyed) agent.stdin.write(line + '\n'); };

function connect() {
  const s = loadSettings();
  write(['CONNECT', oneLine(s.host) || 'auto', s.port, s.token, login.regNo, login.password].join('\t'));
}

const safe = (fn) => async (_e, ...args) => {
  try { return { ok: true, value: await fn(...args) }; } catch (err) { return { ok: false, error: err.message }; }
};

ipcMain.handle('student:info', safe(() => ({ pc: process.env.COMPUTERNAME || os.hostname(), defaults: DEFAULTS })));
ipcMain.handle('student:settings', safe(() => loadSettings()));
ipcMain.handle('student:saveSettings', safe((s) => {
  const next = saveSettings(s);
  if (login) connect(); // signed in: reconnect with the new address/password straight away
  return next;
}));
ipcMain.handle('student:login', safe((regNo, password) => {
  const reg = String(regNo ?? '').trim();
  if (!REG_NO.test(reg)) throw new Error('Enter your registration number (letters, digits, / . _ - only)');
  if (!password || /[\t\r\n]/.test(password)) throw new Error('Enter your password');
  const s = loadSettings();
  if (!s.token && s.host) throw new Error("Enter the class password in Settings (gear icon), or clear the IP so the app finds your instructor by itself.");
  saveSettings({ ...s, regNo: reg }); // remember the reg no, never the password
  login = { regNo: reg, password: String(password) };
  connect();
  return true;
}));
ipcMain.handle('student:logout', safe(() => { login = null; loggedIn = false; write('LOGOUT'); return true; }));
ipcMain.handle('student:chat', safe((text) => {
  const t = oneLine(text).trim().slice(0, 1000);
  if (!t) throw new Error('Type a message first');
  if (!loggedIn) throw new Error('Not connected to your instructor right now');
  write(`CHAT\t${t}`);
  return true;
}));
ipcMain.handle('student:share', safe((on) => {
  if (on && !loggedIn) throw new Error('Not connected to your instructor right now');
  write(`SHARE\t${on ? 1 : 0}`);
  return true;
}));

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); } });

app.whenReady().then(() => {
  startAgent();
  win = new BrowserWindow({
    width: 460, height: 760, minWidth: 400, minHeight: 600,
    backgroundColor: '#0f1215', title: 'Class Control — Student',
    autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'studentPreload.js'), contextIsolation: true, sandbox: true },
  });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.on('close', (e) => {
    if (!loggedIn || quitting) return;
    const r = dialog.showMessageBoxSync(win, { type: 'question', buttons: ['Stay in class', 'Leave class'], defaultId: 0, cancelId: 0,
      message: 'Leave the class?', detail: 'You will be logged out and your instructor will see you left.' });
    if (r === 0) e.preventDefault();
  });
  win.loadFile(path.join(__dirname, '..', 'dist', 'student.html'));
});

app.on('window-all-closed', () => {
  quitting = true;
  write('LOGOUT');
  agent?.stdin.end(); // the agent exits when stdin closes and releases this PC (unlock, web rules)
  app.quit();
});
