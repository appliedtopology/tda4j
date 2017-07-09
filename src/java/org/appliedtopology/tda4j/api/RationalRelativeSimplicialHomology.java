package org.appliedtopology.tda4j.api;


import org.appliedtopology.tda4j.algebra.algebraic.impl.RationalField;
import org.appliedtopology.tda4j.algebra.autogen.algebraic.ObjectAbstractField;
import org.appliedtopology.tda4j.autogen.homology.ObjectRelativeHomology;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;
import org.apache.commons.math.fraction.Fraction;

import java.util.Comparator;

/**
 * Created by mik on 9/7 ,2017.
 */
public class RationalRelativeSimplicialHomology extends ObjectRelativeHomology<Fraction, Simplex> {
    public RationalRelativeSimplicialHomology(Comparator<Simplex> basisComparator, int minDimension, int maxDimension) {
        super(RationalField.getInstance(), basisComparator, minDimension, maxDimension);
    }
    public RationalRelativeSimplicialHomology(int minDimension, int maxDimension) {
        this(SimplexComparator.getInstance(), minDimension, maxDimension);
    }
    public RationalRelativeSimplicialHomology(int maxDimension){
        this(0, maxDimension);
    }
}
