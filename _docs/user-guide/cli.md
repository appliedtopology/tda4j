---
layout: main
title: "Command-line tool: `tda4j`"
---

The assembly jar from the [installation page](installation.md) runs as a command-line tool. It reads a point cloud,
distance matrix, image or relation, computes persistence with the same code as the [MATLAB and Java interface](matlab.md),
and writes the diagram or draws it.

```
java -jar tda4j-VERSION-assembly.jar [options] <input-file>
```

Every flag has the same name and meaning as the MATLAB option in kebab-case (`--max-dimension` is `maxDimension`), and
`--help` lists them all. A flag you leave out keeps the library default, which is the default in the tables below.
Flags that take `true` or `false` take it as a value: `--edge-collapse true`.

```
java -jar tda4j-VERSION-assembly.jar --max-dimension 1 points.csv
java -jar tda4j-VERSION-assembly.jar --representatives --max-dimension 1 points.csv
java -jar tda4j-VERSION-assembly.jar --complex alpha --output-format csv --output diagram.csv points.csv
java -jar tda4j-VERSION-assembly.jar --input-format csv-relation --dual true relation.csv
java -jar tda4j-VERSION-assembly.jar --max-dimension 1 --save-result circle.json points.csv
java -jar tda4j-VERSION-assembly.jar --plot representative --output loop.svg circle.json
java -jar tda4j-VERSION-assembly.jar --output-format gudhi --output circle.pers circle.json
```

## Input

`<input-file>` is required. `--input-format` says how to read it:

| `--input-format` | the file holds |
|---|---|
| `csv-points` (default) | a point cloud, one point per row |
| `csv-distances` | a full distance matrix in CSV |
| `csv-lower` | the lower triangle of a distance matrix in CSV |
| `ripser-points`, `ripser-lower`, `ripser-upper`, `ripser-distance`, `ripser-sparse`, `ripser-binary` | Ripser's point-cloud and distance-matrix formats; `ripser-sparse` is `i j d` triples, a pair not listed has no edge |
| `dipha-distance` | DIPHA's distance matrix |
| `off` | a GUDHI OFF point cloud |
| `perseus-cubical` | a Perseus cubical toplex (an image or voxel grid) |
| `dipha-image` | a DIPHA image |
| `image` | an image file (JVM only) |
| `csv-relation` | a relation: one row per point, one column per witness, for the Dowker complex |
| `result` | a result saved with `--save-result`; chosen by itself for such a file |

The [file formats page](input-output.md) describes the formats.

## Computation

| flag | values | default |
|---|---|---|
| `--complex` | `vr`, `alpha`, `cech`, `witness`, `dtm-rips`, `dtm-alpha`, `sparse-rips` | `vr` (a cubical complex for an image, the Dowker complex for a relation) |
| `--engine` | `ripser`, `parallel-ripser`, `chunks`, `naive`, `cohomology`, `fast-cubical`, `fast-alpha` | `ripser` for `vr` and the lazy witness complex (`parallel-ripser` instead for `vr` when there are several threads and it applies), `fast-cubical` for images of dimension 2 and up, `cohomology` otherwise; see [which engine](homology-computation/choosing-engine.md) |
| `--threads` | integer, at least 1 | all the processors; `1` is the sequential computation. The bars do not depend on it; where tied simplices give a choice, the representatives can |
| `--max-dimension` | integer | `2`; for an image, its dimension |
| `--max-filtration-value` | number | the minimum enclosing radius; in the units of the complex (a diameter for `vr`, a radius for `cech` and `alpha`) |
| `--field` | `Z` (a prime field), `R` (floating point) | `Z` |
| `--prime` | prime | `17`, for `--field Z` |
| `--epsilon` | number | `1e-9`, the tolerance of `--field R` |
| `--representative-type` | `cycles`, `cocycles` | `cycles`; every engine gives both except `fast-cubical` and `fast-alpha` (cycles only) |
| `--include-zero-length` | `true`, `false` | `false` |

Options for one complex:

