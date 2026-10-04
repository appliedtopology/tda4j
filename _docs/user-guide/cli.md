---
layout: main
title: Command-line tool: `tda4j`
---


`sbt assembly` builds a runnable fat jar exposing the whole library as a command-line tool, without writing
any Scala:

```
java -jar target/out/jvm/scala-3.9.0/tda4j/tda4j-<version>-assembly.jar [options] <input-file>
```

It loads a point cloud, distance matrix, cubical image, or Dowker relation (`--input-format csv-relation`,
routed to `computeFromRelation` — a plain rows-x-columns CSV, the same shape `csv-points` already reads, just
without any distance/coordinate meaning) in one of several formats (`--input-format`), computes persistence via
the same facade the MATLAB bridge uses (below), and writes the result in one of several formats
(`--output-format`: `text`, `csv`, `gudhi`, `dipha`, `perseus`). Run with `--help` for the full flag list; the
main ones mirror the MATLAB options one-to-one: `--complex` (`vr`/`alpha`/`cech`/
`witness`/`dtm-rips`/`dtm-alpha`/`sparse-rips`, meaningless with `--input-format csv-relation`), `--dual`
(`true`/`false`, only consulted with `--input-format csv-relation` — computes the transposed-relation complex),
`--engine`, `--max-dimension`, `--max-filtration-value`,
`--field`, `--representatives` (also print each bar's representative chain), and (for `--complex=witness`)
`--num-landmarks`, `--witness-variant`, `--landmark-selector`, `--landmark-seed`, `--nu`. For
`--complex=dtm-rips` or `--dtm-alpha`, use `--dtm-k` (required), `--dtm-q` (default 2.0), and `--dtm-p`
(default 1.0, only for `dtm-rips`). For `--complex=sparse-rips`, use `--sparse-epsilon` (required, strictly
between `0` and `1`). `--select-landmarks`/`--landmarks-file` split that same witness-complex computation
into the two-step recipe described above. `--distance-to <file>` (`--distance-format csv`/`gudhi`/`dipha`,
`--distance-order`, `--distance-ground-norm`) compares the freshly-computed diagram against one already saved
to a file, printing bottleneck/Wasserstein distance per dimension instead of writing a diagram — see
"Comparing diagrams and turning them into vectors" below for the underlying `PersistenceResult` methods this
mirrors.

### Which bars are reported

Like the MATLAB facade (see "Which bars are reported" there), the CLI hides bars with persistence at most 1% of
the input's minimum enclosing radius by default (Ripser's enclosing radius — every bar lives between 0 and it;
for a cubical image or Dowker relation, the range of its values); essential bars are always reported. When anything
was hidden, one line on stderr says how many and how to get them back:

```
tda4j: 412 bar(s) with persistence <= 0.0213 not reported (9 reported); pass --min-persistence 0 to report every bar
```

`--min-persistence-fraction <f>` changes the 1% (a fraction of the scale); `--min-persistence <p>` sets an absolute
threshold in the barcode's own units instead — give at most one. `0` for either reports every bar. Both flags are
rejected with `--select-landmarks` (no barcode is produced) and with `--distance-to` (the comparison always uses
the complete barcode, so that the distance does not depend on this run's own scale).