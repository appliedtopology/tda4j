---
layout: main
title: Using it from JavaScript
---

TDA4j runs in JavaScript: the same library, compiled with Scala.js, as an [npm package](https://www.npmjs.com/package/@appliedtopology/tda4j)
for JavaScript and TypeScript, and as a Scala.js library for Scala programs that run in a browser or in Node.js. It
works in browsers and in Node.js 20.16 or newer.

## From JavaScript or TypeScript

```
npm install @appliedtopology/tda4j
```

```js
import { persistence } from "@appliedtopology/tda4j";

const points = [[0, 0], [1, 0], [1, 1], [0, 1]];
const result = persistence(points, { maxDimension: 1 });

result.bars;                // [{ dim: 0, birth: 0, death: Infinity }, { dim: 1, birth: 1, death: 1.41... }, ...]
result.representative(1);   // { cells: [[0, 1], [1, 2], ...], coefficients: [...] }: the loop of the bar result.bars[1]
result.plotBarcode().svg(); // an SVG document, to put in a page or a file
```

The functions take arrays of numbers and an options object, and a bar is `{ dim, birth, death }` with `death` equal to
`Infinity` for a class that never dies. They are the [MATLAB and Java interface](matlab.md) in JavaScript clothes, and
the options are its options, spelled the same way: `complex` (`"vr"` is the default; `"cech"`, `"alpha"`, `"witness"`,
`"dtm-rips"`, `"dtm-alpha"` and `"sparse-rips"` too), `engine`, `maxDimension` (default 2), `maxFiltrationValue`,
`prime` (default 17), `field`, `representativeType`, `minPersistence`, `includeZeroLength` and the rest, which
`index.d.ts` in the package lists with their types. A bar much shorter than the scale of the data is hidden, as in the
other interfaces: `result.allBars` has every bar, and `result.hiddenCount` says how many were left out.

| function | input |
|---|---|
| `persistence(points, options)` | a point cloud, one array of coordinates per point |
| `persistenceFromDistances(distances, options)` | a full distance matrix |
| `persistenceFromImage(pixels, options)` | a grayscale image, `pixels[row][column]`, as a cubical complex |
| `persistenceFromGrid(shape, values, options)` | an image or voxel grid of any dimension, values in row-major order |

A `Result` also has `bottleneckDistance(other, dimension)`, `wassersteinDistance(other, dimension, order)`,
`landscape(dimension, numLevels, tMin, tMax, resolution)` and the plots `plotBarcode()`, `plotDiagram()`,
`plotComplex(at)` and `plotRepresentative(i, at)`. A plot has `svg()` and `html()` for the text of a picture or a page,
`show()` to put it in the page (in a browser) or open it in the default browser (from Node.js), and `save(path)`.

A bad option or a malformed input throws an `Error` that says what to change.

### On the command line

`npx @appliedtopology/tda4j --max-dimension 1 points.csv` runs the [command line](cli.md) of the JVM build, with the same
flags, reading files through Node.js; once the package is installed, `tda4j --max-dimension 1 points.csv` does. It does not read image files (`--input-format image`): decode the image yourself and pass its
pixels to `persistenceFromImage`.

### What differs from the JVM build

- **One thread.** JavaScript has no threads to share the work between. `parallelFiltrationValue` and the `parallel`
  option of the DQP alpha complex give the same answer, not faster; to keep a page responsive, run the computation in a
  Web Worker.
- **Files need Node.js.** In a browser there is no file system: `save`, and the readers of the
  [file formats](input-output.md), throw an error that says so. Pass the data in memory.
- **The live viewer is the page.** `plot.show()` appends the plot to the page in a browser, and opens it in the
  default browser from Node.js; there is no viewer server.
- **Speed.** The engines run at their usual pace on a few thousand simplices; programs that work with hundreds of
  millions of simplices belong on the JVM, where the engines were tuned and memory is not limited by the browser.
- **Floating point.** Sums and square roots are the same IEEE operations as on the JVM, but `Math.pow` and friends are
  the JavaScript engine's own, so a value computed through them can differ in the last digit.

## From Scala.js

In a Scala.js project, the dependency is the same line as for the JVM (see [installation](installation.md)); sbt 2's
`%%` picks the Scala.js artifact `tda4j_sjs1_3` by itself (`%%%` in sbt 1). The code is the Scala code of the
[quickstart](quickstart.md): `Persistence(points)` works as it does on the JVM. The cross-build's layout is described in
the Developer's Guide [architecture](../developers-guide/architecture.md) page.
