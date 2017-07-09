package org.appliedtopology.tda4j.api;


import org.appliedtopology.tda4j.autogen.homology.BooleanRelativeHomology;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;

import java.util.Comparator;

/**
 * Created by mik on 9/7 ,2017.
 */
public class BooleanRelativeSimplicialHomology extends BooleanRelativeHomology<Simplex> {
    public BooleanRelativeSimplicialHomology(Comparator<Simplex> basisComparator, int minDimension, int maxDimension) {
        super(basisComparator, minDimension, maxDimension);
    }
    public BooleanRelativeSimplicialHomology(int minDimension, int maxDimension) {
        this(SimplexComparator.getInstance(), minDimension, maxDimension);
    }
    public BooleanRelativeSimplicialHomology(int maxDimension){
        this(0, maxDimension);
    }
}
