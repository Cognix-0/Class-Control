// Seat geometry + which PC sits where. Shared by the 3D and 2D views.
export const DESK_W = 1.6; // spacing between desks in a row (world units)
export const ROW_D = 1.9;  // spacing between rows

export const seatKey = (bi, r, c) => `${bi}-${r}-${c}`;

/** Every seat with its block/row/col and world position. Row 0 is nearest the stage. */
export function allSeats(layout) {
  const out = [];
  layout.blocks.forEach((b, bi) => {
    const a = (b.angle * Math.PI) / 180;
    for (let r = 0; r < b.rows; r++) {
      for (let c = 0; c < b.cols; c++) {
        const lx = (c - (b.cols - 1) / 2) * DESK_W;
        const lz = r * ROW_D;
        // Shear (not rotate) so rows stay parallel to the stage, like a fanned-out wing.
        out.push({ key: seatKey(bi, r, c), bi, block: b.name, row: r + 1, col: c + 1, x: b.x + lx + lz * Math.tan(a), z: b.z + lz, rot: -a * 0.5 });
      }
    }
  });
  return out;
}

/** Map PCs to seats: saved seat first, then fill free seats block by block (A, B, C, D…). */
export function assignSeats(layout, pcs, seats) {
  const valid = new Set(seats.map((s) => s.key));
  const bySeat = new Map();
  const seatOf = new Map();
  const rest = [];
  const sorted = [...pcs].sort((p, q) => p.host.localeCompare(q.host, undefined, { numeric: true }));
  for (const pc of sorted) {
    const k = layout.seats[pc.host];
    if (k && valid.has(k) && !bySeat.has(k)) { bySeat.set(k, pc); seatOf.set(pc.id, k); } else rest.push(pc);
  }
  const order = [...seats].sort((s, t) => layout.blocks[s.bi].name.localeCompare(layout.blocks[t.bi].name) || s.row - t.row || s.col - t.col);
  const overflow = [];
  for (const pc of rest) {
    const free = order.find((s) => !bySeat.has(s.key));
    if (!free) { overflow.push(pc); continue; }
    bySeat.set(free.key, pc);
    seatOf.set(pc.id, free.key);
  }
  return { bySeat, seatOf, overflow };
}
