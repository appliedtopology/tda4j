package org.appliedtopology.tda4j.algebra.autogen.algebraic;


/**
 * This abstract class defines the behavior of a field over elements
 * of type boolean.
 * 
 * @author autogen
 *
 * @param <boolean> the underlying type
 */
public abstract class BooleanAbstractField extends BooleanAbstractRing {
	/**
	 * Compute a * b^-1.
	 * 
	 * @param a
	 * @param b
	 * @return a * b^-1.
	 */
	public abstract boolean divide(boolean a, boolean b);
	
	/**
	 * Compute the multiplicative inverse of a.
	 * 
	 * @param a
	 * @return a^-1
	 */
	public abstract boolean invert(boolean a);
	
		
	/**
	 * Compute a * b^-1.
	 * 
	 * @param a
	 * @param b
	 * @return a * b^-1.
	 */
	public boolean divide(int a, int b) {
		return this.divide(this.valueOf(a), this.valueOf(b));
	}
	
	/**
	 * Compute the multiplicative inverse of a.
	 * 
	 * @param a
	 * @return a^-1
	 */
	public boolean invert(int a) {
		return this.invert(this.valueOf(a));
	}
	
			
	@Override
	public boolean power(boolean a, int n)	{
		if (n < 0) {
			return this.power(this.invert(a), -n);
		}
	    boolean result = this.getOne();
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
