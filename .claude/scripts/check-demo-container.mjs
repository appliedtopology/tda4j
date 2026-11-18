#!/usr/bin/env node
// Runs the demo's container image (demo/container/Dockerfile) and uses it as a participant would: opens the terminal
// page in headless Chromium, types at the prompt, reads the plots from the second port, reloads the page.
//
//   node .claude/scripts/check-demo-container.mjs [image]     # default image: tda4j-demo
//
// Needs Docker, Playwright with its Chromium, and the ports 7681 and 8337 free on this machine. Starts and removes a
// container named tda4j-demo-check.
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";

const image = process.argv[2] || "tda4j-demo";
const container = "tda4j-demo-check";
const terminal = "http://127.0.0.1:7681/";
const plots = "http://127.0.0.1:8337/";

let chromium;
try {
  ({ chromium } = await import("playwright"));
} catch (notAModule) {
  ({ chromium } = createRequire(import.meta.url)("playwright"));
}

const docker = (...args) => execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
try {
  docker("rm", "-f", container);
} catch (noneRunning) {}
docker("run", "-d", "--name", container, "-p", "127.0.0.1:7681:7681", "-p", "127.0.0.1:8337:8337", image);

const failures = [];
function check(what, holds, shown) {
  console.log(`${holds ? "ok  " : "FAIL"}  ${what}${holds ? "" : "\n      shown: " + JSON.stringify(shown).slice(-700)}`);
  if (!holds) failures.push(what);
}

// Both addresses are this machine's own: a proxy must not be asked for them.
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 700 } });

/** The text of the terminal, every line of its buffer, without the trailing spaces. */
const screen = () =>
  page.evaluate(() => {
    const buffer = window.term.buffer.active;
    const lines = [];
    for (let i = 0; i < buffer.length; i++) lines.push(buffer.getLine(i).translateToString(true));
    return lines.join("\n").trimEnd();
  });

