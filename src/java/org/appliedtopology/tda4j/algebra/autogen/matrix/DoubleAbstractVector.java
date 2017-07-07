package org.appliedtopology.tda4j.algebra.autogen.matrix;




/**
 * This interface defines the functionality of a vector over the type double.
 * 
 * @author autogen
 *
 * @param <double>
 */
public interface DoubleAbstractVector extends Iterable<DoubleVectorEntry> {

	/**
	 * This function returns a new vector of the same type as the current one with
	 * the specified size.
	 * 
	 * @param size the size of the vector to return
	 * @return a new vector of the same type as the current
	 */
	public abstract DoubleAbstractVector like(int size);
	
	/**
	 * This function returns the element at the specified index in the vector.
	 * 
	 * @param index the index of the element to query
	 * @return the value at the given index
	 */
	public abstract double get(int index);
	
	/**
	 * This function sets the element at the specified index to the given value.
	 * 
	 * @param index the index to set the element at
	 * @param value the value to assign
	 */
	public abstract void set(int index, double value);
	
	/**
	 * This function returns the length of the vector.
	 * 
	 * @return the length (dimension) of the vector
	 */
	public abstract int getLength();
	
		/**
	 * This function returns the value of the inner product of the current
	 * vector with another one.
	 * 
	 * @param other the vector to calculate the inner product with
	 * @return the inner product of this with the given vector
	 */
	public abstract double innerProduct(DoubleAbstractVector other);
	}
