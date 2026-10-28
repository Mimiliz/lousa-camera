const { app, BrowserWindow, ipcMain, desktopCapturer, shell, screen } = require('electron');
const path = require('path');
const fs = require('fs');

let win = null;
let normalBounds = null;
let recFile = null;

if (!app.requestSingleInstanceLock()) { app.quit(); }
app.setAsDefaultProtocolClient('mimisala');
const linkFrom = argv => (argv || []).find(a => typeof a === 'string' && a.startsWith('mimisala://'));

app.on('second-instance', (e, argv) => {
  const l = linkFrom(argv);
  if (win) { if (win.isMinimized()) win.restore(); win.focus(); if (l) win.webContents.send('link', l); }
});

app.whenReady().then(() => {
  win = new BrowserWindow({
    width: 1280, height: 800, minWidth: 320, minHeight: 360,
    backgroundColor: '#0d0f13', autoHideMenuBar: true, title: 'Sala Mimi Explica',
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, backgroundThrottling: false }
  });
  win.webContents.session.setPermissionRequestHandler((wc, perm, cb) => cb(['media', 'notifications', 'fullscreen', 'clipboard-sanitized-write'].includes(perm)));
  win.webContents.session.setPermissionCheckHandler(() => true);
  const srv = process.env.SALA_SRV;
  win.loadFile('index.html', srv ? { query: { srv } } : undefined);
  const l = linkFrom(process.argv);
  if (l) win.webContents.once('did-finish-load', () => win.webContents.send('link', l));
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
});

app.on('window-all-closed', () => app.quit());

// telas e janelas que podem ser compartilhadas
ipcMain.handle('sources', async () => {
  const list = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: { width: 320, height: 180 } });
  const screens = list.filter(s => s.id.startsWith('screen'));
  return list
    .filter(s => !s.name.includes('Sala Mimi Explica'))
    .map(s => {
      const isScreen = s.id.startsWith('screen');
      const n = screens.indexOf(s) + 1;
      return { id: s.id, screen: isScreen, thumb: s.thumbnail.toDataURL(),
        name: isScreen ? (screens.length > 1 ? 'Tela inteira (monitor ' + n + ')' : 'Tela inteira') : s.name };
    });
});

// a tela onde está a janela da sala (para a gravação)
ipcMain.handle('myScreen', async () => {
  const d = screen.getDisplayMatching(win.getBounds());
  const list = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } });
  const s = list.find(x => String(x.display_id) === String(d.id)) || list[0];
  return s ? { id: s.id, width: Math.round(d.size.width * d.scaleFactor), height: Math.round(d.size.height * d.scaleFactor) } : null;
});

// aluno: o Windows deixa esta janela preta em prints e gravações de tela
ipcMain.handle('protect', (e, on) => { if (win) win.setContentProtection(!!on); });

// janela pequena por cima das outras (para usar outros programas durante a aula)
ipcMain.handle('mini', (e, on) => {
  if (!win) return;
  if (on) {
    normalBounds = win.getBounds();
    const wa = screen.getDisplayMatching(normalBounds).workArea;
    win.setAlwaysOnTop(true, 'floating');
    win.setBounds({ x: wa.x + wa.width - 360, y: wa.y + wa.height - 500, width: 340, height: 480 });
  } else {
    win.setAlwaysOnTop(false);
    if (normalBounds) win.setBounds(normalBounds);
  }
});

// gravação: os pedaços do vídeo vão direto para o disco
ipcMain.handle('recStart', (e, name) => {
  const dir = path.join(app.getPath('videos'), 'SalaMimi');
  fs.mkdirSync(dir, { recursive: true });
  recFile = path.join(dir, name);
  fs.writeFileSync(recFile, Buffer.alloc(0));
  return recFile;
});
ipcMain.handle('recChunk', (e, buf) => { if (recFile) fs.appendFileSync(recFile, Buffer.from(buf)); });
ipcMain.handle('recEnd', () => { const f = recFile; recFile = null; if (f) shell.showItemInFolder(f); return f; });
ipcMain.handle('open', (e, u) => shell.openExternal(u));
