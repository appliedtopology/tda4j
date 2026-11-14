// Checks the interactive pages of the Gallery in Chromium: what the page shows at a value of its slider is what the still
// picture of the same value (written by `Interactive.at`) shows, element for element, and the 3-D page redraws, plays and
// announces its changes.
//
//   sbt "tda4j/Test/runMain org.appliedtopology.tda4j.plot.Gallery /tmp/gallery"
//   NODE_PATH=/opt/node-tools/node_modules node .claude/scripts/check-interactive-page.mjs /tmp/gallery
//
// Prints one line per check and exits 1 when one fails. Not part of CI (rules/plotting.md).
import { createRequire } from 'module';
import fs from 'fs';
const require = createRequire((process.env.NODE_PATH || '/opt/node-tools/node_modules') + '/');
const { chromium } = require('playwright');

const dir = process.argv[2];
if (!dir) { console.error('usage: check-interactive-page.mjs <gallery directory>'); process.exit(2); }
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || '/opt/pw-browsers/chromium', args: ['--no-sandbox'] });
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
  for (const m of ['beginPath', 'moveTo', 'lineTo', 'arc', 'fill', 'stroke', 'fillRect']) o[m] = P[m];
  let path = null; window.__shapes = [];
  P.fillRect = function (...a) { window.__shapes = []; return o.fillRect.apply(this, a); };
  P.beginPath = function () { path = []; return o.beginPath.call(this); };
  P.moveTo = function (x, y) { if (path) path.push(['m', x, y]); return o.moveTo.call(this, x, y); };
  P.lineTo = function (x, y) { if (path) path.push(['l', x, y]); return o.lineTo.call(this, x, y); };
  P.arc = function (x, y, r, a, b) { if (path) path.push(['a', x, y, r]); return o.arc.call(this, x, y, r, a, b); };
  P.fill = function () { if (path) window.__shapes.push({ op: 'fill', alpha: this.globalAlpha, path: path.slice() }); return o.fill.apply(this, arguments); };
  P.stroke = function () { if (path) window.__shapes.push({ op: 'stroke', alpha: this.globalAlpha, width: this.lineWidth, path: path.slice() }); return o.stroke.apply(this, arguments); };
})()`;
// the kind of a recorded shape: a triangle, a segment, a disc (point, ball or halo)
const kindOf = s => s.path[0][0] === 'a' ? (s.op === 'fill' ? 'disc' : 'ring') : s.path.length === 3 ? 'triangle' : 'segment';
const kinds = shapes => { const n = { triangle: 0, segment: 0, disc: 0 }; shapes.forEach(s => { const k = kindOf(s); if (k in n) n[k]++; }); return n; };
// what a still picture holds, read in a blank page (Node has no DOM): the counts of the scene's polygons, lines and circles
// per 3-D scene, and the balls (the circles of opacity 0.09)
const reader = await browser.newPage();
const inStill = (text, fn) => reader.evaluate(`(() => { const doc = new DOMParser().parseFromString(${JSON.stringify(text.replace(/^<\?xml[^>]*\?>\s*/, ''))}, 'image/svg+xml'); return (${fn.toString()})(doc); })()`);
const stillKinds = text => inStill(text, doc => { const scenes = [...doc.querySelectorAll('svg[id^="tda4j3d"]')];
  return (scenes.length ? scenes : [doc]).map(s => ({ triangle: s.querySelectorAll('polygon').length, segment: s.querySelectorAll('line').length, disc: s.querySelectorAll('circle').length })); });
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
  const kindOf0 = s => (s.path[0][0] === 'a' && s.op === 'fill' && Math.abs(s.alpha - 0.09) < 1e-9) ? 'ball' : 'other';
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
    const centre = async () => page.evaluate(() => { const c = [...document.querySelectorAll('.tda4j-ia svg[data-zm] g[data-z=p] [data-c]')].filter(e => e.getClientRects().length)[3] || document.querySelector('.tda4j-ia svg[data-zm] g[data-z=p] [data-c]');
      const r = c.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; });
    await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.resetZoom(0));
    await page.evaluate(() => { const t = document.querySelector('.tda4j-ia').tda4j, w = t.window(0); t.zoom(0, [w[0] + 0.1 * (w[1] - w[0]), w[0] + 0.7 * (w[1] - w[0]), w[2] + 0.1 * (w[3] - w[2]), w[2] + 0.7 * (w[3] - w[2])].map((v, i) => v)); });
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
  check(JSON.stringify(buttons) === '[3,4,4]', `every panel zooms, and the ones that also brush have a button to pan instead (${buttons})`);

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
  const context = await browser.newContext({ viewport: { width: 900, height: 900 }, hasTouch: true });
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

  // zoomed, a rectangle that runs over the margin chooses only what the plot area shows of it
  await api('t.zoom(0, [2, 6, 2, 6]);');
  const [zx, zy, zw, zh] = await svgBox(), area = (await domPoints()).plot;
  const R = [[area[0] + 0.5 * (area[2] - area[0]), area[1] + 0.4 * (area[3] - area[1])], [zx + zw - 2, zy + zh - 2]];
  await page.mouse.move(...R[0]); await page.mouse.down(); await page.mouse.move(...R[1], { steps: 6 }); await page.mouse.up();
  const clipped = await chosen(), zoomedPts = await domPoints();
  check(JSON.stringify(clipped) === JSON.stringify(touched(zoomedPts, [R[0][0], R[0][1], R[1][0], R[1][1]], true)), `zoomed, a drag over the margin chooses what the plot area shows: [${clipped}]`);
  check(touched(zoomedPts, [R[0][0], R[0][1], R[1][0], R[1][1]], false).length > clipped.length, 'and there were points under the margin that it leaves out');
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
  check(errors.length === 0, `no errors on the brush page ${errors.join(' | ')}`);

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
    const extent = async () => tab.evaluate(() => { const xs = [], ys = []; window.__shapes.forEach(s => s.path.forEach(p => { xs.push(p[1]); ys.push(p[2]); }));
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
  await page.close();
  await context.close();
}

await browser.close();
process.exit(failures ? 1 : 0);
