const { app, BrowserWindow, ipcMain, desktopCapturer, screen } = require('electron');
const path = require('node:path');
const os = require('node:os');
const dgram = require('node:dgram');
const { Lab } = require('./lab');
const { JavaLab } = require('./javaLab');
const layout = require('./layout');
const { startDemo } = require('./demo');

let win;

// Teacher screen share: grab the primary screen as a 1024px-wide JPEG.
async function grabFrame() {
  const { width, height } = screen.getPrimaryDisplay().size;
  const w = 1024;
  const [src] = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: w, height: Math.round((height * w) / width) } });
  if (!src) throw new Error('No screen to capture');
  return src.thumbnail.toJPEG(70).toString('base64');
}

// The Java server (backend/LabServer.java) is the default; CC_BACKEND=node uses the older JavaScript one.
const lab = process.env.CC_BACKEND === 'node' ? new Lab({ grabFrame }) : new JavaLab();
lab.on('event', (e) => { if (win && !win.isDestroyed()) win.webContents.send('lab:event', e); });

const localIps = () => Object.values(os.networkInterfaces()).flat()
  .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);

const safe = (fn) => async (_e, ...args) => {
  try { return { ok: true, value: await fn(...args) }; } catch (err) { return { ok: false, error: err.message }; }
};

ipcMain.handle('lab:start', safe((token, port, share) => {
  if (typeof token !== 'string' || !token.trim()) throw new Error('Enter a class password');
  const p = Number(port);
  if (!Number.isInteger(p) || p < 1 || p > 65535) throw new Error('Port must be 1-65535');
  return lab.start(token.trim(), p, os.hostname(), path.join(app.getPath('userData'), 'students.properties'), !!share).catch((e) => {
    throw new Error(e.code === 'EADDRINUSE' ? `Port ${p} is already in use (is another console running?)` : e.message);
  });
}));
ipcMain.handle('lab:demo', safe(async () => {
  const port = await lab.start('demo', 0); // random free port so it never clashes with a real class
  startDemo(port, 'demo');
  return port;
}));
// Classes already running on this network (their UDP beacons), so a second instructor joins instead of splitting the room.
ipcMain.handle('lab:classes', safe(() => new Promise((resolve) => {
  const found = new Map();
  const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  const done = () => { try { sock.close(); } catch { /* already closed */ } resolve([...found.values()]); };
  sock.on('error', done);
  sock.on('message', (msg, from) => {
    const p = msg.toString('utf8').split('\t');
    const port = Number(p[1]);
    if (p[0] !== 'CCLASS1' || !Number.isInteger(port) || port < 1 || port > 65535) return;
    found.set(`${from.address}:${port}`, { host: from.address, port, name: (p[2] ?? '').slice(0, 64) || from.address });
  });
  sock.bind(5758, () => setTimeout(done, 2500)); // beacons come every 2 s
})));
ipcMain.handle('lab:join', safe((host, port) => {
  if (typeof host !== 'string' || !/^[A-Za-z0-9.:-]{1,253}$/.test(host.trim())) throw new Error('Enter the IP address of the instructor\'s computer');
  const p = Number(port);
  if (!Number.isInteger(p) || p < 1 || p > 65535) throw new Error('Port must be 1-65535');
  if (!lab.join) throw new Error('Joining a class needs the Java backend');
  return lab.join(host.trim(), p, os.hostname());
}));
ipcMain.handle('lab:coaccept', safe((id, ok) => lab.coaccept(String(id), !!ok)));
ipcMain.handle('lab:info', safe(() => ({ ips: localIps(), hostname: os.hostname(), sharing: lab.sharingIds(), presenter: lab.presenterId?.() ?? null, role: lab.role?.() ?? 'host' })));
ipcMain.handle('lab:present', safe((id, mode) => lab.present(String(id ?? ''), String(mode))));
ipcMain.handle('lab:forget', safe((reg) => lab.forget(String(reg))));
ipcMain.handle('lab:list', safe(() => lab.list()));
ipcMain.handle('lab:send', safe((ids, cmd, arg) => lab.send(Array.isArray(ids) ? ids : [], cmd, arg)));
ipcMain.handle('lab:remote', safe((id, cmd, arg) => lab.remote(String(id), cmd, arg)));
ipcMain.handle('lab:wb', safe((arg) => lab.whiteboard(String(arg))));
ipcMain.handle('lab:live', safe((on) => lab.setLive(!!on, 2000)));
ipcMain.handle('layout:get', safe(() => layout.load(app.getPath('userData'))));
ipcMain.handle('layout:save', safe((l) => layout.save(app.getPath('userData'), l)));

app.whenReady().then(() => {
  win = new BrowserWindow({
    width: 1440, height: 900, minWidth: 1100, minHeight: 700,
    backgroundColor: '#0f1215', title: 'Class Control',
    autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true },
  });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
});

app.on('window-all-closed', () => { lab.stop(); app.quit(); });
