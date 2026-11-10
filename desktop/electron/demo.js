// Demo mode: fake student PCs that speak the real agent protocol over TCP,
// so the whole UI can be tried on one machine without any Agent.java running.
const net = require('node:net');
const { nativeImage } = require('electron');

/** Gradient "desktop" with a taskbar, a window and an optional cursor dot, as base64 JPEG. */
function fakeScreen(w, h, hue, t, cursor) {
  const buf = Buffer.alloc(w * h * 4);
  const win = { x: Math.round(w * (0.12 + 0.08 * Math.sin(t / 3))), y: Math.round(h * 0.15), w: Math.round(w * 0.55), h: Math.round(h * 0.55) };
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      let r = 30 + 60 * Math.sin(hue + x / w), g = 50 + 50 * (y / h), b = 90 + 80 * Math.cos(hue + y / h);
      if (y > h * 0.93) { r = 22; g = 26; b = 30; }                                   // taskbar
      if (x >= win.x && x < win.x + win.w && y >= win.y && y < win.y + win.h) {       // app window
        const bar = y < win.y + h * 0.05;
        r = bar ? 60 : 236; g = bar ? 66 : 238; b = bar ? 74 : 240;
        if (!bar && (y - win.y) % Math.round(h * 0.04) < 2 && x < win.x + win.w * 0.8) { r = 170; g = 180; b = 190; } // "text" lines
      }
      if (cursor && Math.hypot(x - cursor[0] * w, y - cursor[1] * h) < Math.max(4, w / 120)) { r = 255; g = 60; b = 60; }
      buf[i] = b; buf[i + 1] = g; buf[i + 2] = r; buf[i + 3] = 255; // BGRA
    }
  }
  return nativeImage.createFromBitmap(buf, { width: w, height: h }).toJPEG(70).toString('base64');
}

function fakeAgent(port, token, name, hue) {
  const s = net.connect(port, '127.0.0.1');
  s.setEncoding('utf8');
  s.write(`${token}\t${name}\n`);
  let buf = '';
  let viewTimer = null;
  let cursor = null;
  const t0 = Date.now();
  const t = () => (Date.now() - t0) / 1000;
  s.on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const [cmd, arg = ''] = buf.slice(0, i).split('\t');
      buf = buf.slice(i + 1);
      if (cmd === 'THUMB') s.write(`THUMB\t${fakeScreen(320, 200, hue, t(), null)}\n`);
      else if (cmd === 'VIEW') {
        clearInterval(viewTimer);
        viewTimer = setInterval(() => s.write(`FRAME\t${fakeScreen(960, 600, hue, t(), cursor)}\n`), 1000 / Math.max(1, Number(arg) || 5));
      } else if (cmd === 'VIEWOFF') { clearInterval(viewTimer); viewTimer = null; }
      else if (cmd === 'MOUSE') { const p = arg.split(','); cursor = [Number(p[1]), Number(p[2])]; }
    }
  });
  s.on('error', () => {});
  s.on('close', () => clearInterval(viewTimer));
  return s;
}

function startDemo(port, token, count = 18) {
  return Array.from({ length: count }, (_, i) => fakeAgent(port, token, `LAB-PC${String(i + 1).padStart(2, '0')}`, i * 0.7));
}

module.exports = { startDemo };
