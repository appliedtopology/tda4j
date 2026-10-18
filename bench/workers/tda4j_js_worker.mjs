// TDA4j's Scala.js side of the cross-platform benchmark (bench/, README.md): the same protocol as the JVM's
// PaperBenchmarkDriver. One computation per process: read the input once, run `warmup` untimed and `trials` timed
// computations through the package's public API (`persistence`, `persistenceFromDistances`, `persistenceFromGrid`),
// print one JSON line with the timings, and write the last barcode as `dim<TAB>birth<TAB>death`.
//
//   node [--max-old-space-size=MB] tda4j_js_worker.mjs task=vr input=sphere.txt format=points dim=2 \
//     [threshold=1.8] [p=2] [engine=auto|ripser|cohomology|chunks|naive|fastcubical|fastalpha] \
//     [reps=cycles|cocycles] [backend=default|bowyer-watson|helix|dqp] [warmup=2] [trials=5] [out=bars.tsv]
//
// The package comes from the environment: TDA4J_JS is the path of its main.mjs (`bench/run.sh setup` installs the
// npm tarball that `sbt tda4jJS/npmPack` builds and records the path).
//
// Times cover the whole call: building the complex, the reduction and the representatives, then reading the bars.
// A combination the JavaScript API does not offer exits with code 3, which the harness records as `unsupported`.
import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const opts = Object.fromEntries(
  process.argv.slice(2).map((arg) => {
    const i = arg.indexOf("=");
    if (i <= 0) throw new Error(`arguments are key=value, got '${arg}'`);
    return [arg.slice(0, i), arg.slice(i + 1)];
  }),
);

function unsupported(message) {
  console.error(message);
  process.exit(3);
}

const task = opts.task;
const input = opts.input;
const dim = Number(opts.dim);
const p = Number(opts.p ?? "17");
const threshold = opts.threshold === undefined ? undefined : Number(opts.threshold);
const engineName = (opts.engine ?? "auto").toLowerCase();
const repsName = (opts.reps ?? "cycles").toLowerCase();
const backend = (opts.backend ?? "default").toLowerCase();
const warmup = Number(opts.warmup ?? "2");
const trials = Number(opts.trials ?? "5");
const readReps = opts.read === "true";

if (!process.env.TDA4J_JS) throw new Error("TDA4J_JS is not set: it is the path of the npm package's main.mjs");
const tda4j = await import(pathToFileURL(process.env.TDA4J_JS).href);

// The facade's option names (npm/index.d.ts). Zero-length bars are dropped by default, as in the JVM driver;
// `minPersistence: 0` shows every bar of positive length, which is what the other tools report.
const engines = {
  auto: undefined,
  ripser: "ripser",
  cohomology: "cohomology",
  chunks: "chunks",
  naive: "naive",
  fastcubical: "fast-cubical",
  fastalpha: "fast-alpha",
};
if (!(engineName in engines)) throw new Error(`engine=${engineName} (${Object.keys(engines).join("|")})`);
if (repsName === "none") unsupported("reps=none is not offered by the JavaScript API");
if (!["cycles", "cocycles"].includes(repsName)) throw new Error(`reps=${repsName} (cycles|cocycles)`);

const options = { maxDimension: dim, prime: p, minPersistence: 0 };
if (engines[engineName] !== undefined) options.engine = engines[engineName];
if (repsName === "cocycles") options.representativeType = "cocycles";
if (threshold !== undefined) options.maxFiltrationValue = threshold;

/** Whitespace- or comma-separated numbers, one row per line; blank lines and `#` comments skipped. */
function readMatrix(path) {
  return readFileSync(path, "utf8")
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith("#"))
    .map((line) => line.split(/[,\s]+/).filter((x) => x.length > 0).map(Number));
}

