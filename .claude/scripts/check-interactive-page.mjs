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
  const a = await shot(0.2), b = await shot(0.6), c = await shot(0.2);
  check(a !== b && a === c, 'the canvas differs between 0.2 and 0.6 and is the same on returning to 0.2');
  await page.focus('input[type=range]');
  await page.keyboard.press('ArrowRight');
  const readout = await page.textContent('output');
  check(/^[-0-9.]+$/.test(readout.trim()), `the readout is the number alone: "${readout}"`);
  await page.click('.tda4j-play'); await page.waitForTimeout(1500);
  const mid = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.get('t'));
  await page.waitForTimeout(8000);
  const end = await page.evaluate(() => document.querySelector('.tda4j-ia').tda4j.get('t'));
  check(mid > 0.2 && mid < end && Math.abs(end - 0.8) < 1e-9, `play moves the slider to its end: ${mid.toFixed(3)} then ${end.toFixed(3)}`);
  const evt = await page.evaluate(() => new Promise(res => { const r = document.querySelector('.tda4j-ia'); r.addEventListener('tda4j:change', e => res(e.detail), { once: true }); r.tda4j.set('t', 0.4); }));
  check(evt.name === 't' && evt.value === 0.4, `a change is announced: ${JSON.stringify(evt)}`);
  check(errors.length === 0, `no errors on the 3-D page ${errors.join(' | ')}`);
  await page.close();
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

await browser.close();
process.exit(failures ? 1 : 0);
