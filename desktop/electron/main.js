const { app, BrowserWindow, ipcMain, desktopCapturer, screen } = require('electron');
const path = require('node:path');
const os = require('node:os');
const { Lab } = require('./lab');
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

const lab = new Lab({ grabFrame });
lab.on('event', (e) => { if (win && !win.isDestroyed()) win.webContents.send('lab:event', e); });

const localIps = () => Object.values(os.networkInterfaces()).flat()
  .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);

const safe = (fn) => async (_e, ...args) => {
  try { return { ok: true, value: await fn(...args) }; } catch (err) { return { ok: false, error: err.message }; }
};

ipcMain.handle('lab:start', safe((token, port) => {
  if (typeof token !== 'string' || !token.trim()) throw new Error('Enter a class password');
  const p = Number(port);
  if (!Number.isInteger(p) || p < 1 || p > 65535) throw new Error('Port must be 1-65535');
  return lab.start(token.trim(), p).catch((e) => {
    throw new Error(e.code === 'EADDRINUSE' ? `Port ${p} is already in use (is another console running?)` : e.message);
  });
}));
ipcMain.handle('lab:demo', safe(async () => {
  const port = await lab.start('demo', 0); // random free port so it never clashes with a real class
  startDemo(port, 'demo');
  return port;
}));
ipcMain.handle('lab:info', safe(() => ({ ips: localIps(), hostname: os.hostname(), sharing: lab.sharingIds() })));
ipcMain.handle('lab:list', safe(() => lab.list()));
ipcMain.handle('lab:send', safe((ids, cmd, arg) => lab.send(Array.isArray(ids) ? ids : [], cmd, arg)));
ipcMain.handle('lab:remote', safe((id, cmd, arg) => lab.remote(String(id), cmd, arg)));
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
