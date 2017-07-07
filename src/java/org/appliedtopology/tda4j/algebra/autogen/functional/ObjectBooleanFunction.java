package org.appliedtopology.tda4j.algebra.autogen.functional;


/**
 * This interface defines a function from type T to type boolean.
 * 
 * @author autogen
 *
 */
public interface ObjectBooleanFunction<T> {
	/**
	 * This performs the function evaluation on an argument.
	 * 
	 * @param argument the argument of the function
	 * @return the function evaluated at the supplied argument value
	 */
	public boolean evaluate(T argument);
}
