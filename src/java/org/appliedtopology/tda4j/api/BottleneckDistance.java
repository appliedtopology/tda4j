package org.appliedtopology.tda4j.api;

import org.appliedtopology.tda4j.homology.barcodes.BarcodeCollection;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;

/**
 * Created by mik on 9/7 ,2017.
 */
public class BottleneckDistance  extends org.appliedtopology.tda4j.bottleneck.BottleneckDistance {
    public static double computeBottleneckDistance(BarcodeCollection<Double> bcA, int dimA, BarcodeCollection<Double> bcB, int dimB) {
        return BottleneckDistance.computeBottleneckDistance(bcA.getIntervalsAtDimension(dimA), bcB.getIntervalsAtDimension(dimB));
    }

    public static double computeBottleneckDistance(BarcodeCollection<Double> bcA, BarcodeCollection<Double> bcB, int dim) {
        return BottleneckDistance.computeBottleneckDistance(bcA.getIntervalsAtDimension(dim), bcB.getIntervalsAtDimension(dim));
    }
}
