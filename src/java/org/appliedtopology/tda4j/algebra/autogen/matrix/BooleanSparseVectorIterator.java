package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.util.Iterator;

import gnu.trove.TIntIterator;

/**
 * This class implements the Iterator interface and allows for the traversal of a
 * sparse binary vector. Note that it only traverses the non-false elements of the vector, 
 * and omits the zero entries.
 * 
 * @author autogen
 *
 * @param <boolean>
 */
public class BooleanSparseVectorIterator implements Iterator<BooleanVectorEntry> {
	private final TIntIterator iterator;
	
	/**
	 * Constructor which initializes from a sparse vector.
	 * 
	 * @param vector the sparse vector to initialize from
	 */
	public BooleanSparseVectorIterator(BooleanSparseVector vector) {
		this.iterator = vector.map.iterator();
	}
	
	public boolean hasNext() {
		return this.iterator.hasNext();
	}

	public BooleanVectorEntry next() {
		return new BooleanVectorEntry(this.iterator.next());
	}

	public void remove() {
		this.iterator.remove();
	}
};
