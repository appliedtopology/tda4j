package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.util.Iterator;

import gnu.trove.TIntDoubleIterator;



/**
 * This class implements the Iterator interface and allows for the traversal of a
 * sparse vector. Note that it only traverses the non-zero elements of the vector, and
 * omits the zero entries.
 * 
 * @author autogen
 *
 * @param <double>
 */
public class DoubleSparseVectorIterator implements Iterator<DoubleVectorEntry> {
	private final TIntDoubleIterator iterator;
	
	/**
	 * Constructor which initializes from a sparse vector.
	 * 
	 * @param vector the sparse vector to initialize from
	 */
	public DoubleSparseVectorIterator(DoubleSparseVector vector) {
		this.iterator = vector.map.iterator();
	}
	
	public boolean hasNext() {
		return this.iterator.hasNext();
	}

	public DoubleVectorEntry next() {
		this.iterator.advance();
		return new DoubleVectorEntry(this.iterator.key(), this.iterator.value());
	}

	public void remove() {
		this.iterator.remove();
	}
}
