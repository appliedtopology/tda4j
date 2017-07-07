package org.appliedtopology.tda4j.algebra.collections.utility;

import java.util.Iterator;

import org.appliedtopology.tda4j.algebra.autogen.functional.ObjectObjectFunction;

/**
 * This class applies a function to each element produced by an iterator.
 * 
 * @author Andrew Tausz
 *
 * @param <T> the domain type
 * @param <U> the codomain type
 */
public class AdaptedIterator<T, U> implements Iterator<U> {
	private final ObjectObjectFunction<T, U> adapter;
	private final Iterator<T> baseIterator;
	
	/**
	 * The constructor accepts a function mapping T -> U, and an iterator over the type T.
	 * 
	 * @param baseIterator an iterator over the domain type
	 * @param adapter the mapping function
	 */
	public AdaptedIterator(Iterator<T> baseIterator, ObjectObjectFunction<T, U> adapter) {
		this.adapter = adapter;
		this.baseIterator = baseIterator;
	}
	
	public boolean hasNext() {
		return this.baseIterator.hasNext();
	}

	public U next() {
		return this.adapter.evaluate(this.baseIterator.next());
	}

	public void remove() {
		this.baseIterator.remove();
	}
}
