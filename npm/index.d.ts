/** The version of tda4j. */
export const version: string;

export type Complex = "vr" | "cech" | "alpha" | "witness" | "dtm-rips" | "dtm-alpha" | "sparse-rips";
export type Engine = "ripser" | "naive" | "chunks" | "cohomology" | "fast-cubical" | "fast-alpha";

/**
 * The options of a computation. Names are not case sensitive; an option the function does not use, or a misspelled
 * one, throws an Error that says so. Leave an option out (or set it to `undefined`) to get its default.
 */
export interface Options {
  /** The complex to build on a point cloud or a distance matrix. Default `"vr"`. */
  complex?: Complex;
  /** The algorithm that reduces the boundary matrix. The default depends on the complex and the input. */
  engine?: Engine;
  /** Whether each bar's representative is a cycle (default) or a cocycle. */
  representativeType?: "cycles" | "cocycles";
  /** For `complex: "alpha"`: `"default"`, `"helix"`, `"dqp"` or `"bowyer-watson"`. */
  alphaBackend?: string;
  /** The top homological degree to compute. Default 2. */
  maxDimension?: number;
  /** Stop the complex at this scale. Default: the enclosing radius. */
  maxFiltrationValue?: number;
  /** Coefficients: `"Z"` (a prime field, see `prime`, the default) or `"R"` (floating point numbers). */
  field?: "Z" | "R";
  /** The prime of the field when `field` is `"Z"`. Default 17. */
  prime?: number;
  /** The tolerance of the alpha complex's degeneracy tests when `field` is `"R"`. */
  epsilon?: number;
  /** For images: sublevel sets (default) or superlevel sets. */
  sublevel?: boolean;
  /** For images: a NaN pixel is a missing pixel (default) or an error. */
  nanMask?: boolean;
  /** How many landmarks `selectLandmarks` picks (required there), or the witness complex uses. */
  numLandmarks?: number;
  witnessVariant?: string;
  /** `"maxmin"` (default) or `"random"`. */
  landmarkSelector?: string;
  landmarkSeed?: number;
  nu?: number;
  dtmK?: number;
  dtmQ?: number;
  dtmP?: number;
  sparseEpsilon?: number;
  edgeCollapse?: boolean;
  requireValidTriangulation?: boolean;
  /** Hide bars that persist for less than this. */
  minPersistence?: number;
  /** Hide bars that persist for less than this fraction of the enclosing radius. Default 0.01; 0 shows every bar. */
  minPersistenceFraction?: number;
  /** Keep the bars of length zero. Default false. */
  includeZeroLength?: boolean;
}

export interface Bar {
  /** The homological degree. */
  dim: number;
  birth: number;
  /** `Infinity` for a class that never dies. */
  death: number;
}

export interface Representative {
  /** Each cell is a simplex's vertices (point numbers from 0), or a cube's doubled coordinates for an image. */
  cells: number[][];
  /** One coefficient for each cell. */
  coefficients: number[];
}

/** A plot. */
export class Chart {
  readonly title: string;
  /** An SVG document, in plain colors (`"light"`, the default, or `"dark"`). */
  svg(theme?: "light" | "dark"): string;
  /** A page of HTML that follows the reader's light or dark setting (`"auto"`, the default). */
  html(theme?: "auto" | "light" | "dark"): string;
  /** Writes the plot to `path` (Node.js only): an SVG file for `.svg`, an HTML page otherwise. Returns the absolute path. */
  save(path: string): string;
  /** Appends the plot to the page in a browser; opens it in the default browser from Node.js. */
  show(): void;
}

