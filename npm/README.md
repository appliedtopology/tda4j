# @appliedtopology/tda4j

Persistent homology and topological data analysis, in JavaScript: Vietoris-Rips, Cech and alpha complexes, cubical
images, barcodes with a representative for every bar, distances between diagrams, and plots. The same library as
[TDA4j for the JVM](https://tda4j.appliedtopology.org), compiled with Scala.js.

```js
import { persistence } from "@appliedtopology/tda4j";

const result = persistence([[0, 0], [1, 0], [1, 1], [0, 1]], { maxDimension: 1 });

result.bars;                       // [{ dim: 0, birth: 0, death: Infinity }, { dim: 1, birth: 1, death: 1.414... }, ...]
result.representative(1);          // the loop: { cells: [[0, 1], [1, 2], ...], coefficients: [...] }
result.plotBarcode().svg();        // an SVG document
```

Other inputs: `persistenceFromDistances(matrix)`, `persistenceFromImage(pixels)`, `persistenceFromGrid(shape, values)`,
`persistenceFromLandmarks(points, landmarks)`. Landmarks, circular and toroidal coordinates, and parsers for point clouds,
OFF files and distance matrices (`parsePointCloud(text)`) are exported too.
The options are listed in `index.d.ts`; by default coefficients are in the field with 17 elements, degrees 0 to 2 are
computed, and a bar shorter than 1% of the scale of the data is hidden (`allBars` has every bar).

Install it with `npm install @appliedtopology/tda4j`. From a terminal, `npx @appliedtopology/tda4j --max-dimension 1 points.csv`
prints the barcode of a file with one point per line (the command is `tda4j` once the package is installed);
`npx @appliedtopology/tda4j --help` lists the options.

Runs in browsers and in Node.js 20.16 or newer. Reading and writing files (`save`, the command line) needs Node.js.
Everything runs on one thread, so a large computation is better placed in a Web Worker.

MIT license. Documentation and source: <https://github.com/appliedtopology/tda4j>.
