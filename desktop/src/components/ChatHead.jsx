import { useEffect, useRef, useState } from 'react';
import { Send, X } from 'lucide-react';

// Chat head that pops up above a student's desk when they write. Click it to read and reply right there;
// it goes away once the chat was viewed (closed) or answered.
export default function ChatHead({ pc, head, messages, onReply, onDismiss }) {
  const [open, setOpen] = useState(false);
  const [peek, setPeek] = useState(true); // preview of the newest message, hides after a few seconds
  const [text, setText] = useState('');
  const list = useRef(null);
  const last = messages[messages.length - 1];

  useEffect(() => {
    setPeek(true);
    const t = setTimeout(() => setPeek(false), 8000);
    return () => clearTimeout(t);
  }, [head.at]);
  useEffect(() => { list.current?.scrollTo({ top: list.current.scrollHeight }); }, [open, messages.length]);

  const name = pc.student || pc.host;
  return (
    <div className={`chathead ${open ? 'open' : ''}`} key={head.at} onPointerDown={(e) => e.stopPropagation()}>
      {open ? (
        <div className="chathead-card" role="dialog" aria-label={`Chat with ${name}`}>
          <div className="chathead-top">
            <span className="chathead-av sm">{name.slice(0, 2)}</span>
            <b>{name}</b><span className="muted mono" style={{ fontSize: 10 }}>{pc.host !== name ? pc.host : ''}</span>
            <div className="spacer" />
            <button className="btn ghost sm icon-btn" style={{ width: 26, height: 26 }} onClick={() => onDismiss(pc)} aria-label="Close chat"><X size={13} /></button>
          </div>
          <div className="chathead-list" ref={list}>
            {messages.map((m) => <div key={m.id} className={`bubble ${m.from}`}><span>{m.text}</span></div>)}
          </div>
          <form className="reply" onSubmit={(e) => { e.preventDefault(); if (text.trim()) { onReply(pc.id, text.trim()); setText(''); } }}>
            <input className="input" autoFocus value={text} onChange={(e) => setText(e.target.value)} placeholder={`Reply to ${name}…`} aria-label="Reply"
              onKeyDown={(e) => e.key === 'Escape' && onDismiss(pc)} />
            <button className="btn sm primary icon-btn" style={{ width: 34 }} disabled={!text.trim()} aria-label="Send"><Send size={13} /></button>
          </form>
        </div>
      ) : (
        <button className="chathead-btn" onClick={() => { setOpen(true); onDismiss(pc, true); }} title={`Message from ${name}`}>
          <span className="chathead-av">{name.slice(0, 2)}{head.unread > 0 && <i className="chathead-n">{head.unread}</i>}</span>
          {peek && last && <span className="chathead-peek"><b>{name}</b>{last.text}</span>}
        </button>
      )}
    </div>
  );
}
