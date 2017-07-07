package org.appliedtopology.tda4j.algebra.autogen.algebraic;


/**
 * This abstract class defines the behavior of a field over elements
 * of type double.
 * 
 * @author autogen
 *
 * @param <double> the underlying type
 */
public abstract class DoubleAbstractField extends DoubleAbstractRing {
	/**
	 * Compute a * b^-1.
	 * 
	 * @param a
	 * @param b
	 * @return a * b^-1.
	 */
	public abstract double divide(double a, double b);
	
	/**
	 * Compute the multiplicative inverse of a.
	 * 
	 * @param a
	 * @return a^-1
	 */
	public abstract double invert(double a);
	
		
	/**
	 * Compute a * b^-1.
	 * 
	 * @param a
	 * @param b
	 * @return a * b^-1.
	 */
	public double divide(int a, int b) {
		return this.divide(this.valueOf(a), this.valueOf(b));
	}
	
	/**
	 * Compute the multiplicative inverse of a.
	 * 
	 * @param a
	 * @return a^-1
	 */
	public double invert(int a) {
		return this.invert(this.valueOf(a));
	}
	
			
	@Override
	public double power(double a, int n)	{
		if (n < 0) {
			return this.power(this.invert(a), -n);
		}
	    double result = this.getOne();
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
