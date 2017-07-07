package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.util.Iterator;

import gnu.trove.TIntIntIterator;



/**
 * This class implements the Iterator interface and allows for the traversal of a
 * sparse vector. Note that it only traverses the non-zero elements of the vector, and
 * omits the zero entries.
 * 
 * @author autogen
 *
 * @param <int>
 */
public class IntSparseVectorIterator implements Iterator<IntVectorEntry> {
	private final TIntIntIterator iterator;
	
	/**
	 * Constructor which initializes from a sparse vector.
	 * 
	 * @param vector the sparse vector to initialize from
	 */
	public IntSparseVectorIterator(IntSparseVector vector) {
		this.iterator = vector.map.iterator();
	}
	
	public boolean hasNext() {
		return this.iterator.hasNext();
	}

	public IntVectorEntry next() {
		this.iterator.advance();
		return new IntVectorEntry(this.iterator.key(), this.iterator.value());
	}

	public void remove() {
		this.iterator.remove();
	}
}