export class Result {
  /** The bars that pass the persistence threshold. */
  readonly bars: Bar[];
  /** Every bar of positive length. */
  readonly allBars: Bar[];
  /** How many bars the threshold hides. */
  readonly hiddenCount: number;
  /** The representative of `bars[i]`. */
  representative(i: number): Representative;
  /** The cells that open and close `bars[i]`, each a cell as in `representative(i).cells`: `birth` is `null` where the engine recorded none, `death` where no cell closes the bar (an essential one). */
  barCells(i: number): { birth: number[] | null; death: number[] | null };
  bottleneckDistance(other: Result, dimension: number): number;
  wassersteinDistance(other: Result, dimension: number, order?: number): number;
  /** `landscape[k][j]` is level `k` of the persistence landscape at sample `j` of `[tMin, tMax]`. */
  landscape(dimension: number, numLevels: number, tMin: number, tMax: number, resolution: number): number[][];
  /** An optimal matching of the bars of degree `dimension` with those of `other` for the `order`-Wasserstein distance (default 1): `[i, j]` pairs bar `i` here with bar `j` there, `[i, -1]` sends a bar here to the diagonal and `[-1, j]` one there. Empty when the numbers of essential bars differ. */
  wassersteinMatching(other: Result, dimension: number, order?: number): number[][];
  /** As `wassersteinMatching`, for the bottleneck distance. */
  bottleneckMatching(other: Result, dimension: number): number[][];
  /** The number of bars of degree `dimension` alive at each of `resolution` samples of `[tMin, tMax]`: a bar `[birth, death)` is alive at `t` when `birth <= t < death`. */
  bettiCurve(dimension: number, tMin: number, tMax: number, resolution: number): number[];
  /** The persistence silhouette of the bars of degree `dimension`: the average of their tents weighted by persistence to the power `power` (default 1). Essential bars are left out. */
  silhouette(dimension: number, tMin: number, tMax: number, resolution: number, power?: number): number[];
  /** The persistence entropy of the bars of degree `dimension` (in `[0, 1]` when `normalized`). Essential bars are left out. */
  persistenceEntropy(dimension: number, normalized?: boolean): number;
  /** The sliced Wasserstein distance between the bars of degree `dimension` of two results, averaged over `directions` directions (default 50). Essential bars are left out. */
  slicedWassersteinDistance(other: Result, dimension: number, directions?: number): number;
  /**
   * `image[i][j]` is the pixel of the persistence image of the bars of degree `dimension` at birth sample `i` and
   * persistence sample `j`, over `[birthMin, birthMax] x [persistenceMin, persistenceMax]`, with a Gaussian of width
   * `sigma`. Essential bars are left out. A bar's weight grows with its persistence up to `weightCap` (default: the
   * largest persistence).
   */
  persistenceImage(
    dimension: number,
    sigma: number,
    birthMin: number,
    birthMax: number,
    persistenceMin: number,
    persistenceMax: number,
    birthResolution: number,
    persistenceResolution: number,
    weightCap?: number
  ): number[][];
  /** The number of cells of the complex: the size of the boundary matrix. */
  readonly numCells: number;
  /**
   * The nonzero entries of the boundary matrix, in the cells' filtration order: cell `cols[k]` has cell `rows[k]` in
   * its boundary with coefficient `values[k]` (a residue over a prime field).
   */
  boundary(): { rows: number[]; cols: number[]; values: number[] };
  /** The dimension of cell `j`. */
  columnDimension(j: number): number;
  /** Cell `j` as its vertices (point numbers from 0), or a cube's doubled coordinates for an image. */
  columnVertices(j: number): number[];
  /** The complex at scale `r` holds the cells with a value of at most `r`. */
  columnFiltrationValue(j: number): number;
  plotBarcode(): Chart;
  plotDiagram(): Chart;
  /** The complex at scale `at`, over the input points. */
  plotComplex(at: number): Chart;
  /** The representative of `bars[i]` over the input, at scale `at` when given. */
  plotRepresentative(i: number, at?: number): Chart;
  /** Writes the result as JSON (Node.js only). Returns the absolute path. */
  save(path: string): string;
}

