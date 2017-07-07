package org.appliedtopology.tda4j.algebra.autogen.algebraic;


/**
 * This abstract class defines the behavior of a field over elements
 * of type R.
 * 
 * @author autogen
 *
 * @param <R> the underlying type
 */
public abstract class ObjectAbstractField<R> extends ObjectAbstractRing<R> {
	/**
	 * Compute a * b^-1.
	 * 
	 * @param a
	 * @param b
	 * @return a * b^-1.
	 */
	public abstract R divide(R a, R b);
	
	/**
	 * Compute the multiplicative inverse of a.
	 * 
	 * @param a
	 * @return a^-1
	 */
	public abstract R invert(R a);
	
		
	/**
	 * Compute a * b^-1.
	 * 
	 * @param a
	 * @param b
	 * @return a * b^-1.
	 */
	public R divide(int a, int b) {
		return this.divide(this.valueOf(a), this.valueOf(b));
	}
	
	/**
	 * Compute the multiplicative inverse of a.
	 * 
	 * @param a
	 * @return a^-1
	 */
	public R invert(int a) {
		return this.invert(this.valueOf(a));
	}
	
			
	@Override
	public R power(R a, int n)	{
		if (n < 0) {
			return this.power(this.invert(a), -n);
		}
	    R result = this.getOne();
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
