// Hero: a phone running ForApp's home screen. Deposits arrive as SMS, land in the log and go out to a webhook and a Telegram group.
import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';

const host = document.getElementById('scene');
const cards = [document.getElementById('c-hook'), document.getElementById('c-tg')];
const $ = (id) => document.getElementById(id);
const C = { bg: '#FFFFFF', fg: '#111111', mid: '#6B6B6B', faint: '#ECECEC', line: '#F2F2F2', red: '#E4002B' };
const fa = (n) => String(n).replace(/\d/g, (d) => '۰۱۲۳۴۵۶۷۸۹'[d]);
const group = (n) => fa(String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ','));

// ---------- the app screen, drawn like MainActivity (360dp wide, 2px per dp) ----------

const SW = 720, SH = 1560, S = 2;
const cv = document.createElement('canvas');
cv.width = SW; cv.height = SH;
const g = cv.getContext('2d');

const banks = ['ملت', 'ملی', 'سامان', 'تجارت', 'پاسارگاد', 'صادرات', 'رسالت'];
const dests = ['سرور اصلی', 'حسابداری'];
let clock = 12 * 60 + 40;
const rows = [];
function deposit() {
  const amount = (Math.floor(Math.random() * 4800) + 150) * 1000 + Math.floor(Math.random() * 1000);
  return { time: clock, bank: banks[Math.floor(Math.random() * banks.length)], dest: dests[Math.random() < .75 ? 0 : 1], amount };
}
for (let i = 0; i < 14; i++) { clock -= 3 + Math.floor(Math.random() * 11); rows.push(deposit()); }
clock = 12 * 60 + 40;
let count = 23, sum = rows.reduce((s, r) => s + r.amount, 0) + 9_214_000;
const hours = [0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 2, 4, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];

const ICON = {
  send: new Path2D('M21,3L10,14M21,3l-7,18 -4,-7 -7,-4z'),
  search: new Path2D('M11,4.5a6.5,6.5 0,1 0,0.01 0zM20,20l-4.2,-4.2'),
  gear: new Path2D('M12,9a3,3 0,1 0,0.01 0z M19.4,15a1.7,1.7 0,0 0,0.3 1.8l0.1,0.1a2,2 0,1 1,-2.8 2.8l-0.1,-0.1a1.7,1.7 0,0 0,-1.8 -0.3,1.7 1.7,0 0,0 -1,1.5V21a2,2 0,1 1,-4 0v-0.1a1.7,1.7 0,0 0,-1.1 -1.5,1.7 1.7,0 0,0 -1.8,0.3l-0.1,0.1a2,2 0,1 1,-2.8 -2.8l0.1,-0.1a1.7,1.7 0,0 0,0.3 -1.8,1.7 1.7,0 0,0 -1.5,-1H3a2,2 0,1 1,0 -4h0.1a1.7,1.7 0,0 0,1.5 -1.1,1.7 1.7,0 0,0 -0.3,-1.8l-0.1,-0.1a2,2 0,1 1,2.8 -2.8l0.1,0.1a1.7,1.7 0,0 0,1.8 0.3H9a1.7,1.7 0,0 0,1 -1.5V3a2,2 0,1 1,4 0v0.1a1.7,1.7 0,0 0,1 1.5,1.7 1.7,0 0,0 1.8,-0.3l0.1,-0.1a2,2 0,1 1,2.8 2.8l-0.1,0.1a1.7,1.7 0,0 0,-0.3 1.8V9a1.7,1.7 0,0 0,1.5 1H21a2,2 0,1 1,0 4h-0.1a1.7,1.7 0,0 0,-1.5 1z'),
  ok: new Path2D('M5,12.5l4.5,4.5L19,7.5'),
  fwd: new Path2D('M15,6l-6,6 6,6'),
};
function icon(p, x, y, size, color, w = 1.5) {
  g.save(); g.translate(x * S, y * S); g.scale(size * S / 24, size * S / 24);
  g.strokeStyle = color; g.lineWidth = w; g.lineCap = 'round'; g.lineJoin = 'round'; g.stroke(p); g.restore();
}
function text(s, x, y, sp, color, weight, align = 'right', dir = 'rtl') {
  g.font = `${weight} ${sp * S}px Vazir`; g.fillStyle = color; g.textAlign = align; g.direction = dir;
  g.fillText(s, x * S, y * S);
  return g.measureText(s).width / S;
}
function rrect(x, y, w, h, r) { g.beginPath(); g.roundRect(x * S, y * S, w * S, h * S, r * S); }
const hhmm = (m) => fa(`${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')}`);

