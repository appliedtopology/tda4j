package org.appliedtopology.tda4j.algebra.autogen.formal_sum;



/**
 * This interface defines the functionality of a formal sum with
 * objects of type M and coefficients of type double. 
 * One can think of such a sum as an element of a free module over the
 * type M.
 * 
 * @author autogen
 *
 * @param <double> the coefficient type
 * @param <M> the object type
 */
public interface DoubleAbstractFormalSum<M> {

	/**
	 * This function puts the specified coefficient-object pair
	 * into the sum. Note that if the object was already present in
	 * the sum previously, the old coefficient will be overwritten with
	 * the new-one.
	 * 
	 * @param coefficient the coefficient of the new entry
	 * @param object the object of the entry
	 */
	void put(double coefficient, M object);
	
	/**
	 * This function removes the entry with the given object from the
	 * sum.
	 * 
	 * @param object the object to remove
	 */
	void remove(M object);
	
	/**
	 * This function returns true if the sum contains the specified object
	 * and false otherwise.
	 * 
	 * @param object the object to search for
	 * @return true if the sum contains the given object
	 */
	boolean containsObject(M object);
	
	/**
	 * This function returns the coefficient of the given object in the sum.
	 * In the event that the sum does not contain the object, it returns the
	 * default element for the coefficient type (e.g. 0 for numeric types, null
	 * for object types).
	 * 
	 * @param object the object to get the coefficient for
	 * @return the coefficient of the given object
	 */
	double getCoefficient(M object);
	
	/**
	 * This function returns the number of elements in the sum.
	 * 
	 * @return the number of elements in the sum
	 */
	int size();
	
	/**
	 * This function returns true if the sum does not contain any elements, and false
	 * otherwise.
	 * 
	 * @return true if the sum is empty
	 */
	boolean isEmpty();
}
