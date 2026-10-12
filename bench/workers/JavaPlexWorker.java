// JavaPlex side of the cross-platform benchmark (TDA4j's predecessor), same contract as py_worker.py.
// Compiled by bench/run.sh against $JAVAPLEX_JAR. Written against JavaPlex 4.x's documented API
// (Plex4.createVietorisRipsStream, Plex4.getModularSimplicialAlgorithm); not compiled in the session
// that wrote it -- if a signature differs in your jar, adjust here.
//
//   java -cp $JAVAPLEX_JAR:bench/build JavaPlexWorker task=vr input=x.txt format=points dim=2
//        threshold=1.8 [p=2] [divisions=1000] [warmup=2] [trials=5] [out=bars.tsv]
//
// JavaPlex snaps filtration values to `divisions` equal steps of [0, threshold], so its barcode can only
// agree with the others up to threshold/divisions; the harness uses that as its tolerance.
import edu.stanford.math.plex4.api.Plex4;
import edu.stanford.math.plex4.homology.barcodes.BarcodeCollection;
import edu.stanford.math.plex4.homology.barcodes.Interval;
import edu.stanford.math.plex4.homology.chain_basis.Simplex;
import edu.stanford.math.plex4.homology.interfaces.AbstractPersistenceAlgorithm;
import edu.stanford.math.plex4.metric.impl.ExplicitMetricSpace;
import edu.stanford.math.plex4.streams.impl.VietorisRipsStream;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class JavaPlexWorker {
  public static void main(String[] args) throws Exception {
    Map<String, String> o = new HashMap<>();
    for (String a : args) { int i = a.indexOf('='); o.put(a.substring(0, i), a.substring(i + 1)); }
    if (!o.get("task").equals("vr")) System.exit(3);
    int dim = Integer.parseInt(o.get("dim"));
    int p = Integer.parseInt(o.getOrDefault("p", "2"));
    double threshold = Double.parseDouble(o.get("threshold")); // JavaPlex needs an explicit maximum
    int divisions = Integer.parseInt(o.getOrDefault("divisions", "1000"));
    int warmup = Integer.parseInt(o.getOrDefault("warmup", "2"));
    int trials = Integer.parseInt(o.getOrDefault("trials", "5"));
    boolean distance = o.getOrDefault("format", "points").equals("distance");

    long t0 = System.nanoTime();
    List<double[]> rows = new ArrayList<>();
    for (String line : Files.readAllLines(Paths.get(o.get("input")))) {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] parts = line.split("[,\\s]+");
      double[] r = new double[parts.length];
      for (int i = 0; i < parts.length; i++) r[i] = Double.parseDouble(parts[i]);
      rows.add(r);
    }
    double[][] data = rows.toArray(new double[0][]);
    double loadS = (System.nanoTime() - t0) / 1e9;

    BarcodeCollection<Double> bars = null;
    double[] times = new double[trials];
    for (int t = -warmup; t < trials; t++) {
      long s = System.nanoTime();
      VietorisRipsStream<?> stream = distance
          ? Plex4.createVietorisRipsStream(new ExplicitMetricSpace(data), dim + 1, threshold, divisions)
          : Plex4.createVietorisRipsStream(data, dim + 1, threshold, divisions);
      AbstractPersistenceAlgorithm<Simplex> alg = Plex4.getModularSimplicialAlgorithm(dim + 1, p);
      bars = alg.computeIntervals(stream);
      if (t >= 0) times[t] = (System.nanoTime() - s) / 1e9;
    }

    StringBuilder counts = new StringBuilder();
    PrintWriter w = o.containsKey("out") ? new PrintWriter(o.get("out")) : null;
    for (int k = 0; k <= dim; k++) {
      List<Interval<Double>> ints = bars.getIntervalsAtDimension(k);
      if (counts.length() > 0) counts.append(',');
      counts.append('"').append(k).append("\":").append(ints == null ? 0 : ints.size());
      if (w != null && ints != null)
        for (Interval<Double> in : ints)
          w.println(k + "\t" + in.getStart() + "\t" + (in.isRightInfinite() ? "inf" : in.getEnd().toString()));
    }
    if (w != null) w.close();
    StringBuilder ts = new StringBuilder();
    for (int t = 0; t < trials; t++) { if (t > 0) ts.append(','); ts.append(times[t]); }
    System.out.println("{\"tool\":\"javaplex\",\"task\":\"vr\",\"load_s\":" + loadS + ",\"times_s\":[" + ts
        + "],\"bars\":{" + counts + "},\"divisions\":" + divisions + "}");
  }
}
