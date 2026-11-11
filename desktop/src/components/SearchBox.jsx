import { useEffect, useMemo, useRef, useState } from 'react';
import { Monitor, Search, X } from 'lucide-react';

function Hl({ text, q }) {
  const i = text.toLowerCase().indexOf(q.toLowerCase());
  if (!q || i < 0) return text;
  return <>{text.slice(0, i)}<mark>{text.slice(i, i + q.length)}</mark>{text.slice(i + q.length)}</>;
}

export default function SearchBox({ pcs, where, onPick }) {
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [idx, setIdx] = useState(0);
  const ref = useRef(null);

  const results = useMemo(() => {
    const s = q.trim().toLowerCase();
    if (!s) return [];
    return pcs.filter((p) => p.host.toLowerCase().includes(s) || p.ip.includes(s) || p.student?.toLowerCase().includes(s)).slice(0, 8);
  }, [q, pcs]);

  useEffect(() => {
    const f = (e) => { if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'f') { e.preventDefault(); ref.current?.focus(); } };
    window.addEventListener('keydown', f);
    return () => window.removeEventListener('keydown', f);
  }, []);

  const pick = (pc) => { setQ(pc.student || pc.host); setOpen(false); onPick(pc.id); ref.current?.blur(); };

  function key(e) {
    if (e.key === 'ArrowDown') { e.preventDefault(); setIdx((i) => Math.min(i + 1, results.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setIdx((i) => Math.max(i - 1, 0)); }
    else if (e.key === 'Enter' && results[idx]) pick(results[idx]);
    else if (e.key === 'Escape') { setOpen(false); ref.current?.blur(); }
  }

  return (
    <div className="search">
      <div className="search-box">
        <Search size={15} className="muted" />
        <input ref={ref} value={q} placeholder="Find a student, PC or IP…   ⌘F" aria-label="Find a student or PC"
          onChange={(e) => { setQ(e.target.value); setOpen(true); setIdx(0); }}
          onFocus={() => setOpen(true)} onBlur={() => setTimeout(() => setOpen(false), 150)} onKeyDown={key} />
        {q && <button className="btn ghost sm icon-btn" style={{ width: 26, height: 26 }} aria-label="Clear search" onClick={() => { setQ(''); onPick(null); }}><X size={14} /></button>}
      </div>
      {open && q.trim() && (
        <div className="search-list" role="listbox">
          {results.length === 0 && <div className="search-item muted">No student or PC matches “{q}”</div>}
          {results.map((pc, i) => (
            <div key={pc.id} role="option" aria-selected={i === idx} className={`search-item ${i === idx ? 'active' : ''}`}
              onMouseDown={(e) => { e.preventDefault(); pick(pc); }} onMouseEnter={() => setIdx(i)}>
              <div className="ico"><Monitor size={15} /></div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div>{pc.student && <><Hl text={pc.student} q={q.trim()} /> · </>}<Hl text={pc.host} q={q.trim()} /></div>
                <div className="where">{where(pc.id)} · <Hl text={pc.ip} q={q.trim()} /></div>
              </div>
              <span className={`dot ${pc.status}`} />
            </div>
          ))}
          <div className="search-foot"><span>{results.length} found</span><span><kbd>↑↓</kbd> navigate</span><span><kbd>↵</kbd> select</span><span><kbd>esc</kbd> close</span></div>
        </div>
      )}
    </div>
  );
}
