package org.appliedtopology.tda4j.api;


import org.appliedtopology.tda4j.algebra.algebraic.impl.ModularIntField;
import org.appliedtopology.tda4j.autogen.homology.IntAbsoluteHomology;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;


/**
 * Created by mik on 9/7 ,2017.
 */
public class IntAbsoluteSimplicialHomology extends IntAbsoluteHomology<Simplex> {
    public IntAbsoluteSimplicialHomology(int prime, int minDimension, int maxDimension) {
        super(ModularIntField.getInstance(prime), SimplexComparator.getInstance(), minDimension, maxDimension);
    }
    public IntAbsoluteSimplicialHomology(int minDimension, int maxDimension) {
        this(3, minDimension, maxDimension);
    }
    public IntAbsoluteSimplicialHomology(int maxDimension){
        this(0, maxDimension);
    }
}