/** A version 1-3 `.npy` file holding little-endian float64 in C order: [shape, values]. */
function readNpy(path) {
  const buffer = readFileSync(path);
  if (buffer[0] !== 0x93 || buffer.toString("latin1", 1, 6) !== "NUMPY") throw new Error(`${path} is not a .npy file`);
  const major = buffer[6];
  const headerLength = major === 1 ? buffer.readUInt16LE(8) : buffer.readUInt32LE(8);
  const start = (major === 1 ? 10 : 12) + headerLength;
  const header = buffer.toString("latin1", major === 1 ? 10 : 12, start);
  if (!header.includes("'<f8'")) throw new Error(`${path}: expected little-endian float64 ('<f8'), header ${header}`);
  if (!header.includes("'fortran_order': False")) throw new Error(`${path}: expected C order, header ${header}`);
  const shapeText = /'shape':\s*\(([^)]*)\)/.exec(header);
  if (!shapeText) throw new Error(`${path}: no shape in header ${header}`);
  const shape = shapeText[1].split(",").map((s) => s.trim()).filter((s) => s.length > 0).map(Number);
  const count = shape.reduce((a, b) => a * b, 1);
  const values = new Float64Array(buffer.buffer.slice(buffer.byteOffset + start, buffer.byteOffset + start + 8 * count));
  return [shape, Array.from(values)];
}

const loadStart = performance.now();
let compute;
if (task === "vr") {
  const format = opts.format ?? "points";
  const matrix = readMatrix(input);
  if (format === "points") compute = () => tda4j.persistence(matrix, options);
  else if (format === "distance") compute = () => tda4j.persistenceFromDistances(matrix, options);
  else throw new Error(`format=${format} (points|distance)`);
} else if (task === "cubical") {
  const [shape, values] = readNpy(input);
  compute = () => tda4j.persistenceFromGrid(shape, values, options);
} else if (task === "alpha") {
  if (engineName === "fastalpha" && backend === "dqp") unsupported("engine=fastalpha needs a triangulation, not DQP");
  if (engineName === "fastalpha" && threshold !== undefined) unsupported("engine=fastalpha takes no threshold");
  const matrix = readMatrix(input);
  const alpha = { ...options, complex: "alpha" };
  if (backend !== "default") alpha.alphaBackend = backend;
  // The JVM driver's DQP path truncates the stream and the others use `Persistence(.., complex = AlphaShapes)`;
  // the API's maxDimension does both.
  compute = () => tda4j.persistence(matrix, alpha);
} else if (task === "cech") {
  const matrix = readMatrix(input);
  compute = () => tda4j.persistence(matrix, { ...options, complex: "cech" });
} else {
  throw new Error(`task=${task} (vr|cubical|alpha|cech)`);
}
const loadSeconds = (performance.now() - loadStart) / 1000;

/** One computation: the bars as [dim, birth, death] (death Infinity when essential). `read=true` also reads every
 * representative inside the timed region, as a caller reading them all would. */
function once() {
  const result = compute();
  const all = result.allBars; // a new array on every access: take it once
  const bars = all.map((bar) => [bar.dim, bar.birth, bar.death]);
  if (readReps) for (let i = 0; i < all.length; i++) result.representative(i);
  return bars;
}

let bars = [];
for (let i = 0; i < warmup; i++) bars = once();
const times = [];
for (let i = 0; i < trials; i++) {
  const t0 = performance.now();
  bars = once();
  times.push((performance.now() - t0) / 1000);
}

if (opts.out) {
  const format = (x) => (x === Infinity ? "inf" : String(x));
  writeFileSync(opts.out, bars.map(([d, b, e]) => `${d}\t${format(b)}\t${format(e)}\n`).join(""));
}
const counts = {};
for (const [d] of bars) counts[d] = (counts[d] ?? 0) + 1;
console.log(
  JSON.stringify({
    tool: "tda4j-js",
    task,
    engine: engineName,
    p,
    warmup,
    load_s: loadSeconds,
    times_s: times,
    bars: counts,
    heap_used_mb: Math.round(process.memoryUsage().heapUsed / 1048576),
    node: process.version,
    tda4j: tda4j.version,
  }),
);