// Amount with the last three digits (the matching code) in red, left to right, ending at x.
function amount(n, xEnd, y) {
  const s = group(n), head = s.slice(0, -3), tail = s.slice(-3);
  g.font = `700 ${15 * S}px Vazir`; g.direction = 'ltr'; g.textAlign = 'left';
  const wt = g.measureText(tail).width / S, wh = g.measureText(head).width / S;
  g.fillStyle = C.fg; g.fillText(head, (xEnd - wt - wh) * S, y * S);
  g.fillStyle = C.red; g.fillText(tail, (xEnd - wt) * S, y * S);
  return wh + wt;
}

const st = { nt: -1, notifRow: null, insert: 2, flash: 0 };

function draw() {
  g.fillStyle = C.bg; g.fillRect(0, 0, SW, SH);

  // status bar with the punch-hole camera
  text(hhmm(clock), 336, 21, 12.5, C.fg, 700);
  g.fillStyle = C.fg;
  rrect(20, 11, 20, 10, 2.5); g.fill(); rrect(41, 14, 2, 4, 1); g.fill();
  for (let i = 0; i < 4; i++) { g.fillRect((50 + i * 5) * S, (21 - 3 - i * 2) * S, 3 * S, (3 + i * 2) * S); }
  g.beginPath(); g.arc(180 * S, 16 * S, 6.5 * S, 0, Math.PI * 2); g.fillStyle = '#050505'; g.fill();

  // header: red dot, name, active destinations; send / search / gear
  let y = 34;
  g.fillStyle = C.red; g.beginPath(); g.arc(340 * S, (y + 28) * S, 4 * S, 0, Math.PI * 2); g.fill();
  const nw = text('فوراپ', 328, y + 35, 17, C.fg, 900);
  text('۲ مقصد فعال', 328 - nw - 8, y + 34, 11.5, C.mid, 400);
  icon(ICON.send, 98, y + 17, 22, C.mid);
  icon(ICON.search, 58, y + 17, 22, C.mid);
  icon(ICON.gear, 18, y + 17, 22, C.mid);
  y += 56;
  g.fillStyle = C.faint; g.fillRect(0, y * S, SW, 2);

  // today: the big numeral, then the sum on the end side
  const pulse = st.flash;
  text(fa(count), 340, y + 104, 96, pulse > 0.01 ? mix(C.fg, C.red, pulse) : C.fg, 900);
  text(group(Math.round(sum / 1000) * 1000).replace(/,/g, ','), 20, y + 76, 14, C.fg, 700, 'left');
  text('تومان امروز', 20, y + 96, 11.5, C.mid, 400, 'left');
  text('واریز امروز دریافت و ارسال شد.', 340, y + 140, 14, C.fg, 700);
  y += 156;

  // chart: one bar per hour, red "now" line
  const ch = 70, base = y + ch, slot = 320 / 24, gap = 3;
  for (let i = 0; i < 24; i++) {
    const x = 20 + i * slot;
    if (!hours[i]) { g.fillStyle = C.faint; for (let yy = base - 6; yy > y + 8; yy -= 6) g.fillRect(x * S, yy * S, (slot - gap) * S, 2); continue; }
    const h = hours[i] / 6 * (ch - 8);
    g.fillStyle = C.fg; g.fillRect(x * S, (base - h) * S, (slot - gap) * S, h * S);
  }
  g.fillStyle = C.fg; g.fillRect(20 * S, base * S, 320 * S, 2);
  const nx = 20 + 12.6 * slot;
  g.fillStyle = C.red; g.fillRect(nx * S, (y + 2) * S, 2, (ch - 2) * S);
  text('اکنون', nx + 4, y + 10, 10, C.red, 700, 'left');
  ['۰', '۶', '۱۲', '۱۸', '۲۴'].forEach((h, i) => text(h, 20 + i * 80, base + 16, 10, C.mid, 400, i === 0 ? 'left' : i === 4 ? 'right' : 'center'));
  y = base + 34;

  // destination chips
  let cx = 340;
  [['همه', true], ['سرور اصلی', false], ['حسابداری', false]].forEach(([s, on]) => {
    g.font = `700 ${12 * S}px Vazir`; const w = g.measureText(s).width / S + 24;
    rrect(cx - w, y, w, 26, 13);
    if (on) { g.fillStyle = C.fg; g.fill(); } else { g.strokeStyle = C.faint; g.lineWidth = 2; g.stroke(); }
    text(s, cx - 12, y + 18, 12, on ? C.bg : C.mid, 700);
    cx -= w + 6;
  });
  y += 44;

  // day header
  g.fillStyle = C.faint; g.fillRect(0, y * S, SW, 2);
  const tw = text('امروز', 340, y + 26, 13.5, C.fg, 900);
  text('شنبه ۱۸ مهر', 340 - tw - 6, y + 26, 11.5, C.mid, 400);
  text(fa(count) + ' واریز', 40, y + 26, 11.5, C.mid, 400, 'left');
  icon(ICON.fwd, 20, y + 13, 14, C.mid, 2);
  y += 38;

  // log rows (the newest slides in from the top)
  g.save(); g.beginPath(); g.rect(0, y * S, SW, SH - y * S); g.clip();
  const rh = 48;
  let ry = y - rh * (1 - ease(st.insert));
  rows.forEach((r, i) => {
    if (ry > 780) return;
    if (i === 0 && st.insert < 1.6) { g.fillStyle = `rgba(17,17,17,${0.05 * Math.max(0, 1.6 - st.insert)})`; g.fillRect(0, ry * S, SW, rh * S); }
    text(hhmm(r.time), 340, ry + 30, 13, C.fg, 700);
    g.font = `700 ${15 * S}px Vazir`;
    const aw = g.measureText(group(r.amount)).width / S;
    amount(r.amount, 40 + aw, ry + 31);
    g.save(); g.beginPath(); g.rect((40 + aw + 10) * S, ry * S, (294 - 40 - aw - 10) * S, rh * S); g.clip();
    text(`${r.bank} ← ${r.dest}`, 294, ry + 30, 12.5, C.mid, 400);
    g.restore();
    icon(ICON.ok, 18, ry + 16, 16, C.mid, 2);
    ry += rh;
  });
  g.restore();

  // gesture bar
  g.fillStyle = '#CFCFCF'; rrect(130, 768, 100, 4, 2); g.fill();

  // SMS notification dropping in from the top
  if (st.nt >= 0 && st.notifRow) {
    const p = st.nt < 0.45 ? ease(st.nt / 0.45) : st.nt < 3 ? 1 : 1 - ease((st.nt - 3) / 0.45), ny = -90 + p * 128;
    g.save();
    g.shadowColor = 'rgba(0,0,0,.18)'; g.shadowBlur = 24 * S; g.shadowOffsetY = 6 * S;
    rrect(10, ny, 340, 78, 22); g.fillStyle = '#FFFFFF'; g.fill();
    g.restore();
    rrect(10, ny, 340, 78, 22); g.strokeStyle = C.faint; g.lineWidth = 2; g.stroke();
    rrect(310, ny + 14, 26, 26, 8); g.fillStyle = C.fg; g.fill();
    g.fillStyle = '#fff'; g.fillRect(316 * S, (ny + 20) * S, 14 * S, 3 * S); g.fillRect(316 * S, (ny + 25.5) * S, 9 * S, 3 * S); g.fillRect(316 * S, (ny + 31) * S, 14 * S, 3 * S);
    g.fillStyle = C.red; g.beginPath(); g.arc(329 * S, (ny + 27) * S, 2.2 * S, 0, Math.PI * 2); g.fill();
    const bw = text('بانک ' + st.notifRow.bank, 298, ny + 31, 13, C.fg, 900);
    text('· پیامک · اکنون', 298 - bw - 6, ny + 30, 10.5, C.mid, 400);
    const msg = 'واریز: ' + group(st.notifRow.amount) + ' ریال';
    text(msg, 298, ny + 57, 12, C.mid, 400);
  }
}
const ease = (t) => { t = Math.min(Math.max(t, 0), 1); return 1 - Math.pow(1 - t, 3); };
function mix(a, b, t) {
  const pa = parseInt(a.slice(1), 16), pb = parseInt(b.slice(1), 16);
  const ch = (s) => Math.round(((pa >> s) & 255) * (1 - t) + ((pb >> s) & 255) * t);
  return `rgb(${ch(16)},${ch(8)},${ch(0)})`;
}

