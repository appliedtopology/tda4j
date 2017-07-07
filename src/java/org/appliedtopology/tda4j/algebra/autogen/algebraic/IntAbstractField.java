package org.appliedtopology.tda4j.algebra.autogen.algebraic;


/**
 * This abstract class defines the behavior of a field over elements
 * of type int.
 * 
 * @author autogen
 *
 * @param <int> the underlying type
 */
public abstract class IntAbstractField extends IntAbstractRing {
	/**
	 * Compute a * b^-1.
	 * 
	 * @param a
	 * @param b
	 * @return a * b^-1.
	 */
	public abstract int divide(int a, int b);
	
	/**
	 * Compute the multiplicative inverse of a.
	 * 
	 * @param a
	 * @return a^-1
	 */
	public abstract int invert(int a);
	
			
	@Override
	public int power(int a, int n)	{
		if (n < 0) {
			return this.power(this.invert(a), -n);
		}
	    int result = this.getOne();
	    while (n > 0) {
	        if ((n & 1) == 1) {
	            result = this.multiply(result, a);
	        }
	        a = this.multiply(a, a);
	        n /= 2;
	    }
	    return result;
	}
}
