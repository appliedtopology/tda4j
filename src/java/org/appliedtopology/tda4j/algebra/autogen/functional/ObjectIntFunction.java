package org.appliedtopology.tda4j.algebra.autogen.functional;


/**
 * This interface defines a function from type T to type int.
 * 
 * @author autogen
 *
 */
public interface ObjectIntFunction<T> {
	/**
	 * This performs the function evaluation on an argument.
	 * 
	 * @param argument the argument of the function
	 * @return the function evaluated at the supplied argument value
	 */
	public int evaluate(T argument);
}
