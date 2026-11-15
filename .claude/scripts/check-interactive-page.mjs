// Checks the interactive pages of the Gallery in Chromium: what the page shows at a value of its slider is what the still
// picture of the same value (written by `Interactive.at`) shows, element for element, and the 3-D page redraws, plays and
// announces its changes.
//
//   sbt "tda4j/Test/runMain org.appliedtopology.tda4j.plot.Gallery /tmp/gallery"
//   NODE_PATH=/opt/node-tools/node_modules node .claude/scripts/check-interactive-page.mjs /tmp/gallery
//
// Prints one line per check and exits 1 when one fails. CI runs it in Chromium (the `interactive-pages` job of test.yml).
import { createRequire } from 'module';
import fs from 'fs';
const require = createRequire((process.env.NODE_PATH || '/opt/node-tools/node_modules') + '/');
const playwright = require('playwright');

const dir = process.argv[2];
if (!dir) { console.error('usage: check-interactive-page.mjs <gallery directory>'); process.exit(2); }
// BROWSER=firefox or webkit runs the same checks in that engine (Playwright's browsers must be installed); the touch gestures
// are sent through the Chrome DevTools protocol, so they run in Chromium only
const engine = process.env.BROWSER || 'chromium';
const chromiumPath = process.env.CHROMIUM || (fs.existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined);
const browser = await playwright[engine].launch(engine === 'chromium' ? { executablePath: chromiumPath, args: ['--no-sandbox'] } : {});
let failures = 0;
const check = (ok, what) => { console.log(ok ? 'ok  ' : 'FAIL', what); if (!ok) failures++; };

// the elements of a panel that are drawn, as strings: the tag and the numbers that place it (a hidden element has display:none)
const describe = (svg, visibleOnly) => {
  const num = v => (v === null ? '' : (+v).toFixed(2));
  const attrs = { line: ['x1', 'y1', 'x2', 'y2'], circle: ['cx', 'cy', 'r'], ellipse: ['cx', 'cy', 'rx', 'ry'], polygon: ['points'] };
  const out = [];
  svg.querySelectorAll('g[clip-path] *').forEach(el => {
    const tag = el.tagName.toLowerCase();
    if (!attrs[tag]) return;
    if (visibleOnly && el.style.display === 'none') return;
    out.push(tag + ':' + attrs[tag].map(a => a === 'points' ? el.getAttribute(a) : num(el.getAttribute(a))).join(','));
  });
  return out.sort();
};

