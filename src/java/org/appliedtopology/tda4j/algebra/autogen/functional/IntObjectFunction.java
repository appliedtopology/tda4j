package org.appliedtopology.tda4j.algebra.autogen.functional;


/**
 * This interface defines a function from type int to type U.
 * 
 * @author autogen
 *
 */
public interface IntObjectFunction<U> {
	/**
	 * This performs the function evaluation on an argument.
	 * 
	 * @param argument the argument of the function
	 * @return the function evaluated at the supplied argument value
	 */
	public U evaluate(int argument);
}
