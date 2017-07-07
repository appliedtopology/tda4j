package org.appliedtopology.tda4j.algebra.autogen.matrix;

import java.util.Iterator;

import gnu.trove.TIntObjectIterator;



/**
 * This class implements the Iterator interface and allows for the traversal of a
 * sparse vector. Note that it only traverses the non-zero elements of the vector, and
 * omits the zero entries.
 * 
 * @author autogen
 *
 * @param <R>
 */
public class ObjectSparseVectorIterator<R> implements Iterator<ObjectVectorEntry<R>> {
	private final TIntObjectIterator<R> iterator;
	
	/**
	 * Constructor which initializes from a sparse vector.
	 * 
	 * @param vector the sparse vector to initialize from
	 */
	public ObjectSparseVectorIterator(ObjectSparseVector<R> vector) {
		this.iterator = vector.map.iterator();
	}
	
	public boolean hasNext() {
		return this.iterator.hasNext();
	}

	public ObjectVectorEntry<R> next() {
		this.iterator.advance();
		return new ObjectVectorEntry<R>(this.iterator.key(), this.iterator.value());
	}

	public void remove() {
		this.iterator.remove();
	}
}