{ // the 2-D page against its stills
  const page = await browser.newPage({ viewport: { width: 1800, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('file://' + dir + '/interactive-filtration.html');
  for (const t of [0.15, 0.3, 0.5]) {
    await page.evaluate(v => document.querySelector('.tda4j-ia').tda4j.set('t', v), t);
    const live = await page.evaluate(`
      (() => { const describe = ${describe.toString()};
        return [...document.querySelectorAll('.tda4j-ia svg[role=img]')].filter(s => s.querySelector('g[clip-path]')).map(s => describe(s, true)); })()`);
    const still = fs.readFileSync(`${dir}/interactive-still-${t.toFixed(2)}.svg`, 'utf8').replace(/^<\?xml[^>]*\?>\s*/, '');
    const ref = await page.evaluate(`
      (() => { const describe = ${describe.toString()}; const holder = document.createElement('div'); holder.innerHTML = ${JSON.stringify(still)};
        return [...holder.querySelectorAll('svg[role=img]')].filter(s => s.querySelector('g[clip-path]')).map(s => describe(s, false)); })()`);
    check(live.length === ref.length && live.length >= 2, `t=${t}: ${live.length} panels on the page, ${ref.length} in the still`);
    live.forEach((elements, k) => {
      const mine = JSON.stringify(elements), theirs = JSON.stringify(ref[k]);
      const onlyLive = elements.filter(e => !ref[k].includes(e)).slice(0, 3), onlyStill = ref[k].filter(e => !elements.includes(e)).slice(0, 3);
      check(mine === theirs, `t=${t}, panel ${k}: ${elements.length} drawn elements as in the still` + (mine === theirs ? '' : ` (only live ${onlyLive}; only still ${onlyStill})`));
    });
  }
  check(errors.length === 0, `no errors on the 2-D page ${errors.join(' | ')}`);
  await page.close();
}

{ // the panels take the room there is: their own size when it is there, smaller side by side, stacked when too narrow
  const page = await browser.newPage({ viewport: { width: 2400, height: 800 } });
  await page.goto('file://' + dir + '/interactive-filtration.html');
  const boxes = () => page.evaluate(() => [...document.querySelectorAll('.tda4j-ia .tda4j-panel')].map(p => {
    const r = p.querySelector('svg').getBoundingClientRect(), q = p.parentNode.getBoundingClientRect();
    return { y: Math.round(r.y), w: r.width, h: r.height, right: r.right, roomRight: q.right, cap: parseFloat(p.style.maxWidth) };
  }));
  const rows = b => new Set(b.map(x => x.y)).size;
  let b = await boxes();
  check(b.length >= 2 && rows(b) === 1 && b.every(x => Math.abs(x.w - x.cap) < 1), `2400 px: ${b.length} panels in a row, each at its own size (${b.map(x => Math.round(x.w))})`);
  for (const w of [1300, 1000, 800, 500, 360]) {
    await page.setViewportSize({ width: w, height: 800 });
    b = await boxes();
    check(b.every(x => x.right <= x.roomRight + 1 && x.w <= x.cap + 1), `${w} px: ${rows(b)} row(s) of ${b.length} panels, none wider than its own size or the page (${b.map(x => Math.round(x.w))})`);
  }
  await page.setViewportSize({ width: 1300, height: 800 });
  b = await boxes();
  check(rows(b) === 1 && b.some(x => x.w < x.cap - 1), `1300 px: the panels share a row by getting smaller (${b.map(x => Math.round(x.w))})`);
  await page.setViewportSize({ width: 500, height: 800 });
  b = await boxes();
  check(rows(b) === b.length && b.every(x => x.w >= 300), `500 px: the panels stack, each at least 300 px wide (${b.map(x => Math.round(x.w))})`);
  await page.close();
}

{ // the 3-D page redraws, plays, shows the value and announces its changes
  const page = await browser.newPage({ viewport: { width: 1300, height: 700 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('file://' + dir + '/interactive-sphere.html');
  const shot = async t => { await page.evaluate(v => document.querySelector('.tda4j-ia').tda4j.set('t', v), t); return await page.evaluate(() => document.querySelector('canvas').toDataURL()); };
  const a = await shot(0.8), b = await shot(2.4), c = await shot(0.8);
  check(a !== b && a === c, 'the canvas differs between 0.8 and 2.4 and is the same on returning to 0.8');
  await page.focus('input[type=range]');
  await page.keyboard.press('ArrowRight');
  const readout = await page.textContent('output');
  check(/^[-0-9.]+$/.test(readout.trim()), `the readout is the number alone: "${readout}"`);
  await page.click('.tda4j-play'); await page.waitForTimeout(1500);
  const mid = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.get('t'));
  await page.waitForTimeout(8000);
  const end = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.get('t'));
  check(mid > 0.8 && mid < end && Math.abs(end - 3.2) < 1e-9, `play moves the slider to its end: ${mid.toFixed(3)} then ${end.toFixed(3)}`);
  const evt = await page.evaluate(() => new Promise(res => { const r = document.querySelector('.tda4j-ia'); r.addEventListener('tda4j:change', e => res(e.detail), { once: true }); r.tda4j.set('t', 1.6); }));
  check(evt.name === 't' && evt.value === 1.6, `a change is announced: ${JSON.stringify(evt)}`);
  check(errors.length === 0, `no errors on the 3-D page ${errors.join(' | ')}`);
  await page.close();
}

// What a 3-D canvas draws, read off the 2-D context as it draws: every filled or stroked path of the last redraw, so a
// check can count the items, find where one is, and compare them with the still picture of the same state.
const spy = `(() => {
  const P = CanvasRenderingContext2D.prototype, o = {};
  for (const m of ['beginPath', 'moveTo', 'lineTo', 'arc', 'rect', 'fill', 'stroke', 'fillRect', 'fillText']) o[m] = P[m];
  let path = null; window.__shapes = []; window.__texts = []; window.__draws = 0;
  // a full redraw starts by painting the surface: one fillRect, so __draws counts the redraws
  P.fillRect = function (...a) { window.__shapes = []; window.__texts = []; window.__draws++; return o.fillRect.apply(this, a); };
  // a text with its font and the turn of the context (the axis label beside a plot box is drawn turned: its place is the context's origin)
  P.fillText = function (t, x, y) { const m = this.getTransform(), k = window.devicePixelRatio || 1; window.__texts.push({ text: t, x, y, align: this.textAlign, font: this.font, turn: [m.a / k, m.b / k, m.e / k, m.f / k] }); return o.fillText.apply(this, arguments); };
  P.rect = function (x, y, w, h) { if (path) path.push(['r', x, y, w, h]); return o.rect.call(this, x, y, w, h); };
  P.beginPath = function () { path = []; return o.beginPath.call(this); };
  P.moveTo = function (x, y) { if (path) path.push(['m', x, y]); return o.moveTo.call(this, x, y); };
  P.lineTo = function (x, y) { if (path) path.push(['l', x, y]); return o.lineTo.call(this, x, y); };
  P.arc = function (x, y, r, a, b) { if (path) path.push(['a', x, y, r]); return o.arc.call(this, x, y, r, a, b); };
  // ink is the font in force: only the axes of a flat canvas set one, so their lines are told apart from the items by it
  P.fill = function () { if (path) window.__shapes.push({ op: 'fill', alpha: this.globalAlpha, path: path.slice(), ink: this.font }); return o.fill.apply(this, arguments); };
  P.stroke = function () { if (path) window.__shapes.push({ op: 'stroke', alpha: this.globalAlpha, width: this.lineWidth, color: this.strokeStyle, path: path.slice(), ink: this.font }); return o.stroke.apply(this, arguments); };
})()`;
// the kind of a recorded shape: a triangle, a segment, a disc (point, ball or halo)
const kindOf = s => s.path[0][0] === 'r' ? 'box' : s.path[0][0] === 'a' ? (s.op === 'fill' ? 'disc' : 'ring') : s.path.length === 3 ? 'triangle' : 'segment';
// the axes of a flat canvas (lines drawn with a font in force; Chromium, Firefox and WebKit all start from 10px sans-serif) are no item
const axisShape = s => s.ink !== undefined && s.ink !== '10px sans-serif';
const kinds = shapes => { const n = { triangle: 0, segment: 0, disc: 0 }; shapes.filter(s => !axisShape(s)).forEach(s => { const k = kindOf(s); if (k in n) n[k]++; }); return n; };
// what a still picture holds, read in a blank page (Node has no DOM): the counts of the scene's polygons, lines and circles
// per 3-D scene, and the balls (the circles of opacity 0.09)
const reader = await browser.newPage();
const inStill = (text, fn) => reader.evaluate(`(() => { const doc = new DOMParser().parseFromString(${JSON.stringify(text.replace(/^<\?xml[^>]*\?>\s*/, ''))}, 'image/svg+xml'); return (${fn.toString()})(doc); })()`);
const stillKinds = text => inStill(text, doc => { const scenes = [...doc.querySelectorAll('svg[id^="tda4j3d"]')];
  return (scenes.length ? scenes : [doc]).map(s => ({ triangle: s.querySelectorAll('polygon').length, segment: s.querySelectorAll('line:not(.tda4j-axes line):not(.tda4j-grid line)').length, disc: s.querySelectorAll('circle').length })); });
const stillBalls = text => inStill(text, doc => [...doc.querySelectorAll('circle')].filter(c => /fill-opacity:0\.09(;|$)/.test(c.getAttribute('style') || ''))
  .map(c => ({ x: +c.getAttribute('cx'), y: +c.getAttribute('cy'), r: +c.getAttribute('r') })));
const middle = s => { const pts = s.path.map(p => p.slice(1, 3)); return [pts.reduce((a, p) => a + p[0], 0) / pts.length, pts.reduce((a, p) => a + p[1], 0) / pts.length]; };
const read = f => fs.readFileSync(dir + '/' + f, 'utf8');

{ // 3-D canvas: pointing at an item chooses its keys, clicking pins them, the halo is what the still of that choice shows
  const page = await browser.newPage({ viewport: { width: 1300, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(spy);
  await page.goto('file://' + dir + '/interactive-pick-3d.html');
  const shapes = () => page.evaluate(() => window.__shapes);
  const origin = () => page.evaluate(() => { const r = document.querySelector('canvas').getBoundingClientRect(); return [r.left, r.top]; });
  const chosen = () => page.evaluate(() => [...document.querySelector('.tda4j-ia').tda4j.selection('item')].sort((a, b) => a - b));
  const settle = () => page.waitForTimeout(120);
  const [ox, oy] = await origin();
  const initial = await shapes();
  const pickable = initial.filter(s => ['triangle', 'segment'].includes(kindOf(s)) || (kindOf(s) === 'disc' && s.alpha === 1));
  check(JSON.stringify(kinds(initial)) === JSON.stringify({ triangle: 1, segment: 2, disc: 3 }), `the canvas draws ${JSON.stringify(kinds(initial))}`);
  // pointing: each item chooses its own keys while the pointer is on it, and nothing once it leaves
  const found = [];
  for (const s of pickable) {
    const [x, y] = middle(s);
    await page.mouse.move(ox + x, oy + y); await settle();
    const keys = await chosen();
    found.push(...keys);
    check(keys.length >= 1 && keys.length <= 1, `pointing at a ${kindOf(s)} chooses its key: [${keys}]`);
    await page.mouse.move(ox + 4, oy + 4); await settle();
    check((await chosen()).length === 0, 'moving away chooses nothing again');
  }
  check(JSON.stringify(found.sort((a, b) => a - b)) === '[0,1,2,10,11,20]', `the six items choose six different keys: [${found}]`);
  // clicking pins: the choice stays when the pointer leaves, a second click clears it, shift adds
  const seg = pickable.filter(s => kindOf(s) === 'segment');
  let k10 = null;
  for (const s of seg) {
    const [x, y] = middle(s);
    await page.mouse.click(ox + x, oy + y); await settle();
    const keys = await chosen();
    await page.mouse.move(ox + 4, oy + 4); await settle();
    check(JSON.stringify(await chosen()) === JSON.stringify(keys) && keys.length === 1, `a click on a segment pins its key and the pointer leaving keeps it: [${keys}]`);
    if (keys[0] === 10) {
      k10 = s;
      const now = await shapes();
      const ref = (await stillKinds(read('interactive-pick-3d-still.svg')))[0];
      check(JSON.stringify(kinds(now)) === JSON.stringify(ref), `with key 10 chosen the canvas draws ${JSON.stringify(kinds(now))} as the still does ${JSON.stringify(ref)}`);
      const halo = now.filter(t => kindOf(t) === 'segment' && t.alpha < 1 && t.width > 4);
      check(halo.length === 1, `exactly one halo segment, wider than the segment it lights (${halo.map(h => h.width)})`);
    }
    await page.mouse.click(ox + x, oy + y);
    await page.mouse.move(ox + 4, oy + 4); await settle();
    check((await chosen()).length === 0, 'a second click on the same item clears it');
  }
  check(k10 !== null, 'one of the segments chooses key 10');
  const [px, py] = middle(pickable.find(s => kindOf(s) === 'disc'));
  await page.mouse.click(ox + px, oy + py); await settle();
  await page.keyboard.down('Shift');
  await page.mouse.click(ox + middle(k10)[0], oy + middle(k10)[1]); await settle();
  await page.keyboard.up('Shift');
  check((await chosen()).length === 2, `shift adds a second item: [${await chosen()}]`);
  await page.mouse.click(ox + 4, oy + 4); await settle();
  check((await chosen()).length === 0, 'a click on nothing clears the choice');
  // dragging turns the picture and chooses nothing
  const before = JSON.stringify(await shapes());
  await page.mouse.move(ox + 300, oy + 200); await page.mouse.down(); await page.mouse.move(ox + 380, oy + 230, { steps: 5 }); await page.mouse.up(); await settle();
  check(JSON.stringify(await shapes()) !== before && (await chosen()).length === 0, 'a drag turns the picture and chooses nothing');
  check(errors.length === 0, `no errors on the picking page ${errors.join(' | ')}`);
  await page.close();
}

{ // a 3-D canvas that chooses by rectangle: the box button, what a drag chooses (the library's own answer for each rectangle), the still
  const cases = JSON.parse(read('interactive-box-3d.json'));
  const context = await browser.newContext({ viewport: { width: 1300, height: 800 }, hasTouch: engine === 'chromium' });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(spy);
  await page.goto('file://' + dir + '/interactive-box-3d.html');
  const api = (body, arg) => page.evaluate(`((arg) => { const t = document.querySelector('.tda4j-ia').tda4j; ${body} })(${JSON.stringify(arg ?? null)})`);
  const shapes = () => page.evaluate(() => window.__shapes);
  const settle = () => page.waitForTimeout(100);
  const chosen = () => api("return [...t.selection('item')].sort((a, b) => a - b);");
  // a place in the view plane (x right, y up, in the radius of the frame), as a client pixel of the canvas: its middle is the
  // middle of the picture, and one unit is 0.46 of the smaller side
  const toClient = async ([u, v]) => page.evaluate(([u, v]) => { const cv = document.querySelector('canvas'), r = cv.getBoundingClientRect(), W = cv.clientWidth, H = W * cv.height / cv.width,
    sc = 0.46 * Math.min(W, H), k = r.width / W; return [r.left + (W / 2 + u * sc) * k, r.top + (H / 2 - v * sc) * k]; }, [u, v]);
  const corners = async w => [await toClient([w[0], w[3]]), await toClient([w[1], w[2]])];
  const drag = async (w, { shift = false } = {}) => { const [a, b] = await corners(w);
    if (shift) await page.keyboard.down('Shift');
    await page.mouse.move(...a); await page.mouse.down(); await page.mouse.move(...b, { steps: 6 }); await page.mouse.up();
    if (shift) await page.keyboard.up('Shift');
    await page.mouse.move(6, 6); await settle(); };
  const window_ = () => api('return t.region(0);');
  const near = (a, b, tol = 0.01) => a && b && a.length === b.length && a.every((v, i) => Math.abs(v - b[i]) <= tol);
  const nonBox = sh => sh.filter(s => kindOf(s) !== 'box');
  const boxes = sh => sh.filter(s => kindOf(s) === 'box' && s.op === 'fill');
  const button = page.locator('.tda4j-box');

  check(await button.count() === 1 && await button.getAttribute('aria-pressed') === 'false', 'a panel that chooses by rectangle has a box button, not pressed');
  // box mode off: a drag turns the picture, as before, and chooses nothing
  const before = JSON.stringify(nonBox(await shapes()));
  const [tl, br] = await corners([-0.5, 0.5, -0.5, 0.5]);
  await page.mouse.move(...tl); await page.mouse.down(); await page.mouse.move(...br, { steps: 5 }); await page.mouse.up(); await page.mouse.move(6, 6); await settle();
  check(JSON.stringify(nonBox(await shapes())) !== before && (await chosen()).length === 0 && boxes(await shapes()).length === 0, `without the box button a drag turns the picture: nothing chosen, no rectangle (moved ${JSON.stringify(nonBox(await shapes())) !== before}, chosen [${await chosen()}], boxes ${boxes(await shapes()).length})`);
  await page.reload(); await page.waitForTimeout(100);
  await button.click();
  check(await button.getAttribute('aria-pressed') === 'true', 'the box button is pressed once clicked');

  // each rectangle of the library's cases: the page chooses the keys the library says it does, and shows the rectangle it was dragged as
  const picture = JSON.stringify(nonBox(await shapes()));
  const bad = [];
  for (const [i, c] of cases.entries()) {
    await api("t.set('t', arg); t.select('item', []);", c.t);
    await settle();
    await drag(c.window);
    const keys = await chosen(), win = await window_();
    if (JSON.stringify(keys) !== JSON.stringify(c.keys)) bad.push(`case ${i} (t=${c.t}, [${c.window}]): page [${keys}], library [${c.keys}]`);
    if (!near(win, c.window)) bad.push(`case ${i}: the rectangle on the page is [${win && win.map(v => v.toFixed(3))}], not [${c.window}]`);
    if (boxes(await shapes()).length !== 1) bad.push(`case ${i}: ${boxes(await shapes()).length} rectangles drawn`);
  }
  check(bad.length === 0, `${cases.length} rectangles choose what the library says they do` + (bad.length ? ` -- ${bad.slice(0, 3).join(' || ')}` : ''));
  check(cases.some(c => c.keys.length === 0) && cases.some(c => c.keys.length >= 4) && new Set(cases.flatMap(c => c.keys)).size >= 4, 'and the cases cover nothing chosen, several things chosen and every kind of item');
  // a drag in box mode does not turn the picture
  await api("t.set('t', 0.9); t.select('item', []);"); await settle();
  const still0 = JSON.stringify(nonBox(await shapes()));
  await drag([-0.3, 0.3, 0.1, 0.3]);
  // the rectangle stays until the next click, and goes with a click or a turn of the wheel
  check(boxes(await shapes()).length === 1, 'the rectangle stays on the picture after the drag');
  await api("t.select('item', []);"); await settle();
  check(JSON.stringify(nonBox(await shapes())) === still0, 'a drag in box mode leaves the picture in its view (nothing turns)');
  const [mx, my] = await toClient([0.8, -0.8]);
  await page.mouse.click(mx, my); await settle();
  check(boxes(await shapes()).length === 0 && (await window_()) === null, 'a click removes it');
  await drag([-0.3, 0.3, 0.35, 0.55]);
  check(boxes(await shapes()).length === 1, 'drawn again');
  await page.mouse.move(mx, my); await page.mouse.wheel(0, -100); await settle();
  check(boxes(await shapes()).length === 0, 'the wheel takes it away, as zooming moves the picture from under it');
  // a plain drag adds nothing and a shift-drag adds to what is chosen
  await api("t.set('t', 0); t.select('item', []);");
  await drag([-0.15, 0.15, 0.35, 0.55]);
  const first = await chosen();
  await drag([-0.6, -0.4, -0.6, -0.4]);
  const second = await chosen();
  await drag([-0.15, 0.15, 0.35, 0.55], { shift: true });
  const third = await chosen();
  check(JSON.stringify(first) === '[10]' && JSON.stringify(second) === '[20]' && JSON.stringify(third) === '[10,20]', `a drag replaces what is chosen and a shift-drag adds to it: [${first}] then [${second}] then [${third}]`);
  // the library's still of the same rectangle draws it where the page does: the same window, read off the still's own anchors
  const stillWin = await inStill(read('interactive-box-3d-still.svg'), doc => { const svg = doc.querySelector('svg[id^="tda4j3d"]'), r = svg.querySelector('rect[stroke-dasharray]');
    const anchors = [...svg.querySelectorAll('circle')].filter(c => c.getAttribute('r') === '1.5'), xs = anchors.map(c => +c.getAttribute('cx')), ys = anchors.map(c => +c.getAttribute('cy'));
    const cx = (Math.min(...xs) + Math.max(...xs)) / 2, cy = (Math.min(...ys) + Math.max(...ys)) / 2, sc = (Math.max(...xs) - Math.min(...xs)) / 2;
    const x = +r.getAttribute('x'), y = +r.getAttribute('y'), w = +r.getAttribute('width'), h = +r.getAttribute('height');
    return [(x - cx) / sc, (x + w - cx) / sc, (cy - y - h) / sc, (cy - y) / sc]; });
  check(near(stillWin, [-0.15, 0.15, 0.35, 0.55], 0.002), `the still draws the rectangle [-0.15, 0.15, 0.35, 0.55] where its own picture puts it: [${stillWin.map(v => v.toFixed(3))}]`);
  // the page's own rectangle set from the data is that of the still
  await api('t.brush(0, arg);', [-0.15, 0.15, 0.35, 0.55]);
  check(near(await window_(), [-0.15, 0.15, 0.35, 0.55], 1e-6) && boxes(await shapes()).length === 1, 'brush(panel, window) puts the same rectangle on the page');
  await api('t.brush(0, null);');
  check((await window_()) === null && boxes(await shapes()).length === 0, 'brush(panel, null) takes it away');
  // the keyboard: ] [ Home End step through the items drawn (left to right, top to bottom), and two Enters take the rectangle between
  // two of them, which a drag over a hair more than the same rectangle takes just the same
  {
    await api("t.set('t', 0); t.select('item', []);"); await settle();
    // at t = 0 the chosen things are the triangle (key 20), the segment (key 10) and two points on one place (keys 0 and 2); the six small
    // points that frame the picture choose nothing, and key 1 is not shown yet
    const found = (await shapes()).filter(s => ['triangle', 'segment'].includes(kindOf(s)) || (kindOf(s) === 'disc' && s.alpha === 1 && s.path[0][3] === 4)).map(middle);
    const order = found.filter((p, i) => found.findIndex(q => Math.abs(q[0] - p[0]) < 1e-6 && Math.abs(q[1] - p[1]) < 1e-6) === i).sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    const litAt = [[20], [10], [0, 2]];
    const crossNow = () => page.evaluate(() => { const c = window.__shapes.find(s => s.op === 'stroke' && s.width === 1.5 && s.path.length === 4 && s.ink === '10px sans-serif'); return c ? [c.path[0][1] + 8, c.path[0][2]] : null; });
    const toPixels = ([x, y]) => page.evaluate(([x, y]) => { const cv = document.querySelector('canvas'), r = cv.getBoundingClientRect(), k = r.width / cv.clientWidth; return [r.left + x * k, r.top + y * k]; }, [x, y]);
    const key = async (k, n = 1) => { for (let i = 0; i < n; i++) await page.keyboard.press(k); await page.waitForTimeout(30); };
    const onPlace = async (i, what) => { const c = await crossNow(), want = order[i], lit = await chosen();
      check(c && Math.abs(c[0] - want[0]) < 0.6 && Math.abs(c[1] - want[1]) < 0.6 && JSON.stringify(lit) === JSON.stringify(litAt[i]),
        `${what}: the cross is on place ${i} of ${order.length} and lights [${litAt[i]}]: cross ${c && c.map(v => v.toFixed(1))}, wanted ${want.map(v => v.toFixed(1))}, lit [${lit}]`); };
    check(order.length === 3, `${order.length} places to step through: the triangle's middle, the segment's and the points'`);
    await page.mouse.move(6, 6); await page.focus('canvas'); await page.waitForTimeout(100);
    await key(']'); await onPlace(0, '] with no cross');
    await key(']'); await onPlace(1, '] again');
    await key(']'); await onPlace(2, 'a third time: both points at that place light up');
    await key(']'); await onPlace(2, '] at the last place stays');
    await key('['); await onPlace(1, '[');
    await key('Home'); await onPlace(0, 'Home');
    await key('['); await onPlace(0, '[ at the first place stays');
    await key('End'); await onPlace(2, 'End');
    await key('Escape');
    check(await crossNow() === null && (await chosen()).length === 0, 'Escape takes the cross away and puts the light out');
    // the keyboard and the mouse take the same rectangle (the mouse's is two pixels larger all round: no item is within two pixels of it)
    const outset = ([a, b]) => [[Math.min(a[0], b[0]) - 2, Math.min(a[1], b[1]) - 2], [Math.max(a[0], b[0]) + 2, Math.max(a[1], b[1]) + 2]];
    for (const [i, j, expected] of [[0, 1, [0, 2, 10, 20]], [0, 2, [0, 2, 20]]]) {
      await api("t.select('item', []);"); await settle(); await page.mouse.move(6, 6);
      await page.focus('canvas'); await key('Home'); await key(']', i); await key('Enter'); await key('Home'); await key(']', j);
      const [a, b] = [await toPixels(order[i]), await toPixels(order[j])];
      await key('Enter');
      const byKeys = await chosen(), win = await window_();
      check(JSON.stringify(byKeys) === JSON.stringify(expected), `places ${i} and ${j} as corners choose [${expected}]: [${byKeys}]`);
      check(await crossNow() === null && boxes(await shapes()).length === 1 && win && win[0] < win[1] && win[2] < win[3], `the cross is gone, the rectangle stays and is a window: [${win && win.map(v => v.toFixed(3))}]`);
      await api("t.select('item', []);"); await settle();
      const [c, d] = outset([a, b]);
      await page.mouse.move(...c); await page.mouse.down(); await page.mouse.move(...d, { steps: 6 }); await page.mouse.up(); await page.mouse.move(6, 6); await settle();
      check(JSON.stringify(await chosen()) === JSON.stringify(byKeys), `a drag over the same corners chooses the same: [${await chosen()}]`);
    }
    // Enter at one place twice chooses what is there (a rectangle of no size), the next place replaces it, and shift adds
    await api("t.select('item', []);"); await settle(); await page.focus('canvas');
    await key('Home'); await key('Enter'); await key('Enter');
    const there = await chosen();
    await key('Home'); await key(']'); await key('Enter'); await key('Enter');
    check(JSON.stringify(there) === '[20]' && JSON.stringify(await chosen()) === '[10]', `two Enters at a place choose what is there, and the next place replaces it: [${there}] then [${await chosen()}]`);
    await key('Home'); await key('Enter'); await page.keyboard.press('Shift+Enter'); await settle();
    check(JSON.stringify(await chosen()) === '[10,20]', `with shift it is added: [${await chosen()}]`);
    await api("t.select('item', []); t.brush(0, null);"); await settle();
  }

  // box mode off again: a drag turns
  await button.click();
  check(await button.getAttribute('aria-pressed') === 'false', 'a second click on the box button lets go of it');
  await api("t.select('item', []);");
  const turnFrom = JSON.stringify(nonBox(await shapes()));
  await page.mouse.move(...tl); await page.mouse.down(); await page.mouse.move(...br, { steps: 5 }); await page.mouse.up(); await page.mouse.move(6, 6); await settle();
  check(JSON.stringify(nonBox(await shapes())) !== turnFrom && (await chosen()).length === 0, 'and a drag turns the picture again');

  if (engine !== 'chromium') console.log(`skip  box by touch: touch is sent through the Chrome DevTools protocol, and this is ${engine}`);
  else { // one finger draws the rectangle in box mode and turns the picture otherwise; a second finger lands and it is a pinch
    await page.reload(); await page.waitForTimeout(100);
    const cdp = await context.newCDPSession(page);
    const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y], id) => ({ x, y, id })) });
    const finger = async (w, steps = 6) => { const [a, b] = await corners(w);
      await touch('touchStart', [a]); for (let i = 1; i <= steps; i++) await touch('touchMove', [[a[0] + (b[0] - a[0]) * i / steps, a[1] + (b[1] - a[1]) * i / steps]]); await touch('touchEnd', []); await settle(); };
    await api("t.set('t', 0.9);"); await settle();
    const idle = JSON.stringify(nonBox(await shapes()));
    await button.click();
    await finger([-0.15, 0.15, 0.35, 0.55]);
    check(JSON.stringify(await chosen()) === '[10]' && near(await window_(), [-0.15, 0.15, 0.35, 0.55], 0.01), `with the box button pressed one finger draws a rectangle and chooses what it touches: [${await chosen()}]`);
    await api("t.select('item', []);"); await api('t.brush(0, null);'); await settle();
    check(JSON.stringify(nonBox(await shapes())) === idle, 'and the picture keeps its view');
    // a second finger turns the drag into a pinch: no rectangle is chosen
    const [a0] = await corners([-0.3, 0.3, 0.1, 0.3]); const [b0] = await corners([0.3, 0.6, -0.2, 0.0]);
    await touch('touchStart', [a0]); await touch('touchMove', [[a0[0] + 40, a0[1] + 20]]);
    await touch('touchStart', [[a0[0] + 40, a0[1] + 20], b0]); await touch('touchMove', [[a0[0] + 40, a0[1] + 20], [b0[0] + 30, b0[1]]]); await touch('touchEnd', []); await settle();
    check((await chosen()).length === 0 && boxes(await shapes()).length === 0, 'a second finger makes it a pinch: no rectangle, nothing chosen');
    await button.click();
    const turnTouch = JSON.stringify(nonBox(await shapes()));
    const [t0, t1] = await corners([-0.5, 0.5, -0.5, 0.5]);
    await touch('touchStart', [t0]); for (let i = 1; i <= 5; i++) await touch('touchMove', [[t0[0] + (t1[0] - t0[0]) * i / 5, t0[1] + (t1[1] - t0[1]) * i / 5]]); await touch('touchEnd', []); await settle();
    check(JSON.stringify(nonBox(await shapes())) !== turnTouch, 'with the box button let go one finger turns the picture again');
  }
  check(errors.length === 0, `no errors on the rectangle page ${errors.join(' | ')}`);
  await page.close();
  await context.close();
}

{ // the 3-D sphere: the balls follow the slider and are the balls of the still of that value
  const page = await browser.newPage({ viewport: { width: 1300, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(spy);
  await page.goto('file://' + dir + '/interactive-sphere.html');
  // the ball radius against the spread of the centres, which both drawings share whatever their size
  const ratio = discs => { const xs = discs.map(d => d.x), ys = discs.map(d => d.y), r = discs[0].r;
    return r / Math.max(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys)); };
  const live = async t => { await page.evaluate(v => document.querySelector('.tda4j-ia').tda4j.set('t', v), t);
    const sh = await page.evaluate(() => window.__shapes);
    const balls = sh.filter(s => kindOf0(s) === 'ball').map(s => ({ x: s.path[0][1], y: s.path[0][2], r: s.path[0][3] }));
    return { balls, counts: kinds(sh) }; };
  const kindOf0 = s => (s.path[0][0] === 'a' && s.op === 'fill' && Math.abs(s.alpha - 0.09) < 1e-6) ? 'ball' : 'other';
  const small = await live(1.2), large = await live(2.4);
  check(small.balls.length > 0 && large.balls.length >= small.balls.length, `balls on the page: ${small.balls.length} at 1.2, ${large.balls.length} at 2.4`);
  check(large.balls[0].r > small.balls[0].r * 1.5, `a ball grows with the slider: ${small.balls[0].r.toFixed(2)} px then ${large.balls[0].r.toFixed(2)} px`);
  for (const [t, now] of [[1.2, small], [2.4, large]]) {
    const ref = await stillBalls(read(`interactive-sphere-still-${t.toFixed(2)}.svg`));
    check(ref.length === now.balls.length, `t=${t}: ${now.balls.length} balls on the page, ${ref.length} in the still`);
    const a = ratio(now.balls), b = ratio(ref);
    check(Math.abs(a - b) < 0.01 * b, `t=${t}: ball radius over spread ${a.toFixed(4)} on the page, ${b.toFixed(4)} in the still`);
  }
  check(errors.length === 0, `no errors on the sphere page ${errors.join(' | ')}`);
  await page.close();
}

{ // a plane and a pole that follow the slider in z, points that appear as it reaches them: the page and the still of the
  // same value draw the same items, in the same depth order, in the same places
  const page = await browser.newPage({ viewport: { width: 1300, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(spy);
  await page.goto('file://' + dir + '/interactive-rising.html');
  // the items of a drawing, back to front, each with the kind and the middle of its shape; positions are measured from the
  // centre of all of them in units of their largest spread, which the page and the still share whatever their size
  const items = sh => sh.filter(s => ['triangle', 'segment'].includes(kindOf(s)) || (kindOf(s) === 'disc')).map(s => ({ kind: kindOf(s), at: middle(s) }));
  const stillItems = text => inStill(text, doc => [...doc.querySelectorAll('svg[id^="tda4j3d"] > polygon, svg[id^="tda4j3d"] > line, svg[id^="tda4j3d"] > circle')].map(e => {
    if (e.tagName === 'polygon') { const q = e.getAttribute('points').trim().split(/\s+/).map(p => p.split(',').map(Number)); return { kind: 'triangle', at: [q.reduce((a, p) => a + p[0], 0) / q.length, q.reduce((a, p) => a + p[1], 0) / q.length] }; }
    if (e.tagName === 'line') return { kind: 'segment', at: [(+e.getAttribute('x1') + +e.getAttribute('x2')) / 2, (+e.getAttribute('y1') + +e.getAttribute('y2')) / 2] };
    return { kind: 'disc', at: [+e.getAttribute('cx'), +e.getAttribute('cy')] }; }));
  const normal = list => { const xs = list.map(i => i.at[0]), ys = list.map(i => i.at[1]);
    const cx = (Math.min(...xs) + Math.max(...xs)) / 2, cy = (Math.min(...ys) + Math.max(...ys)) / 2, w = Math.max(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys)) || 1;
    return list.map(i => ({ kind: i.kind, at: [(i.at[0] - cx) / w, (i.at[1] - cy) / w] })); };
  // the values in an order that makes each one's depth order differ from the one before it
  let orders = [];
  for (const t of [0.2, 0.9, 0.5, 0.05, 0.2]) {
    await page.evaluate(v => document.querySelector('.tda4j-ia').tda4j.set('t', v), t);
    const live = normal(items(await page.evaluate(() => window.__shapes)));
    const ref = normal(await stillItems(read(`interactive-rising-still-${t.toFixed(2)}.svg`)));
    check(live.length === ref.length && live.every((a, i) => a.kind === ref[i].kind), `t=${t}: the page draws ${live.map(a => a.kind[0]).join('')}, the still ${ref.map(a => a.kind[0]).join('')} (depth order)`);
    const worst = live.length === ref.length ? Math.max(...live.map((a, i) => Math.hypot(a.at[0] - ref[i].at[0], a.at[1] - ref[i].at[1]))) : Infinity;
    check(worst < 0.01, `t=${t}: every item is within ${worst.toFixed(4)} of the still's place`);
    orders.push(live.map(a => a.kind[0]).join(''));
  }
  check(new Set(orders).size >= 3, `the depth order changes as the plane rises and the clamped ends hold it: ${orders.join(' ')}`);
  check(errors.length === 0, `no errors on the rising-plane page ${errors.join(' | ')}`);
  await page.close();
}

{ // the linked 3-D filtration: choosing a class in the diagram draws its cycle in the canvas, as the still does
  const { key, t } = JSON.parse(read('interactive-linked-3d.json'));
  const page = await browser.newPage({ viewport: { width: 1800, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(spy);
  await page.goto('file://' + dir + '/interactive-linked-3d.html');
  await page.evaluate(v => document.querySelector('.tda4j-ia').tda4j.set('t', v), t);
  const none = kinds(await page.evaluate(() => window.__shapes));
  // click the class's mark in the diagram, in the 2-D panel
  const at = await page.evaluate(k => { const it = document.querySelector('.tda4j-ia').tda4j.items.find(i => i.picks.some(p => p.n === 'class' && p.key === k) && i.el.getClientRects().length);
    const r = it.el.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; }, key);
  await page.mouse.click(at[0], at[1]); await page.waitForTimeout(150);
  const chosen = await page.evaluate(() => [...document.querySelector('.tda4j-ia').tda4j.selection('class')]);
  check(chosen.length === 1 && chosen[0] === key, `a click in the diagram chooses class ${key}: [${chosen}]`);
  const now = kinds(await page.evaluate(() => window.__shapes));
  check(now.segment > none.segment, `the cycle's edges are drawn: ${none.segment} segments before, ${now.segment} after`);
  // the still holds every panel; its scene is the one `tda4j3d` svg
  const sceneRef = await stillKinds(read('interactive-linked-3d-still.svg'));
  check(sceneRef.length >= 1 && JSON.stringify(sceneRef[0]) === JSON.stringify(now), `the canvas draws ${JSON.stringify(now)}, the still of the same state ${JSON.stringify(sceneRef[0])}`);
  await page.mouse.click(at[0], at[1]); await page.mouse.move(2, 2); await page.waitForTimeout(150);
  const gone = kinds(await page.evaluate(() => window.__shapes));
  check(JSON.stringify(gone) === JSON.stringify(none), 'a second click removes the cycle again');
  check(errors.length === 0, `no errors on the linked 3-D page ${errors.join(' | ')}`);
  await page.close();
}

{ // a complex too large for an SVG is a flat canvas: the page draws what the still shows, pans with a drag, zooms about the pointer
  const { key, t } = JSON.parse(read('interactive-large.json'));
  const page = await browser.newPage({ viewport: { width: 1800, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(spy);
  const t0 = Date.now();
  await page.goto('file://' + dir + '/interactive-large.html');
  const load = Date.now() - t0;
  check(fs.statSync(dir + '/interactive-large.html').size < 4e6, `the page of ${(fs.statSync(dir + '/interactive-large.html').size / 1e6).toFixed(2)} MB loads in ${load} ms`);
  const shapes = () => page.evaluate(() => window.__shapes);
  await page.evaluate(() => { const a = document.querySelector('.tda4j-ia').tda4j; a.set('t', a.controls[0].max); });
  const everything = kinds(await shapes());
  check(everything.triangle > 20000, `at the largest scale the canvas draws ${everything.triangle} triangles, more than an SVG page holds`);
  await page.evaluate(v => document.querySelector('.tda4j-ia').tda4j.set('t', v), t);
  const asDrawn = kinds(await shapes());
  const ref = (await stillKinds(read('interactive-large-still.svg')))[0];
  const withoutSelection = JSON.stringify(asDrawn);
  const bar = await page.evaluate(k => { const it = document.querySelector('.tda4j-ia').tda4j.items.find(i => i.picks.some(p => p.n === 'class' && p.key === k) && i.el.getClientRects().length);
    const r = it.el.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; }, key);
  await page.mouse.click(bar[0], bar[1]); await page.waitForTimeout(300);
  const chosen = await shapes();
  check(JSON.stringify(kinds(chosen)) === JSON.stringify(ref), `with the class chosen the canvas draws ${JSON.stringify(kinds(chosen))}, the still ${JSON.stringify(ref)}`);
  await page.mouse.click(bar[0], bar[1]); await page.mouse.move(2, 2); await page.waitForTimeout(300);
  check(JSON.stringify(kinds(await shapes())) === withoutSelection, 'a second click removes the cycle again');
  // a drag pans: every item moves by the drag, nothing turns
  const canvas = await page.evaluate(() => { const r = document.querySelector('canvas').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
  const firstDisc = s => s.find(x => kindOf(x) === 'disc' && x.alpha === 1);
  const before = firstDisc(await shapes()).path[0];
  const [cx, cy] = [canvas[0] + canvas[2] / 2, canvas[1] + canvas[3] / 2];
  await page.mouse.move(cx, cy); await page.mouse.down(); await page.mouse.move(cx + 60, cy + 25, { steps: 6 }); await page.mouse.up(); await page.waitForTimeout(200);
  const after = firstDisc(await shapes()).path[0];
  check(Math.abs(after[1] - before[1] - 60) < 1 && Math.abs(after[2] - before[2] - 25) < 1, `a drag of (60, 25) moves a point by (${(after[1] - before[1]).toFixed(1)}, ${(after[2] - before[2]).toFixed(1)})`);
  // the wheel zooms about the pointer: the point under it stays there
  const spread = sh => { const xs = sh.filter(x => kindOf(x) === 'disc' && x.alpha === 1).map(x => x.path[0][1]); return Math.max(...xs) - Math.min(...xs); };
  const sh0 = await shapes(), p0 = firstDisc(sh0).path[0], s0 = spread(sh0);
  const [px, py] = [canvas[0] + p0[1], canvas[1] + p0[2]];
  await page.mouse.move(px, py); await page.mouse.wheel(0, -400); await page.waitForTimeout(200);
  const sh1 = await shapes(), p1 = firstDisc(sh1).path[0], s1 = spread(sh1);
  check(Math.abs(p1[1] - p0[1]) < 1 && Math.abs(p1[2] - p0[2]) < 1, `zooming at a point keeps it in place (${(p1[1] - p0[1]).toFixed(2)}, ${(p1[2] - p0[2]).toFixed(2)})`);
  check(s1 > 1.3 * s0 && s1 < 1.7 * s0, `the picture grew from ${s0.toFixed(0)} to ${s1.toFixed(0)} px across`);
  check(errors.length === 0, `no errors on the large page ${errors.join(' | ')}`);
  await page.close();
}

// the pixels that differ from the first one in the strips above and to the right of a plot box of a canvas: nothing is drawn there
const marginOf = (page, box) => page.evaluate(([x0, y0, x1, y1]) => { const c = document.querySelector('canvas'), k = c.width / c.getBoundingClientRect().width, ctx = c.getContext('2d');
  const strip = (xa, ya, xb, yb) => { const d = ctx.getImageData(Math.round(xa * k), Math.round(ya * k), Math.max(1, Math.round((xb - xa) * k)), Math.max(1, Math.round((yb - ya) * k))).data;
    let odd = 0; for (let i = 4; i < d.length; i += 4) if (d[i] !== d[0] || d[i + 1] !== d[1] || d[i + 2] !== d[2]) odd++; return odd; };
  return [strip(x0, 0, x1, y0 - 1), strip(x1 + 1, 0, c.getBoundingClientRect().width, y1)]; }, box);
// a checksum of the same two strips, to tell whether anything was painted over them since the last look (a uniform tint passes marginOf)
const marginHash = (page, box) => page.evaluate(([x0, y0, x1, y1]) => { const c = document.querySelector('canvas'), k = c.width / c.getBoundingClientRect().width, ctx = c.getContext('2d');
  const sum = (xa, ya, xb, yb) => { const d = ctx.getImageData(Math.round(xa * k), Math.round(ya * k), Math.max(1, Math.round((xb - xa) * k)), Math.max(1, Math.round((yb - ya) * k))).data;
    let h = 0; for (let i = 0; i < d.length; i++) h = (h * 31 + d[i]) % 1000003; return h; };
  return [sum(x0, 0, x1, y0 - 1), sum(x1 + 1, 0, c.getBoundingClientRect().width, y1)]; }, box);
// the axes of a flat canvas as the spy saw them (helpers of the next blocks)
// what the canvas painted last. Lines drawn with a font in force are the axes' and the grid's, in the order of the script: the
// grid first (inside the clip, under the items), then the plot box (bottom, left), the x marks and the y marks. The words come in
// two runs: the tick words (x first, then y, which are right-aligned) and then the labels of the axes
const paintedOf = page => page.evaluate(() => {
  const plain = s => s.ink === undefined || s.ink === '10px sans-serif';
  const axis = window.__shapes.filter(s => !plain(s)).map(s => [s.path[0][1], s.path[0][2], s.path[1][1], s.path[1][2]]);
  const last = window.__texts.map(t => t.align).lastIndexOf('right');
  const words = window.__texts.slice(0, last + 1), labels = window.__texts.slice(last + 1);
  const keep = 2 + words.length; // the box and one mark for each word
  const drawn = window.__shapes.filter(s => !plain(s)), ink = s => ({ color: s.color, width: s.width, alpha: s.alpha });
  return { lines: axis.slice(axis.length - keep), grid: axis.slice(0, axis.length - keep), texts: words, labels,
    gridInk: axis.length > keep ? ink(drawn[0]) : null, axisInk: ink(drawn[drawn.length - 1]),
    discs: window.__shapes.filter(s => plain(s) && s.path[0][0] === 'a' && s.op === 'fill').map(s => [s.path[0][1], s.path[0][2]]) };
});
// the axes as numbers: a tick is its value and the pixel it is drawn at
const ticksOf = p => {
  const [bottom, left] = p.lines;
  const marks = p.lines.slice(2);
  const xm = marks.filter(l => l[0] === l[2]).map(l => l[0]), ym = marks.filter(l => l[1] === l[3]).map(l => l[1]);
  const xw = p.texts.filter(t => t.align === 'center'), yw = p.texts.filter(t => t.align === 'right');
  return { box: [left[0], left[1], bottom[2], bottom[3]], xm, ym, xw: xw.map(t => t.text), yw: yw.map(t => t.text), xwAt: xw.map(t => t.x) };
};
// the transforms an axis may have, as the library defines them (Transform.apply): the identity, log10, the signed square root,
// and the symmetric log with a linear stretch of 2
const TR = { i: v => v, l: Math.log10, s: v => Math.sign(v) * Math.sqrt(Math.abs(v)), m2: v => Math.sign(v) * Math.log10(1 + Math.abs(v) / 2) };
// the pixel of a data value according to the ticks: the line through the first and the last, in the units of the transform
const place = (marks, words, T = TR.i) => { const v = words.map(parseFloat).map(T), a = 0, b = words.length - 1; return x => marks[a] + (T(x) - v[a]) / (v[b] - v[a]) * (marks[b] - marks[a]); };
const fits = (marks, words, T = TR.i) => { const f = place(marks, words, T), v = words.map(parseFloat); return words.length >= 3 && v.every((x, i) => Math.abs(f(x) - marks[i]) < 0.01); };
// the axes of a still, as fractions of its plot box (a canvas has another size): the marks and the words, the gridlines, the
// labels (their text, where they stand and whether they are turned)
const stillAxesOf = text => inStill(text, doc => {
  const g = doc.querySelector('g.tda4j-axes'), four = l => ['x1', 'y1', 'x2', 'y2'].map(a => +l.getAttribute(a));
  const ls = [...g.querySelectorAll('line')].map(four), grid = [...doc.querySelectorAll('g.tda4j-grid line')].map(four);
  const [bottom, left] = ls, marks = ls.slice(2), box = [left[0], left[1], bottom[2], bottom[3]];
  const fx = x => (x - box[0]) / (box[2] - box[0]), fy = y => (y - box[1]) / (box[3] - box[1]);
  const upright = l => l[0] === l[2], level = l => l[1] === l[3];
  return { box, xs: marks.filter(upright).map(l => fx(l[0])), ys: marks.filter(level).map(l => fy(l[1])),
    xw: [...g.querySelectorAll('text[text-anchor=middle]:not(.tda4j-axis-label)')].map(t => t.textContent), yw: [...g.querySelectorAll('text[text-anchor=end]')].map(t => t.textContent),
    gridX: grid.filter(upright).map(l => fx(l[0])), gridY: grid.filter(level).map(l => fy(l[1])),
    gridInk: (() => { const e = doc.querySelector('g.tda4j-grid'); return e ? { color: /stroke:([^;]+)/.exec(e.getAttribute('style'))[1], width: +e.getAttribute('stroke-width'), alpha: +(/stroke-opacity:([^;]+)/.exec(e.getAttribute('style')) || [0, 1])[1] } : null; })(),
    axisInk: { color: /stroke:([^;]+)/.exec(g.getAttribute('style'))[1], width: +g.getAttribute('stroke-width'), alpha: 1 },
    gridSpan: grid.every(l => upright(l) ? Math.abs(l[1] - box[1]) < 0.01 && Math.abs(l[3] - box[3]) < 0.01 : Math.abs(l[0] - box[0]) < 0.01 && Math.abs(l[2] - box[2]) < 0.01),
    labels: [...g.querySelectorAll('text.tda4j-axis-label')].map(t => ({ text: t.textContent, x: +t.getAttribute('x'), y: +t.getAttribute('y'), turned: /rotate\(-90/.test(t.getAttribute('transform') || ''),
      size: +t.getAttribute('font-size'), fromBottom: +t.getAttribute('y') - box[3], fy: fy(+t.getAttribute('y')), fx: fx(+t.getAttribute('x')) })) };
});
// the pen of the gridlines and of the axes: color, width and opacity, the still's against the canvas's
const sameInk = (a, b) => a !== null && b !== null && a.color.toLowerCase() === b.color.toLowerCase() && Math.abs(a.width - b.width) < 1e-9 && Math.abs(a.alpha - b.alpha) < 1e-9;
const frac = (ms, lo, hi) => ms.map(m => (m - lo) / (hi - lo));
const near = (a, b) => a.length === b.length && a.every((v, i) => Math.abs(v - b[i]) < 0.003);
// the gridlines of the canvas as fractions of its plot box
const gridOf = (p, t) => ({ x: frac(p.grid.filter(l => l[0] === l[2]).map(l => l[0]), t.box[0], t.box[2]), y: frac(p.grid.filter(l => l[1] === l[3]).map(l => l[1]), t.box[1], t.box[3]),
  span: p.grid.every(l => l[0] === l[2] ? Math.abs(l[1] - t.box[1]) < 0.01 && Math.abs(l[3] - t.box[3]) < 0.01 : Math.abs(l[0] - t.box[0]) < 0.01 && Math.abs(l[2] - t.box[2]) < 0.01) });
// the labels of the canvas in the terms of stillAxesOf (a label turned to read upwards is drawn at the origin of a context turned by a quarter, counter-clockwise)
const labelsOf = (p, t) => p.labels.map(l => { const turned = l.turn[1] < -0.5, x = turned ? l.turn[2] : l.x, y = turned ? l.turn[3] : l.y;
  return { text: l.text, turned, size: parseFloat(l.font), x, y, fromBottom: y - t.box[3], fy: (y - t.box[1]) / (t.box[3] - t.box[1]), fx: (x - t.box[0]) / (t.box[2] - t.box[0]) }; });
// a gridline on every mark (and none elsewhere), across the plot box
const onMarks = (p, t, which = 'xy') => { const g = gridOf(p, t); return g.span && (!which.includes('x') || near(g.x, frac(t.xm, t.box[0], t.box[2]))) && (!which.includes('y') || near(g.y, frac(t.ym, t.box[1], t.box[3]))); };

{ // the axes of a flat canvas: the ticks it paints are the still's at the start, and after a zoom and a pan they still say what the picture shows
  const { points } = JSON.parse(read('interactive-large.json'));
  const page = await browser.newPage({ viewport: { width: 1800, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(spy);
  await page.goto('file://' + dir + '/interactive-large.html');
  const painted = () => paintedOf(page);
  // does every data point have its disc where the ticks put it?
  const agree = (p, t) => { const px = place(t.xm, t.xw), py = place(t.ym, t.yw);
    const miss = points.filter(([x, y]) => !p.discs.some(d => Math.abs(d[0] - px(x)) < 0.6 && Math.abs(d[1] - py(y)) < 0.6));
    return miss.length; };

  const p0 = await painted(), t0 = ticksOf(p0);
  check(t0.xw.length >= 3 && t0.yw.length >= 3 && t0.xm.length === t0.xw.length && t0.ym.length === t0.yw.length, `the canvas has a mark for each word: x ${t0.xw}, y ${t0.yw}`);
  check(t0.xwAt.every((x, i) => Math.abs(x - t0.xm[i]) < 1e-6), 'each word of the x axis stands at its mark');
  check(fits(t0.xm, t0.xw) && fits(t0.ym, t0.yw), 'the marks are evenly spaced as their words say');
  check(agree(p0, t0) === 0, `at the start every point is where its ticks put it (${agree(p0, t0)} of ${points.length} are not)`);
  // the still of the same picture: the same words, at the same places in the plot box, and gridlines at the marks
  const stillAxes = await stillAxesOf(read('interactive-large-still.svg'));
  check(JSON.stringify(t0.xw) === JSON.stringify(stillAxes.xw) && JSON.stringify(t0.yw) === JSON.stringify(stillAxes.yw), `the canvas says ${t0.xw} / ${t0.yw}, the still ${stillAxes.xw} / ${stillAxes.yw}`);
  check(near(frac(t0.xm, t0.box[0], t0.box[2]), stillAxes.xs) && near(frac(t0.ym, t0.box[1], t0.box[3]), stillAxes.ys), 'the marks stand at the same fractions of the plot box as the still\'s');
  const g0 = gridOf(p0, t0);
  check(stillAxes.gridX.length === t0.xm.length && stillAxes.gridY.length === t0.ym.length, `the still has a gridline at each mark: ${stillAxes.gridX.length} and ${stillAxes.gridY.length}`);
  check(near(g0.x, stillAxes.gridX) && near(g0.y, stillAxes.gridY) && g0.span && stillAxes.gridSpan, `the canvas's gridlines are the still's (${g0.x.length} + ${g0.y.length} lines, across the whole plot box)`);
  check(near(g0.x, frac(t0.xm, t0.box[0], t0.box[2])) && near(g0.y, frac(t0.ym, t0.box[1], t0.box[3])), 'each gridline lies on a mark');
  check(sameInk(p0.gridInk, stillAxes.gridInk) && sameInk(p0.axisInk, stillAxes.axisInk), `the pens are the still's: grid ${JSON.stringify(p0.gridInk)} / ${JSON.stringify(stillAxes.gridInk)}, axes ${JSON.stringify(p0.axisInk)} / ${JSON.stringify(stillAxes.axisInk)}`);
  check(p0.labels.length === 0 && stillAxes.labels.length === 0, 'a picture with no label asked for draws none');

  const cv = await page.evaluate(() => { const r = document.querySelector('canvas').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
  // the wheel zooms about a point of the picture: the words change, and they still say where things are
  const [qx, qy] = [cv[0] + t0.box[0] + 0.3 * (t0.box[2] - t0.box[0]), cv[1] + t0.box[1] + 0.6 * (t0.box[3] - t0.box[1])];
  await page.mouse.move(qx, qy); await page.mouse.wheel(0, -500); await page.waitForTimeout(200);
  const p1 = await painted(), t1 = ticksOf(p1);
  check(JSON.stringify(t1.xw) !== JSON.stringify(t0.xw), `zooming in changes the words: ${t0.xw} then ${t1.xw}`);
  check(t1.xw.length >= 3 && fits(t1.xm, t1.xw) && fits(t1.ym, t1.yw), 'the words are evenly spaced after the zoom');
  check(agree(p1, t1) === 0, `after the zoom every point is where its ticks put it (${agree(p1, t1)} of ${points.length} are not)`);
  check(onMarks(p1, t1), 'the gridlines follow the marks after the zoom');
  check(JSON.stringify(t1.box) === JSON.stringify(t0.box), 'the plot box does not move with the picture');
  // a drag pans: the words follow
  await page.mouse.move(cv[0] + t0.box[0] + 100, cv[1] + t0.box[1] + 100); await page.mouse.down();
  await page.mouse.move(cv[0] + t0.box[0] + 330, cv[1] + t0.box[1] + 40, { steps: 8 }); await page.mouse.up(); await page.waitForTimeout(200);
  const p2 = await painted(), t2 = ticksOf(p2);
  check(JSON.stringify(t2.xw) !== JSON.stringify(t1.xw) || JSON.stringify(t2.yw) !== JSON.stringify(t1.yw), `panning changes the words: ${t1.xw} / ${t1.yw} then ${t2.xw} / ${t2.yw}`);
  check(fits(t2.xm, t2.xw) && fits(t2.ym, t2.yw), 'the words are evenly spaced after the pan');
  check(agree(p2, t2) === 0, `after the pan every point is where its ticks put it (${agree(p2, t2)} of ${points.length} are not)`);
  check(onMarks(p2, t2), 'the gridlines follow the marks after the pan');
  // the items are cut at the plot box: a disc centred beyond it leaves nothing in the margin above or to the right of it
  const beyond = p2.discs.filter(d => d[1] < t2.box[1] - 1 || d[0] > t2.box[2] + 1).length;
  const margin = await marginOf(page, t2.box);
  check(beyond > 0, `after zooming and panning ${beyond} discs are centred above or right of the plot box, so the clip has something to hide`);
  check(margin[0] === 0 && margin[1] === 0, `the margins above and right of the plot box hold nothing but the surface (${margin} pixels differ)`);
  check(errors.length === 0, `no errors on the canvas axes ${errors.join(' | ')}`);
  await page.close();
}

// flat canvases on other scales, with labels and gridlines: a log-log picture and a square-root x axis over a symmetric log y axis
// (gridlines on x only). The page's words, marks, gridlines and labels are its still's; they follow a zoom and a pan; every point
// stands where the transformed ticks put it
for (const [name, Tx, Ty, texts, gridded] of [['interactive-axes-log', TR.l, TR.l, ['birth', 'death'], 'xy'], ['interactive-axes-scales', TR.s, TR.m2, ['size', 'signed value'], 'x']]) {
  const { points } = JSON.parse(read(`${name}.json`));
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(spy);
  await page.goto(`file://${dir}/${name}.html`);
  const painted = () => paintedOf(page);
  const agree = (p, t) => { const px = place(t.xm, t.xw, Tx), py = place(t.ym, t.yw, Ty);
    return points.filter(([x, y]) => !p.discs.some(d => Math.abs(d[0] - px(x)) < 0.6 && Math.abs(d[1] - py(y)) < 0.6)).length; };
  const sameLabel = (a, b) => a.text === b.text && a.turned === b.turned && Math.abs(a.size - b.size) < 0.01 &&
    (a.turned ? Math.abs(a.x - b.x) < 0.02 && Math.abs(a.fy - b.fy) < 0.003 : Math.abs(a.fx - b.fx) < 0.003 && Math.abs(a.fromBottom - b.fromBottom) < 0.02);
  const p0 = await painted(), t0 = ticksOf(p0);
  const ref = await stillAxesOf(read(`${name}-still.svg`));
  check(t0.xw.length >= 3 && t0.yw.length >= 3 && t0.xm.length === t0.xw.length && t0.ym.length === t0.yw.length, `${name}: a mark for each word: x ${t0.xw}, y ${t0.yw}`);
  check(JSON.stringify(t0.xw) === JSON.stringify(ref.xw) && JSON.stringify(t0.yw) === JSON.stringify(ref.yw), `${name}: the canvas says ${t0.xw} / ${t0.yw}, the still ${ref.xw} / ${ref.yw}`);
  check(near(frac(t0.xm, t0.box[0], t0.box[2]), ref.xs) && near(frac(t0.ym, t0.box[1], t0.box[3]), ref.ys), `${name}: the marks stand at the still's fractions of the plot box`);
  check(fits(t0.xm, t0.xw, Tx) && fits(t0.ym, t0.yw, Ty), `${name}: the marks are spaced as their words say, after the transforms of the axes`);
  check(agree(p0, t0) === 0, `${name}: every point is where its ticks put it (${agree(p0, t0)} of ${points.length} are not)`);
  const g0 = gridOf(p0, t0);
  check(near(g0.x, ref.gridX) && near(g0.y, ref.gridY) && g0.span && ref.gridSpan, `${name}: the gridlines are the still's (${g0.x.length} + ${g0.y.length} lines; the still has ${ref.gridX.length} + ${ref.gridY.length})`);
  check((g0.x.length > 0) === gridded.includes('x') && (g0.y.length > 0) === gridded.includes('y') && onMarks(p0, t0, gridded), `${name}: gridlines on the ${gridded} axis marks only`);
  check(sameInk(p0.gridInk, ref.gridInk) && sameInk(p0.axisInk, ref.axisInk), `${name}: the pens are the still's: grid ${JSON.stringify(p0.gridInk)} / ${JSON.stringify(ref.gridInk)}, axes ${JSON.stringify(p0.axisInk)} / ${JSON.stringify(ref.axisInk)}`);
  const l0 = labelsOf(p0, t0);
  check(JSON.stringify(l0.map(l => l.text)) === JSON.stringify(texts) && l0.length === ref.labels.length && l0.every((l, i) => sameLabel(l, ref.labels[i])), `${name}: the labels are the still's: ${JSON.stringify(l0.map(l => [l.text, l.turned, +l.x.toFixed(1), +l.y.toFixed(1)]))} against ${JSON.stringify(ref.labels.map(l => [l.text, l.turned, l.x, l.y]))}`);
  check(l0.length === 2 && !l0[0].turned && l0[1].turned, `${name}: the y label is turned to read upwards, the x label is not`);
  const cv = await page.evaluate(() => { const r = document.querySelector('canvas').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
  const [qx, qy] = [cv[0] + t0.box[0] + 0.3 * (t0.box[2] - t0.box[0]), cv[1] + t0.box[1] + 0.6 * (t0.box[3] - t0.box[1])];
  let prev = t0;
  for (const [what, act] of [['zoom in', async () => { await page.mouse.move(qx, qy); await page.mouse.wheel(0, -700); }],
                             ['zoom in again', async () => { await page.mouse.wheel(0, -900); }],
                             ['pan', async () => { await page.mouse.move(qx, qy); await page.mouse.down(); await page.mouse.move(qx + 120, qy - 60, { steps: 8 }); await page.mouse.up(); }]]) {
    await act(); await page.waitForTimeout(250);
    const p = await painted(), t = ticksOf(p);
    check(JSON.stringify(t.xw) !== JSON.stringify(prev.xw) || JSON.stringify(t.yw) !== JSON.stringify(prev.yw), `${name} ${what}: the words change (${prev.xw} / ${prev.yw} then ${t.xw} / ${t.yw})`);
    check(fits(t.xm, t.xw, Tx) && fits(t.ym, t.yw, Ty), `${name} ${what}: the marks are spaced as their words say (${t.xw} / ${t.yw})`);
    check(agree(p, t) === 0, `${name} ${what}: every point is where its ticks put it (${agree(p, t)} of ${points.length} are not)`);
    check(onMarks(p, t, gridded) && (gridOf(p, t).x.length > 0) === gridded.includes('x') && (gridOf(p, t).y.length > 0) === gridded.includes('y'), `${name} ${what}: the gridlines are on the marks`);
    const l = labelsOf(p, t);
    check(JSON.stringify(l) === JSON.stringify(l0) && JSON.stringify(t.box) === JSON.stringify(t0.box), `${name} ${what}: the labels and the plot box stay where they were`);
    prev = t;
  }
  check(errors.length === 0, `${name}: no errors ${errors.join(' | ')}`);
  await page.close();
}

{ // a flat canvas with axes whose points choose and that takes a rectangle: pointing and the rectangle see only what the plot area shows
  const page = await browser.newPage({ viewport: { width: 1300, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(spy);
  await page.goto('file://' + dir + '/interactive-axes-pick.html');
  const grid = []; for (const y of [-1, 0, 1]) for (const x of [-2, -1, 0, 1, 2]) grid.push([x, y]); // the Gallery's points: the key is the index
  const cv = await page.evaluate(() => { const r = document.querySelector('canvas').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
  const chosen = () => page.evaluate(() => [...document.querySelector('.tda4j-ia').tda4j.selection('item')].sort((a, b) => a - b));
  const view = async () => { const t = ticksOf(await paintedOf(page)); return { t, px: place(t.xm, t.xw), py: place(t.ym, t.yw) }; };
  let v = await view();
  const at = k => [cv[0] + v.px(grid[k][0]), cv[1] + v.py(grid[k][1])];
  const corner = () => [cv[0] + v.t.box[0] + 3, cv[1] + v.t.box[1] + 3];
  for (const k of [0, 7, 14]) {
    await page.mouse.click(...corner()); await page.mouse.click(...at(k)); await page.waitForTimeout(80);
    const got = await chosen();
    check(got.length === 1 && got[0] === k, `pointing at item ${k} in the plot box chooses it: [${got}]`);
  }
  // a drag pans the picture down until the bottom row (keys 0 to 4) lies in the margin below the plot box
  const dy = v.t.box[3] + 12 - v.py(-1);
  await page.mouse.click(...corner());
  await page.mouse.move(cv[0] + v.t.box[0] + 200, cv[1] + v.t.box[1] + 100); await page.mouse.down();
  await page.mouse.move(cv[0] + v.t.box[0] + 200, cv[1] + v.t.box[1] + 100 + dy, { steps: 8 }); await page.mouse.up(); await page.waitForTimeout(150);
  v = await view();
  check(Math.abs(v.py(-1) - (v.t.box[3] + 12)) < 1, `the bottom row lies ${(v.py(-1) - v.t.box[3]).toFixed(1)} px below the plot box`);
  await page.mouse.click(...at(2)); await page.waitForTimeout(80);
  check((await chosen()).length === 0, `pointing at an item in the margin chooses nothing: [${await chosen()}]`);
  await page.mouse.click(...at(7)); await page.waitForTimeout(80);
  check(JSON.stringify(await chosen()) === '[7]', `pointing at one in the plot box still does: [${await chosen()}]`);
  // the box button: a rectangle drawn only in the margin touches nothing, one that starts there takes what is inside the plot box
  await page.click('.tda4j-box');
  const drag = async (a, b) => { await page.mouse.move(...a); await page.mouse.down(); await page.mouse.move(...b, { steps: 8 }); await page.mouse.up(); await page.waitForTimeout(100); };
  await drag([cv[0] + v.t.box[0] + 8, cv[1] + v.t.box[3] + 4], [cv[0] + v.t.box[2] - 8, cv[1] + v.t.box[3] + 22]);
  check((await chosen()).length === 0, `a rectangle in the margin chooses nothing: [${await chosen()}]`);
  await drag([cv[0] + v.t.box[0] + 8, cv[1] + v.t.box[3] + 22], [cv[0] + v.t.box[2] - 8, cv[1] + v.py(0) - 6]);
  check(JSON.stringify(await chosen()) === '[5,6,7,8,9]', `a rectangle from the margin up past the middle row chooses that row alone: [${await chosen()}]`);
  // the keyboard: ] [ Home End step through the items the plot box shows (left to right, top to bottom), and two Enters take the rectangle between two of them
  await page.mouse.click(...corner());
  await page.focus('canvas');
  const crossNow = () => page.evaluate(() => { const c = window.__shapes.find(s => s.op === 'stroke' && s.width === 1.5 && s.path.length === 4 && s.ink === '10px sans-serif'); return c ? [c.path[0][1] + 8, c.path[0][2]] : null; });
  const keyPress = async (k, n = 1) => { for (let i = 0; i < n; i++) await page.keyboard.press(k); await page.waitForTimeout(30); };
  // the redraws a key costs: one (the spy counts the surface painted at the start of each)
  const redraws = async k => { const a = await page.evaluate(() => window.__draws); await keyPress(k); await page.waitForTimeout(60); return (await page.evaluate(() => window.__draws)) - a; };
  // what is in the plot box now: the bottom row (keys 0 to 4) is in the margin; top to bottom is y descending
  const seen = [10, 5, 11, 6, 12, 7, 13, 8, 14, 9];
  const where = k => [v.px(grid[k][0]), v.py(grid[k][1])];
  const onItem = async (i, what) => { const c = await crossNow(), want = where(seen[i]), lit = await chosen();
    check(c && Math.abs(c[0] - want[0]) < 0.6 && Math.abs(c[1] - want[1]) < 0.6 && JSON.stringify(lit) === JSON.stringify([seen[i]]),
      `${what}: the cross is on item ${seen[i]} and lights it: cross ${c && c.map(x => x.toFixed(1))}, wanted ${want.map(x => x.toFixed(1))}, lit [${lit}]`); };
  // a pointer move still waiting for its frame must not put out the light that the key has just lit (WebKit ran the frame after the key)
  await page.evaluate(() => { const c = document.querySelector('canvas'), r = c.getBoundingClientRect();
    c.dispatchEvent(new PointerEvent('pointermove', { clientX: r.left + 2, clientY: r.top + 2, pointerId: 1, bubbles: true }));
    c.dispatchEvent(new KeyboardEvent('keydown', { key: ']', bubbles: true })); });
  await page.waitForTimeout(80); await onItem(0, '] with no cross, a pointer move waiting for its frame');
  const costs = [await redraws(']')]; await onItem(1, '] again');
  await keyPress(']', 2); await onItem(3, 'two more');
  costs.push(await redraws('[')); await onItem(2, '[');
  costs.push(await redraws('End')); await onItem(9, 'End (the hidden row is skipped)');
  check(costs.every(c => c === 1), `a key that steps to an item redraws the picture once: ${costs}`);
  await keyPress(']'); await onItem(9, '] at the last item stays');
  await keyPress('Home'); await onItem(0, 'Home');
  await keyPress('PageDown'); await onItem(1, 'Page Down');
  await keyPress('PageDown'); await onItem(2, 'Page Down again');
  await keyPress('PageUp'); await onItem(1, 'Page Up');
  await keyPress('Home');
  await keyPress('['); await onItem(0, '[ at the first item stays');
  const letGoCost = await redraws('Escape');
  check(await crossNow() === null && (await chosen()).length === 0, 'Escape takes the cross away and puts the light out');
  check(letGoCost === 1, `Escape redraws the picture once: ${letGoCost}`);
  // the rectangle from item 6 (x -1, y 0) to item 11 (x 2, y 1): the items of the plot box inside it
  await keyPress('Home'); await keyPress(']', 3); await keyPress('Enter'); await keyPress(']', 5);
  const rubberband = await page.evaluate(() => window.__shapes.some(s => s.path[0][0] === 'r'));
  await keyPress('Enter');
  const [xa, xb] = [-1, 2], [ya, yb] = [0, 1];
  const expectKeys = grid.map((g, k) => [g, k]).filter(([g]) => g[0] >= xa && g[0] <= xb && g[1] >= ya && g[1] <= yb).map(([, k]) => k).sort((a, b) => a - b);
  check(rubberband, 'the rectangle is drawn between the two Enters');
  check(JSON.stringify(await chosen()) === JSON.stringify(expectKeys), `the rectangle between items 6 and 14 chooses what lies in it: [${await chosen()}], wanted [${expectKeys}]`);
  check(await crossNow() === null, 'the cross is gone once it has chosen');
  await keyPress('Home'); await keyPress('Enter'); await keyPress('Enter');
  check(JSON.stringify(await chosen()) === '[10]', `two Enters on one item choose it alone: [${await chosen()}]`);
  // the arrows move the cross a twenty-fifth of the plot box (shift: four twenty-fifths) and hold it inside
  await page.mouse.click(...corner()); await page.focus('canvas');
  const [bx0, by0, bx1, by1] = v.t.box, [mx0, my0] = [(bx0 + bx1) / 2, (by0 + by1) / 2];
  const crossIs = async (want, what) => { const c = await crossNow(); check(c && Math.abs(c[0] - want[0]) < 0.6 && Math.abs(c[1] - want[1]) < 0.6, `${what}: ${c && c.map(x => x.toFixed(1))}, wanted ${want.map(x => x.toFixed(1))}`); };
  await keyPress('ArrowRight'); await crossIs([mx0 + 0.04 * (bx1 - bx0), my0], 'one arrow moves the cross 4% of the box');
  await keyPress('Shift+ArrowUp'); await crossIs([mx0 + 0.04 * (bx1 - bx0), my0 - 0.16 * (by1 - by0)], 'shift-arrow moves it 16%');
  await keyPress('Shift+ArrowRight', 8); await crossIs([bx1, my0 - 0.16 * (by1 - by0)], 'and it stays inside the box on the right');
  await keyPress('Shift+ArrowUp', 8); await crossIs([bx1, by0], 'and at the top');
  await keyPress('Escape');
  // a rectangle set from the data may reach beyond the plot box (here far beyond): it is cut at the box, over the margins as well
  await page.mouse.click(...corner()); await page.waitForTimeout(100);
  const quiet = await marginHash(page, v.t.box);
  await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.brush(0, [-9, 9, -9, 9])); await page.waitForTimeout(100);
  const over = await marginHash(page, v.t.box);
  check(await page.evaluate(() => window.__shapes.some(s => s.path[0][0] === 'r')) && JSON.stringify(over) === JSON.stringify(quiet), `a rectangle larger than the plot box leaves the margins above and right of it as they were (${quiet} then ${over})`);
  await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.brush(0, null));
  check(errors.length === 0, `no errors on the flat axes page ${errors.join(' | ')}`);
  await page.close();
}

{ // two items on one place: the cross steps to the place, and every item there lights up
  const page = await browser.newPage({ viewport: { width: 900, height: 900 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(`file://${dir}/interactive-brush-twins.html`);
  const items = await page.evaluate(() => [...document.querySelectorAll('.tda4j-ia svg[tabindex] [data-ia]')].filter(e => /(^|;)s,/.test(e.getAttribute('data-ia')) && e.getClientRects().length).map(e => {
    const r = e.getBoundingClientRect(); return { key: +e.getAttribute('data-ia').split(';').find(s => s.startsWith('s,')).split(',')[2], at: [(r.left + r.right) / 2, (r.top + r.bottom) / 2] }; }));
  const groups = []; for (const it of items) { const g = groups.find(h => Math.abs(h.at[0] - it.at[0]) < 1e-6 && Math.abs(h.at[1] - it.at[1]) < 1e-6); if (g) g.keys.push(it.key); else groups.push({ at: it.at, keys: [it.key] }); }
  groups.sort((a, b) => a.at[0] - b.at[0] || a.at[1] - b.at[1]);
  const lit = () => page.evaluate(() => [...document.querySelector('.tda4j-ia').tda4j.selection('c')].sort((a, b) => a - b));
  check(groups.length === 40 && groups.filter(g => g.keys.length === 2).length === 2, `${groups.length} places, two of them with two items: ${groups.filter(g => g.keys.length === 2).map(g => '[' + g.keys.sort((a, b) => a - b) + ']')}`);
  await page.focus('.tda4j-ia svg[tabindex]'); await page.mouse.move(2, 2); await page.waitForTimeout(100);
  const bad = [];
  for (let i = 0; i < groups.length; i++) { await page.keyboard.press(']'); const got = await lit(), want = groups[i].keys.slice().sort((a, b) => a - b);
    if (JSON.stringify(got) !== JSON.stringify(want)) bad.push(`place ${i}: lit [${got}], wanted [${want}]`); }
  check(bad.length === 0, `stepping with ] through every place lights exactly the items there` + (bad.length ? ` -- ${bad.slice(0, 3).join(' || ')}` : ''));
  await page.keyboard.press(']');
  check(JSON.stringify(await lit()) === JSON.stringify(groups[groups.length - 1].keys.slice().sort((a, b) => a - b)), 'one more ] stays on the last place');
  await page.keyboard.press('Escape');
  check(errors.length === 0, `no errors on the twins page ${errors.join(' | ')}`);
  await page.close();
}

{ // zoom on log, square-root, symmetric log, reversed and formatted axes: the axes of the page are the axes of the still of the same window
  // what an axis draws: its lines and texts (not the marks, which the zoom moves as a whole), where, and as which words
  const axisRecords = root => {
    const hidden = e => { for (let n = e; n && n.nodeType === 1; n = n.parentNode) if (n.style && n.style.display === 'none') return true; return false; };
    const out = [];
    root.querySelectorAll('line, text').forEach(e => {
      if (e.closest('g[clip-path]') || e.closest('g[data-z]') || hidden(e)) return;
      if (e.getAttribute('font-weight') === '600' && +e.getAttribute('y') < 30) return; // the figure's title: a still draws it, the page puts it in its own heading
      const tag = e.tagName.toLowerCase();
      const nums = tag === 'line' ? ['x1', 'y1', 'x2', 'y2'].map(a => +e.getAttribute(a)) : [+e.getAttribute('x'), +e.getAttribute('y')];
      out.push({ tag, text: tag === 'text' ? e.textContent : '', nums, tr: ((e.getAttribute('transform') || '').match(/-?[0-9.]+/g) || []).map(Number), anchor: e.getAttribute('text-anchor') || '' });
    });
    return out;
  };
  const alike = (a, b) => a.tag === b.tag && a.text === b.text && a.anchor === b.anchor && a.nums.length === b.nums.length && a.tr.length === b.tr.length &&
    a.nums.every((v, i) => Math.abs(v - b.nums[i]) < 0.02) && a.tr.every((v, i) => Math.abs(v - b.tr[i]) < 0.02);
  const unmatched = (xs, ys) => { const rest = ys.slice(); const lost = [];
    for (const x of xs) { const i = rest.findIndex(y => alike(x, y)); if (i < 0) lost.push(x); else rest.splice(i, 1); }
    return { lost, extra: rest }; };
  const show = r => `${r.tag}${r.text ? ' "' + r.text + '"' : ''} @${r.nums.map(n => n.toFixed(1))}${r.tr.length ? ' rot' + r.tr[0] : ''}`;
  const liveRecords = page => page.evaluate(`(() => { const axisRecords = ${axisRecords.toString()}; return axisRecords(document.querySelector('.tda4j-ia svg[data-zm]')); })()`);
  for (const name of ['log', 'sqrt', 'symlog', 'reversed', 'format', 'logfar', 'extras']) {
    const meta = JSON.parse(read(`zoomaxes-${name}.json`));
    const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
    const errors = [];
    page.on('pageerror', e => errors.push(String(e)));
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    await page.goto(`file://${dir}/zoomaxes-${name}.html`);
    const bad = []; let drawn = 0, minor = 0;
    for (const [i, w] of meta.windows.entries()) {
      await page.evaluate(w => document.querySelector('.tda4j-ia').tda4j.zoom(0, w), w);
      const live = await liveRecords(page);
      const ref = await reader.evaluate(`(() => { const axisRecords = ${axisRecords.toString()}; const doc = new DOMParser().parseFromString(${JSON.stringify(meta.stills[i].replace(/^<\?xml[^>]*\?>\s*/, ''))}, 'image/svg+xml');
        const svg = [...doc.querySelectorAll('svg')].find(s => s.querySelector('g[clip-path]')); return axisRecords(svg); })()`);
      drawn += live.length; minor += live.filter(r => r.tag === 'line').length;
      const { lost, extra } = unmatched(live, ref);
      if (lost.length || extra.length) bad.push(`window ${i} ${JSON.stringify(w.map(v => +v.toPrecision(4)))}: page only ${lost.slice(0, 2).map(show)}; still only ${extra.slice(0, 2).map(show)}`);
    }
    check(bad.length === 0, `${name}: ${meta.windows.length} windows draw the axes of their stills (${drawn} elements)` + (bad.length ? ` -- ${bad.slice(0, 2).join(' || ')}` : ''));
    // a drag moves every mark with the pointer, and the wheel zooms about it, whatever the direction and scale of the axes
    // one mark, the nearest to the middle of the whole picture, followed through the drag and the zoom; a window is put around it
    // first (a mark outside the picture could not be pointed at: the wheel would go to the page, and zoom that)
    let which = null;
    const centre = async () => { const got = await page.evaluate(which => { const svg = document.querySelector('.tda4j-ia svg[data-zm]'), box = svg.getBoundingClientRect();
        const all = [...svg.querySelectorAll('g[data-z=p] [data-c]')].map(e => e.getBoundingClientRect()), mid = [box.left + box.width / 2, box.top + box.height / 2];
        const k = which ?? all.map((r, i) => [i, Math.hypot(r.left + r.width / 2 - mid[0], r.top + r.height / 2 - mid[1])]).sort((a, b) => a[1] - b[1])[0][0];
        const r = all[k]; return [k, r.left + r.width / 2, r.top + r.height / 2]; }, which);
      which = got[0]; return [got[1], got[2]]; };
    await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.resetZoom(0));
    {
      const [mx, my] = await centre();
      const [a, b] = await page.evaluate(([x, y]) => [document.querySelector('.tda4j-ia').tda4j.pointAt(0, x - 110, y - 80), document.querySelector('.tda4j-ia').tda4j.pointAt(0, x + 110, y + 80)], [mx, my]);
      await page.evaluate(w => document.querySelector('.tda4j-ia').tda4j.zoom(0, w), [Math.min(a[0], b[0]), Math.max(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[1], b[1])]);
    }
    const p0 = await centre();
    const svgBox = await page.evaluate(() => { const r = document.querySelector('.tda4j-ia svg[data-zm]').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
    const [gx, gy] = [svgBox[0] + svgBox[2] * 0.5, svgBox[1] + svgBox[3] * 0.5];
    await page.mouse.move(gx, gy); await page.mouse.down(); await page.mouse.move(gx + 50, gy - 25, { steps: 6 }); await page.mouse.up(); await page.waitForTimeout(100);
    const p1 = await centre();
    check(Math.abs(p1[0] - p0[0] - 50) < 1.5 && Math.abs(p1[1] - p0[1] + 25) < 1.5, `${name}: a drag of (50, -25) moves a mark by (${(p1[0] - p0[0]).toFixed(1)}, ${(p1[1] - p0[1]).toFixed(1)})`);
    await page.mouse.move(p1[0], p1[1]); await page.keyboard.down('Control'); await page.mouse.wheel(0, -300); await page.keyboard.up('Control'); await page.waitForTimeout(100);
    const p2 = await centre();
    check(Math.abs(p2[0] - p1[0]) < 1.5 && Math.abs(p2[1] - p1[1]) < 1.5, `${name}: zooming at a mark keeps it where it is (${(p2[0] - p1[0]).toFixed(1)}, ${(p2[1] - p1[1]).toFixed(1)})`);
    check(errors.length === 0, `${name}: no errors ${errors.join(' | ')}`);
    if (name === 'format') { // the page writes %f and %e itself: as String.format does, over the cases the Gallery asked Java for
      const cases = JSON.parse(read('formats.json')).cases;
      const got = await page.evaluate(cs => { const f = document.querySelector('.tda4j-ia').tda4j.text.format; return cs.map(c => f(c.p, Number(c.v))); }, cases);
      const wrong = cases.map((c, i) => [c, got[i]]).filter(([c, g]) => g !== c.out);
      check(wrong.length === 0, `format: ${cases.length} number formats read as Java writes them` +
        (wrong.length ? ` -- ${wrong.length} differ, e.g. ${wrong.slice(0, 4).map(([c, g]) => `${c.p} of ${c.v}: java "${c.out}", page "${g}"`).join('; ')}` : ''));
    }
    await page.close();
  }
}

{ // linked views: pointing at a class lights it up in every picture, as the still of that class does
  const key = JSON.parse(fs.readFileSync(dir + '/interactive-explore.json', 'utf8')).key;
  const still = fs.readFileSync(dir + '/interactive-explore-still.svg', 'utf8').replace(/^<\?xml[^>]*\?>\s*/, '');
  const page = await browser.newPage({ viewport: { width: 1800, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('file://' + dir + '/interactive-explore.html');
  await page.evaluate(() => { window.events = []; document.querySelector('.tda4j-ia').addEventListener('tda4j:change', e => window.events.push(e.detail)); });
  const drawn = () => page.evaluate(`
    (() => { const describe = ${describe.toString()};
      return [...document.querySelectorAll('.tda4j-ia svg[role=img]')].filter(s => s.querySelector('g[clip-path]')).map(s => describe(s, true)); })()`);
  const reference = await page.evaluate(`
    (() => { const describe = ${describe.toString()}; const holder = document.createElement('div'); holder.innerHTML = ${JSON.stringify(still)};
      return [...holder.querySelectorAll('svg[role=img]')].filter(s => s.querySelector('g[clip-path]')).map(s => describe(s, false)); })()`);
  const chosen = () => page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.selection('class'));
  const readout = () => page.evaluate(() => document.querySelector('.tda4j-selection output').textContent);
  // the middle of the item that chooses `k` in panel `p` (0 is the first panel with something to choose)
  const where = (p, k) => page.evaluate(([p, k]) => {
    const it = document.querySelector('.tda4j-ia').tda4j.items.filter(i => i.picks.length)
      .filter((i, _, all) => i.svg === [...new Set(all.map(j => j.svg))][p]).find(i => i.picks.some(q => q.key === k));
    const r = it.el.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2];
  }, [p, k]);
  const settle = () => page.waitForTimeout(120);
  const sameAsStill = async what => { const live = await drawn(); check(JSON.stringify(live) === JSON.stringify(reference), `${what}: the drawn elements are the still's (${live.map(l => l.length)} / ${reference.map(l => l.length)})`); };

  const hidden = await page.evaluate(() => [...document.querySelectorAll('[data-ia^="w,"]')].filter(e => e.style.display !== 'none' || e.hasAttribute('display')).length);
  check(hidden === 0, 'nothing is lit before a key is chosen');
  check((await chosen()).length === 0 && (await readout()) === 'nothing chosen', 'nothing is chosen and the list says so');

  const [dx, dy] = await where(0, key);
  await page.mouse.move(dx, dy); await settle();
  check(JSON.stringify(await chosen()) === JSON.stringify([key]), `pointing at the class ${key} in the diagram chooses it`);
  await sameAsStill('pointing at the diagram');
  const words = await readout();
  check(words.startsWith('H1') && words.includes('persistence'), `the list names the class (${words})`);
  await page.mouse.move(dx + 120, dy - 120); await settle();
  check((await chosen()).length === 0, 'moving off it lets it go');

  const [bx, by] = await where(1, key);
  await page.mouse.move(bx, by); await settle();
  check(JSON.stringify(await chosen()) === JSON.stringify([key]), 'pointing at the same class in the barcode chooses the same key');
  await sameAsStill('pointing at the barcode');
  await page.mouse.move(bx, by + 3); await settle();
  check(JSON.stringify(await chosen()) === JSON.stringify([key]), 'a thin bar is hit a few pixels off its centre');

  await page.mouse.click(bx, by); await settle();
  await page.mouse.move(bx + 10, by - 200); await settle();
  check(JSON.stringify(await chosen()) === JSON.stringify([key]), 'a click keeps the class chosen after the pointer leaves');
  await sameAsStill('after the click');
  await page.mouse.click(bx, by); await settle();
  await page.mouse.move(dx + 120, dy - 120); await settle();
  check((await chosen()).length === 0, 'a second click lets it go');

  await page.mouse.click(bx, by); await settle();
  const [ox, oy] = await where(1, 0);
  await page.keyboard.down('Shift'); await page.mouse.click(ox, oy); await page.keyboard.up('Shift'); await settle();
  const two = await chosen();
  check(two.length === 2 && two.includes(key) && two.includes(0), `a shift-click adds the class 0 (${two})`);
  // the empty corner below the diagonal of the diagram
  const [cx, cy] = await page.evaluate(() => {
    const svg = document.querySelector('.tda4j-ia').tda4j.items.find(i => i.picks.length).svg.getBoundingClientRect();
    return [svg.left + 0.8 * svg.width, svg.top + 0.78 * svg.height];
  });
  await page.mouse.click(cx, cy); await settle();
  check((await chosen()).length === 0, 'a click on the background lets all go');

  await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.select('class', [3]));
  check(JSON.stringify(await chosen()) === '[3]', 'the script API chooses a class');
  const events = await page.evaluate(() => window.events.filter(e => e.name === 'class').map(e => JSON.stringify(e.value)));
  check(events.length > 4 && events[events.length - 1] === '[3]', `every change is announced (${events.length} events)`);
  check(errors.length === 0, `no errors on the linked page ${errors.join(' | ')}`);
  await page.close();
}


{ // the linked sweep: the slider and the selection together are the still of both
  const key = JSON.parse(fs.readFileSync(dir + '/interactive-explore.json', 'utf8')).key;
  const still = fs.readFileSync(dir + '/interactive-linked-still.svg', 'utf8').replace(/^<\?xml[^>]*\?>\s*/, '');
  const page = await browser.newPage({ viewport: { width: 1800, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('file://' + dir + '/interactive-linked.html');
  await page.evaluate(k => { const a = document.querySelector('.tda4j-ia').tda4j; a.set('t', 0.5); a.select('class', [k]); }, key);
  const live = await page.evaluate(`
    (() => { const describe = ${describe.toString()};
      return [...document.querySelectorAll('.tda4j-ia svg[role=img]')].filter(s => s.querySelector('g[clip-path]')).map(s => describe(s, true)); })()`);
  const ref = await page.evaluate(`
    (() => { const describe = ${describe.toString()}; const holder = document.createElement('div'); holder.innerHTML = ${JSON.stringify(still)};
      return [...holder.querySelectorAll('svg[role=img]')].filter(s => s.querySelector('g[clip-path]')).map(s => describe(s, false)); })()`);
  check(live.length === ref.length && live.length === 3, `${live.length} panels on the linked sweep`);
  live.forEach((elements, k) => {
    const onlyLive = elements.filter(e => !ref[k].includes(e)).slice(0, 3), onlyStill = ref[k].filter(e => !elements.includes(e)).slice(0, 3);
    check(JSON.stringify(elements) === JSON.stringify(ref[k]), `linked sweep, panel ${k}: ${elements.length} drawn elements as in the still (only live ${onlyLive}; only still ${onlyStill})`);
  });
  check(errors.length === 0, `no errors on the linked sweep ${errors.join(' | ')}`);
  await page.close();
}


{ // brushing: a dragged rectangle chooses the classes it touches
  const meta = JSON.parse(fs.readFileSync(dir + '/interactive-explore.json', 'utf8'));
  const page = await browser.newPage({ viewport: { width: 1800, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('file://' + dir + '/interactive-explore.html');
  const chosen = () => page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.selection('class'));
  const sorted = a => JSON.stringify([...a].sort((x, y) => x - y));
  const drag = async (x0, y0, x1, y1, shift) => {
    if (shift) await page.keyboard.down('Shift');
    await page.mouse.move(x0, y0); await page.mouse.down(); await page.mouse.move((x0 + x1) / 2, (y0 + y1) / 2, { steps: 4 });
    await page.mouse.move(x1, y1, { steps: 4 }); await page.mouse.up();
    if (shift) await page.keyboard.up('Shift');
    await page.waitForTimeout(150);
  };
  // the boxes of the items that choose, by panel (the diagram, then the barcode), with their keys
  const panels = await page.evaluate(() => {
    const items = document.querySelector('.tda4j-ia').tda4j.items.filter(i => i.picks.length);
    const svgs = [...new Set(items.map(i => i.svg))];
    return svgs.map(svg => ({
      box: (r => [r.left, r.top, r.right, r.bottom])(svg.getBoundingClientRect()),
      items: items.filter(i => i.svg === svg).map(i => { const r = i.el.getBoundingClientRect(); return { key: i.picks[0].key, box: [r.left, r.top, r.right, r.bottom] }; })
    }));
  });
  const labels = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.selections[0].labels);
  check(panels.length === 2, `two panels brush (${panels.length})`);

  // the diagram: the column of H0 points at birth 0, dragged from its lower right corner to its upper left
  const h0 = panels[0].items.filter(i => labels[i.key].startsWith('H0'));
  const [x0, x1] = [Math.min(...h0.map(i => i.box[0])) - 8, Math.max(...h0.map(i => i.box[2])) + 8];
  const [y0, y1] = [Math.min(...h0.map(i => i.box[1])) - 8, Math.max(...h0.map(i => i.box[3])) + 8];
  await drag(x1, y1, x0, y0);
  const wanted = labels.map((l, k) => (l.startsWith('H0') ? k : -1)).filter(k => k >= 0);
  const got = await chosen();
  check(sorted(got) === sorted(wanted), `a drag over the H0 column chooses the ${wanted.length} classes of degree 0, and no other (${got.length})`);
  check(await page.evaluate(() => document.querySelectorAll('.tda4j-brush').length) === 1, 'the rectangle stays after the drag');
  check(got.length > 0, 'the click that ends a drag does not let the classes go');

  // the barcode: a stretch of the filtration, the pixels calibrated on the one H1 bar
  const [hk, hb, hd] = [meta.key, meta.bars[meta.key][1], meta.bars[meta.key][2]];
  const bar = panels[1].items.find(i => i.key === hk).box;
  const px = v => bar[0] + (v - hb) / (hd - hb) * (bar[2] - bar[0]);
  const [lo, hi] = [0.52, 0.6];
  await drag(px(lo), panels[1].box[1] + 3, px(hi), panels[1].box[3] - 3);
  const alive = meta.bars.map((b, k) => (b[1] <= hi && (b[2] === null || b[2] >= lo) ? k : -1)).filter(k => k >= 0);
  const inStretch = await chosen();
  check(alive.length >= 2 && sorted(inStretch) === sorted(alive), `a drag across ${lo} to ${hi} in the barcode chooses the ${alive.length} bars alive there (${inStretch.length})`);
  check(await page.evaluate(() => document.querySelectorAll('.tda4j-brush').length) === 1, 'a new drag replaces the rectangle');

  // shift-drag adds
  await drag(x1, y1, x0, y0, true);
  const both = await chosen();
  check(sorted(both) === sorted([...new Set([...alive, ...wanted])]), `a shift-drag adds to what is chosen (${both.length})`);

  // a click on the background lets everything go and takes the rectangle away
  const [cx, cy] = [panels[0].box[0] + 0.8 * (panels[0].box[2] - panels[0].box[0]), panels[0].box[1] + 0.78 * (panels[0].box[3] - panels[0].box[1])];
  await page.mouse.click(cx, cy); await page.waitForTimeout(150);
  check((await chosen()).length === 0 && (await page.evaluate(() => document.querySelectorAll('.tda4j-brush').length)) === 0, 'a click lets all go and removes the rectangle');
  check(errors.length === 0, `no errors while brushing ${errors.join(' | ')}`);
  await page.close();
}

// what a panel draws that can be compared: the tag (or the text) and the box of each visible line, circle, ellipse,
// polygon and text, in the panel's own units. Inside the plot area, a line along an axis is cut at the edge (that is
// what is seen of it), anything else counts only when it lies wholly inside; so what the window cuts through at a slant
// is left out of both sides alike.
const geometry = svg => {
  const to = svg.getScreenCTM().inverse();
  const clip = svg.querySelector('clipPath rect');
  const pr = { l: +clip.getAttribute('x'), t: +clip.getAttribute('y') };
  pr.r = pr.l + +clip.getAttribute('width'); pr.b = pr.t + +clip.getAttribute('height');
  const out = [];
  svg.querySelectorAll('line,circle,ellipse,polygon,text').forEach(el => {
    if (el.closest('defs')) return;
    for (let n = el; n && n !== svg; n = n.parentNode) if (n.style && n.style.display === 'none') return;
    const m = to.multiply(el.getScreenCTM()), b = el.getBBox();
    const xs = [], ys = [];
    [[b.x, b.y], [b.x + b.width, b.y], [b.x, b.y + b.height], [b.x + b.width, b.y + b.height]].forEach(([x, y]) => { const p = new DOMPoint(x, y).matrixTransform(m); xs.push(p.x); ys.push(p.y); });
    let l = Math.min(...xs), t = Math.min(...ys), r = Math.max(...xs), bt = Math.max(...ys);
    const tag = el.tagName.toLowerCase();
    if (el.closest('g[clip-path]')) {
      if (tag === 'line' && (r - l < 0.01 || bt - t < 0.01)) {
        l = Math.max(l, pr.l); t = Math.max(t, pr.t); r = Math.min(r, pr.r); bt = Math.min(bt, pr.b);
        if (r < l - 0.01 || bt < t - 0.01) return;
      } else if (l < pr.l - 0.5 || t < pr.t - 0.5 || r > pr.r + 0.5 || bt > pr.b + 0.5) return;
    }
    out.push([tag === 'text' ? el.textContent : tag, l, t, r, bt]);
  });
  return out;
};
const matches = (a, b, tol = 0.1) => {
  const used = new Set(); const missing = [];
  for (const x of a) {
    let found = -1;
    for (let j = 0; j < b.length; j++) {
      if (used.has(j) || b[j][0] !== x[0]) continue;
      if ([1, 2, 3, 4].every(i => Math.abs(b[j][i] - x[i]) <= tol)) { found = j; break; }
    }
    if (found < 0) missing.push(x); else used.add(found);
  }
  return { ok: a.length === b.length && missing.length === 0, missing: missing.slice(0, 3).map(m => m.map(v => typeof v === 'number' ? v.toFixed(1) : v).join(' ')) };
};

{ // zoom and pan: what the page shows in a window is what the still of that window shows
  const meta = JSON.parse(fs.readFileSync(dir + '/interactive-zoom.json', 'utf8'));
  const page = await browser.newPage({ viewport: { width: 1800, height: 900 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  const events = [];
  const live = (file, panel) => page.evaluate(`(() => { const g = ${geometry.toString()}; return g(document.querySelectorAll('.tda4j-ia svg[role=img]')[${panel}]); })()`);
  const stillOf = async (file, panel) => {
    const text = fs.readFileSync(`${dir}/${file}`, 'utf8').replace(/^<\?xml[^>]*\?>\s*/, '');
    return page.evaluate(`(() => { const g = ${geometry.toString()}; const holder = document.createElement('div');
      holder.style.cssText = 'position:absolute;left:0;top:0;width:900px;visibility:hidden'; holder.innerHTML = ${JSON.stringify(text)}; document.body.appendChild(holder);
      const s = [...holder.querySelectorAll('svg[role=img]')].filter(x => x.querySelector('g[clip-path]'))[${panel}];
      const r = g(s); holder.remove(); return r; })()`);
  };

  await page.goto('file://' + dir + '/interactive-zoom.html');
  await page.evaluate(() => document.querySelector('.tda4j-ia').addEventListener('tda4j:zoom', e => (window.zoomEvents = window.zoomEvents || []).push(e.detail)));
  const whole = await live(null, 0);
  check(await page.evaluate(() => document.querySelectorAll('.tda4j-zoombar button').length) === 3, 'a panel that only zooms has three buttons: in, out, everything');
  check(await page.evaluate(() => [...document.querySelectorAll('.tda4j-zoombar button')].map(b => b.textContent).join(' ')) === '+ \u2212 1:1', 'the buttons write their glyphs, not escape codes');
  for (const [i, w] of meta.windows.entries()) {
    await page.evaluate(w => document.querySelector('.tda4j-ia').tda4j.zoom(0, w), w);
    const mine = await live(null, 0), theirs = await stillOf(`interactive-zoom-${i}.svg`, 0);
    const m = matches(mine, theirs);
    check(m.ok, `window ${JSON.stringify(w)}: ${mine.length} drawn elements, ticks and numbers as in the still (${theirs.length})` + (m.ok ? '' : ` -- not in the still: ${m.missing.join(' | ')}`));
  }
  const tickNumbers = (await live(null, 0)).filter(e => /^[-0-9.e+]+$/.test(e[0])).map(e => e[0]);
  check(tickNumbers.some(t => /e[-+]\d\d$/.test(t)), `the numbers of a tiny window are written like the still writes them: ${tickNumbers.slice(0, 8).join(' ')}`);

  await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.resetZoom(0));
  const back = await live(null, 0);
  const mb = matches(back, whole);
  check(mb.ok, `resetting the zoom brings back the picture as it was (${back.length} elements)` + (mb.ok ? '' : ` -- ${mb.missing.join(' | ')}`));
  const ev = await page.evaluate(() => window.zoomEvents);
  check(ev.length === meta.windows.length + 1 && JSON.stringify(ev[0].window) === JSON.stringify(meta.windows[0]) && ev[0].panel === 0, `each change of the window announces itself (${ev.length} events)`);

  { // sixty windows: the words on the page are the words of the still, number for number
    const words = JSON.parse(fs.readFileSync(dir + '/interactive-zoom-words.json', 'utf8'));
    let wrong = [];
    for (const [i, w] of words.windows.entries()) {
      const got = await page.evaluate(w => { const t = document.querySelector('.tda4j-ia').tda4j; t.zoom(0, w);
        return [...document.querySelectorAll('.tda4j-ia svg[role=img] text')].filter(e => { for (let n = e; n && n.tagName !== 'svg'; n = n.parentNode) if (n.style && n.style.display === 'none') return false; return true; }).map(e => e.textContent); }, w);
      const a = [...got].sort(), b = [...words.words[i]].filter(t => !t.startsWith('Zoom and pan')).sort();
      if (JSON.stringify(a) !== JSON.stringify(b)) wrong.push(`${JSON.stringify(w)}: page ${a.join(' ')} / still ${b.join(' ')}`);
    }
    check(wrong.length === 0, `${words.windows.length} windows of every size write the same numbers as the still` + (wrong.length ? ` -- ${wrong.slice(0, 2).join(' || ')}` : ''));
    await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.resetZoom(0));
  }
  // the buttons and the wheel
  const win = () => page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(0));
  const full = await win();
  await page.click('.tda4j-zoombar button[aria-label="zoom in"]');
  const half = await win();
  const near = (a, b, r) => Math.abs(a - b) <= 1e-6 * r;
  check(near(half[1] - half[0], (full[1] - full[0]) / 2, full[1] - full[0]) && near((half[0] + half[1]) / 2, (full[0] + full[1]) / 2, full[1] - full[0]), 'the + button halves the window about its centre');
  await page.click('.tda4j-zoombar button[aria-label="zoom out"]');
  const doubled = await win();
  check(doubled.every((v, i) => near(v, full[i], full[1] - full[0])), 'the - button brings it back');
  const box = await page.evaluate(() => { const r = document.querySelector('.tda4j-ia svg[role=img]').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
  const [cx, cy] = [box[0] + 0.7 * box[2], box[1] + 0.4 * box[3]];
  const dataAt = (x, y) => page.evaluate(([x, y]) => { const svg = document.querySelector('.tda4j-ia svg[role=img]'), fr = svg.getAttribute('data-fr').split(';').map(s => s.split(',').map(Number)), w = document.querySelector('.tda4j-ia').tda4j.window(0);
    const p = new DOMPoint(x, y).matrixTransform(svg.getScreenCTM().inverse());
    return [w[0] + (p.x - fr[0][0]) / fr[0][1] * (w[1] - w[0]), w[2] + (fr[1][0] + fr[1][1] - p.y) / fr[1][1] * (w[3] - w[2])]; }, [x, y]);
  const before = await dataAt(cx, cy);
  await page.mouse.move(cx, cy); await page.keyboard.down('Control'); await page.mouse.wheel(0, -400); await page.keyboard.up('Control');
  await page.waitForTimeout(100);
  const afterWin = await win(), after = await dataAt(cx, cy);
  check(afterWin[1] - afterWin[0] < 0.8 * (full[1] - full[0]), 'Ctrl and the wheel zoom in');
  check(near(before[0], after[0], full[1] - full[0]) && near(before[1], after[1], full[3] - full[2]), 'the point under the cursor stays under it');
  await page.mouse.move(cx, cy); await page.mouse.wheel(0, -400);
  check(near((await win())[1] - (await win())[0], afterWin[1] - afterWin[0], full[1] - full[0]), 'the wheel alone leaves the picture (the page scrolls)');
  // a drag pans: the point that was grabbed stays under the cursor, and the window keeps its size
  const w0 = await win();
  const grabbed = await dataAt(cx, cy);
  await page.mouse.move(cx, cy); await page.mouse.down(); await page.mouse.move(cx + 60, cy - 30, { steps: 6 }); await page.mouse.up();
  const w1 = await win(), under = await dataAt(cx + 60, cy - 30);
  check(near(under[0], grabbed[0], full[1] - full[0]) && near(under[1], grabbed[1], full[3] - full[2]), 'a drag pans: the point grabbed stays under the cursor');
  check(near(w1[1] - w1[0], w0[1] - w0[0], full[1] - full[0]) && w1[0] < w0[0], 'a pan keeps the size of the window');
  await page.click('.tda4j-zoombar button[aria-label="show everything"]');
  const reset = await win();
  check(reset.every((v, i) => near(v, full[i], full[1] - full[0])), 'the 1:1 button shows everything again');
  // far past the limit, the window stays inside the picture and keeps a size
  await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.zoom(0, [-1e9, 1e9, -1e9, 1e9]));
  const out = await win();
  check(out.every((v, i) => near(v, full[i], full[1] - full[0])), 'a window beyond the picture is the whole picture');
  await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.zoom(0, [1000, 1000.0001, 0, 1e-9]));
  const tiny = await win();
  check(tiny[1] - tiny[0] >= (full[1] - full[0]) / 201 && tiny[3] - tiny[2] >= (full[3] - full[2]) / 201, 'zooming stops at 1/200 of the picture');
  check(errors.length === 0, `no errors while zooming ${errors.join(' | ')}`);
  await page.close();
}

{ // zoom together with the selections: the diagram of the explore page zoomed, brushed and panned
  const meta = JSON.parse(fs.readFileSync(dir + '/interactive-explore.json', 'utf8'));
  const zw = JSON.parse(fs.readFileSync(dir + '/interactive-zoom.json', 'utf8')).diagram;
  const page = await browser.newPage({ viewport: { width: 1800, height: 900 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto('file://' + dir + '/interactive-explore.html');
  const chosen = () => page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.selection('class'));
  const drag = async (x0, y0, x1, y1) => {
    await page.mouse.move(x0, y0); await page.mouse.down(); await page.mouse.move((x0 + x1) / 2, (y0 + y1) / 2, { steps: 4 });
    await page.mouse.move(x1, y1, { steps: 4 }); await page.mouse.up(); await page.waitForTimeout(150);
  };
  check(await page.evaluate(() => document.querySelectorAll('.tda4j-panel').length) === 3, 'the explore page has three panels');
  const buttons = await page.evaluate(() => [...document.querySelectorAll('.tda4j-panel')].map(p => p.querySelectorAll('.tda4j-zoombar button').length));
  check(JSON.stringify(buttons) === '[3,5,5]', `every panel zooms, and the ones that also brush have a button to pan instead and one for a finger (${buttons})`);

  // the diagram, zoomed, is the still of the same window
  const live = panel => page.evaluate(`(() => { const g = ${geometry.toString()}; return g(document.querySelectorAll('.tda4j-ia svg[role=img]')[${panel}]); })()`);
  const stillOf = async (file, panel) => {
    const text = fs.readFileSync(`${dir}/${file}`, 'utf8').replace(/^<\?xml[^>]*\?>\s*/, '');
    return page.evaluate(`(() => { const g = ${geometry.toString()}; const holder = document.createElement('div');
      holder.style.cssText = 'position:absolute;left:0;top:0;width:900px;visibility:hidden'; holder.innerHTML = ${JSON.stringify(text)}; document.body.appendChild(holder);
      const s = [...holder.querySelectorAll('svg[role=img]')].filter(x => x.querySelector('g[clip-path]'))[${panel}];
      const r = g(s); holder.remove(); return r; })()`);
  };
  await page.evaluate(w => document.querySelector('.tda4j-ia').tda4j.zoom(1, w), zw);
  for (const panel of [0, 1]) { // (the board of the still resizes the barcode)
    const mine = await live(panel), theirs = await stillOf('interactive-explore-zoomed.svg', panel);
    const m = matches(mine, theirs);
    check(m.ok, `explore panel ${panel}${panel === 1 ? ' zoomed' : ' untouched'}: ${mine.length} drawn elements as in the still (${theirs.length})` + (m.ok ? '' : ` -- not in the still: ${m.missing.join(' | ')}`));
  }

  // a drag across the whole zoomed diagram chooses what is in the window, and not what is behind the axes
  const where = (x, y) => page.evaluate(([x, y]) => { const svg = document.querySelectorAll('.tda4j-ia svg[role=img]')[1], fr = svg.getAttribute('data-fr').split(';').map(s => s.split(',').map(Number)), w = document.querySelector('.tda4j-ia').tda4j.window(1);
    const px = fr[0][0] + (x - w[0]) / (w[1] - w[0]) * fr[0][1], py = fr[1][0] + fr[1][1] - (y - w[2]) / (w[3] - w[2]) * fr[1][1];
    const p = new DOMPoint(px, py).matrixTransform(svg.getScreenCTM()); return [p.x, p.y]; }, [x, y]);
  const svgBox = await page.evaluate(() => { const r = document.querySelectorAll('.tda4j-ia svg[role=img]')[1].getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom]; });
  const ew = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(1));
  check(ew[1] - ew[0] > zw[1] - zw[0] && Math.abs((ew[1] - ew[0]) / (ew[3] - ew[2]) - 486 / 420) < 1e-9, 'the diagram keeps its equal aspect: the window asked for is widened along x');
  const pl = await where(ew[0], ew[3]), pr = await where(ew[1], ew[2]);   // the plot area's corners
  const finite = meta.bars.map((b, k) => ({ k, b })).filter(e => e.b[2] !== null);
  const points = [];
  for (const e of finite) points.push({ k: e.k, p: await where(e.b[1], e.b[2]) });
  const margin = 7;
  const mustIn = points.filter(q => q.p[0] > pl[0] + margin && q.p[0] < pr[0] - margin && q.p[1] > pl[1] + margin && q.p[1] < pr[1] - margin).map(q => q.k);
  const mustOut = points.filter(q => q.p[0] < pl[0] - margin || q.p[0] > pr[0] + margin || q.p[1] < pl[1] - margin || q.p[1] > pr[1] + margin).map(q => q.k);
  check(mustIn.length >= 3 && mustOut.length >= 3, `the window has ${mustIn.length} points inside it and ${mustOut.length} outside, so the drag tells something`);
  // pointing: a class in the window answers, a class the window has cut away does not, though it sits under the pointer
  const hidden = points.filter(q => mustOut.includes(q.k) && q.p[0] > svgBox[0] + 4 && q.p[0] < svgBox[2] - 4 && q.p[1] > svgBox[1] + 4 && q.p[1] < svgBox[3] - 4);
  const shown = points.find(q => mustIn.includes(q.k));
  await page.mouse.move(shown.p[0], shown.p[1]); await page.waitForTimeout(150);
  check(JSON.stringify(await chosen()) === JSON.stringify([shown.k]), `pointing at a class in the window chooses it (${shown.k})`);
  check(hidden.length > 0, `${hidden.length} classes cut away by the window lie inside the picture's frame, under the margins`);
  await page.mouse.move(svgBox[0] + 3, svgBox[1] + 3);
  let leaked = [];
  for (const q of hidden) { await page.mouse.move(q.p[0], q.p[1]); await page.waitForTimeout(120); if ((await chosen()).includes(q.k)) leaked.push(q.k); }
  check(leaked.length === 0, `pointing at the place where a cut-away class lies, outside the plot area, chooses nothing (${leaked.length} of ${hidden.length} leaked)`);
  await page.mouse.move(svgBox[0] + 3, svgBox[1] + 3); await page.waitForTimeout(100);
  await drag(svgBox[0] + 2, svgBox[1] + 2, svgBox[2] - 2, svgBox[3] - 2);
  const got = await chosen();
  check(mustIn.every(k => got.includes(k)), `a drag across the whole picture chooses the classes shown in it (${mustIn.length})`);
  check(mustOut.every(k => !got.includes(k)), `and none of those the window has cut away (${mustOut.length} outside, ${mustOut.filter(k => got.includes(k)).length} chosen)`);
  check(await page.evaluate(() => document.querySelectorAll('.tda4j-brush').length) === 1, 'the rectangle is there');

  // the pan button turns the drag into a pan: the selection stays, and the rectangle goes
  const bar = page.locator('.tda4j-panel').nth(1).locator('.tda4j-zoombar');
  await bar.locator('button[aria-label^="drag to pan"]').click();
  const w0 = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(1));
  await drag(svgBox[0] + 200, svgBox[1] + 200, svgBox[0] + 230, svgBox[1] + 170);
  const w1 = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(1));
  check(w1[0] < w0[0] && w1[2] > w0[2] - 1e-12 || w1[0] !== w0[0], 'in pan mode a drag moves the window');
  check(JSON.stringify((await chosen()).sort()) === JSON.stringify(got.sort()), 'and does not touch the selection');
  check(await page.evaluate(() => document.querySelectorAll('.tda4j-brush').length) === 0, 'the rectangle of the old window is taken away when the window moves');
  await bar.locator('button[aria-label^="drag to pan"]').click();
  await bar.locator('button[aria-label="show everything"]').click();
  const full = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(1));
  check(full[0] < 0.02 && full[1] > zw[1], 'the 1:1 button shows everything again');

  // the barcode zooms along the filtration only
  const bw0 = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(2));
  await page.locator('.tda4j-panel').nth(2).locator('.tda4j-zoombar button[aria-label="zoom in"]').click();
  const bw1 = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(2));
  check(Math.abs((bw1[1] - bw1[0]) - (bw0[1] - bw0[0]) / 2) < 1e-9 && bw1[2] === bw0[2] && bw1[3] === bw0[3], 'the barcode zooms along the filtration and keeps all its rows');

  check(errors.length === 0, `no errors while zooming the explore page ${errors.join(' | ')}`);
  await page.close();
}

{ // brushing on a page of forty points: the rectangle is the one of the still (dragged, set from the data, or walked out with the keyboard)
  const meta = JSON.parse(read('interactive-brush.json'));
  const context = await browser.newContext({ viewport: { width: 900, height: 900 }, hasTouch: engine === 'chromium' });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(`file://${dir}/interactive-brush.html`);
  const api = (body, arg) => page.evaluate(`((arg) => { const t = document.querySelector('.tda4j-ia').tda4j; ${body} })(${JSON.stringify(arg ?? null)})`);
  const pageRect = () => page.evaluate(() => { const r = document.querySelector('.tda4j-brush'); return r ? ['x', 'y', 'width', 'height'].map(a => +r.getAttribute(a)) : null; });
  const stillRect = text => inStill(text, doc => { const r = doc.querySelector('rect[stroke-dasharray]'); return r ? ['x', 'y', 'width', 'height'].map(a => +r.getAttribute(a)) : null; });
  const near = (a, b, tol = 0.02) => a && b && a.length === b.length && a.every((v, i) => Math.abs(v - b[i]) <= tol);
  const chosen = () => api("return [...t.selection('c')].sort((a, b) => a - b);");
  const svgBox = () => page.evaluate(() => { const r = document.querySelector('.tda4j-ia svg[tabindex]').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
  // the points the page draws (key and box, in client pixels) and the plot area, read off the document, whatever the zoom
  const domPoints = () => page.evaluate(() => { const svg = document.querySelector('.tda4j-ia svg[tabindex]'), m = svg.getScreenCTM();
    const ax = svg.getAttribute('data-fr').split(';').map(s => s.split(',').map(Number)), a = new DOMPoint(ax[0][0], ax[1][0]).matrixTransform(m), b = new DOMPoint(ax[0][0] + ax[0][1], ax[1][0] + ax[1][1]).matrixTransform(m);
    const pts = [...svg.querySelectorAll('[data-ia]')].filter(e => /(^|;)s,/.test(e.getAttribute('data-ia')) && e.getClientRects().length).map(e => { const r = e.getBoundingClientRect();
      return { key: +decodeURIComponent(e.getAttribute('data-ia').split(';').find(s => s.startsWith('s,')).split(',')[2]), box: [r.left, r.top, r.right, r.bottom] }; });
    return { pts, plot: [a.x, a.y, b.x, b.y] }; });
  // the keys a client rectangle touches: the points whose discs meet it (and, when asked, the part of it that is over the plot area)
  const touched = (d, [x0, y0, x1, y1], clip) => { if (clip) { x0 = Math.max(x0, d.plot[0]); y0 = Math.max(y0, d.plot[1]); x1 = Math.min(x1, d.plot[2]); y1 = Math.min(y1, d.plot[3]); }
    return d.pts.filter(p => p.box[2] >= x0 && p.box[0] <= x1 && p.box[3] >= y0 && p.box[1] <= y1).map(p => p.key).sort((a, b) => a - b); };
  const rectClient = () => page.evaluate(() => { const r = document.querySelector('.tda4j-brush').getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom]; });

  // the rectangle set from the data is the still's, zoomed or not
  const bad = [];
  for (const [i, c] of meta.cases.entries()) {
    await api('if (arg[0]) t.zoom(0, arg[0]); else t.resetZoom(0); t.brush(0, arg[1]);', [c.zoom, c.brush]);
    const mine = await pageRect(), theirs = await stillRect(c.still);
    if (!near(mine, theirs)) bad.push(`case ${i} (${c.zoom ? 'zoomed' : 'whole'}): page ${mine && mine.map(v => v.toFixed(2))}, still ${theirs && theirs.map(v => v.toFixed(2))}`);
  }
  check(bad.length === 0, `${meta.cases.length} rectangles set from the data are those of their stills` + (bad.length ? ` -- ${bad.slice(0, 2).join(' || ')}` : ''));
  await api('t.resetZoom(0); t.brush(0, null);');
  check(await pageRect() === null, 'brush(0, null) takes the rectangle away');

  // a drag draws the rectangle that its two corners name, and chooses the points it touches
  const [bx, by, bw, bh] = await svgBox();
  const [P, Q] = [[bx + 0.3 * bw, by + 0.35 * bh], [bx + 0.65 * bw, by + 0.7 * bh]];
  await page.mouse.move(...P); await page.mouse.down(); await page.mouse.move(...Q, { steps: 8 }); await page.mouse.up();
  const dragged = await pageRect(), picked = await chosen(), where = await domPoints();
  const [a, b] = await api('return [t.pointAt(0, arg[0][0], arg[0][1]), t.pointAt(0, arg[1][0], arg[1][1])];', [P, Q]);
  await api('t.brush(0, arg);', [Math.min(a[0], b[0]), Math.max(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[1], b[1])]);
  check(near(dragged, await pageRect()), `a drag from ${P.map(Math.round)} to ${Q.map(Math.round)} draws the rectangle of the data under its corners`);
  check(JSON.stringify(picked) === JSON.stringify(touched(where, [P[0], P[1], Q[0], Q[1]], false)) && picked.length > 0, `and chooses the points it touches: [${picked}]`);
  await page.mouse.click(bx + 5, by + 5);
  check((await chosen()).length === 0, 'a click on nothing clears the choice');

  // the keyboard: arrows move a cross, Enter sets the corners, shift-Enter adds, Escape lets go
  await page.focus('.tda4j-ia svg[tabindex]');
  const press = async (key, times = 1) => { for (let i = 0; i < times; i++) await page.keyboard.press(key); };
  const frame = await page.evaluate(() => document.querySelector('.tda4j-ia svg[tabindex]').getAttribute('data-fr').split(';').map(s => s.split(',').map(Number)));
  const [W, H, X0, Y0] = [frame[0][1], frame[1][1], frame[0][0], frame[1][0]];
  await press('Shift+ArrowLeft'); await press('Shift+ArrowUp'); await press('Enter');
  await press('Shift+ArrowRight', 3); await press('Shift+ArrowDown', 2);
  const rubber = await pageRect();
  await press('Enter');
  const walked = await pageRect(), keys1 = await chosen();
  const expected = [X0 + W / 2 - 0.16 * W, Y0 + H / 2 - 0.16 * H, 0.48 * W, 0.32 * H];
  check(near(walked, expected, 0.05), `arrows and Enter walk out the rectangle ${expected.map(v => v.toFixed(1))}: ${walked && walked.map(v => v.toFixed(1))}`);
  check(rubber && near(rubber, walked, 0.01), 'the rectangle follows the cross between the two Enters');
  check(JSON.stringify(keys1) === JSON.stringify(touched(await domPoints(), await rectClient(), false)) && keys1.length >= 3, `the keyboard chooses the points it touches, as the mouse does: [${keys1}]`);
  check(await page.evaluate(() => !document.querySelector('.tda4j-cursor')), 'the cross is gone once the rectangle is chosen');
  await press('Shift+ArrowLeft', 3); await press('Shift+ArrowUp', 2); await press('Enter');
  await press('Shift+ArrowDown', 4); await press('Shift+ArrowRight'); await page.keyboard.press('Shift+Enter');
  const added = await chosen(), second = touched(await domPoints(), await rectClient(), false);
  const union = [...new Set([...keys1, ...second])].sort((a, b) => a - b);
  check(JSON.stringify(added) === JSON.stringify(union) && second.some(k => !keys1.includes(k)), `shift-Enter adds a second rectangle to the first: [${keys1}] and [${second}] make [${added}]`);
  await press('ArrowLeft'); await press('Enter'); await press('ArrowDown'); await press('Escape');
  check(await page.evaluate(() => !document.querySelector('.tda4j-brush')) && JSON.stringify(await chosen()) === JSON.stringify(added), 'Escape lets go: no rectangle, nothing changed');

  // one press moves the cross a twenty-fifth of the plot area (shift: 4/25)
  await press('ArrowRight'); await press('Enter'); await press('ArrowRight', 5); await press('ArrowDown', 2); await press('Enter');
  const small = await pageRect();
  check(near(small, [X0 + W / 2 + 0.04 * W, Y0 + H / 2, 0.2 * W, 0.08 * H], 0.05), `plain arrows move the cross 4% of the plot area: ${small && small.map(v => v.toFixed(1))}`);
  await page.mouse.click(bx + 5, by + 5);
  await page.focus('.tda4j-ia svg[tabindex]');

  // ] and [ put the cross on the next and the previous point (left to right, top to bottom), Home and End on the first and the last,
  // and two Enters around two of them take the rectangle between them, which the mouse takes just the same
  {
    const ps = await domPoints();
    const mid = p => [(p.box[0] + p.box[2]) / 2, (p.box[1] + p.box[3]) / 2];
    const inside = p => { const [x, y] = mid(p); return x >= ps.plot[0] && x <= ps.plot[2] && y >= ps.plot[1] && y <= ps.plot[3]; };
    const order = ps.pts.filter(inside).sort((a, b) => mid(a)[0] - mid(b)[0] || mid(a)[1] - mid(b)[1]);
    const crossAt = () => page.evaluate(() => { const c = document.querySelector('.tda4j-cursor'); if (!c) return null;
      const m = /^M(-?[0-9.]+) (-?[0-9.]+)H/.exec(c.getAttribute('d')), svg = document.querySelector('.tda4j-ia svg[tabindex]'), p = new DOMPoint(+m[1] + 8, +m[2]).matrixTransform(svg.getScreenCTM());
      return [p.x, p.y]; });
    const onPoint = async (i, what) => { const c = await crossAt(), want = mid(order[i]), lit = await chosen();
      check(c && Math.abs(c[0] - want[0]) < 0.6 && Math.abs(c[1] - want[1]) < 0.6 && JSON.stringify(lit) === JSON.stringify([order[i].key]),
        `${what}: the cross is on point ${i} of ${order.length} (key ${order[i].key}) and lights it: cross ${c && c.map(v => v.toFixed(1))}, wanted ${want.map(v => v.toFixed(1))}, lit [${lit}]`); };
    check(order.length >= 20, `${order.length} points lie in the plot area to step through`);
    await page.waitForTimeout(120); // a hover from the mouse's last move would otherwise light its own point after the first key
    await press(']'); await onPoint(0, '] with no cross');
    await press(']'); await onPoint(1, '] again');
    await press('['); await onPoint(0, '[');
    await press('End'); await onPoint(order.length - 1, 'End');
    await press(']'); await onPoint(order.length - 1, '] at the last point stays');
    await press('PageUp'); await onPoint(order.length - 2, 'Page Up');
    await press('Home'); await onPoint(0, 'Home');
    await press('['); await onPoint(0, '[ at the first point stays');
    await press('PageDown'); await onPoint(1, 'Page Down');
    await press('Escape');
    check(await crossAt() === null && (await chosen()).length === 0, 'Escape takes the cross away and puts the light out');
    await press('['); await onPoint(order.length - 1, '[ with no cross starts at the last point');
    await press('Escape');
    // the rectangle between two points that hold a third, set by the keyboard (which two depends on the layout: the fonts of a browser
    // move the plot area, so a fixed pair of positions has nothing between them in one browser and three points in another)
    const rectOf = (u, v) => { const [a, b] = [mid(u), mid(v)]; return [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])]; };
    const holdsThird = (i, j) => { const r = rectOf(order[i], order[j]);
      return order.some((p, k) => k !== i && k !== j && mid(p)[0] > r[0] + 3 && mid(p)[0] < r[2] - 3 && mid(p)[1] > r[1] + 3 && mid(p)[1] < r[3] - 3); };
    let pair = null;
    for (let i = 0; i < order.length && !pair; i++) for (let j = i + 3; j < order.length && !pair; j++) if (holdsThird(i, j)) pair = [i, j];
    check(pair !== null, `two points of the ${order.length} have a third inside the rectangle between them: ${pair}`);
    const [i0, j0] = pair || [3, 9];
    await press('Home'); await press(']', i0); await press('Enter'); await press(']', j0 - i0);
    await press('Enter');
    const want = rectOf(order[i0], order[j0]);
    const got = await rectClient(), keysK = await chosen();
    check(near(got, want, 0.6), `Home, ] ${i0} times, Enter, ] ${j0 - i0} times, Enter draws the rectangle between points ${i0} and ${j0}: ${got.map(v => v.toFixed(1))}, wanted ${want.map(v => v.toFixed(1))}`);
    check(JSON.stringify(keysK) === JSON.stringify(touched(await domPoints(), got, false)) && keysK.includes(order[i0].key) && keysK.includes(order[j0].key) && keysK.length > 2, `it chooses the points it touches, both ends among them: [${keysK}]`);
    check(await crossAt() === null, 'the cross is gone once it has chosen');
    // and the mouse, dragged over the same rectangle, chooses the same
    await page.mouse.click(bx + 5, by + 5);
    await page.mouse.move(got[0], got[1]); await page.mouse.down(); await page.mouse.move(got[2], got[3], { steps: 8 }); await page.mouse.up();
    check(JSON.stringify(await chosen()) === JSON.stringify(keysK), `a drag over the same rectangle chooses the same points: [${await chosen()}]`);
    await page.mouse.click(bx + 5, by + 5);
    await page.focus('.tda4j-ia svg[tabindex]');
    // shift-Enter adds to what is there, with stepping too
    await press('Home'); await press('Enter'); await press('Enter');
    const one = await chosen();
    await press('End'); await press('Enter'); await press('Enter');
    check(JSON.stringify(one) === JSON.stringify([order[0].key]) && JSON.stringify(await chosen()) === JSON.stringify([order[order.length - 1].key]), `two Enters on one point choose that point alone: [${one}] then [${await chosen()}]`);
    await press('Home'); await press('Enter'); await page.keyboard.press('Shift+Enter');
    check(JSON.stringify(await chosen()) === JSON.stringify([order[0].key, order[order.length - 1].key].sort((u, v) => u - v)), `with shift the point is added: [${await chosen()}]`);
    await page.mouse.click(bx + 5, by + 5);
    await page.focus('.tda4j-ia svg[tabindex]');
  }

  // zoomed, a rectangle that runs over the margin chooses only what the plot area shows of it
  await api('t.zoom(0, [2, 6, 2, 6]);');
  const [zx, zy, zw, zh] = await svgBox(), area = (await domPoints()).plot;
  const R = [[area[0] + 0.5 * (area[2] - area[0]), area[1] + 0.4 * (area[3] - area[1])], [zx + zw - 2, zy + zh - 2]];
  await page.mouse.move(...R[0]); await page.mouse.down(); await page.mouse.move(...R[1], { steps: 6 }); await page.mouse.up();
  const clipped = await chosen(), zoomedPts = await domPoints();
  check(JSON.stringify(clipped) === JSON.stringify(touched(zoomedPts, [R[0][0], R[0][1], R[1][0], R[1][1]], true)), `zoomed, a drag over the margin chooses what the plot area shows: [${clipped}]`);
  check(touched(zoomedPts, [R[0][0], R[0][1], R[1][0], R[1][1]], false).length > clipped.length, 'and there were points under the margin that it leaves out');
  { // and the cross steps through the points the plot area shows, not the ones under the margin
    const mid = p => [(p.box[0] + p.box[2]) / 2, (p.box[1] + p.box[3]) / 2];
    const inside = p => { const [x, y] = mid(p); return x >= zoomedPts.plot[0] && x <= zoomedPts.plot[2] && y >= zoomedPts.plot[1] && y <= zoomedPts.plot[3]; };
    const order = zoomedPts.pts.filter(inside).sort((a, b) => mid(a)[0] - mid(b)[0] || mid(a)[1] - mid(b)[1]);
    await page.mouse.click(bx + 5, by + 5); await page.focus('.tda4j-ia svg[tabindex]'); await page.waitForTimeout(120);
    await press('Home'); const first = await chosen(); await press('End'); const last = await chosen();
    await press('Escape');
    check(order.length >= 2 && order.length < zoomedPts.pts.length && JSON.stringify(first) === JSON.stringify([order[0].key]) && JSON.stringify(last) === JSON.stringify([order[order.length - 1].key]),
      `zoomed, Home and End reach the first and the last of the ${order.length} points shown (of ${zoomedPts.pts.length}): [${first}] and [${last}], wanted [${order[0].key}] and [${order[order.length - 1].key}]`);
  }
  await api('t.resetZoom(0);');
  await page.mouse.click(bx + 5, by + 5);

  // + and - zoom about the middle, 0 shows everything, and in pan mode the arrows pan
  await api('t.brush(0, null); t.resetZoom(0);');
  const whole = await api('return t.window(0);');
  await page.focus('.tda4j-ia svg[tabindex]');
  await page.keyboard.press('+');
  const half = await api('return t.window(0);');
  check(Math.abs((half[1] - half[0]) - (whole[1] - whole[0]) / 2) < 1e-9 && Math.abs((half[0] + half[1]) / 2 - (whole[0] + whole[1]) / 2) < 1e-9, '+ halves the window about its middle');
  await page.keyboard.press('ArrowRight');
  check(JSON.stringify(await api('return t.window(0);')) === JSON.stringify(half), 'where a drag chooses, the arrows do not pan');
  await page.locator('.tda4j-zoombar button[aria-label^="drag to pan"]').click();
  await page.focus('.tda4j-ia svg[tabindex]');
  await page.keyboard.press('ArrowRight');
  const right = await api('return t.window(0);');
  check(Math.abs(right[0] - half[0] - 0.1 * (half[1] - half[0])) < 1e-9 && right[2] === half[2], 'in pan mode the right arrow moves the window a tenth of its width to the right');
  await page.keyboard.press('Shift+ArrowUp');
  const up = await api('return t.window(0);');
  check(Math.abs(up[2] - right[2] - 0.5 * (right[3] - right[2])) < 1e-9, 'shift and the up arrow move it half its height up');
  await page.keyboard.press('0');
  check(JSON.stringify(await api('return t.window(0);')) === JSON.stringify(whole), '0 shows everything');
  await page.keyboard.press('-');
  check(JSON.stringify(await api('return t.window(0);')) === JSON.stringify(whole), '- cannot zoom out past the whole picture');

  if (engine !== 'chromium') console.log(`skip  touch gestures: they are sent through the Chrome DevTools protocol, and this is ${engine}`);
  else {
    // touch: two fingers pinch (the distance) and drag (the midpoint); one finger leaves the picture to the page, a tap chooses
    const cdp = await context.newCDPSession(page);
    const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y], id) => ({ x, y, id })) });
    const gesture = async (from, to, steps = 6) => {
      await touch('touchStart', from);
      for (let i = 1; i <= steps; i++) await touch('touchMove', from.map((p, k) => [p[0] + (to[k][0] - p[0]) * i / steps, p[1] + (to[k][1] - p[1]) * i / steps]));
      await touch('touchEnd', []);
      await page.waitForTimeout(60);
    };
    await page.locator('.tda4j-zoombar button[aria-label="show everything"]').click();
    check(await page.evaluate(() => getComputedStyle(document.querySelector('.tda4j-ia svg[tabindex]')).touchAction) === 'pan-x pan-y', 'the picture leaves one-finger scrolling to the page (touch-action: pan-x pan-y)');
    const [tx, ty, tw, th] = await svgBox();
    const [mx, my] = [tx + 0.45 * tw, ty + 0.5 * th];
    const w0 = await api('return t.window(0);'), c0 = await api('return t.pointAt(0, arg[0], arg[1]);', [mx, my]);
    await gesture([[mx - 40, my], [mx + 40, my]], [[mx - 80, my], [mx + 80, my]]);
    const w1 = await api('return t.window(0);'), c1 = await api('return t.pointAt(0, arg[0], arg[1]);', [mx, my]);
    check(Math.abs((w1[1] - w1[0]) - (w0[1] - w0[0]) / 2) < 1e-6 * (w0[1] - w0[0]) && Math.abs((w1[3] - w1[2]) - (w0[3] - w0[2]) / 2) < 1e-6 * (w0[3] - w0[2]), `two fingers twice as far apart show half the window: width ${(w1[1] - w1[0]).toFixed(3)} from ${(w0[1] - w0[0]).toFixed(3)}`);
    check(Math.abs(c1[0] - c0[0]) < 1e-6 && Math.abs(c1[1] - c0[1]) < 1e-6, `and what was between the fingers still is: (${c0.map(v => v.toFixed(3))}) then (${c1.map(v => v.toFixed(3))})`);
    const w2in = await api('return t.window(0);');
    await gesture([[mx - 40, my], [mx + 40, my]], [[mx - 40 + 70, my - 50], [mx + 40 + 70, my - 50]]);
    const w2 = await api('return t.window(0);'), c2 = await api('return t.pointAt(0, arg[0], arg[1]);', [mx + 70, my - 50]);
    check(Math.abs((w2[1] - w2[0]) - (w2in[1] - w2in[0])) < 1e-9 && Math.abs(c2[0] - c1[0]) < 1e-6 && Math.abs(c2[1] - c1[1]) < 1e-6, 'two fingers moving together carry what is between them along, and keep the size');
    const w3in = await api('return t.window(0);'), c3in = await api('return t.pointAt(0, arg[0], arg[1]);', [mx + 20, my + 20]);
    await gesture([[mx - 30 + 20, my + 20], [mx + 30 + 20, my + 20]], [[mx - 60 - 30, my - 10], [mx + 60 - 30, my - 10]]);
    const w3 = await api('return t.window(0);'), c3 = await api('return t.pointAt(0, arg[0], arg[1]);', [mx - 30, my - 10]);
    check(Math.abs((w3[1] - w3[0]) - (w3in[1] - w3in[0]) / 2) < 1e-6 * (w3in[1] - w3in[0]) && Math.abs(c3[0] - c3in[0]) < 1e-6 && Math.abs(c3[1] - c3in[1]) < 1e-6, 'pinching and moving at once does both');
    await gesture([[mx - 40, my], [mx + 40, my]], [[mx - 4000, my], [mx + 4000, my]]);
    const deep = await api('return t.window(0);'), full = await api('return t.pointAt(0, arg[0], arg[1]);', [mx, my]);
    check(deep[1] - deep[0] >= (whole[1] - whole[0]) / 200 - 1e-12, `pinching out stops at 1/200 of the picture (${((whole[1] - whole[0]) / (deep[1] - deep[0])).toFixed(1)}x)`);
    await page.locator('.tda4j-zoombar button[aria-label="show everything"]').click();
    const stay = await api('return t.window(0);');
    await touch('touchStart', [[mx, my]]); for (let i = 1; i <= 5; i++) await touch('touchMove', [[mx + 12 * i, my + 8 * i]]); await touch('touchEnd', []);
    check(JSON.stringify(await api('return t.window(0);')) === JSON.stringify(stay) && await pageRect() === null, 'one finger moving leaves the window alone, and draws no rectangle');
    const target = (await domPoints()).pts.find(p => p.key === 7).box.map((v, i, b) => (v + b[(i + 2) % 4]) / 2).slice(0, 2);
    await touch('touchStart', [target]); await touch('touchEnd', []); await page.waitForTimeout(100);
    check(JSON.stringify(await chosen()) === JSON.stringify([7]), `a tap on a point chooses it: [${await chosen()}]`);
    // choosing by finger: the touch button turns one finger into a brush, as the mouse is (and a second finger is still a pinch)
    await page.mouse.click(bx + 5, by + 5);
    await page.locator('.tda4j-zoombar button[aria-label^="drag to pan"]').click(); // back to choosing: the keyboard checks above left it at panning
    const touchBtn = page.locator('.tda4j-zoombar button[aria-label^="choose with a finger"]');
    check(await touchBtn.count() === 1 && await touchBtn.getAttribute('aria-pressed') === 'false', 'a brushable panel has a touch button, not pressed');
    await touchBtn.click();
    check(await touchBtn.getAttribute('aria-pressed') === 'true' && await page.evaluate(() => getComputedStyle(document.querySelector('.tda4j-ia svg[tabindex]')).touchAction) === 'none', 'pressed, the picture takes the finger (touch-action: none)');
    await touch('touchStart', [P]); for (let i = 1; i <= 6; i++) await touch('touchMove', [[P[0] + (Q[0] - P[0]) * i / 6, P[1] + (Q[1] - P[1]) * i / 6]]); await touch('touchEnd', []);
    await page.waitForTimeout(100);
    check(near(await pageRect(), dragged) && JSON.stringify(await chosen()) === JSON.stringify(picked) && picked.length > 0, `one finger from ${P.map(Math.round)} to ${Q.map(Math.round)} draws the mouse's rectangle and chooses its points: [${await chosen()}]`);
    await page.mouse.click(bx + 5, by + 5);
    const pinchFrom = await api('return t.window(0);');
    await gesture([[mx - 40, my], [mx + 40, my]], [[mx - 80, my], [mx + 80, my]]);
    const pinched = await api('return t.window(0);');
    check(Math.abs((pinched[1] - pinched[0]) - (pinchFrom[1] - pinchFrom[0]) / 2) < 1e-6 * (pinchFrom[1] - pinchFrom[0]) && await pageRect() === null && (await chosen()).length === 0, 'two fingers still pinch, and draw no rectangle');
    await page.locator('.tda4j-zoombar button[aria-label="show everything"]').click();
    await touchBtn.click();
    check(await page.evaluate(() => getComputedStyle(document.querySelector('.tda4j-ia svg[tabindex]')).touchAction) === 'pan-x pan-y', 'a second press gives the finger back to the page (touch-action: pan-x pan-y)');
    // a swipe the page can take is mostly vertical: a wide sideways one is the browser's "back", which leaves the page
    const down = [P[0] + 10, Q[1]];
    await touch('touchStart', [P]); for (let i = 1; i <= 6; i++) await touch('touchMove', [[P[0] + (down[0] - P[0]) * i / 6, P[1] + (down[1] - P[1]) * i / 6]]); await touch('touchEnd', []);
    await page.waitForTimeout(100);
    check(await pageRect() === null && (await chosen()).length === 0, 'and one finger draws nothing again');
    check(errors.length === 0, `no errors on the brush page ${errors.join(' | ')}`);

    { // a brushable panel with no zoom has a bar of its own, with the one button
      const tab = await context.newPage();
      const tabErrors = [];
      tab.on('pageerror', e => tabErrors.push(String(e)));
      await tab.goto(`file://${dir}/interactive-brush-plain.html`);
      const buttons = await tab.locator('.tda4j-zoombar button').count();
      check(buttons === 1 && await tab.locator('.tda4j-zoombar button[aria-label^="choose with a finger"]').count() === 1, `a brushable panel that does not zoom has one button, the touch one (${buttons})`);
      const sel = () => tab.evaluate(() => [...document.querySelector('.tda4j-ia').tda4j.selection('c')].sort((a, b) => a - b));
      const rect = () => tab.evaluate(() => { const r = document.querySelector('.tda4j-brush'); return r ? ['x', 'y', 'width', 'height'].map(a => +r.getAttribute(a)) : null; });
      const box = await tab.evaluate(() => { const r = document.querySelector('.tda4j-ia svg[tabindex]').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
      const [A, B] = [[box[0] + 0.3 * box[2], box[1] + 0.35 * box[3]], [box[0] + 0.65 * box[2], box[1] + 0.7 * box[3]]];
      await tab.mouse.move(...A); await tab.mouse.down(); await tab.mouse.move(...B, { steps: 8 }); await tab.mouse.up();
      const byMouse = { rect: await rect(), keys: await sel() };
      await tab.mouse.click(box[0] + 5, box[1] + 5);
      const cd = await context.newCDPSession(tab);
      const tch = (type, pts) => cd.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y], id) => ({ x, y, id })) });
      const A2 = [A[0] + 10, B[1]];
      await tch('touchStart', [A]); for (let i = 1; i <= 6; i++) await tch('touchMove', [[A[0] + (A2[0] - A[0]) * i / 6, A[1] + (A2[1] - A[1]) * i / 6]]); await tch('touchEnd', []);
      await tab.waitForTimeout(100);
      check(await rect() === null && (await sel()).length === 0, 'before the touch button is pressed a finger draws nothing');
      await tab.locator('.tda4j-zoombar button').click();
      await tch('touchStart', [A]); for (let i = 1; i <= 6; i++) await tch('touchMove', [[A[0] + (B[0] - A[0]) * i / 6, A[1] + (B[1] - A[1]) * i / 6]]); await tch('touchEnd', []);
      await tab.waitForTimeout(100);
      const byFinger = { rect: await rect(), keys: await sel() };
      check(near(byFinger.rect, byMouse.rect) && JSON.stringify(byFinger.keys) === JSON.stringify(byMouse.keys) && byMouse.keys.length > 0, `after it, one finger draws the mouse's rectangle and chooses [${byFinger.keys}]`);
      // a second finger landing during the drag drops the rectangle
      await tab.mouse.click(box[0] + 5, box[1] + 5);
      await tch('touchStart', [A]); await tch('touchMove', [[A[0] + 30, A[1] + 20]]);
      await tch('touchStart', [[A[0] + 30, A[1] + 20], B]); await tch('touchMove', [[A[0] + 60, A[1] + 40], B]); await tch('touchEnd', []);
      await tab.waitForTimeout(100);
      check(await rect() === null && (await sel()).length === 0, 'a second finger landing during the drag drops the rectangle and chooses nothing');
      // the browser taking the touch over (a system gesture) cancels the drag: no rectangle is left, and a mouse moving after it draws nothing
      await tab.mouse.click(box[0] + 5, box[1] + 5);
      await tch('touchStart', [A]); for (let i = 1; i <= 3; i++) await tch('touchMove', [[A[0] + (B[0] - A[0]) * i / 6, A[1] + (B[1] - A[1]) * i / 6]]);
      const rubber = await rect();
      await tch('touchCancel', []);
      await tab.mouse.move(...B); await tab.mouse.move(B[0] + 20, B[1] + 20, { steps: 3 });
      await tab.waitForTimeout(100);
      check(rubber !== null && await rect() === null && (await sel()).length === 0, 'a cancelled touch leaves no rectangle, and nothing for a mouse to carry on with');
      check(tabErrors.length === 0, `no errors on the plain brush page ${tabErrors.join(' | ')}`);
      await tab.close();
    }

    // the touch on pages with other axes: the data between the fingers stays between them (reversed axes, a log axis)
    for (const name of ['reversed', 'log', 'symlog']) {
      const tab = await context.newPage();
      await tab.goto(`file://${dir}/zoomaxes-${name}.html`);
      const cd = await context.newCDPSession(tab);
      const tch = (type, pts) => cd.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y], id) => ({ x, y, id })) });
      const box = await tab.evaluate(() => { const r = document.querySelector('.tda4j-ia svg[tabindex]').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
      const [qx, qy] = [box[0] + 0.5 * box[2], box[1] + 0.5 * box[3]];
      const at = (x, y) => tab.evaluate(([x, y]) => document.querySelector('.tda4j-ia').tda4j.pointAt(0, x, y), [x, y]);
      const before = await at(qx, qy);
      const from = [[qx - 40, qy], [qx + 40, qy]], to = [[qx - 70 + 30, qy - 20], [qx + 70 + 30, qy - 20]];
      await tch('touchStart', from); for (let i = 1; i <= 6; i++) await tch('touchMove', from.map((p, k) => [p[0] + (to[k][0] - p[0]) * i / 6, p[1] + (to[k][1] - p[1]) * i / 6])); await tch('touchEnd', []);
      await tab.waitForTimeout(60);
      const after = await at(qx + 30, qy - 20);
      const rel = (u, v) => Math.abs(u - v) / (Math.abs(v) || 1);
      check(rel(after[0], before[0]) < 1e-6 && rel(after[1], before[1]) < 1e-6, `${name}: after a pinch and a move, what was between the fingers is: (${before.map(v => v.toPrecision(5))}) then (${after.map(v => v.toPrecision(5))})`);
      const spanned = await tab.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(0));
      check(spanned.every(isFinite), `${name}: the window is still numbers`);
      if (name === 'reversed') { // on an axis that runs backwards the right arrow moves the view to the right, which is toward smaller values
        await tab.evaluate(() => { const t = document.querySelector('.tda4j-ia').tda4j; t.resetZoom(0); const w = t.window(0);
          t.zoom(0, [w[0] + 0.3 * (w[1] - w[0]), w[0] + 0.6 * (w[1] - w[0]), w[2] + 0.3 * (w[3] - w[2]), w[2] + 0.6 * (w[3] - w[2])]); });
        const k0 = await tab.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(0));
        await tab.focus('.tda4j-ia svg[tabindex]'); await tab.keyboard.press('ArrowRight'); await tab.keyboard.press('ArrowUp');
        const k1 = await tab.evaluate(() => document.querySelector('.tda4j-ia').tda4j.window(0));
        check(Math.abs((k1[0] - k0[0]) + 0.1 * (k0[1] - k0[0])) < 1e-6 * (k0[1] - k0[0]) && Math.abs((k1[2] - k0[2]) + 0.1 * (k0[3] - k0[2])) < 1e-6 * (k0[3] - k0[2]),
          `reversed: right and up arrows move the window toward smaller values (${(k1[0] - k0[0]).toFixed(2)}, ${(k1[2] - k0[2]).toFixed(2)})`);
      }
      await tab.close();
    }

    // the 3-D canvas: two fingers pinch to zoom and drag to turn; a flat canvas pans and zooms about the fingers
    for (const [file, turns] of [['interactive-pick-3d.html', true], ['interactive-large.html', false]]) {
      const tab = await context.newPage();
      await tab.addInitScript(spy);
      await tab.goto(`file://${dir}/${file}`);
      const cd = await context.newCDPSession(tab);
      const tch = (type, pts) => cd.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map(([x, y], id) => ({ x, y, id })) });
      const extent = async () => tab.evaluate(() => { const xs = [], ys = []; window.__shapes.filter(s => s.ink === undefined || s.ink === '10px sans-serif').forEach(s => s.path.forEach(p => { xs.push(p[1]); ys.push(p[2]); }));
        return [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)]; });
      const cv = await tab.evaluate(() => { const r = document.querySelector('canvas').getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; });
      const [cx, cy] = [cv[0] + cv[2] / 2, cv[1] + Math.min(cv[3], 500) / 2];
      const e0 = await extent();
      const from = [[cx - 30, cy], [cx + 30, cy]], to = [[cx - 60, cy], [cx + 60, cy]];
      await tch('touchStart', from); for (let i = 1; i <= 6; i++) await tch('touchMove', from.map((p, k) => [p[0] + (to[k][0] - p[0]) * i / 6, p[1] + (to[k][1] - p[1]) * i / 6])); await tch('touchEnd', []);
      await tab.waitForTimeout(100);
      const e1 = await extent();
      const ratio = (e1[1] - e1[0]) / (e0[1] - e0[0]);
      check(Math.abs(ratio - 2) < 0.08, `${file}: fingers twice as far apart draw it ${ratio.toFixed(2)} times as wide`);
      if (!turns) {
        const f2 = [[cx - 60, cy], [cx + 60, cy]], t2 = [[cx - 60 + 40, cy + 25], [cx + 60 + 40, cy + 25]];
        await tch('touchStart', f2); for (let i = 1; i <= 6; i++) await tch('touchMove', f2.map((p, k) => [p[0] + (t2[k][0] - p[0]) * i / 6, p[1] + (t2[k][1] - p[1]) * i / 6])); await tch('touchEnd', []);
        await tab.waitForTimeout(100);
        const e2 = await extent();
        check(Math.abs((e2[0] - e1[0]) - 40) < 1.5 && Math.abs((e2[2] - e1[2]) - 25) < 1.5, `${file}: two fingers moving together pan it by (${(e2[0] - e1[0]).toFixed(1)}, ${(e2[2] - e1[2]).toFixed(1)})`);
      }
      await tab.close();
    }
  }
  await page.close();
  await context.close();
}

await browser.close();
process.exit(failures ? 1 : 0);
