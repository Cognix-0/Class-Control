import { useCallback, useEffect, useState } from 'react';
import Login from './Login.jsx';
import Home from './Home.jsx';
import Settings from './Settings.jsx';
import Toasts from '../components/Toasts.jsx';

let seq = 0;
const BUSY = new Set(['searching', 'connecting', 'offline']);

export default function StudentApp() {
  const [info, setInfo] = useState({ pc: '' });
  const [settings, setSettings] = useState(null);
  const [conn, setConn] = useState({ state: 'idle', detail: '' }); // last STATE from the agent
  const [signedIn, setSignedIn] = useState(false);                // reached "loggedin" since the last sign-in
  const [regNo, setRegNo] = useState('');
  const [chat, setChat] = useState([]);                            // { id, from: 'teacher'|'me', text, at }
  const [share, setShare] = useState('off');                       // off | waiting | on
  const [viewed, setViewed] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [toasts, setToasts] = useState([]);

  const toast = useCallback((msg, level = 'ok') => {
    const id = ++seq;
    setToasts((t) => [...t.slice(-2), { id, msg, level }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), level === 'error' ? 6000 : 3500);
  }, []);

  useEffect(() => {
    window.student.info().then((r) => r.ok && setInfo(r.value));
    window.student.getSettings().then((r) => { if (r.ok) { setSettings(r.value); setRegNo(r.value.regNo ?? ''); } });
    return window.student.onEvent((e) => {
      if (e.type === 'state') {
        setConn({ state: e.state, detail: e.detail });
        if (e.state === 'loggedin') setSignedIn(true);
        if (['badlogin', 'rejected', 'loggedout', 'fatal'].includes(e.state)) { setSignedIn(false); setShare('off'); setViewed(false); }
        if (BUSY.has(e.state)) { setShare((s) => (s === 'on' || s === 'waiting' ? 'off' : s)); setViewed(false); } // a new connection forgets requests
      } else if (e.type === 'msg') {
        setChat((c) => [...c.slice(-300), { id: ++seq, from: 'teacher', text: e.text, at: e.at }]);
      } else if (e.type === 'sharing') {
        if (e.value === '1') { setShare('on'); toast('Your screen is now on the class screens'); }
        else if (e.value === 'deny') { setShare('off'); toast('Your instructor said not now', 'warn'); }
        else setShare('off');
      } else if (e.type === 'viewed') setViewed(e.on);
    });
  }, [toast]);

  async function login(reg, password) {
    const r = await window.student.login(reg, password);
    if (!r.ok) return r.error;
    setRegNo(reg);
    setConn({ state: 'connecting', detail: 'Connecting to your instructor…' });
    return null;
  }

  async function logout() {
    await window.student.logout();
    setSignedIn(false); setShare('off'); setViewed(false); setChat([]);
  }

  async function sendChat(text) {
    const r = await window.student.chat(text);
    if (!r.ok) { toast(r.error, 'error'); return false; }
    setChat((c) => [...c, { id: ++seq, from: 'me', text, at: Date.now() }]);
    return true;
  }

  async function askShare(on) {
    const r = await window.student.share(on);
    if (!r.ok) return toast(r.error, 'error');
    setShare(on ? 'waiting' : 'off');
  }

  async function saveSettings(s) {
    const r = await window.student.saveSettings(s);
    if (!r.ok) return r.error;
    setSettings(r.value);
    setSettingsOpen(false);
    toast(signedIn ? 'Saved. Reconnecting with the new settings…' : 'Settings saved');
    return null;
  }

  return (
    <div className="student">
      {signedIn
        ? <Home regNo={regNo} pc={info.pc} conn={conn} chat={chat} share={share} viewed={viewed}
            onChat={sendChat} onShare={askShare} onLogout={logout} onSettings={() => setSettingsOpen(true)} />
        : settings && <Login regNo={regNo} conn={conn} settings={settings} onLogin={login} onCancel={logout} onSettings={() => setSettingsOpen(true)} />}
      {settingsOpen && settings && <Settings value={settings} defaults={info.defaults} onSave={saveSettings} onClose={() => setSettingsOpen(false)} />}
      <Toasts items={toasts} />
    </div>
  );
}
