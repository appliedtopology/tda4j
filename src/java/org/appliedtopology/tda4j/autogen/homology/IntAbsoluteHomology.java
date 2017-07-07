package org.appliedtopology.tda4j.autogen.homology;

import java.util.Comparator;

import org.appliedtopology.tda4j.homology.barcodes.AnnotatedBarcodeCollection;
import org.appliedtopology.tda4j.homology.barcodes.BarcodeCollection;
import org.appliedtopology.tda4j.streams.interfaces.AbstractFilteredStream;
import org.appliedtopology.tda4j.algebra.autogen.algebraic.IntAbstractField;
import org.appliedtopology.tda4j.algebra.autogen.formal_sum.IntSparseFormalSum;
import org.appliedtopology.tda4j.algebra.autogen.pair.ObjectObjectPair;
import gnu.trove.THashMap;





public class IntAbsoluteHomology<U> extends IntPersistentHomology<U> {
		/**
	 * This constructor initializes the object with a field and a comparator on the basis type.
	 * 
	 * @param field a field structure on the type int
	 * @param basisComparator a comparator on the basis type U
	 * @param minDimension the minimum dimension to compute 
	 * @param maxDimension the maximum dimension to compute
	 */
	public IntAbsoluteHomology(IntAbstractField field, Comparator<U> basisComparator, int minDimension, int maxDimension) {
		super(field, basisComparator, minDimension, maxDimension);
	}
		
	@Override
	protected AnnotatedBarcodeCollection<Integer, IntSparseFormalSum<U>> getAnnotatedIntervals(
			ObjectObjectPair<THashMap<U, IntSparseFormalSum<U>>, 
			THashMap<U, IntSparseFormalSum<U>>> RV_pair, 
			AbstractFilteredStream<U> stream) {
			
		return this.getAnnotatedIntervals(RV_pair, stream, true);
	}

	@Override
	protected BarcodeCollection<Integer> getIntervals(ObjectObjectPair<THashMap<U, IntSparseFormalSum<U>>, 
			THashMap<U, IntSparseFormalSum<U>>> RV_pair,
			AbstractFilteredStream<U> stream) {
			
		return this.getIntervals(RV_pair, stream, true);
	}
}
