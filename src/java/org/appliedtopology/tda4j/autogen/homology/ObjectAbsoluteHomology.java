package org.appliedtopology.tda4j.autogen.homology;

import java.util.Comparator;

import org.appliedtopology.tda4j.homology.barcodes.AnnotatedBarcodeCollection;
import org.appliedtopology.tda4j.homology.barcodes.BarcodeCollection;
import org.appliedtopology.tda4j.streams.interfaces.AbstractFilteredStream;
import org.appliedtopology.tda4j.algebra.autogen.algebraic.ObjectAbstractField;
import org.appliedtopology.tda4j.algebra.autogen.formal_sum.ObjectSparseFormalSum;
import org.appliedtopology.tda4j.algebra.autogen.pair.ObjectObjectPair;
import gnu.trove.THashMap;





public class ObjectAbsoluteHomology<F, U> extends ObjectPersistentHomology<F, U> {
		/**
	 * This constructor initializes the object with a field and a comparator on the basis type.
	 * 
	 * @param field a field structure on the type F
	 * @param basisComparator a comparator on the basis type U
	 * @param minDimension the minimum dimension to compute 
	 * @param maxDimension the maximum dimension to compute
	 */
	public ObjectAbsoluteHomology(ObjectAbstractField<F> field, Comparator<U> basisComparator, int minDimension, int maxDimension) {
		super(field, basisComparator, minDimension, maxDimension);
	}
		
	@Override
	protected AnnotatedBarcodeCollection<Integer, ObjectSparseFormalSum<F, U>> getAnnotatedIntervals(
			ObjectObjectPair<THashMap<U, ObjectSparseFormalSum<F, U>>, 
			THashMap<U, ObjectSparseFormalSum<F, U>>> RV_pair, 
			AbstractFilteredStream<U> stream) {
			
		return this.getAnnotatedIntervals(RV_pair, stream, true);
	}

	@Override
	protected BarcodeCollection<Integer> getIntervals(ObjectObjectPair<THashMap<U, ObjectSparseFormalSum<F, U>>, 
			THashMap<U, ObjectSparseFormalSum<F, U>>> RV_pair,
			AbstractFilteredStream<U> stream) {
			
		return this.getIntervals(RV_pair, stream, true);
	}
}
