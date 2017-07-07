package org.appliedtopology.tda4j.algebra.collections.utility;

import java.util.Iterator;

import org.appliedtopology.tda4j.algebra.autogen.pair.ObjectObjectPair;

/**
 * This class allows one to iterate through all pairs of the form (t, u) where
 * the elements t and u are produced by iterable collections. Note that this
 * iterator does not use any additional storage of the pairs, and only relies
 * on the reuse of iterators from the provided collections. In other words it
 * does not construct all pairs first and then iterate through them.
 * 
 * @author Andrew Tausz
 *
 * @param <T>
 * @param <U>
 */
public class PairwiseIterator<T, U> implements Iterator<ObjectObjectPair<T, U>> {
	private final Iterable<T> TCollection;
	private final Iterable<U> UCollection;
	private Iterator<T> TIterator;
	private Iterator<U> UIterator;
	private T currentTElement;
	
	public PairwiseIterator(Iterable<T> TCollection, Iterable<U> UCollection) {
		this.TCollection = TCollection;
		this.UCollection = UCollection;
		this.TIterator = this.TCollection.iterator();
		this.UIterator = this.UCollection.iterator();
		this.currentTElement = this.TIterator.next();
	}
	
	public boolean hasNext() {
		if (this.UIterator.hasNext()) {
			return true;
		}
		
		return this.TIterator.hasNext();
	}

	public ObjectObjectPair<T, U> next() {
		if (!this.UIterator.hasNext()) {
			this.currentTElement = this.TIterator.next();
			this.UIterator = this.UCollection.iterator();
		}
		
		return new ObjectObjectPair<T, U>(this.currentTElement, this.UIterator.next());
	}

	public void remove() {
		throw new UnsupportedOperationException();
	}
}
