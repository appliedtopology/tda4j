package org.appliedtopology.tda4j.algebra.autogen.matrix;




/**
 * This interface defines the functionality of a vector over the type R.
 * 
 * @author autogen
 *
 * @param <R>
 */
public interface ObjectAbstractVector<R> extends Iterable<ObjectVectorEntry<R>> {

	/**
	 * This function returns a new vector of the same type as the current one with
	 * the specified size.
	 * 
	 * @param size the size of the vector to return
	 * @return a new vector of the same type as the current
	 */
	public abstract ObjectAbstractVector<R> like(int size);
	
	/**
	 * This function returns the element at the specified index in the vector.
	 * 
	 * @param index the index of the element to query
	 * @return the value at the given index
	 */
	public abstract R get(int index);
	
	/**
	 * This function sets the element at the specified index to the given value.
	 * 
	 * @param index the index to set the element at
	 * @param value the value to assign
	 */
	public abstract void set(int index, R value);
	
	/**
	 * This function returns the length of the vector.
	 * 
	 * @return the length (dimension) of the vector
	 */
	public abstract int getLength();
	
	}
