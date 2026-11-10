// Lab layout (blocks + which host sits where), stored as JSON in the app's userData folder.
const fs = require('node:fs');
const path = require('node:path');

// Default mirrors the reference room: left wing D, centre pair A + B, right wing C.
const DEFAULT = {
  blocks: [
    // angle = degrees the block's rows drift sideways going back (wings fan out like the reference)
    { name: 'D', cols: 6, rows: 6, x: -16, z: 0, angle: -18 },
    { name: 'A', cols: 4, rows: 6, x: -4.2, z: 0, angle: 0 },
    { name: 'B', cols: 4, rows: 6, x: 4.2, z: 0, angle: 0 },
    { name: 'C', cols: 6, rows: 6, x: 16, z: 0, angle: 10 },
  ],
  seats: {}, // host name -> "block-row-col"
};

const file = (dir) => path.join(dir, 'layout.json');

function valid(l) {
  return l && Array.isArray(l.blocks) && l.blocks.length <= 12 && typeof l.seats === 'object' &&
    l.blocks.every((b) => typeof b.name === 'string' && [b.cols, b.rows].every((n) => Number.isInteger(n) && n >= 1 && n <= 30) &&
      [b.x, b.z, b.angle].every((n) => typeof n === 'number' && Number.isFinite(n)));
}

function load(dir) {
  try {
    const l = JSON.parse(fs.readFileSync(file(dir), 'utf8'));
    if (valid(l)) return l;
  } catch { /* first run or unreadable: use default */ }
  return structuredClone(DEFAULT);
}

function save(dir, l) {
  if (!valid(l)) throw new Error('Invalid layout');
  const tmp = file(dir) + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(l, null, 2));
  fs.renameSync(tmp, file(dir)); // atomic swap so a crash never leaves a half-written layout
  return l;
}

module.exports = { load, save, DEFAULT };
