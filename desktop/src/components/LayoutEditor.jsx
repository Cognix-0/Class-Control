import { Plus, RotateCcw, Trash2, X } from 'lucide-react';

const DEFAULT_BLOCKS = [
  { name: 'D', cols: 6, rows: 6, x: -16, z: 0, angle: -18 },
  { name: 'A', cols: 4, rows: 6, x: -4.2, z: 0, angle: 0 },
  { name: 'B', cols: 4, rows: 6, x: 4.2, z: 0, angle: 0 },
  { name: 'C', cols: 6, rows: 6, x: 16, z: 0, angle: 10 },
];
const FIELDS = [['cols', 'Cols', 1, 30, 1], ['rows', 'Rows', 1, 30, 1], ['x', 'X pos', -60, 60, 0.5], ['z', 'Depth', -10, 40, 0.5], ['angle', 'Angle', -45, 45, 1]];

export default function LayoutEditor({ layout, onChange, onClose, seatCount, pcCount }) {
  const set = (i, k, v) => onChange({ ...layout, blocks: layout.blocks.map((b, j) => (j === i ? { ...b, [k]: v } : b)) });
  const add = () => {
    const last = layout.blocks[layout.blocks.length - 1];
    const name = String.fromCharCode(65 + layout.blocks.length);
    onChange({ ...layout, blocks: [...layout.blocks, { name, cols: 4, rows: 6, x: (last?.x ?? 0) + 10, z: 0, angle: 0 }] });
  };
  return (
    <aside className="panel" aria-label="Lab layout">
      <div className="panel-head"><h2>Lab layout</h2><div className="spacer" />
        <button className="btn ghost sm icon-btn" style={{ width: 30 }} onClick={onClose} aria-label="Close"><X size={15} /></button>
      </div>
      <div className="panel-body">
        <p className="muted" style={{ marginTop: 0, fontSize: 13 }}>
          {seatCount} seats for {pcCount} connected PC{pcCount === 1 ? '' : 's'}. Changes save automatically.
          To move a PC to another seat, switch to <b>2D</b> view and drag it.
        </p>
        {layout.blocks.map((b, i) => (
          <div className="block-card" key={i}>
            <div className="top">
              <span className="caps muted">Block</span>
              <input className="input" value={b.name} maxLength={3} aria-label="Block name" onChange={(e) => set(i, 'name', e.target.value.toUpperCase())} />
              <div className="spacer" />
              <button className="btn ghost sm icon-btn" style={{ width: 30 }} aria-label={`Remove block ${b.name}`} disabled={layout.blocks.length === 1}
                onClick={() => onChange({ ...layout, blocks: layout.blocks.filter((_, j) => j !== i) })}><Trash2 size={14} /></button>
            </div>
            <div className="nums">
              {FIELDS.map(([k, label, min, max, step]) => (
                <label key={k}>{label}
                  <input className="input mono" type="number" min={min} max={max} step={step} value={b[k]}
                    onChange={(e) => { const n = Number(e.target.value); if (e.target.value !== '' && n >= min && n <= max) set(i, k, step === 1 ? Math.round(n) : n); }} />
                </label>
              ))}
            </div>
          </div>
        ))}
      </div>
      <div className="panel-foot">
        <button className="btn" onClick={add} disabled={layout.blocks.length >= 12}><Plus size={15} /> Add block</button>
        <div className="spacer" />
        <button className="btn ghost" onClick={() => onChange({ ...layout, seats: {} })} title="Forget saved seats">Reset seats</button>
        <button className="btn ghost" onClick={() => onChange({ blocks: DEFAULT_BLOCKS, seats: {} })} title="Back to the default room"><RotateCcw size={15} /></button>
      </div>
    </aside>
  );
}
