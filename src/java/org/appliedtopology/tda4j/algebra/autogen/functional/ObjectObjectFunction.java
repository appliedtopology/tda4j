package org.appliedtopology.tda4j.algebra.autogen.functional;


/**
 * This interface defines a function from type T to type U.
 * 
 * @author autogen
 *
 */
public interface ObjectObjectFunction<T, U> {
	/**
	 * This performs the function evaluation on an argument.
	 * 
	 * @param argument the argument of the function
	 * @return the function evaluated at the supplied argument value
	 */
	public U evaluate(T argument);
}
