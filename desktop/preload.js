const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('sala', {
  sources: () => ipcRenderer.invoke('sources'),
  myScreen: () => ipcRenderer.invoke('myScreen'),
  protect: on => ipcRenderer.invoke('protect', on),
  mini: on => ipcRenderer.invoke('mini', on),
  recStart: name => ipcRenderer.invoke('recStart', name),
  recChunk: buf => ipcRenderer.invoke('recChunk', buf),
  recEnd: () => ipcRenderer.invoke('recEnd'),
  open: u => ipcRenderer.invoke('open', u),
  onLink: cb => ipcRenderer.on('link', (e, l) => cb(l))
});