| flag | applies to | values | default |
|---|---|---|---|
| `--edge-collapse` | `vr` | `true`, `false` | `false`; the same diagram from a smaller complex |
| `--alpha-backend` | `alpha` | `default`, `bowyer-watson`, `helix`, `DQP` | `default`: `bowyer-watson` up to 4 dimensions, `helix` above; with `--max-filtration-value`, `DQP` when it is expected to be faster |
| `--require-valid-triangulation` | `alpha` with `helix` | `true`, `false` | `false`; `true` also checks for cavities and fails rather than return a triangulation that could not be repaired |
| `--dtm-k` | `dtm-rips`, `dtm-alpha` | integer | required: the number of nearest neighbours, the point itself included |
| `--dtm-q` | `dtm-rips`, `dtm-alpha` | number | `2.0`, the exponent of the distance to measure |
| `--dtm-p` | `dtm-rips` | `1.0`, `2.0` | `1.0` |
| `--sparse-epsilon` | `sparse-rips` | number in `(0, 1)` | required; the barcode is within a factor `1 + epsilon` of the Vietoris-Rips barcode |
| `--num-landmarks` | `witness` | integer | required |
| `--witness-variant` | `witness` | `lazy`, `general` | `lazy` |
| `--landmark-selector` | `witness` | `maxmin`, `random` | `maxmin` |
| `--landmark-seed` | `witness` | integer | `0`, for `random` |
| `--nu` | lazy `witness` | `0`, `1`, `2` | `2` |
| `--dual` | relations | `true`, `false` | `false`; the Dowker complex on the columns, with the same diagram |
| `--sublevel` | images | `true`, `false` | `true`; `false` is the superlevel filtration |
| `--nan-mask` | images | `true`, `false` | `true`: a `NaN` pixel is masked; `false`: it is an error |

A flag that does not apply to the chosen complex, an unknown value or an engine that cannot take the complex is an
error that names the flag.

### The witness complex in two steps

`--complex witness --num-landmarks N` selects landmarks and computes in one call, but does not report the covering
radius `R`. To use `2R` as the threshold, run two steps. `--select-landmarks --num-landmarks N` writes the landmarks,
one 0-based index per line and a `# coveringRadius=...` line, to `--output` or standard output, and prints `R` and the
exact `2R` to pass on. `--landmarks-file landmarks.txt --max-filtration-value <2R>` then computes with them (it implies
`--complex witness`). See [witness complexes](topological-spaces/witness-complexes.md).

## Output

The diagram goes to standard output or to `--output`.

| flag | meaning |
|---|---|
| `--output-format` | `text` (default), `csv`, `gudhi`, `dipha`, `perseus` |
| `--output` | the file to write. Required for every format but `text`. For `perseus` it is a filename prefix: one file per dimension |
| `--representatives` | with `text`, also print each bar's representative after `rep:`, as `coefficient*[vertices] + ...` |

