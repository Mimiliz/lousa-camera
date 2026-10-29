(() => {
'use strict';
const $ = s => document.querySelector(s);
const api = window.sala || {};   // ponte com o Windows (preload.js)
const SITE = 'https://mimiliz.github.io/lousa-camera/';
const q = new URLSearchParams(location.search);
const srv = q.get('srv');        // só para testes: servidor de sinalização próprio
const peerOpts = srv ? {host: srv.split(':')[0], port: +(srv.split(':')[1] || 9000), path: '/', secure: false} : {};
const store = {
  get: (k, d) => { try{ const v = localStorage.getItem('sala.' + k); return v == null ? d : v; }catch(e){ return d; } },
  set: (k, v) => { try{ localStorage.setItem('sala.' + k, v); }catch(e){} }
};

const ICON = {
  mic: 'M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3',
  micOff: 'M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3 M3 3l18 18',
  cam: 'M5 5h9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M16 10l5-3v10l-5-3',
  camOff: 'M5 5h9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M16 10l5-3v10l-5-3 M2 2l20 20',
  trocar: 'M4 7h11l-3-3 M20 17H9l3 3 M15 4l3 3-3 3 M9 20l-3-3 3-3',
  tela: 'M4 4h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M8 20h8 M12 16v4 M9 10l3-3 3 3 M12 7v6',
  gravar: 'M12 4a8 8 0 1 1 0 16a8 8 0 1 1 0-16z',
  convidar: 'M9 11a4 4 0 1 0 0-8a4 4 0 0 0 0 8z M2 21v-1a6 6 0 0 1 12 0v1 M19 8v6M16 11h6',
  mini: 'M3 5h18v14H3z M12 12h7v5h-7z',
  chat: 'M4 5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9l-5 4V6a1 1 0 0 1 1-1z M8 10h8 M8 13h5',
  micSel: 'M9 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M3 10a6 6 0 0 0 12 0 M9 16v4 M17 8l3 3-3 3 M20 11h-5',
  alunoMudo: 'M9 11a4 4 0 1 0 0-8a4 4 0 0 0 0 8z M2 21v-1a6 6 0 0 1 12 0v1 M16 9l5 5M21 9l-5 5',
  sair: 'M10 17l5-5-5-5 M15 12H3 M14 3h5a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-5'
};
const svg = d => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="${d}"/></svg>`;

let toastT = null;
function toast(t){ const e = $('#toast'); e.textContent = t; e.hidden = false; clearTimeout(toastT); toastT = setTimeout(() => e.hidden = true, 3500); }
const rnd = () => Math.random().toString(36).slice(2, 12);
function newCode(){ const abc = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; let s = ''; for (let i = 0; i < 6; i++) s += abc[Math.floor(Math.random() * abc.length)]; return s; }
// cada aula ganha um código novo: quem tem o código de uma aula antiga não entra na seguinte
function roomCode(){ return newCode(); }

/* ================= estado da aula ================= */
let papel = null, sala = '', peer = null, local = null;
let camCall = null, screenCall = null, otherPeer = null, otherVia = '';
let remoteCam = null, remoteScreen = null, myScreen = null;
let inCall = false, leaving = false, lastDial = 0;
let recorder = null, recScreen = null, recCtx = null, recDest = null, recStart = 0, recTimer = null, remoteAudioNode = null;
let miniOn = false;
let teacherMuted = false;     // aluno: o professor desligou meu microfone
let studentMuted = false;     // professor: eu desliguei o microfone do aluno
let studentMicOn = null;      // professor: o aluno está com o microfone ligado?
let localMicNode = null;

/* recados entre professor e aluno (passam pelo mesmo servidor de encontro) */
function sendCtlTo(dst, obj){
  try{ if (peer && peer.open && dst) peer.socket.send({type: 'CANDIDATE', dst, payload: {type: 'media', connectionId: 'ctl', ctl: obj}}); }catch(e){}
}
function sendCtl(obj){ sendCtlTo(otherPeer, obj); }
function sendMicState(){ const t = local && local.getAudioTracks()[0]; sendCtl({micOn: !!(t && t.enabled)}); }
function onCtl(src, c){
  if (src !== otherPeer) return;
  if (typeof c.chat === 'string'){ addMsg(c.chat.slice(0, 2000), false); return; }
  if (papel === 'aluno' && c.busy){ roomBusy(); return; }
  if (papel === 'aluno' && typeof c.mute === 'boolean'){
    teacherMuted = c.mute;
    const t = local && local.getAudioTracks()[0]; if (t) t.enabled = !c.mute;
    paintMic(); sendMicState();
    toast(c.mute ? 'O professor desligou o seu microfone' : 'O professor liberou o seu microfone');
  }
  if (papel === 'professor' && typeof c.micOn === 'boolean'){ studentMicOn = c.micOn; paintStudentMic(); }
}
function paintMic(){
  const t = local && local.getAudioTracks()[0], on = !!(t && t.enabled);
  if (teacherMuted) setBtn('mic', 'Mutado pelo professor', 'off', ICON.micOff);
  else setBtn('mic', on ? 'Microfone' : 'Mudo', on ? '' : 'off', on ? ICON.mic : ICON.micOff);
}
function paintStudentMic(){
  setBtn('mutar', studentMuted ? 'Liberar aluno' : 'Mutar aluno', studentMuted ? 'off' : '');
  if (otherPeer && remoteCam) setStatus(statusAluno() + (studentMuted ? ' · microfone do aluno desligado por você' : studentMicOn === false ? ' · aluno com microfone desligado' : ''));
}
function applyStudentMute(){
  if (remoteCam) remoteCam.getAudioTracks().forEach(t => t.enabled = !studentMuted);   // silêncio do seu lado e na gravação
}

/* ================= início ================= */
$('#codeIn').value = store.get('codigoAluno', '');
$('#profBtn').onclick = () => start('professor', roomCode());
$('#alunoBtn').onclick = () => {
  const c = $('#codeIn').value.toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (c.length < 4){ $('#homeErr').hidden = false; $('#homeErr').textContent = 'Digite o código completo.'; return; }
  store.set('codigoAluno', c); start('aluno', c);
};
$('#codeIn').addEventListener('keydown', e => { if (e.key === 'Enter') $('#alunoBtn').click(); });
if (api.onLink) api.onLink(l => {
  try{
    const c = (new URL(l).searchParams.get('sala') || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    if (c.length >= 4 && !inCall){ store.set('codigoAluno', c); $('#codeIn').value = c; start('aluno', c); }
  }catch(e){}
});

async function start(p, code){
  papel = p; sala = code;
  try{
    local = await navigator.mediaDevices.getUserMedia({
      video: camConstraints(store.get('camera', '')),
      audio: Object.assign({echoCancellation: true, noiseSuppression: true, autoGainControl: true}, store.get('mic', '') ? {deviceId: {ideal: store.get('mic', '')}} : {})
    });
  }catch(e){
    try{ local = await navigator.mediaDevices.getUserMedia({audio: true, video: false}); toast('Câmera não encontrada: entrando só com o microfone'); }
    catch(e2){ $('#homeErr').hidden = false; $('#homeErr').textContent = 'O Windows não liberou a câmera e o microfone. Confira em Configurações › Privacidade.'; return; }
  }
  if (papel === 'aluno' && api.protect) api.protect(true);   // print e gravação de tela ficam pretos
  if (papel === 'professor' && api.protect) api.protect(false);
  inCall = true; leaving = false; busy = false; clearChat(); teacherMuted = false; studentMuted = false; studentMicOn = null;
  $('#home').hidden = true; $('#call').hidden = false;
  $('#me').srcObject = local;
  buildBar(); layout();
  connect();
  if (papel === 'professor') setTimeout(invite, 500);
}
function camConstraints(id){ return id ? {deviceId: {exact: id}, width: {ideal: 1280}, height: {ideal: 720}} : {width: {ideal: 1280}, height: {ideal: 720}}; }
function setStatus(t){ $('#status').textContent = t; }
const statusAluno = () => otherVia === 'app' ? 'Aluno na aula · pelo app Android (print e gravação bloqueados)'
  : otherVia === 'computador' ? 'Aluno na aula · pelo programa no computador (print e gravação bloqueados)'
  : 'Aluno na aula · pelo navegador (sem bloqueio de print)';

/* ================= conexão ================= */
function connect(){
  const id = papel === 'professor' ? 'mimisala-' + sala : 'mimialuno-' + rnd();
  setStatus(papel === 'professor' ? 'Abrindo a sala ' + sala + '…' : 'Conectando…');
  peer = new Peer(id, peerOpts);
  peer.on('open', () => {
    if (papel === 'professor') setStatus(otherPeer ? statusAluno() : 'Sala ' + sala + ' aberta. Convide o aluno.');
    else dial();
  });
  peer.on('disconnected', () => { if (!leaving){ setStatus('Sem internet. Tentando de novo…'); setTimeout(() => { try{ peer.reconnect(); }catch(e){} }, 2000); } });
  peer.on('error', e => {
    if (leaving) return;
    if (e.type === 'unavailable-id'){ setStatus('Abrindo a sala…'); setTimeout(() => { if (!leaving){ try{ peer.destroy(); }catch(_){} connect(); } }, 4000); }
    else if (e.type === 'peer-unavailable'){ setStatus('O professor ainda não abriu a sala…'); setTimeout(dial, 3000); }
    else if (e.type === 'network' || e.type === 'server-error' || e.type === 'socket-error'){ setStatus('Sem internet. Tentando de novo…'); }
  });
  peer.on('call', onIncoming);
  peer.socket.on('message', m => { if (m && m.type === 'CANDIDATE' && m.payload && m.payload.ctl) onCtl(m.src, m.payload.ctl); });
}

function onIncoming(call){
  const meta = call.metadata || {};
  const kind = meta.kind || 'cam';
  if (kind === 'screen'){
    if (call.peer !== otherPeer){ try{ call.close(); }catch(e){} return; }
    call.answer();
    call.on('stream', s => { remoteScreen = s; watchEnd(s, () => { if (remoteScreen === s){ remoteScreen = null; layout(); } }); layout(); });
    call.on('close', () => { remoteScreen = null; layout(); });
    return;
  }
  if (papel !== 'professor') return;
  if (camCall && remoteCam && call.peer !== otherPeer){
    // já tem um aluno na aula: o novo é barrado
    sendCtlTo(call.peer, {busy: true});
    setTimeout(() => { try{ call.close(); }catch(e){} }, 500);
    toast('Outra pessoa tentou entrar e foi barrada: a sala é de um aluno por vez.');
    return;
  }
  if (camCall){ try{ camCall.close(); }catch(e){} }
  if (call.peer !== otherPeer) clearChat();
  otherPeer = call.peer; otherVia = meta.via || 'navegador';
  camCall = call;
  call.answer(local);
  call.on('stream', s => { remoteCam = s; layout(); applyStudentMute(); setStatus(statusAluno()); hookRemoteAudio(); sendCtl({mute: studentMuted}); paintStudentMic(); });
  call.on('close', () => { if (camCall === call) otherLeft(); });
  if (myScreen) setTimeout(startScreenCall, 1500);
}

function watchEnd(s, done){
  const t = s.getVideoTracks()[0]; if (!t) return;
  t.addEventListener('ended', done);
  t.addEventListener('mute', () => setTimeout(() => { if (t.muted) done(); }, 2500));
}

let busy = false;
function roomBusy(){
  busy = true;
  const old = camCall; camCall = null; if (old){ try{ old.close(); }catch(e){} }
  remoteCam = null; remoteScreen = null; layout();
  setStatus('A sala está ocupada');
  $('#wait').textContent = 'O professor já está em aula com outro aluno.\nConfira se o código está certo ou peça um código novo.';
  $('#wait').hidden = false;
}
function dial(){
  if (!inCall || papel !== 'aluno' || !peer || !peer.open || busy) return;
  const now = Date.now(); if (now - lastDial < 2500) return; lastDial = now;
  const old = camCall; camCall = null; if (old){ try{ old.close(); }catch(e){} }
  otherPeer = 'mimisala-' + sala;
  const c = peer.call(otherPeer, local, {metadata: {kind: 'cam', via: 'computador'}});
  if (!c) return;
  camCall = c;
  c.on('stream', s => { remoteCam = s; layout(); setStatus('Na aula com o professor'); sendMicState(); });
  c.on('close', () => { if (camCall === c){ camCall = null; remoteCam = null; remoteScreen = null; layout(); if (inCall && !leaving){ setStatus('A conexão caiu. Reconectando…'); setTimeout(dial, 3000); } } });
  setTimeout(() => { if (camCall === c && !remoteCam && inCall){ setStatus('O professor ainda não abriu a sala…'); dial(); } }, 10000);
  if (myScreen) setTimeout(startScreenCall, 1500);
}

function otherLeft(){
  studentMicOn = null;
  camCall = null; remoteCam = null; remoteScreen = null;
  if (screenCall){ try{ screenCall.close(); }catch(e){} screenCall = null; }
  otherPeer = null; layout();
  if (papel === 'professor') setStatus('O aluno saiu. A sala ' + sala + ' continua aberta.');
}

/* ================= vídeo na tela ================= */
function layout(){
  const big = $('#big'), small = $('#small');
  const b = remoteScreen || remoteCam;
  const s = remoteScreen ? remoteCam : null;
  if (big.srcObject !== b){ big.srcObject = b; big.play().catch(() => {}); }
  big.className = remoteScreen ? '' : 'cam';
  small.hidden = !s; if (s && small.srcObject !== s){ small.srcObject = s; small.play().catch(() => {}); }
  $('#wait').hidden = !!b;
  $('#wait').textContent = papel === 'professor' ? 'Esperando o aluno entrar…\nCódigo da sala: ' + sala : 'Esperando o professor…';
}

/* ================= botões ================= */
function buildBar(){
  const bar = $('#bar'); bar.innerHTML = '';
  const add = (key, icon, label, fn, cls) => {
    const b = document.createElement('button'); b.id = 'b-' + key; if (cls) b.className = cls;
    b.innerHTML = `<span class="ic">${svg(icon)}</span><span class="lb">${label}</span>`;
    b.onclick = fn; bar.append(b); return b;
  };
  add('mic', ICON.mic, 'Microfone', toggleMic);
  add('cam', ICON.cam, 'Câmera', toggleCam);
  add('trocar', ICON.trocar, 'Trocar câmera', chooseCamera);
  add('micsel', ICON.micSel, 'Trocar microfone', chooseMic);
  add('tela', ICON.tela, 'Compartilhar tela', toggleShare);
  if (papel === 'professor'){
    add('mutar', ICON.alunoMudo, 'Mutar aluno', toggleStudentMute);
    add('gravar', ICON.gravar, 'Gravar aula', toggleRecord);
    add('convidar', ICON.convidar, 'Convidar', invite);
  }
  add('chat', ICON.chat, 'Chat', toggleChat, 'keep');
  add('mini', ICON.mini, 'Janela pequena', toggleMini, 'keep');
  add('sair', ICON.sair, 'Sair', confirmLeave, 'danger');
}
function setBtn(key, label, cls, icon){
  const b = $('#b-' + key); if (!b) return;
  b.querySelector('.lb').textContent = label;
  b.classList.remove('on', 'off'); if (cls) b.classList.add(cls);
  if (icon) b.querySelector('.ic').innerHTML = svg(icon);
}

function toggleMic(){
  if (teacherMuted){ toast('O professor desligou o seu microfone. Ele precisa liberar para você falar.'); return; }
  const t = local.getAudioTracks()[0]; if (!t) return; t.enabled = !t.enabled;
  paintMic(); sendMicState();
}
function toggleStudentMute(){
  if (!otherPeer || !remoteCam){ toast('Nenhum aluno na sala agora'); return; }
  studentMuted = !studentMuted;
  applyStudentMute(); sendCtl({mute: studentMuted}); paintStudentMic();
  toast(studentMuted ? 'Microfone do aluno desligado' : 'Microfone do aluno liberado');
}
async function chooseMic(){
  const devs = (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === 'audioinput');
  if (!devs.length){ toast('Nenhum microfone encontrado'); return; }
  const at = local.getAudioTracks()[0];
  const cur = at ? at.getSettings().deviceId : '';
  const box = modal(`<h3>Escolher microfone</h3><select id="micSel">${devs.map((d, i) => `<option value="${d.deviceId}" ${d.deviceId === cur ? 'selected' : ''}>${esc(d.label || 'Microfone ' + (i + 1))}</option>`).join('')}</select>
    <div class="row"><button id="mCancel">Cancelar</button><button class="go" id="mOk">Usar este</button></div>`);
  box.querySelector('#mCancel').onclick = closeModal;
  box.querySelector('#mOk').onclick = async () => {
    const id = box.querySelector('#micSel').value; closeModal();
    try{
      const ns = await navigator.mediaDevices.getUserMedia({audio: {deviceId: {exact: id}, echoCancellation: true, noiseSuppression: true, autoGainControl: true}});
      const nt = ns.getAudioTracks()[0], old = local.getAudioTracks()[0];
      nt.enabled = teacherMuted ? false : (old ? old.enabled : true);
      if (old){ local.removeTrack(old); old.stop(); }
      local.addTrack(nt); store.set('mic', id);
      const snd = camCall && camCall.peerConnection && camCall.peerConnection.getSenders().find(x => x.track && x.track.kind === 'audio');
      if (snd) await snd.replaceTrack(nt);
      hookLocalMic();
      toast('Microfone trocado');
    }catch(e){ toast('Não consegui usar esse microfone'); }
  };
}
function toggleCam(){
  const t = local.getVideoTracks()[0]; if (!t) return; t.enabled = !t.enabled;
  setBtn('cam', t.enabled ? 'Câmera' : 'Câmera desligada', t.enabled ? '' : 'off', t.enabled ? ICON.cam : ICON.camOff);
}

async function chooseCamera(){
  const devs = (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === 'videoinput');
  if (!devs.length){ toast('Nenhuma câmera encontrada'); return; }
  const cur = (local.getVideoTracks()[0] || {}).getSettings ? local.getVideoTracks()[0].getSettings().deviceId : '';
  const box = modal(`<h3>Escolher câmera</h3><select id="camSel">${devs.map((d, i) => `<option value="${d.deviceId}" ${d.deviceId === cur ? 'selected' : ''}>${esc(d.label || 'Câmera ' + (i + 1))}</option>`).join('')}</select>
    <div class="row"><button id="mCancel">Cancelar</button><button class="go" id="mOk">Usar esta</button></div>`);
  box.querySelector('#mCancel').onclick = closeModal;
  box.querySelector('#mOk').onclick = async () => {
    const id = box.querySelector('#camSel').value; closeModal();
    try{
      const ns = await navigator.mediaDevices.getUserMedia({video: camConstraints(id)});
      const nt = ns.getVideoTracks()[0], old = local.getVideoTracks()[0];
      if (old){ nt.enabled = old.enabled; local.removeTrack(old); old.stop(); }
      local.addTrack(nt); $('#me').srcObject = local; store.set('camera', id);
      const snd = camCall && camCall.peerConnection && camCall.peerConnection.getSenders().find(x => x.track && x.track.kind === 'video');
      if (snd) await snd.replaceTrack(nt);
    }catch(e){ toast('Não consegui abrir essa câmera'); }
  };
}
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));

/* ================= compartilhar tela ================= */
async function pickSource(){
  if (!api.sources) return null;
  const list = await api.sources();
  return new Promise(res => {
    const box = modal(`<h3>O que você quer mostrar?</h3><p>Escolha a tela inteira ou um programa.</p>
      <div class="grid">${list.map((s, i) => `<button class="src" data-i="${i}"><img src="${s.thumb}" alt=""><span>${esc(s.name)}</span></button>`).join('')}</div>
      <div class="row"><button id="mCancel">Cancelar</button></div>`);
    box.querySelectorAll('.src').forEach(b => b.onclick = () => { closeModal(); res(list[+b.dataset.i]); });
    box.querySelector('#mCancel').onclick = () => { closeModal(); res(null); };
  });
}
function desktopStream(id, w, h, fps){
  if (window.__fakeDesktop) return window.__fakeDesktop(id);
  return navigator.mediaDevices.getUserMedia({audio: false, video: {mandatory: {
    chromeMediaSource: 'desktop', chromeMediaSourceId: id, maxWidth: w, maxHeight: h, maxFrameRate: fps}}});
}
async function toggleShare(){
  if (myScreen){ stopShare(); return; }
  const src = await pickSource(); if (!src) return;
  try{ myScreen = await desktopStream(src.id, 1920, 1080, 15); }catch(e){ toast('Não consegui capturar essa tela'); return; }
  myScreen.getVideoTracks()[0].addEventListener('ended', stopShare);
  setBtn('tela', 'Parar de mostrar', 'on');
  startScreenCall();
  toast('Sua tela está sendo mostrada. Use “Janela pequena” para trabalhar nos outros programas.');
}
function startScreenCall(){
  if (!myScreen || !otherPeer || !camCall || !peer) return;
  if (screenCall){ try{ screenCall.close(); }catch(e){} }
  screenCall = peer.call(otherPeer, myScreen, {metadata: {kind: 'screen'}});
}
function stopShare(){
  if (screenCall){ try{ screenCall.close(); }catch(e){} screenCall = null; }
  if (myScreen){ myScreen.getTracks().forEach(t => t.stop()); myScreen = null; }
  setBtn('tela', 'Compartilhar tela', '');
}

/* ================= chat ================= */
let unread = 0;
const hhmm = () => { const d = new Date(); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); };
function addMsg(text, mine){
  const box = $('#msgs'); const e = box.querySelector('.empty'); if (e) e.remove();
  const m = document.createElement('div'); m.className = 'msg' + (mine ? ' mine' : '');
  const who = mine ? 'Você' : (papel === 'professor' ? 'Aluno' : 'Professor');
  m.innerHTML = '<small></small>'; m.querySelector('small').textContent = who + ' · ' + hhmm();
  m.append(document.createTextNode(text));
  box.append(m); box.scrollTop = box.scrollHeight;
  if (!mine && ($('#chat').hidden || document.body.classList.contains('mini-mode'))){ unread++; paintUnread(); toast((papel === 'professor' ? 'Aluno: ' : 'Professor: ') + text.slice(0, 80)); }
}
function paintUnread(){
  const b = $('#b-chat'); if (!b) return;
  let bd = b.querySelector('.badge');
  if (!unread){ if (bd) bd.remove(); return; }
  if (!bd){ bd = document.createElement('span'); bd.className = 'badge'; b.append(bd); }
  bd.textContent = unread > 9 ? '9+' : unread;
}
function clearChat(){ $('#msgs').innerHTML = '<div class="empty">As mensagens desta aula aparecem aqui.</div>'; unread = 0; paintUnread(); }
function toggleChat(){
  if (miniOn) toggleMini();
  const open = $('#chat').hidden;
  $('#chat').hidden = !open; setBtn('chat', 'Chat', open ? 'on' : '');
  if (open){ unread = 0; paintUnread(); setTimeout(() => $('#chatIn').focus(), 50); }
}
$('#chatClose').onclick = toggleChat;
$('#chatForm').addEventListener('submit', e => {
  e.preventDefault();
  const t = $('#chatIn').value.trim(); if (!t) return;
  if (!otherPeer || !remoteCam){ toast(papel === 'professor' ? 'Nenhum aluno na sala agora' : 'Você ainda não está conectado ao professor'); return; }
  sendCtl({chat: t.slice(0, 2000)}); addMsg(t, true); $('#chatIn').value = '';
});
$('#chatIn').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey){ e.preventDefault(); $('#chatForm').requestSubmit(); } });

function toggleMini(){
  miniOn = !miniOn;
  document.body.classList.toggle('mini-mode', miniOn);
  if (api.mini) api.mini(miniOn);
  setBtn('mini', miniOn ? 'Janela normal' : 'Janela pequena', miniOn ? 'on' : '');
}

/* ================= gravar (só o professor) ================= */
function hookLocalMic(){
  if (!recCtx || !recDest || !local) return;
  try{ if (localMicNode) localMicNode.disconnect(); }catch(e){}
  localMicNode = null;
  if (local.getAudioTracks().length){ localMicNode = recCtx.createMediaStreamSource(new MediaStream(local.getAudioTracks())); localMicNode.connect(recDest); }
}
function hookRemoteAudio(){
  if (!recCtx || !recDest || !remoteCam || !remoteCam.getAudioTracks().length) return;
  try{ if (remoteAudioNode) remoteAudioNode.disconnect(); }catch(e){}
  remoteAudioNode = recCtx.createMediaStreamSource(new MediaStream(remoteCam.getAudioTracks()));
  remoteAudioNode.connect(recDest);
}
async function toggleRecord(){
  if (recorder){ stopRecord(); return; }
  const scr = api.myScreen ? await api.myScreen() : null;
  try{
    recScreen = await desktopStream(scr ? scr.id : 'screen:0:0', Math.min(scr ? scr.width : 1920, 1920), Math.min(scr ? scr.height : 1080, 1080), 30);
  }catch(e){ toast('Não consegui gravar a tela'); return; }
  recCtx = new AudioContext({sampleRate: 48000});
  recDest = recCtx.createMediaStreamDestination();
  hookLocalMic();
  hookRemoteAudio();
  const types = ['video/mp4;codecs=avc1,mp4a.40.2', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm'];
  const mime = types.find(t => MediaRecorder.isTypeSupported(t)) || '';
  const ext = mime.includes('mp4') ? 'mp4' : 'webm';
  const d = new Date(), p = n => String(n).padStart(2, '0');
  const name = `aula-${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}-${p(d.getHours())}h${p(d.getMinutes())}.${ext}`;
  if (api.recStart) await api.recStart(name);
  const stream = new MediaStream([...recScreen.getVideoTracks(), ...recDest.stream.getAudioTracks()]);
  recorder = new MediaRecorder(stream, mime ? {mimeType: mime, videoBitsPerSecond: 6000000, audioBitsPerSecond: 128000} : undefined);
  let chain = Promise.resolve();
  recorder.ondataavailable = e => { if (e.data && e.data.size && api.recChunk){ const b = e.data; chain = chain.then(async () => api.recChunk(await b.arrayBuffer())); } };
  recorder.onstop = () => { chain.then(async () => {
    const f = api.recEnd ? await api.recEnd() : null;
    toast(f ? 'Aula gravada. O vídeo está na pasta Vídeos › SalaMimi.' : 'Gravação encerrada');
  }); };
  recorder.start(1000);
  recStart = Date.now();
  setBtn('gravar', 'Parar gravação', 'on');
  $('#recTag').hidden = false;
  recTimer = setInterval(() => { const s = Math.floor((Date.now() - recStart) / 1000); $('#recTag').textContent = `● GRAVANDO ${p(Math.floor(s / 60))}:${p(s % 60)}`; }, 500);
}
function stopRecord(){
  if (!recorder) return;
  try{ recorder.stop(); }catch(e){}
  recorder = null;
  if (recScreen){ recScreen.getTracks().forEach(t => t.stop()); recScreen = null; }
  setTimeout(() => { try{ recCtx && recCtx.close(); }catch(e){} recCtx = null; recDest = null; remoteAudioNode = null; localMicNode = null; }, 1500);
  clearInterval(recTimer); $('#recTag').hidden = true;
  setBtn('gravar', 'Gravar aula', '');
}

/* ================= convite ================= */
function invite(){
  const link = SITE + '?sala=' + sala;
  let qrHtml = '';
  try{ const qr = qrcode(0, 'M'); qr.addData(link); qr.make(); qrHtml = qr.createSvgTag({cellSize: 6, margin: 2}); }catch(e){}
  const box = modal(`<h3>Convidar o aluno</h3>
    <div class="qr">${qrHtml}</div>
    <div class="link">${esc(link)}</div>
    <p>Código da sala: <b style="color:var(--fg);letter-spacing:.15em">${sala}</b>. Este código vale só para esta aula: na próxima, a sala ganha um código novo.</p>
    <p>Pelo app no Android ou pelo programa no computador, print e gravação de tela ficam bloqueados para o aluno. Pelo navegador ele entra, mas sem esse bloqueio.</p>
    <div class="row"><button id="mNovo">Trocar código</button><button id="mCopy">Copiar link</button><button class="go" id="mOk">Fechar</button></div>`);
  box.querySelector('#mOk').onclick = closeModal;
  box.querySelector('#mCopy').onclick = () => {
    navigator.clipboard.writeText('Entre na minha aula ao vivo: ' + link + ' (código ' + sala + ')')
      .then(() => toast('Link copiado. Cole no WhatsApp do aluno.')).catch(() => toast('Selecione o link e copie com Ctrl+C'));
  };
  box.querySelector('#mNovo').onclick = () => confirmBox('Criar um código novo? O link antigo deixa de funcionar e a sala reabre.', 'Trocar');
}
// confirmação dentro da própria janela
function confirmBox(msg, okLabel, onOk){
  const box = modal(`<h3>${esc(msg)}</h3><div class="row"><button id="mNo">Cancelar</button><button class="go" id="mYes">${esc(okLabel || 'Trocar')}</button></div>`);
  box.querySelector('#mNo').onclick = closeModal;
  box.querySelector('#mYes').onclick = () => {
    closeModal();
    if (onOk) onOk();
    else { leave(); start('professor', roomCode()); }
  };
}

function modal(html){ $('#box').innerHTML = html; $('#modal').hidden = false; return $('#box'); }
function closeModal(){ $('#modal').hidden = true; $('#box').innerHTML = ''; }

/* ================= sair ================= */
function confirmLeave(){
  confirmBox(recorder ? 'Sair da aula? A gravação será encerrada e salva.' : 'Sair da aula?', 'Sair', () => { leave(); showHome(); });
}
function leave(){
  leaving = true; inCall = false;
  if (recorder) stopRecord();
  stopShare();
  if (miniOn) toggleMini();
  try{ camCall && camCall.close(); }catch(e){}
  try{ peer && peer.destroy(); }catch(e){}
  camCall = null; peer = null; otherPeer = null; remoteCam = null; remoteScreen = null;
  if (local){ local.getTracks().forEach(t => t.stop()); local = null; }
  if (api.protect) api.protect(false);
}
function showHome(){ $('#call').hidden = true; $('#home').hidden = false; $('#homeErr').hidden = true; }
window.addEventListener('beforeunload', () => { if (recorder) stopRecord(); });
})();
