import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowLeft, Eraser, PenLine, Radio, Square, Trash2, Undo2 } from 'lucide-react';

// Strokes are sent to students as fractions of a 16:9 board, so every screen shows the same picture.
const COLORS = ['#111827', '#e53935', '#1e88e5', '#43a047', '#fb8c00', '#8e24aa']; // lower case: the server only accepts lower-case hex
const SIZES = [{ label: 'Thin', w: 0.003 }, { label: 'Medium', w: 0.006 }, { label: 'Thick', w: 0.012 }];
const ERASER = { color: '#ffffff', w: 0.03 };
const frac = (n) => Math.min(1, Math.max(0, n)).toFixed(4);
const width = (w) => w.toFixed(4);
const hex = (c) => c.slice(1);
const wb = (arg) => window.lab.whiteboard(arg);

/** Sends one stroke (start, points in chunks the server accepts, end). */
function sendStroke(s) {
  wb(`beg,${hex(s.color)},${width(s.w)},${frac(s.pts[0][0])},${frac(s.pts[0][1])}`);
  for (let i = 1; i < s.pts.length; i += 100) wb(`add,${s.pts.slice(i, i + 100).map(([x, y]) => `${frac(x)},${frac(y)}`).join(',')}`);
  wb('end');
}

export default function Whiteboard({ visible, onMinimise, onStop }) {
  const [color, setColor] = useState(COLORS[0]);
  const [size, setSize] = useState(1);
  const [erasing, setErasing] = useState(false);
  const [count, setCount] = useState(0);
  const canvas = useRef(null);
  const stage = useRef(null);
  const strokes = useRef([]);        // [{ color, w, pts: [[x, y]] }]
  const drawing = useRef(null);      // stroke being drawn
  const pending = useRef([]);        // points not yet sent
  const timer = useRef(null);

  const paint = useCallback(() => {
    const c = canvas.current;
    if (!c || !c.width) return;
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    for (const s of strokes.current) {
      ctx.strokeStyle = s.color;
      ctx.lineWidth = Math.max(1, s.w * c.width);
      ctx.beginPath();
      ctx.moveTo(s.pts[0][0] * c.width, s.pts[0][1] * c.height);
      if (s.pts.length === 1) ctx.lineTo(s.pts[0][0] * c.width + 0.01, s.pts[0][1] * c.height); // a tap draws a dot
      for (const [x, y] of s.pts) ctx.lineTo(x * c.width, y * c.height);
      ctx.stroke();
    }
  }, []);

  // Fit the largest 16:9 canvas into the stage and keep it sharp on high-DPI screens.
  useEffect(() => {
    const fit = () => {
      const box = stage.current;
      const c = canvas.current;
      if (!box || !c || box.clientWidth === 0) return;
      const cssW = Math.min(box.clientWidth - 28, (box.clientHeight - 28) * (16 / 9));
      const dpr = window.devicePixelRatio || 1;
      c.style.width = `${cssW}px`;
      c.style.height = `${(cssW * 9) / 16}px`;
      c.width = Math.round(cssW * dpr);
      c.height = Math.round(((cssW * 9) / 16) * dpr);
      paint();
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(stage.current);
    return () => ro.disconnect();
  }, [paint, visible]);

  // Opening the whiteboard starts a fresh board on every student screen.
  useEffect(() => {
    wb('clear');
    return () => clearInterval(timer.current);
  }, []);

  const flush = () => {
    const pts = pending.current.splice(0);
    for (let i = 0; i < pts.length; i += 100) wb(`add,${pts.slice(i, i + 100).map(([x, y]) => `${frac(x)},${frac(y)}`).join(',')}`);
  };

  const at = (e) => {
    const r = canvas.current.getBoundingClientRect();
    return [Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), Math.min(1, Math.max(0, (e.clientY - r.top) / r.height))];
  };

  const down = (e) => {
    if (e.button !== 0) return;
    canvas.current.setPointerCapture(e.pointerId);
    const tool = erasing ? ERASER : { color, w: SIZES[size].w };
    const p = at(e);
    drawing.current = { color: tool.color, w: tool.w, pts: [p] };
    strokes.current.push(drawing.current);
    wb(`beg,${hex(tool.color)},${width(tool.w)},${frac(p[0])},${frac(p[1])}`);
    timer.current = setInterval(flush, 40);
    paint();
  };

  const move = (e) => {
    const s = drawing.current;
    if (!s) return;
    for (const ev of e.nativeEvent.getCoalescedEvents?.() ?? [e.nativeEvent]) {
      const p = at(ev);
      const last = s.pts[s.pts.length - 1];
      if (Math.hypot(p[0] - last[0], p[1] - last[1]) < 0.0008) continue; // sub-pixel jitter
      s.pts.push(p);
      pending.current.push(p);
    }
    paint();
  };

  const up = () => {
    if (!drawing.current) return;
    clearInterval(timer.current);
    flush();
    wb('end');
    drawing.current = null;
    setCount(strokes.current.length);
  };

  const undo = useCallback(() => {
    if (drawing.current || !strokes.current.length) return;
    strokes.current.pop();
    wb('clear');
    strokes.current.forEach(sendStroke);
    setCount(strokes.current.length);
    paint();
  }, [paint]);

  const clearAll = () => {
    strokes.current = [];
    wb('clear');
    setCount(0);
    paint();
  };

  const stop = () => { wb('off'); onStop(); };

  useEffect(() => {
    if (!visible) return undefined;
    const f = (e) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); undo(); } };
    window.addEventListener('keydown', f);
    return () => window.removeEventListener('keydown', f);
  }, [visible, undo]);

  return (
    <div className="board" style={{ display: visible ? 'flex' : 'none' }}>
      <div className="board-bar">
        <button className="btn sm" onClick={onMinimise} title="Back to the lab. Students keep seeing the board."><ArrowLeft size={15} /> Lab</button>
        <div className="title"><PenLine size={17} /> Whiteboard</div>
        <span className="live-badge"><Radio size={12} /> STUDENTS SEE THIS LIVE</span>
        <div className="sep" />
        {COLORS.map((c) => (
          <button key={c} className={`swatch ${!erasing && color === c ? 'on' : ''}`} style={{ background: c }} aria-label={`Pen colour ${c}`}
            onClick={() => { setColor(c); setErasing(false); }} />
        ))}
        <div className="sep" />
        {SIZES.map((s, i) => (
          <button key={s.label} className={`btn sm ${!erasing && size === i ? 'on' : ''}`} onClick={() => { setSize(i); setErasing(false); }}>{s.label}</button>
        ))}
        <button className={`btn sm ${erasing ? 'on' : ''}`} onClick={() => setErasing(!erasing)}><Eraser size={15} /> Eraser</button>
        <div className="sep" />
        <button className="btn sm" onClick={undo} disabled={!count} title="Undo (Ctrl+Z)"><Undo2 size={15} /> Undo</button>
        <button className="btn sm" onClick={clearAll} disabled={!count}><Trash2 size={15} /> Clear</button>
        <div className="spacer" />
        <button className="btn sm danger" onClick={stop}><Square size={14} /> Stop showing</button>
      </div>
      <div className="board-stage" ref={stage}>
        <canvas ref={canvas} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up} />
      </div>
    </div>
  );
}
