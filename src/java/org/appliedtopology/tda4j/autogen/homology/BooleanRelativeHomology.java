package org.appliedtopology.tda4j.autogen.homology;

import java.util.Comparator;

import org.appliedtopology.tda4j.homology.barcodes.AnnotatedBarcodeCollection;
import org.appliedtopology.tda4j.homology.barcodes.BarcodeCollection;
import org.appliedtopology.tda4j.streams.interfaces.AbstractFilteredStream;
import org.appliedtopology.tda4j.algebra.autogen.formal_sum.BooleanSparseFormalSum;
import org.appliedtopology.tda4j.algebra.autogen.pair.ObjectObjectPair;
import gnu.trove.THashMap;





public class BooleanRelativeHomology<U> extends BooleanPersistentHomology<U> {
		/**
	 * This constructor initializes the object with a comparator on the basis type.
	 * 
	 * @param basisComparator a comparator on the basis type U
	 * @param minDimension the minimum dimension to compute 
	 * @param maxDimension the maximum dimension to compute
	 */
	public BooleanRelativeHomology(Comparator<U> basisComparator, int minDimension, int maxDimension) {
		super(basisComparator, minDimension, maxDimension);
	}
	
	@Override
	protected AnnotatedBarcodeCollection<Integer, BooleanSparseFormalSum<U>> getAnnotatedIntervals(
			ObjectObjectPair<THashMap<U, BooleanSparseFormalSum<U>>, 
			THashMap<U, BooleanSparseFormalSum<U>>> RV_pair, 
			AbstractFilteredStream<U> stream) {
			
		return this.getAnnotatedIntervals(RV_pair, stream, false);
	}

	@Override
	protected BarcodeCollection<Integer> getIntervals(ObjectObjectPair<THashMap<U, BooleanSparseFormalSum<U>>, 
			THashMap<U, BooleanSparseFormalSum<U>>> RV_pair,
			AbstractFilteredStream<U> stream) {
			
		return this.getIntervals(RV_pair, stream, false);
	}
}