// ---------- three.js ----------

const renderer = (() => { try { return new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' }); } catch { return null; } })();
if (renderer) start();

async function start() {
  try { await Promise.race([Promise.all(['400', '700', '900'].map((w) => document.fonts.load(`${w} 20px Vazir`, 'فوراپ'))), new Promise((r) => setTimeout(r, 2500))]); } catch {}

  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.NeutralToneMapping;
  host.insertBefore(renderer.domElement, host.firstChild);

  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environmentIntensity = 0.9;

  const camera = new THREE.PerspectiveCamera(28, 1, 0.1, 100);
  const key = new THREE.DirectionalLight(0xffffff, 1.6); key.position.set(-3, 5, 6); scene.add(key);

  // body: rounded slab with a titanium frame and glass back
  const W = 2.16, H = 4.5, D = 0.2, B = 0.055, R = 0.36;
  // Rounded rectangle as plain points. Built from arcs, the outline ended a hair away from where it began, and that
  // sliver bent the frame's bevel into a notch at the bottom-left corner.
  const shape = (w, h, r, seg = 16) => {
    const pts = [], x = w / 2 - r, y = h / 2 - r;
    [[x, -y, -Math.PI / 2], [x, y, 0], [-x, y, Math.PI / 2], [-x, -y, Math.PI]].forEach(([cx, cy, a0]) => {
      for (let i = 0; i <= seg; i++) {
        const t = a0 + (i / seg) * (Math.PI / 2);
        pts.push(new THREE.Vector2(cx + r * Math.cos(t), cy + r * Math.sin(t)));
      }
    });
    return new THREE.Shape(pts);
  };
  const phone = new THREE.Group();
  const bodyGeo = new THREE.ExtrudeGeometry(shape(W - 2 * B, H - 2 * B, R - B), { depth: D, bevelEnabled: true, bevelThickness: B, bevelSize: B, bevelSegments: 10, curveSegments: 32 });
  bodyGeo.translate(0, 0, -D / 2);
  const glassBack = new THREE.MeshPhysicalMaterial({ color: 0x1b1b1e, roughness: 0.35, metalness: 0.3, clearcoat: 1, clearcoatRoughness: 0.08 });
  const frame = new THREE.MeshPhysicalMaterial({ color: 0x8d8d92, roughness: 0.24, metalness: 1 });
  phone.add(new THREE.Mesh(bodyGeo, [glassBack, frame]));

  const front = D / 2 + B;
  const glass = new THREE.Mesh(new THREE.ShapeGeometry(shape(W - 2 * B, H - 2 * B, R - B), 32),
    new THREE.MeshPhysicalMaterial({ color: 0x030303, roughness: 0.06, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.03 }));
  glass.position.z = front + 0.001; phone.add(glass);

  // the screen: the drawn app as an emissive map under glossy glass
  const sw = W - 2 * B - 0.1, sh = sw * SH / SW;
  const scrGeo = new THREE.ShapeGeometry(shape(sw, sh, R - B - 0.05), 32);
  const uv = scrGeo.attributes.uv, pos = scrGeo.attributes.position;
  for (let i = 0; i < uv.count; i++) uv.setXY(i, pos.getX(i) / sw + 0.5, pos.getY(i) / sh + 0.5);
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = renderer.capabilities.getMaxAnisotropy();
  const screen = new THREE.Mesh(scrGeo, new THREE.MeshBasicMaterial({ map: tex, toneMapped: false }));
  screen.position.z = front + 0.002; phone.add(screen);
  // glass on top: black diffuse, added on top so only its reflections show
  const shine = new THREE.Mesh(scrGeo, new THREE.MeshPhysicalMaterial({ color: 0x000000, roughness: 0.05, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.03, transparent: true, blending: THREE.AdditiveBlending, depthWrite: false }));
  shine.position.z = front + 0.004; phone.add(shine);

  // side keys on the right edge
  const keyGeo = (h) => { const k = new THREE.Mesh(new THREE.CapsuleGeometry(0.028, h, 6, 12), frame); k.position.x = W / 2 + 0.005; return k; };
  const vol = keyGeo(0.52); vol.position.y = 1.05; phone.add(vol);
  const pwr = keyGeo(0.26); pwr.position.y = 0.25; phone.add(pwr);

  // camera bump on the back
  const bump = new THREE.Mesh(new THREE.ExtrudeGeometry(shape(0.78, 0.78, 0.2), { depth: 0.04, bevelEnabled: true, bevelThickness: 0.02, bevelSize: 0.02, bevelSegments: 4, curveSegments: 16 }), glassBack);
  bump.position.set(0.5, 1.55, -front - 0.04); phone.add(bump);
  [[0.32, 1.73], [0.32, 1.37], [0.68, 1.55]].forEach(([x, y]) => {
    const ring = new THREE.Mesh(new THREE.CylinderGeometry(0.13, 0.13, 0.06, 32), frame);
    ring.rotation.x = Math.PI / 2; ring.position.set(x, y, -front - 0.08); phone.add(ring);
    const lens = new THREE.Mesh(new THREE.CircleGeometry(0.1, 32), new THREE.MeshPhysicalMaterial({ color: 0x050510, roughness: 0, clearcoat: 1 }));
    lens.position.set(x, y, -front - 0.111); lens.rotation.y = Math.PI; phone.add(lens);
  });
  scene.add(phone);

  // soft contact shadow
  const sc = document.createElement('canvas'); sc.width = sc.height = 256;
  const sg = sc.getContext('2d'), grd = sg.createRadialGradient(128, 128, 0, 128, 128, 128);
  grd.addColorStop(0, 'rgba(0,0,0,.42)'); grd.addColorStop(.45, 'rgba(0,0,0,.16)'); grd.addColorStop(1, 'rgba(0,0,0,0)');
  sg.fillStyle = grd; sg.fillRect(0, 0, 256, 256);
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(3.6, 1.1), new THREE.MeshBasicMaterial({ map: new THREE.CanvasTexture(sc), transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.y = -H / 2 - 0.3; scene.add(shadow);

  function resize() {
    const w = host.clientWidth, h = host.clientHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    const fit = Math.max(5.7 / (2 * Math.tan(THREE.MathUtils.degToRad(14))), 3.6 / camera.aspect / (2 * Math.tan(THREE.MathUtils.degToRad(14))));
    camera.position.set(0, 0.55, fit);
    camera.lookAt(0, -0.15, 0);
    camera.updateProjectionMatrix();
    phone.position.x = w > 700 ? 0.35 : 0.25;
    shadow.position.x = phone.position.x;
  }
  new ResizeObserver(resize).observe(host); resize();

  let mx = 0, my = 0;
  addEventListener('pointermove', (e) => { mx = e.clientX / innerWidth - 0.5; my = e.clientY / innerHeight - 0.5; }, { passive: true });

  let visible = true;
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; if (visible) loop(); }).observe(host);
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;

  // deposit cycle: notification -> row slides in -> one card per destination
  let next = 1.2, dirty = true, cardsOff = 0;
  function arrive(t) {
    clock += 1 + Math.floor(Math.random() * 4);
    const d = deposit();
    st.notifRow = d; st.nt = 0;
    setTimeout(() => {
      rows.unshift(d); rows.length = Math.min(rows.length, 16);
      count++; sum += d.amount; hours[12] = Math.min(6, hours[12] + 1);
      st.insert = 0; st.flash = 1;
      const code = String(d.amount).slice(-3);
      $('hook-code').textContent = fa(code);
      $('hook-ms').textContent = fa(90 + Math.floor(Math.random() * 160)) + 'ms';
      $('tg-amt').textContent = group(d.amount).replace(/,/g, '٬');
      $('tg-code').textContent = code;
      cards.forEach((c, i) => setTimeout(() => c.classList.add('on'), i * 380));
      cardsOff = t + 3.3;
    }, 900);
    next = t + 4.2;
  }

  const timer = new THREE.Clock();
  let t = 0, running = false;
  function loop() {
    if (running) return;
    running = true;
    requestAnimationFrame(tick);
  }
  function tick() {
    running = false;
    if (!visible) return;
    const dt = Math.min(timer.getDelta(), 0.05); t += dt;

    if (!reduce && t > next) arrive(t);
    if (cardsOff && t > cardsOff) { cards.forEach((c) => c.classList.remove('on')); cardsOff = 0; }
    if (st.nt >= 0) { st.nt += dt; if (st.nt > 3.5) st.nt = -1; dirty = true; }
    if (st.insert < 1.6) { st.insert += dt / 0.5; dirty = true; }
    if (st.flash > 0.01) { st.flash *= 0.94; dirty = true; }
    if (dirty) { draw(); tex.needsUpdate = true; dirty = false; }

    const sway = reduce ? 0 : Math.sin(t * 0.45) * 0.14;
    phone.rotation.y += ((-0.32 + sway + mx * 0.35) - phone.rotation.y) * 0.06;
    phone.rotation.x += ((0.04 + my * 0.12) - phone.rotation.x) * 0.06;
    phone.rotation.z = reduce ? 0 : Math.sin(t * 0.6) * 0.02;
    phone.position.y = reduce ? 0 : Math.sin(t * 0.9) * 0.08;
    shadow.material.opacity = 0.9 - phone.position.y * 1.5;
    shadow.scale.setScalar(1 - phone.position.y * 0.3);

    renderer.render(scene, camera);
    if (!reduce || dirty) loop();
  }
  draw(); tex.needsUpdate = true;
  loop();
}
