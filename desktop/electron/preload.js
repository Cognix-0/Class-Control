// The only bridge between the UI and the lab server.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('lab', {
  start: (token, port) => ipcRenderer.invoke('lab:start', token, port),
  demo: () => ipcRenderer.invoke('lab:demo'),
  info: () => ipcRenderer.invoke('lab:info'),
  list: () => ipcRenderer.invoke('lab:list'),
  send: (ids, cmd, arg) => ipcRenderer.invoke('lab:send', ids, cmd, arg),
  remote: (id, cmd, arg) => ipcRenderer.invoke('lab:remote', id, cmd, arg),
  whiteboard: (arg) => ipcRenderer.invoke('lab:wb', arg),
  setLive: (on) => ipcRenderer.invoke('lab:live', on),
  present: (id, mode) => ipcRenderer.invoke('lab:present', id, mode),
  forget: (reg) => ipcRenderer.invoke('lab:forget', reg),
  getLayout: () => ipcRenderer.invoke('layout:get'),
  saveLayout: (l) => ipcRenderer.invoke('layout:save', l),
  onEvent: (cb) => {
    const f = (_e, ev) => cb(ev);
    ipcRenderer.on('lab:event', f);
    return () => ipcRenderer.off('lab:event', f);
  },
});
