import { useState } from 'react';
import { Check, Hand, LocateFixed, Send, X } from 'lucide-react';
import { threadKey, who } from '../names.js';

// Messages from students (grouped per PC, newest first) and their requests to share a screen with the class.
export default function Inbox({ chats, pcs, onReply, onPresent, onLocate, onClose }) {
  const requests = pcs.filter((p) => p.shareRequest);
  const threads = [];
  for (const c of [...chats].reverse()) {
    let t = threads.find((x) => x.key === c.key);
    if (!t) threads.push(t = { key: c.key, who: c.who, items: [] });
    t.items.unshift(c);
  }
  const byKey = Object.fromEntries(pcs.map((p) => [threadKey(p), p]));

  return (
    <aside className="panel" aria-label="Messages">
      <div className="panel-head"><h2>Messages</h2><div className="spacer" />
        <button className="btn ghost sm icon-btn" style={{ width: 30 }} onClick={onClose} aria-label="Close"><X size={15} /></button>
      </div>
      <div className="panel-body">
        {requests.map((p) => (
          <div key={p.id} className="share-req">
            <Hand size={16} />
            <div style={{ flex: 1, minWidth: 0 }}><b>{who(p)}</b><div className="muted" style={{ fontSize: 12 }}>wants to share their screen with the class</div></div>
            <button className="btn sm primary" onClick={() => onPresent(p.id, '1')}><Check size={14} /> Allow</button>
            <button className="btn sm" onClick={() => onPresent(p.id, 'deny')}>Deny</button>
          </div>
        ))}
        {threads.length === 0 && requests.length === 0 && <div className="muted">No messages yet. Students write to you from the student app.</div>}
        {threads.map((t) => <Thread key={t.key} t={t} pc={byKey[t.key]} onReply={onReply} onLocate={onLocate} />)}
      </div>
    </aside>
  );
}

function Thread({ t, pc, onReply, onLocate }) {
  const [text, setText] = useState('');
  return (
    <div className="thread">
      <div className="thread-head">
        <b>{pc ? who(pc) : t.who}</b>
        {!pc && <span className="muted" style={{ fontSize: 12 }}>left</span>}
        <div className="spacer" />
        {pc && <button className="btn ghost sm icon-btn" style={{ width: 28 }} onClick={() => onLocate(pc.id)} title="Show on the map" aria-label="Show on the map"><LocateFixed size={14} /></button>}
      </div>
      {t.items.map((m) => (
        <div key={m.id} className={`bubble ${m.from}`}><span>{m.text}</span><time>{new Date(m.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</time></div>
      ))}
      {pc && (
        <form className="reply" onSubmit={(e) => { e.preventDefault(); if (text.trim()) { onReply(pc.id, text.trim()); setText(''); } }}>
          <input className="input" value={text} onChange={(e) => setText(e.target.value)} placeholder={`Reply to ${pc.student || pc.host}…`} aria-label="Reply" />
          <button className="btn sm primary icon-btn" style={{ width: 36 }} disabled={!text.trim()} aria-label="Send"><Send size={14} /></button>
        </form>
      )}
    </div>
  );
}
