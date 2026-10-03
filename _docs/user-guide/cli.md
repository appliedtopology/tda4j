---
layout: main
title: "Command-line tool: `tda4j`"
---

`sbt assembly` builds a jar that runs as a command-line tool:

```
java -jar target/out/jvm/scala-3.9.0/tda4j/tda4j-<version>-assembly.jar [options] <input-file>
```

It reads a point cloud, distance matrix, image or relation, computes persistence with the same code as the
[MATLAB interface](matlab.md), and writes the diagram. `--help` lists every flag; they mirror the MATLAB options one to
one:

* input and output: `--input-format` (`csv-points`, distance-matrix and image formats, `csv-relation`), `--output-format`
  (`text`, `csv`, `gudhi`, `dipha`, `perseus`), `--output`, `--representatives` (also print each bar's representative);
* the computation: `--complex`, `--engine`, `--max-dimension`, `--max-filtration-value`, `--field`, `--prime`,
  `--edge-collapse`, `--alpha-backend`, `--require-valid-triangulation`, `--dtm-k`, `--dtm-q`, `--dtm-p`,
  `--sparse-epsilon`, `--dual` (relations);
* the witness complex: `--num-landmarks`, `--witness-variant`, `--landmark-selector`, `--landmark-seed`, `--nu`, and the
  two-step `--select-landmarks` / `--landmarks-file` (see [witness complexes](topological-spaces/witness-complexes.md));
* which bars: `--min-persistence`, `--min-persistence-fraction`, `--include-zero-length`; and which representatives:
  `--representative-type` (`cycles` or `cocycles`);
* comparing: `--distance-to saved-diagram` (with `--distance-format`, `--distance-order`, `--distance-ground-norm`)
  prints the bottleneck and Wasserstein distances per dimension to a diagram saved earlier, instead of a diagram.

```
java -jar tda4j-assembly.jar --max-dimension 1 points.csv
java -jar tda4j-assembly.jar --input-format csv-relation --dual true relation.csv
```

### Which bars are reported

As in MATLAB, bars whose persistence is at most 1% of the input's scale are not printed; essential bars always are.
When bars were hidden, one line on standard error says how many:

```
tda4j: 3 bar(s) with persistence <= 0.0195 not reported (58 reported); pass --min-persistence 0 to report them
```

`--min-persistence-fraction` changes the 1%, `--min-persistence` sets an absolute threshold (give at most one; `0`
prints every bar). Zero-length bars are only computed with `--include-zero-length true`. The threshold flags are
rejected with `--select-landmarks` (no diagram) and `--distance-to` (distances always use every bar).
