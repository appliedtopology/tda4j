#!/usr/bin/env node
// Drives the demo's web page (demo/web/index.html) in headless Chromium, as a participant would: types inputs at the
// prompt, reads what the page shows, and fails on the first thing that is not what the terminal REPL would show.
//
//   (cd demo && sbt webSite)                                  # demo/target/web: the page, the jar, the patch jar
//   node .claude/scripts/check-web-playground.mjs [site]      # default site: demo/target/web
//
// Needs Playwright with its Chromium, and the network: the page loads its Java runtime (CheerpJ) from the vendor's
// CDN. Behind a proxy that HTTPS_PROXY names, the browser is told to use it. Prints the time of every input: those
// numbers are this machine's, with the jar on localhost.
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { serve } from "../../demo/web/serve.mjs";

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const site = path.resolve(process.argv[2] || path.join(repository, "demo/target/web"));

let chromium;
try {
  ({ chromium } = await import("playwright"));
} catch (notAModule) {
  ({ chromium } = createRequire(import.meta.url)("playwright"));
}

const server = await serve(site, 0);
const address = `http://127.0.0.1:${server.address().port}/`;
const proxy = process.env.HTTPS_PROXY ? { server: process.env.HTTPS_PROXY, bypass: "127.0.0.1,localhost" } : undefined;
const browser = await chromium.launch({ proxy });
const page = await browser.newPage({ viewport: { width: 1100, height: 900 } });
const pageErrors = [];
page.on("pageerror", error => pageErrors.push(String(error)));
// What the page fetched from its own site: the jars, in pieces. The runtime itself comes from the vendor's CDN.
let fromSite = 0;
page.on("requestfinished", async request => {
  if (request.url().startsWith(address)) fromSite += (await request.sizes().catch(() => ({ responseBodySize: 0 }))).responseBodySize;
});
if (process.env.VERBOSE) {
  page.on("console", message => console.log("      [page] " + message.text().slice(0, 300)));
  page.on("requestfailed", request => console.log("      [failed] " + request.url().slice(0, 120)));
}

const failures = [];
function check(what, holds, shown) {
  console.log(`${holds ? "ok  " : "FAIL"}  ${what}${holds ? "" : "\n      shown: " + JSON.stringify(shown).slice(0, 600)}`);
  if (!holds) failures.push(what);
}

const input = page.locator("#input");
async function ready(timeout) {
  await page.waitForFunction(() => !document.getElementById("input").disabled, null, { timeout });
}

/** Types `text` and presses Enter; the page either runs it or, while it is unfinished, opens a new line. */
async function type(text) {
  await input.pressSequentially(text);
  await input.press("Enter");
  await ready(240_000);
}

/** What the last entry of the transcript shows: its output text and the documents of its plots. */
async function lastEntry() {
  return page.evaluate(() => {
    const entry = [...document.querySelectorAll("#transcript .entry")].at(-1);
    return {
      echo: entry.querySelector("pre.in").textContent,
      out: [...entry.querySelectorAll("pre.out")].map(pre => pre.textContent).join("\n"),
      plots: [...entry.querySelectorAll("iframe.plot")].map(frame => frame.contentDocument.documentElement.outerHTML),
      heights: [...entry.querySelectorAll("iframe.plot")].map(frame => frame.getBoundingClientRect().height),
    };
  });
}

/** One whole input: typed, run, and timed. */
async function run(text) {
  const entries = await page.locator("#transcript .entry").count();
  const started = Date.now();
  await type(text);
  await page.waitForFunction(n => document.querySelectorAll("#transcript .entry").length > n, entries, { timeout: 5_000 });
  const shown = await lastEntry();
  console.log(`      ${((Date.now() - started) / 1000).toFixed(1)} s  ${text.split("\n")[0].slice(0, 70)}`);
  return shown;
}

