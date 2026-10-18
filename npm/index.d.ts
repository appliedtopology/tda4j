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
  numLandmarks?: number;
  witnessVariant?: string;
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
  bottleneckDistance(other: Result, dimension: number): number;
  wassersteinDistance(other: Result, dimension: number, order?: number): number;
  /** `landscape[k][j]` is level `k` of the persistence landscape at sample `j` of `[tMin, tMax]`. */
  landscape(dimension: number, numLevels: number, tMin: number, tMax: number, resolution: number): number[][];
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