/** The persistent homology of a point cloud: one array of coordinates per point. */
export function persistence(points: number[][], options?: Options): Result;
/** The persistent homology of a finite metric space, from its full matrix of distances. */
export function persistenceFromDistances(distances: number[][], options?: Options): Result;
/** The persistent homology of a grayscale image, `pixels[row][column]`, as a cubical complex. */
export function persistenceFromImage(pixels: number[][], options?: Options): Result;
/** The persistent homology of a grid of any dimension: its shape and its values in row-major order. */
export function persistenceFromGrid(shape: number[], values: number[], options?: Options): Result;

/** The persistent homology of a witness-style complex on the landmarks (point numbers from 0) of a point cloud. */
export function persistenceFromLandmarks(points: number[][], landmarks: number[], options?: Options): Result;
/** `persistenceFromLandmarks` for a finite metric space given as its full matrix of distances. */
export function persistenceFromDistancesAndLandmarks(distances: number[][], landmarks: number[], options?: Options): Result;

/** The relative persistent homology of the Vietoris-Rips complex of a point cloud modulo the subcomplex on the points `subcomplex` (point numbers from 0): the bars of H(X_t, A_t). The representatives and the cells of the bars are cells outside the subcomplex. */
export function relativePersistence(points: number[][], subcomplex: number[], options?: Options): Result;
/** `relativePersistence` for a finite metric space given as its full matrix of distances. */
export function relativePersistenceFromDistances(distances: number[][], subcomplex: number[], options?: Options): Result;

/** The map on persistent homology in one degree: the bars as `[birth, death]` rows, the matrix as `[source bar, target bar, coefficient]` rows (the bars as positions in `sourceBars` and `targetBars`), and the barcodes of its image, kernel and cokernel. */
export interface MapDegree {
  dimension: number;
  sourceBars: number[][];
  targetBars: number[][];
  entries: number[][];
  image: number[][];
  kernel: number[][];
  cokernel: number[][];
}

/** The map on persistent homology induced by a map of Vietoris-Rips complexes, degree by degree from 0 to `maxDimension`. */
export interface HomologyMap {
  maxDimension: number;
  /** The prime of the coefficient field Z/p. */
  characteristic: number;
  degrees: MapDegree[];
}

/** The map induced by the inclusion of the Vietoris-Rips complex of a subsample (point numbers from 0) into that of the whole point cloud. Options: `engine` ("ripser" or "cohomology"), `maxDimension`, `maxFiltrationValue` (shared; by default the whole cloud's enclosing radius), `prime`, `threads`. */
export function inclusionMap(points: number[][], subsample: number[], options?: Options): HomologyMap;
/** `inclusionMap` for a finite metric space given as its full matrix of distances. */
export function inclusionMapFromDistances(distances: number[][], subsample: number[], options?: Options): HomologyMap;
/** The map induced by the simplicial map of Vietoris-Rips complexes that sends point `i` of `sourcePoints` to point `vertexMap[i]` of `targetPoints`. It must not increase a distance. */
export function simplicialMap(sourcePoints: number[][], targetPoints: number[][], vertexMap: number[], options?: Options): HomologyMap;
/** `simplicialMap` between two finite metric spaces given as full matrices of distances. */
export function simplicialMapFromDistances(sourceDistances: number[][], targetDistances: number[][], vertexMap: number[], options?: Options): HomologyMap;

export interface LandmarkSelection {
  /** Point numbers from 0. */
  landmarks: number[];
  /** The largest distance from a point to its nearest landmark; compute with `maxFiltrationValue: 2 * coveringRadius`. */
  coveringRadius: number;
}
/** Picks `options.numLandmarks` landmarks (`options.landmarkSelector`: `"maxmin"` or `"random"`, with `landmarkSeed`). */
export function selectLandmarks(points: number[][], options?: Options): LandmarkSelection;
/** `selectLandmarks` for a finite metric space given as its full matrix of distances. */
export function selectLandmarksFromDistances(distances: number[][], options?: Options): LandmarkSelection;
/** The covering radius of any set of landmarks (point numbers from 0). */
export function coveringRadius(points: number[][], landmarks: number[]): number;