const started = Date.now();
try {
  await page.goto(address);
  await ready(300_000);
  console.log(`      ${((Date.now() - started) / 1000).toFixed(1)} s  to the first prompt: ${await page.locator("#status").textContent()}`);
  check("the heading shows the library's version", /\d+\.\d+/.test(await page.locator("#version").textContent()), await page.locator("#version").textContent());
  check("the imports ran without a message", (await page.locator("#transcript .entry").count()) === 0, await page.locator("#transcript").textContent());

  let shown = await run("1 + 1");
  check("an expression prints its value", shown.out.includes("val res0: Int = 2"), shown);

  await run("val points = Array.tabulate(50)(i => Array(100*math.cos(i * math.Pi / 25), 100*math.sin(i * math.Pi / 25)))");
  shown = await run("val diagram = Persistence(points)");
  check("Persistence finds the one loop of a circle", /H1: 1 bar/.test(shown.out), shown);

  shown = await run("Plot.barcode(diagram).view()");
  check("view() echoes the plot as one line", shown.out.includes('Figure("Barcode"'), shown);
  check("view() puts one plot under its line", shown.plots.length === 1 && shown.plots[0].includes("<svg"), { plots: shown.plots.length });
  check("the plot's frame was fitted to it", shown.heights.length === 1 && shown.heights[0] > 100 && shown.heights[0] !== 520, shown.heights);

  shown = await run("{ Plot.diagram(diagram).view(); Plot.barcode(diagram).view(); 7 }");
  check("two plots from one input both arrive, in order", shown.plots.length === 2 && shown.out.includes("Int = 7"), { plots: shown.plots.length, out: shown.out });

  // An indented block: Enter opens a new line while the compiler says more is coming, and an empty line ends it.
  await input.pressSequentially("def twice(x: Double) =");
  await input.press("Enter");
  await ready(60_000);
  check("Enter on an unfinished input opens a new line", (await input.inputValue()) === "def twice(x: Double) =\n", await input.inputValue());
  await input.pressSequentially("  x * 2");
  await input.press("Enter");
  await ready(60_000);
  check("an indented block stays open until an empty line", (await input.inputValue()) === "def twice(x: Double) =\n  x * 2\n", await input.inputValue());
  await input.press("Enter");
  await ready(120_000);
  shown = await lastEntry();
  check("the empty line runs the block", shown.out.includes("def twice(x: Double): Double") && shown.echo.includes("     |   x * 2"), shown);
  shown = await run("twice(21)");
  check("the definition is in the session", shown.out.includes("Double = 42.0"), shown);

  shown = await run("List(1, 2, 3).apply(7)");
  check("an exception prints its message and trace", shown.out.includes("java.lang.IndexOutOfBoundsException: 7") && shown.out.includes("at scala.collection"), shown);
  check("the REPL itself did not fail on it", !shown.out.includes("could not finish"), shown);

  shown = await run("Persistence(points, maxDimension = -1)");
  check("the library's own error message arrives", shown.out.includes("maxDimension must be >= 0"), shown);

  shown = await run('val oops: Int = "three"');
  check("a type error is reported as the compiler words it", shown.out.includes("Type Mismatch Error") && shown.out.includes("Required: Int"), shown);

  shown = await run('println("printed"); Console.err.println("to err"); 2 + 2');
  check("printed text and the value both show, after the failures", shown.out.includes("printed") && shown.out.includes("to err") && shown.out.includes("Int = 4"), shown);

  await input.press("ArrowUp");
  check("the up arrow brings back the last input", (await input.inputValue()).startsWith('println("printed")'), await input.inputValue());
  await input.press("ArrowUp");
  await input.press("ArrowDown");
  await input.press("ArrowDown");
  check("the down arrow returns to the empty line", (await input.inputValue()) === "", await input.inputValue());

  await page.locator("#examples button").nth(1).click();
  check("an example goes to the prompt without running", (await input.inputValue()) === "val diagram = Persistence(points)", await input.inputValue());

  check("the page raised no script error", pageErrors.length === 0, pageErrors);
  console.log(`      ${(fromSite / 1e6).toFixed(1)} MB fetched from the site in this session`);
} catch (failure) {
  check("the session ran to the end", false, String(failure));
  console.log("      status line: " + (await page.locator("#status").textContent().catch(() => "?")));
  if (pageErrors.length) console.log("      page errors: " + pageErrors.join(" | ").slice(0, 800));
} finally {
  if (process.env.SCREENSHOT) await page.screenshot({ path: process.env.SCREENSHOT, fullPage: true }).catch(() => {});
  await browser.close();
  server.close();
}
console.log(`${failures.length === 0 ? "all checks passed" : failures.length + " check(s) FAILED"} in ${((Date.now() - started) / 1000).toFixed(0)} s`);
process.exit(failures.length === 0 ? 0 : 1);
