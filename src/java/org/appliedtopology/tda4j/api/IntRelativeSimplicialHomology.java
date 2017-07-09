package org.appliedtopology.tda4j.api;


import org.appliedtopology.tda4j.algebra.algebraic.impl.ModularIntField;
import org.appliedtopology.tda4j.autogen.homology.IntRelativeHomology;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;

import java.util.Comparator;

/**
 * Created by mik on 9/7 ,2017.
 */
public class IntRelativeSimplicialHomology extends IntRelativeHomology<Simplex> {
    public IntRelativeSimplicialHomology(int prime, int minDimension, int maxDimension) {
        super(ModularIntField.getInstance(prime), SimplexComparator.getInstance(), minDimension, maxDimension);
    }
    public IntRelativeSimplicialHomology(int minDimension, int maxDimension) {
        this(3, minDimension, maxDimension);
    }
    public IntRelativeSimplicialHomology(int maxDimension){
        this(0, maxDimension);
    }
}
