package org.appliedtopology.tda4j.api;

import org.appliedtopology.tda4j.algebra.algebraic.impl.ModularIntField;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;

import java.util.Comparator;

/**
 * Created by mik on 9/7 ,2017.
 */
public class ZigZagHomology extends org.appliedtopology.tda4j.homology.zigzag.ZigZagHomology<Simplex> {
    public ZigZagHomology(int prime, Comparator<Simplex> basisComparator, int minDimension, int maxDimension) {
        super(ModularIntField.getInstance(prime), basisComparator, minDimension, maxDimension);
    }

    public ZigZagHomology(int prime, int minDimension, int maxDimension) {
        super(ModularIntField.getInstance(prime), SimplexComparator.getInstance(), minDimension, maxDimension);
    }

    public ZigZagHomology(int prime, int maxDimension) {
        super(ModularIntField.getInstance(prime), SimplexComparator.getInstance(), 0, maxDimension);
    }

    public ZigZagHomology(int maxDimension) {
        super(ModularIntField.getInstance(3), SimplexComparator.getInstance(), 0, maxDimension);
    }
}
