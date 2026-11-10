import { useEffect, useRef } from 'react';

export default function ConfirmModal({ title, body, confirm, onYes, onNo }) {
  const yes = useRef(null);
  useEffect(() => {
    yes.current?.focus();
    const f = (e) => { if (e.key === 'Escape') onNo(); };
    window.addEventListener('keydown', f);
    return () => window.removeEventListener('keydown', f);
  }, [onNo]);
  return (
    <div className="backdrop" onMouseDown={onNo}>
      <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby="cm-title" onMouseDown={(e) => e.stopPropagation()}>
        <h3 id="cm-title">{title}</h3>
        <p>{body}</p>
        <div className="row">
          <button className="btn" onClick={onNo}>Cancel</button>
          <button ref={yes} className="btn primary" onClick={onYes}>{confirm}</button>
        </div>
      </div>
    </div>
  );
}
