// The only bridge between the student UI and the class agent.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('student', {
  info: () => ipcRenderer.invoke('student:info'),
  getSettings: () => ipcRenderer.invoke('student:settings'),
  saveSettings: (s) => ipcRenderer.invoke('student:saveSettings', s),
  login: (regNo, password) => ipcRenderer.invoke('student:login', regNo, password),
  logout: () => ipcRenderer.invoke('student:logout'),
  chat: (text) => ipcRenderer.invoke('student:chat', text),
  share: (on) => ipcRenderer.invoke('student:share', on),
  onEvent: (cb) => {
    const f = (_e, ev) => cb(ev);
    ipcRenderer.on('student:event', f);
    return () => ipcRenderer.off('student:event', f);
  },
});
