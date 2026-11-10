import test from 'node:test';
import assert from 'node:assert';
import { allSeats, assignSeats } from '../src/seating.js';

test('seat assignment', () => {
  const layout = { blocks: [{ name: 'B', cols: 2, rows: 1, x: 5, z: 0, angle: 0 }, { name: 'A', cols: 1, rows: 1, x: 0, z: 0, angle: 0 }], seats: { PC9: '0-0-1', GHOST: '0-0-0' } };
  const seats = allSeats(layout);
  assert.equal(seats.length, 3);
  const pcs = [{ id: '1', host: 'PC9' }, { id: '2', host: 'PC2' }, { id: '3', host: 'PC3' }, { id: '4', host: 'PC4' }];
  const { seatOf, overflow } = assignSeats(layout, pcs, seats);
  assert.equal(seatOf.get('1'), '0-0-1'); // saved seat kept
  assert.equal(seatOf.get('2'), '1-0-0'); // block A fills first
  assert.equal(seatOf.get('3'), '0-0-0'); // seat saved for an absent PC is reused
  assert.deepEqual(overflow.map((p) => p.host), ['PC4']);
});
