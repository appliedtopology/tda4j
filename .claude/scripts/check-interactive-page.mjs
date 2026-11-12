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

await browser.close();
process.exit(failures ? 1 : 0);
