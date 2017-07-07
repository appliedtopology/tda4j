package org.appliedtopology.tda4j.algebra.autogen.functional;


/**
 * This interface defines a function from type double to type U.
 * 
 * @author autogen
 *
 */
public interface DoubleObjectFunction<U> {
	/**
	 * This performs the function evaluation on an argument.
	 * 
	 * @param argument the argument of the function
	 * @return the function evaluated at the supplied argument value
	 */
	public U evaluate(double argument);
}