/** The options of `eulerCurve`. */
export interface EulerCurveOptions {
  /** The largest simplex dimension counted. Default 2. */
  maxSimplexDimension?: number;
  /** Stop the complex at this scale. Default: the enclosing radius. */
  maxFiltrationValue?: number;
  /** Worker threads (JavaScript runs one). */
  threads?: number;
}

/** The Euler characteristic of a Vietoris-Rips complex as a function of the scale: a step function. */
export class EulerCurve {
  /** The scales where the curve changes, strictly increasing; the first is `0`, where the vertices appear. */
  readonly scales: number[];
  /** `values[i]` is the Euler characteristic from `scales[i]` up to (not including) `scales[i + 1]`. */
  readonly values: number[];
  /** The Euler characteristic at scale `t`; `0` below the first scale. */
  at(t: number): number;
  /** The curve at `resolution` evenly spaced scales across `[tMin, tMax]`, both ends included. */
  sample(tMin: number, tMax: number, resolution: number): number[];
}

/** The Euler characteristic curve of a point cloud's Vietoris-Rips complex, counted without building the complex. */
export function eulerCurve(points: number[][], options?: EulerCurveOptions): EulerCurve;
/** `eulerCurve` for a finite metric space given as its full matrix of distances. */
export function eulerCurveFromDistances(distances: number[][], options?: EulerCurveOptions): EulerCurve;

/** Options of `bootstrapBand`. */
export interface BootstrapBandOptions {
  /** The degree of the bars (default 1). */
  dimension?: number;
  /** How many resamples (default 100). */
  rounds?: number;
  /** The band covers `1 - alpha` of the resamples (default 0.05). */
  alpha?: number;
  seed?: number;
  /** Draw this many points without replacement; by default draw all the points with replacement. */
  subsampleSize?: number;
  prime?: number;
  maxFiltrationValue?: number;
  threads?: number;
}

/** The resampling confidence band of the bars of one degree of a point cloud. */
export interface BootstrapBand {
  /** The `1 - alpha` quantile of the bottleneck distances from the resamples' diagrams to the original. */
  width: number;
  /** Twice the width: a bar that persists longer than this survives the resampling. */
  threshold: number;
  alpha: number;
  distances: number[];
}

/** The resampling confidence band of the bars of one degree of a point cloud (Fasy et al. 2014). */
export function bootstrapBand(points: number[][], options?: BootstrapBandOptions): BootstrapBand;
/** The ToMATo cluster of every point (0 for the highest peak): `k` nearest neighbours give the graph and the density, and two peaks merge when the valley between them is shallower than `tau`. */
export function tomatoClusters(points: number[][], k: number, tau: number): number[];
/** The valleys between the density peaks used by `tomatoClusters` as `[birth, death]`; a bar's persistence is the depth of its valley. */
export function tomatoDiagram(points: number[][], k: number): number[][];

/** The sliding-window (Takens) embedding of a time series as a point cloud: the window `(x_t, x_{t+delay}, ..., x_{t+(dimension-1) delay})` for every `stride`-th start `t`. `delay` and `stride` default to 1. */
export function takensEmbedding(series: number[], dimension: number, delay?: number, stride?: number): number[][];
/** `takensEmbedding` for a series of vectors, one array per time step: a point is the `dimension` arrays of a window, one after the other. */
export function takensEmbeddingOfVectors(series: number[][], dimension: number, delay?: number, stride?: number): number[][];
/** The persistent Laplacian of one dimension and one pair of filtration values: a symmetric matrix on the simplices that have entered by `from`, whose kernel has the dimension of the persistent Betti number from `from` to `to`. */
export class PersistentLaplacian {
  /** The dimension of the simplices the matrix is on. */
  readonly dimension: number;
  readonly from: number;
  readonly to: number;
  /** The simplices as arrays of vertex numbers, one for each row and column of `matrix`. */
  readonly cells: number[][];
  readonly matrix: number[][];
  /** The eigenvalues, smallest first. */
  readonly eigenvalues: number[];
  /** The dimension of the kernel: the persistent Betti number from `from` to `to`. */
  readonly bettiNumber: number;
  /** The smallest eigenvalue that is not zero; `undefined` if there is none. */
  readonly spectralGap: number | undefined;
}