`perseus` stores integer filtration steps, so it refuses a diagram with non-integer birth or death values rather than
round them. Cells of an image are given by their coordinates in the doubled grid (see [MATLAB](matlab.md#representatives)).

### Which bars are reported

As in MATLAB, bars whose persistence is at most 1% of the input's scale are not printed; essential bars always are.
When bars were hidden, one line on standard error says how many:

```
tda4j: 3 bar(s) with persistence <= 0.0195 not reported (58 reported); pass --min-persistence 0 to report them
```

`--min-persistence-fraction` changes the 1%, `--min-persistence` sets an absolute threshold (give at most one; `0`
prints every bar). Zero-length bars are only computed with `--include-zero-length true`. The threshold flags are
rejected with `--select-landmarks` (no diagram) and `--distance-to` (distances always use every bar).

| flag | meaning |
|---|---|
| `--min-persistence` | report only bars longer than this; `0` reports every bar of positive length. Essential bars are always reported |
| `--min-persistence-fraction` | the same as a fraction of the input's scale (the minimum enclosing radius, or the range of values of an image or relation); default `0.01` |

## Comparing diagrams

`--distance-to saved-diagram` prints the bottleneck and Wasserstein distances per dimension to a diagram saved earlier,
instead of writing a diagram, one line per dimension: `dim 1: bottleneck=0.04 wasserstein=0.04`. Distances use every
bar, whatever the threshold.

| flag | meaning |
|---|---|
| `--distance-to` | the saved diagram to compare with (text output only) |
| `--distance-format` | its format: `csv` (default), `gudhi`, `dipha` |
| `--distance-order` | the order of the Wasserstein distance; default `1.0` |
| `--distance-ground-norm` | the ground norm on the birth-death plane: a finite `p >= 1`; default `L∞` |

## The Euler characteristic curve

`--euler-curve` writes the [Euler characteristic curve](vectorization.md#the-euler-characteristic-curve) of the
Vietoris-Rips complex of a point cloud or a distance matrix instead of a diagram: a `scale,value` line for each scale
where the curve steps, after a header line.

```
$ tda4j --euler-curve circle.csv        # 30 points on a circle, as in the Scala example
scale,value
0.0,30
0.1928850396070111,29
0.20961433765776488,27
...
```

| flag | meaning |
|---|---|
| `--euler-curve` | write the curve instead of the diagram |
| `--max-simplex-dimension` | the largest simplex dimension counted; default `2` |
| `--max-filtration-value` | the largest scale; default the minimum enclosing radius |
| `--threads` | as for a diagram |
| `--output` | the file to write; default standard output |

A flag that would change a diagram (`--complex`, `--max-dimension`, `--engine`, `--field`, ...), `--plot`,
`--distance-to`, `--save-result` and the landmark flags are rejected with `--euler-curve`, as are images, relations and
saved results as input.

## Saved results

`--save-result result.json` also saves the whole result: every bar, the reported bars' representatives, the input and how
it was computed. The file is an input of its own (`--input-format result`, chosen by itself): print, convert, compare or
plot it without computing again. Computation flags are refused for it.

## Plots

`--plot KIND` draws the result instead of writing it, into `--output` (`.svg` in plain light colors with no background, `.html` a
page that follows the reader's light or dark setting) or, without `--output`, a page opened in the browser.

| `--plot` | draws |
|---|---|
| `barcode`, `diagram` | the barcode, the persistence diagram |
| `landscape`, `persistence-image` | the vectorizations of `--dimension` |
| `representative` | a bar's cycle or cocycle over the points or image |
| `input`, `complex` | the points or image; the complex at `--at` |
| `input-3d`, `complex-3d`, `representative-3d` | the same in 3-D, as a page you can turn |

A comma list of 2-D kinds draws them on one board, left to right: `--plot barcode,diagram`. `--layout RxC` puts them on a
grid instead, row by row (`--plot barcode,diagram,landscape --layout 2x2`), with `--width` and `--height` the size of the
whole drawing (default 960 by 600); `--tagged` letters the panels `(a)`, `(b)`, ...; `--title` is the title of the board,
and the other look flags apply to every panel. A 3-D plot is drawn on its own.

| flag | meaning |
|---|---|
| `--bar N` | for `representative`: the bar, by its 0-based line number in the text output; default the most persistent bar of `--dimension` |
| `--dimension D` | for `representative` (without `--bar`), `landscape` and `persistence-image`; default `1` |
| `--at X` | the scale: required for `complex`; for `representative`, a cycle is drawn over the complex at `X` and a cocycle restricted to it (a cocycle is restricted to its bar's birth unless you say; `--at Infinity` draws it whole) |
| `--layout RxC`, `--tagged` | for a list of kinds: the grid of the board, `2x2` or `1x3`, and lettered panels |
| `--title`, `--x-label`, `--y-label` | the words |
| `--caption`, `--tag`, `--description` | a line under the plot, a letter in its corner for one panel of a figure (`(a)`), and what a screen reader says of it; 2-D plots only |
| `--look` | a whole look at once: `default`, `print` (`--palette print --style print`) or `clear`; the flags below, after it, win |
| `--width`, `--height` | the size in pixels, 100 to 20000 |
| `--palette` | `brand` (default), `reference`, `print` (black on pure white, for a paper), `grayscale` (that in grays only, for a black-and-white print), `okabe-ito` (the eight colors of Okabe and Ito on white), `clear` (black for a light page, white for a dark one) |
| `--style` | `default`, `slides` (larger text and marks), `print` (portable fonts, a white background in an `.svg` too), `clear` (portable fonts, no background) |
| `--theme` | `light`, `dark`, `auto`; default `light` for `.svg`, `auto` for `.html` |
| `--text-scale F` | a factor on every text size (1.3 suits a slide) |
| `--x-scale`, `--y-scale` | `linear` (default), `log`, `sqrt`, `symlog`; 2-D plots only |
| `--set element.property=value` | one change of the look of an element; repeat the flag. `--set title.size=18`, `--set x-axis.reverse=true`, `--set size.width=3.4in` |

The settings `--set` takes are listed under [finer control](plotting.md#finer-control-text-lines-the-plot-area-the-sides-of-the-axes)
on the plotting page, with the other plot flags in [From MATLAB and the command line](plotting.md#from-matlab-and-the-command-line).
`--look print` (or `--palette print --style print`) makes a figure for a paper, white behind it in the `.svg`; `--look clear` one with no background on a page as well.
A flag a plot cannot use (an x scale for a 3-D scene) is an error, reported before anything is computed.

## Exit status

`0` on success. `1` when the command fails, with one line on standard error starting `tda4j:` that says what to change,
and when the arguments do not parse (`--help` shows the usage).