async function waitFor(pattern, timeout = 60_000) {
  const deadline = Date.now() + timeout;
  let shown = "";
  while (Date.now() < deadline) {
    shown = await screen().catch(() => "");
    if (pattern.test(shown)) return shown;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error(`the terminal never showed ${pattern}; it shows:\n${shown.slice(-900)}`);
}

async function open() {
  for (let attempt = 0; ; attempt++) {
    try {
      await page.goto(terminal);
      break;
    } catch (notUpYet) {
      if (attempt > 30) throw notUpYet;
      await new Promise(resolve => setTimeout(resolve, 500));
    }
  }
  await page.waitForFunction(() => window.term && window.term.buffer, null, { timeout: 30_000 });
  await page.locator(".xterm-helper-textarea").focus();
}

/** Types a line at the prompt and waits until the terminal shows `expected` after it. */
async function run(line, expected, timeout) {
  const before = (await screen()).length;
  await page.keyboard.type(line);
  await page.keyboard.press("Enter");
  const deadline = Date.now() + (timeout || 60_000);
  let after = "";
  while (Date.now() < deadline) {
    after = (await screen()).slice(before);
    if (expected.test(after)) return after;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  return after;
}

const started = Date.now();
try {
  await open();
  await waitFor(/scala>\s*$/);
  console.log(`      ${((Date.now() - started) / 1000).toFixed(1)} s  from docker run to the first prompt`);
  check("the page's title is the demo's", (await page.title()) === "TDA4j", await page.title());

  let shown = await run("val d = Persistence(Array(Array(0.0, 0.0), Array(1.0, 0.0), Array(1.0, 1.0), Array(0.0, 1.0)))", /H1: 1 bar[^]*scala>\s*$/);
  check("the imports are done and Persistence answers", /H0: 4 bars/.test(shown) && /H1: 1 bar/.test(shown), shown);

  // Tab completion is the terminal REPL's own; a page that only passed lines to a compiler would not have it.
  await page.keyboard.type("d.longerTh");
  await page.keyboard.press("Tab");
  shown = await waitFor(/longerThanFraction/).catch(error => String(error));
  check("the Tab key completes a name and lists the candidates", /scala> d\.longerThan\n\s*longerThan\s+longerThanFraction/.test(shown), shown);
  shown = await run("(0.5)", /scala>\s*$/);
  check("the completed line runs", /PersistenceDiagram\(/.test(shown), shown);

  shown = await run('Plot.barcode(d).copy(title = "from the container").view()', /TDA4j plots:[^]*scala>\s*$/);
  check("view() says where the plots are", shown.includes("http://127.0.0.1:8337/"), shown);
  const list = await fetch(plots + "list").then(response => response.text(), error => String(error));
  check("the plot is served on the published port", list.includes("from the container"), list);
  const viewer = await browser.newPage();
  await viewer.goto(plots);
  const drawn = await viewer
    .waitForFunction(() => [...document.querySelectorAll("iframe")].some(f => f.contentDocument && f.contentDocument.querySelector("svg")), null, { timeout: 20_000 })
    .then(() => true, () => false);
  check("the viewer's page draws it", drawn, await viewer.content().catch(() => "?"));
  if (process.env.SCREENSHOT) await viewer.screenshot({ path: process.env.SCREENSHOT.replace(/(\.\w+)?$/, "-plots$1") });
  await viewer.close();

  // `∆` typed in has to reach the compiler as that name, and the printed line has to come back as it was printed.
  shown = await run('println(s"${∆(1, 2, 3).size} vertices: ∆ ∞")', /scala>\s*$/);
  check("non-ASCII input and output survive the terminal", shown.split("\n").slice(1).join("\n").includes("3 vertices: ∆ ∞"), shown);
  if (process.env.SCREENSHOT) await page.screenshot({ path: process.env.SCREENSHOT });

  // Pasting several lines at once is what a participant does with a block from a tutorial page. The REPL takes the
  // block as one input and waits for Enter, as it does in any terminal.
  await page.evaluate(() => window.term.paste("val pasted = 20\nval twice = pasted * 2"));
  await waitFor(/scala> val pasted = 20\n\s+val twice = pasted \* 2$/);
  await page.keyboard.press("Enter");
  shown = await waitFor(/twice: Int = \d+[^]*scala>\s*$/).catch(error => String(error));
  check("a pasted block of two lines runs both", /pasted: Int = 20/.test(shown) && /twice: Int = 40/.test(shown), shown);

  // A reload is a new page on the same REPL: `d` is still there.
  await open();
  shown = await run("d.bars.size", /Int = \d+[^]*scala>\s*$/);
  check("a reloaded page is back in the same session", /Int = 5/.test(shown), shown);

  // `:quit` ends the REPL, and the page starts a new one by itself, which has never heard of `d`.
  await page.keyboard.type(":quit");
  await page.keyboard.press("Enter");
  await waitFor(/^TDA4j demo[^]*scala>\s*$/);
  shown = await run("d.bars.size", /Not found: d[^]*scala>\s*$/);
  check("after :quit the page is in a new session", /Not found: d/.test(shown), shown);
  check("the container is still running", docker("inspect", "-f", "{{.State.Running}}", container) === "true", "");
  const leftOver = docker("exec", container, "sh", "-c", "ps -eo stat,args | grep -c '^Z' || true");
  check("the ended REPL left no process behind", leftOver === "0", docker("exec", container, "ps", "-eo", "stat,args"));
} catch (failure) {
  check("the session ran to the end", false, String(failure));
} finally {
  await browser.close();
  if (failures.length) console.log("container log:\n" + docker("logs", "--tail", "15", container));
  try {
    docker("rm", "-f", container);
  } catch (alreadyGone) {}
}
console.log(`${failures.length === 0 ? "all checks passed" : failures.length + " check(s) FAILED"} in ${((Date.now() - started) / 1000).toFixed(0)} s`);
process.exit(failures.length === 0 ? 0 : 1);