/** The persistent Laplacian of the Vietoris-Rips complex of a point cloud, on the simplices of dimension `dimension` that have entered by `from`, for the complex at `to` (`from <= to`). */
export function persistentLaplacian(points: number[][], dimension: number, from: number, to: number): PersistentLaplacian;
/** `persistentLaplacian` for a finite metric space given as its full matrix of distances. */
export function persistentLaplacianFromDistances(distances: number[][], dimension: number, from: number, to: number): PersistentLaplacian;
/** The barcode of the homology of the product of two filtered complexes from the barcodes of the factors, rows `[dimension, birth, death]` (every dimension of each): a cell of the product enters at the larger (`"max"`, the default) or the sum (`"sum"`) of the values of its two factors. */
export function productBars(first: number[][], second: number[][], filtration?: "max" | "sum"): number[][];
/** The internal Hom of two barcodes, rows `[dimension, birth, death]`: the module of maps from the first to the second shifted by `t`; a bar of dimension `p` and one of dimension `q` give a bar of dimension `q - p`. */
export function homBars(first: number[][], second: number[][]): number[][];
/** The dimension of the space of maps of persistence modules between two barcodes, degree by degree. */
export function homDimension(first: number[][], second: number[][]): number;
/** The dual of a barcode: the row `[dimension, birth, death]` of `[b, d)` becomes `[dimension, -d, -b]`, the interval `(-d, -b]` (`-Infinity` as the first end for a bar that never dies). */
export function dualBars(bars: number[][]): number[][];
/** The first lag in `1..maxLag` at which the autocorrelation of the series is zero or negative: a first guess for the `delay` of `takensEmbedding`. */
export function delayByAutocorrelation(series: number[], maxLag: number): number;

/** The loops of a point cloud's Vietoris-Rips complex as `[birth, death]`, the most persistent first. */
export function h1Bars(points: number[][]): number[][];

export interface CircularCoordinates {
  /** One angle in `[0, 1)` per point; `NaN` for a point outside the loop's connected component. */
  theta: number[];
  birth: number;
  death: number;
  r: number;
  prime: number;
}
/**
 * Circular coordinates for the loop `options.cocycleIndex` (default 0) of `h1Bars`, at a scale `r` between its birth and
 * death, over the field with `options.prime` elements (an odd prime, default 47). Throws if the loop has no integer lift at
 * that prime: use a larger one.
 */
export function circularCoordinates(
  points: number[][],
  r: number,
  options?: { cocycleIndex?: number; prime?: number }
): CircularCoordinates;

export interface ToroidalCoordinates {
  /** `theta[c][i]` is coordinate `c` of point `i`, in `[0, 1)`. */
  theta: number[][];
  cocycleIndices: number[];
  basisChange: number[][];
  originalGram: number[][];
  reducedGram: number[][];
  r: number;
  prime: number;
}
/** Toroidal coordinates combining the loops `cocycleIndices` of `h1Bars` that are alive together at `r`. */
export function toroidalCoordinates(
  points: number[][],
  r: number,
  cocycleIndices: number[],
  options?: { prime?: number; reduce?: boolean }
): ToroidalCoordinates;

/** A point cloud from the text of a file: one point per line, separated by commas or whitespace. */
export function parsePointCloud(text: string): number[][];
/** A point cloud from the text of a GUDHI OFF or nOFF file. */
export function parseOff(text: string): number[][];
/**
 * The full matrix of distances from the text of a file. `format` is `"full"` (default), `"lower"`, `"ripser-lower"`,
 * `"ripser-upper"`, `"ripser-dense"` or `"ripser-sparse"` (`i j d` per line; a pair not listed is at distance `Infinity`).
 */
export function parseDistanceMatrix(
  text: string,
  format?: "full" | "lower" | "ripser-lower" | "ripser-upper" | "ripser-dense" | "ripser-sparse"
): number[][];
