import { useRef, useState } from 'react';
import { Hand, Lock, Monitor } from 'lucide-react';

// Top-down grid: easiest for picking many PCs (drag a box) and moving PCs between seats.
export default function LabFlat({ layout, seats, bySeat, selected, focusId, onSeatClick, onSeatDouble, onBlockClick, onBox, onEmpty, onMove }) {
  const [box, setBox] = useState(null);
  const [dropKey, setDropKey] = useState(null);
  const start = useRef(null);

  const blocks = layout.blocks.map((b, bi) => ({ b, bi, seats: seats.filter((s) => s.bi === bi) })).sort((p, q) => p.b.x - q.b.x);

  function down(e) {
    if (e.button !== 0 || e.target.closest('.seat, button')) return;
    start.current = { x: e.clientX, y: e.clientY, add: e.shiftKey || e.ctrlKey || e.metaKey };
    e.currentTarget.setPointerCapture(e.pointerId);
  }
  function move(e) {
    const s = start.current;
    if (!s) return;
    setBox({ left: Math.min(s.x, e.clientX), top: Math.min(s.y, e.clientY), width: Math.abs(e.clientX - s.x), height: Math.abs(e.clientY - s.y) });
  }
  function up(e) {
    const s = start.current;
    start.current = null;
    if (!s) return;
    if (!box || box.width < 5 || box.height < 5) { setBox(null); if (!s.add) onEmpty(); return; }
    const hit = [...e.currentTarget.querySelectorAll('[data-pcid]')].filter((el) => {
      const r = el.getBoundingClientRect();
      return r.right > box.left && r.left < box.left + box.width && r.bottom > box.top && r.top < box.top + box.height;
    }).map((el) => el.dataset.pcid);
    setBox(null);
    onBox(hit, s.add);
  }

  return (
    <div className="flat" onPointerDown={down} onPointerMove={move} onPointerUp={up}>
      <div className="flat-stage">INSTRUCTOR · SCREEN</div>
      <div className="flat-blocks">
        {blocks.map(({ b, bi, seats: ss }) => (
          <div className="flat-block" key={bi}>
            <h3><button className="block-tag" style={{ transform: 'none' }} onClick={(e) => onBlockClick(bi, e.shiftKey || e.ctrlKey || e.metaKey)}>BLOCK {b.name}</button></h3>
            <div className="flat-grid" style={{ gridTemplateColumns: `repeat(${b.cols}, auto)` }}>
              {ss.map((s) => {
                const pc = bySeat.get(s.key);
                if (!pc) {
                  return (
                    <div key={s.key} className={`seat empty ${dropKey === s.key ? 'drop' : ''}`} title={`Block ${s.block} · Row ${s.row} · Seat ${s.col}`}
                      onDragOver={(e) => { e.preventDefault(); setDropKey(s.key); }} onDragLeave={() => setDropKey(null)}
                      onDrop={(e) => { e.preventDefault(); setDropKey(null); onMove(e.dataTransfer.getData('text/host'), s.key); }}>
                      {s.block}{s.row}-{s.col}
                    </div>
                  );
                }
                const cls = ['seat', pc.status, selected.has(pc.id) && 'sel', focusId === pc.id && 'focus', dropKey === s.key && 'drop'].filter(Boolean).join(' ');
                return (
                  <div key={s.key} className={cls} data-pcid={pc.id} draggable title={`${pc.student ? pc.student + ' on ' : ''}${pc.host} · ${pc.ip} — drag to move seat`}
                    onClick={(e) => onSeatClick(pc.id, e)} onDoubleClick={() => onSeatDouble(pc.id)}
                    onDragStart={(e) => e.dataTransfer.setData('text/host', pc.host)}
                    onDragOver={(e) => { e.preventDefault(); setDropKey(s.key); }} onDragLeave={() => setDropKey(null)}
                    onDrop={(e) => { e.preventDefault(); setDropKey(null); onMove(e.dataTransfer.getData('text/host'), s.key); }}>
                    <div className="scr">{pc.thumb ? <img alt="" src={`data:image/jpeg;base64,${pc.thumb}`} /> : <Monitor size={20} />}</div>
                    <div className="nm"><span className={`dot ${pc.status}`} /><span>{pc.student || pc.host}</span></div>
                    {pc.student && <div className="sub">{pc.host}</div>}
                    {pc.locked && <Lock size={13} className="badge" />}
                    {pc.shareRequest && <Hand size={13} className="badge hand" aria-label="Wants to share their screen" />}
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>
      {box && <div className="box-select" style={box} />}
    </div>
  );
}
