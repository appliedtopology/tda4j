package org.appliedtopology.tda4j.homology.zigzag.bootstrap;

import org.appliedtopology.tda4j.homology.chain_basis.PrimitiveBasisElement;
import org.appliedtopology.tda4j.algebra.autogen.formal_sum.IntAlgebraicFreeModule;
import org.appliedtopology.tda4j.algebra.autogen.formal_sum.IntSparseFormalSum;
import org.appliedtopology.tda4j.algebra.autogen.pair.ObjectObjectPair;
import gnu.trove.TObjectIntIterator;

public class SimplexStreamUtility {


	public static <U> IntSparseFormalSum<U> projectFirst(IntSparseFormalSum<? extends ObjectObjectPair<U, U>> chain, IntAlgebraicFreeModule<U> chainModule) {
		IntSparseFormalSum<U> result = new IntSparseFormalSum<U>();

		for (TObjectIntIterator<? extends ObjectObjectPair<U, U>> iterator = chain.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			chainModule.accumulate(result, iterator.key().getFirst(), iterator.value());
		}

		return result;
	}

	public static <U> IntSparseFormalSum<U> projectSecond(IntSparseFormalSum<? extends ObjectObjectPair<U, U>> chain, IntAlgebraicFreeModule<U> chainModule) {
		IntSparseFormalSum<U> result = new IntSparseFormalSum<U>();

		for (TObjectIntIterator<? extends ObjectObjectPair<U, U>> iterator = chain.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			chainModule.accumulate(result, iterator.key().getSecond(), iterator.value());
		}

		return result;
	}
	
	public static <U extends PrimitiveBasisElement> IntSparseFormalSum<U> filterByDimension(IntSparseFormalSum<U> chain, int dimension) {
		IntSparseFormalSum<U> result = new IntSparseFormalSum<U>();
		
		for (TObjectIntIterator<U> iterator = chain.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			
			if (iterator.key().getDimension() == dimension) {
				result.put(iterator.value(), iterator.key());
			}
		}
		
		return result;
	}
}
