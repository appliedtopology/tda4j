package org.appliedtopology.tda4j.api;


import org.appliedtopology.tda4j.autogen.homology.BooleanAbsoluteHomology;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;

import java.util.Comparator;

/**
 * Created by mik on 9/7 ,2017.
 */
public class BooleanAbsoluteSimplicialHomology extends BooleanAbsoluteHomology<Simplex> {
    public BooleanAbsoluteSimplicialHomology(Comparator<Simplex> basisComparator, int minDimension, int maxDimension) {
        super(basisComparator, minDimension, maxDimension);
    }
    public BooleanAbsoluteSimplicialHomology(int minDimension, int maxDimension) {
        this(SimplexComparator.getInstance(), minDimension, maxDimension);
    }
    public BooleanAbsoluteSimplicialHomology(int maxDimension){
        this(0, maxDimension);
    }
}
