package org.appliedtopology.tda4j.algebra.autogen.formal_sum;


import java.util.Map;

import org.appliedtopology.tda4j.algebra.autogen.matrix.ObjectSparseVector;
import org.appliedtopology.tda4j.algebra.autogen.matrix.ObjectVectorEntry;
import gnu.trove.TIntObjectHashMap;
import gnu.trove.TObjectIntHashMap;


/**
 * This class implements the isomorphism between a free module on a set of
 * finite generators, and the module R^n, where R is a commutative ring.
 * In this case, the set of free generators is given by an Iterable stream
 * of values of type M. This class contains the necessary functionality for
 * converting between three formats: formal sums over a generating set,
 * arrays of elements in R, and sparse vectors. Thus basis elements correspond 
 * to standard unit vectors.
 * 
 * @author autogen
 *
 * @param <R> the type of the underlying ring
 * @param <M> the type of the set of free generators
 */
public class ObjectVectorConverter<R, M> {
	/**
	 * This is the iterable collection of elements which constitute the free basis
	 * of the module.
	 */
	protected final Iterable<M> stream;
	
	/**
	 * This maps a basis element to an index. The index is the appropriate
	 * index of the standard basis vector the generating element is mapped to.
	 */
	protected final TObjectIntHashMap<M> indexMapping = new TObjectIntHashMap<M>();
	
	/**
	 * This maps an index (ie. the index of a standard basis vector) to a generating
	 * element of the free module.
	 */
	protected final TIntObjectHashMap<M> basisMapping = new TIntObjectHashMap<M>();
	
	/**
	 * This stores the cardinality of the generating set of the module.
	 */
	protected final int dimension;
	
	/**
	 * This constructor initializes the object with an Iterable stream of the 
	 * free generators of the module.
	 * 
	 * @param stream an iterable collection of free generators
	 */
	public ObjectVectorConverter(Iterable<M> stream) {
		this.stream = stream;
		this.initializeMappings();
		this.dimension = this.indexMapping.size();
	}
	
	/**
	 * This function initializes the basis-index mappings.
	 */
	private void initializeMappings() {
		int index = 0;
		for (M basisElement: this.stream) {
			this.indexMapping.put(basisElement, index);
			this.basisMapping.put(index, basisElement);
			index++;
		}
	}
	
	/**
	 * This function returns the cardinality of the generating set of the free
	 * module.
	 * 
	 * @return the dimension of the free module
	 */
	public int getDimension() {
		return this.dimension;
	}

	/**
	 * This returns the index of a basis element. The index is defined to be the
	 * index of the standard basis vector that the element maps to.
	 * 
	 * @param basisElement the basis element to query
	 * @return the index of the basis element
	 */
	public int getIndex(M basisElement) {
		return this.indexMapping.get(basisElement);
	}
	
	/**
	 * This returns the basis element corresponding to the supplied index.
	 * 
	 * @param index the index to query
	 * @return the basis element with the supplied index
	 */
	public M getBasisElement(int index) {
		return this.basisMapping.get(index);
	}
	
		
		
	/**
	 * This function converts a formal sum to a sparse vector.
	 * 
	 * @param formalSum the formal sum to convert
	 * @return the sparse vector equivalent of the sum
	 */
	public ObjectSparseVector<R> toSparseVector(ObjectSparseFormalSum<R, M> formalSum) {
		ObjectSparseVector<R> vector = new ObjectSparseVector<R>(this.getDimension());
		
		for (Map.Entry<M, R> entry : formalSum.map.entrySet()) {
			int index = this.getIndex(entry.getKey());
			vector.set(index, entry.getValue());
		}
		
		return vector;
	}
	
		
	/**
	 * This function converts a dense array to a sparse vector.
	 * 
	 * @param array the array to convert
	 * @return a sparse vector equivalent of the array
	 */
	public ObjectSparseVector<R> toSparseVector(R[] array) {
		return new ObjectSparseVector<R>(array);
	}
	
	/**
	 * This function converts a dense array to a sparse formal sum.
	 * 
	 * @param array the array to convert
	 * @return a sparse vector equivalent of the array
	 */
	public ObjectSparseFormalSum<R, M> toFormalSum(R[] array) {
		ObjectSparseFormalSum<R, M> sum = new ObjectSparseFormalSum<R, M>();
		
		for (int i = 0; i < array.length; i++) {
						if (array[i] == null) {
				continue;
			}
						sum.put(array[i], this.getBasisElement(i));
		}
		
		return sum;
	}
	
	/**
	 * This function converts a sparse vector to a formal sum.
	 * 
	 * @param sparseVector the sparse vector to convert
	 * @return a formal sum equivalent of the sparse vector
	 */
	public ObjectSparseFormalSum<R, M> toFormalSum(ObjectSparseVector<R> sparseVector) {
		ObjectSparseFormalSum<R, M> sum = new ObjectSparseFormalSum<R, M>();
		
		for (ObjectVectorEntry<R> pair: sparseVector) {
			sum.put(pair.getValue(), this.getBasisElement(pair.getIndex()));
		}
		
		return sum;
	}
}
