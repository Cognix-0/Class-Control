import { useEffect, useRef, useState } from 'react';
import { Eye, Hand, LogOut, MonitorUp, Send, Settings as Gear, Square } from 'lucide-react';

export default function Home({ regNo, pc, conn, chat, share, viewed, onChat, onShare, onLogout, onSettings }) {
  const [text, setText] = useState('');
  const list = useRef(null);
  const online = conn.state === 'loggedin';
  useEffect(() => { list.current?.scrollTo({ top: list.current.scrollHeight, behavior: 'smooth' }); }, [chat.length]);

  async function submit(e) {
    e.preventDefault();
    if (text.trim() && await onChat(text.trim())) setText('');
  }

  return (
    <div className="home">
      <header className="home-head">
        <div className="avatar" aria-hidden>{regNo.slice(0, 2)}</div>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div className="who">{regNo}</div>
          <div className="muted mono" style={{ fontSize: 11 }}>{pc}</div>
        </div>
        <button className="btn ghost icon-btn" onClick={onSettings} aria-label="Connection settings" title="Connection settings"><Gear size={16} /></button>
        <button className="btn sm" onClick={onLogout}><LogOut size={14} /> Log out</button>
      </header>

      <div className={`conn ${online ? 'ok' : 'wait'}`} role="status">
        {online ? <span className="dot online" /> : <span className="pulse" />}
        {online ? 'Connected to your instructor' : conn.detail || 'Reconnecting…'}
      </div>
      {viewed && <div className="notice"><Eye size={15} /> Your instructor is viewing your screen</div>}

      <section className="card share">
        {share === 'on' ? (
          <>
            <div className="share-live"><MonitorUp size={18} /> You are presenting to the class</div>
            <p className="muted">Everyone in the class sees your screen now.</p>
            <button className="btn danger big" onClick={() => onShare(false)}><Square size={14} /> Stop sharing</button>
          </>
        ) : share === 'waiting' ? (
          <>
            <div className="share-wait"><span className="pulse" /> Waiting for your instructor to allow it…</div>
            <button className="btn big" onClick={() => onShare(false)}>Cancel request</button>
          </>
        ) : (
          <>
            <div style={{ fontWeight: 600 }}>Show your work to the class</div>
            <p className="muted">Your instructor decides. Once they allow it, your screen appears on every PC.</p>
            <button className="btn primary big" disabled={!online} onClick={() => onShare(true)}><Hand size={16} /> Ask to share my screen</button>
          </>
        )}
      </section>

      <section className="card chat">
        <div className="chat-title">Messages with your instructor</div>
        <div className="chat-list" ref={list}>
          {chat.length === 0 && <div className="muted" style={{ fontSize: 13 }}>Need help? Write to your instructor. Their messages also appear here.</div>}
          {chat.map((m) => (
            <div key={m.id} className={`bubble ${m.from === 'me' ? 'me' : 'student'}`}>
              {m.from === 'teacher' && <b className="from">Instructor</b>}
              <span>{m.text}</span>
              <time>{new Date(m.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</time>
            </div>
          ))}
        </div>
        <form className="reply" onSubmit={submit}>
          <input className="input" value={text} maxLength={1000} onChange={(e) => setText(e.target.value)} placeholder={online ? 'Message your instructor…' : 'Waiting for the connection…'} disabled={!online} aria-label="Message" />
          <button className="btn primary icon-btn" style={{ width: 40 }} disabled={!online || !text.trim()} aria-label="Send"><Send size={15} /></button>
        </form>
      </section>
    </div>
  );
}
