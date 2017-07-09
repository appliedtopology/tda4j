package org.appliedtopology.tda4j.api;


import org.apache.commons.math.fraction.Fraction;
import org.appliedtopology.tda4j.algebra.algebraic.impl.RationalField;
import org.appliedtopology.tda4j.autogen.homology.ObjectAbsoluteHomology;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;

import java.util.Comparator;

/**
 * Created by mik on 9/7 ,2017.
 */
public class RationalAbsoluteSimplicialHomology extends ObjectAbsoluteHomology<Fraction, Simplex> {
    public RationalAbsoluteSimplicialHomology(Comparator<Simplex> basisComparator, int minDimension, int maxDimension) {
        super(RationalField.getInstance(), basisComparator, minDimension, maxDimension);
    }
    public RationalAbsoluteSimplicialHomology(int minDimension, int maxDimension) {
        this(SimplexComparator.getInstance(), minDimension, maxDimension);
    }
    public RationalAbsoluteSimplicialHomology(int maxDimension){
        this(0, maxDimension);
    }
}
