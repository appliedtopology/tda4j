package org.appliedtopology.tda4j.algebra.autogen.algebraic;


/**
 * This class defines the functionality of a ring over elements
 * of type int.
 * 
 * @author autogen
 *
 * @param <int> the underlying type
 */
public abstract class IntAbstractRing {
	/**
	 * Compute the sum of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a + b
	 */
	public abstract int add(int a, int b);
	
	/**
	 * Compute the difference of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a - b
	 */
	public abstract int subtract(int a, int b);
	
	/**
	 * Compute the product of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a * b
	 */
	public abstract int multiply(int a, int b);
	
	/**
	 * Compute the additive inverse of a.
	 * 
	 * @param a
	 * @return -a
	 */
	public abstract int negate(int a);
	
	/**
	 * Return the canonical value of the integer n,
	 * which is 1 + ... + 1 (n terms) if n >= 0, and
	 * -1 + ... + -1 (n terms) if (n < 0), where 1 is
	 * the multiplicative identity in the ring
	 * 
	 * @param n
	 * @return the value of n in the ring
	 */
	public abstract int valueOf(int n);
	
	/**
	 * Return the additive identity element.
	 * 
	 * @return the additive identity element, 0
	 */
	public abstract int getZero();
	
	/**
	 * Return the multiplicative identity element.
	 * 
	 * @return the multiplicative identity element
	 */
	public abstract int getOne();
	
	/**
	 * Return the additive inverse of the multiplicative
	 * identity element.
	 * 
	 * @return -1
	 */
	public int getNegativeOne() {
		return this.negate(this.getOne());
	}
	
	/**
	 * This function returns true if a has a multiplicative
	 * inverse, and false otherwise.
	 * 
	 * @param a
	 * @return true if a is a unit in the ring
	 */
	public abstract boolean isUnit(int a);
	
	/**
	 * This function returns true if a is the additive identity,
	 * and false otherwise.
	 * 
	 * @param a
	 * @return true if a is the additive identity
	 */
	public abstract boolean isZero(int a);
	
	/**
	 * This function returns true if a is the multiplicative identity,
	 * and false otherwise.
	 * 
	 * @param a
	 * @return true if a is the multiplicative identity
	 */
	public abstract boolean isOne(int a);
	
	/**
	 * This function returns the smallest number (if it exists) such that
	 * n * 1 = 0 in the ring. If it doesn't exist, it returns 0.
	 * 
	 * @return the characteristic of the ring
	 */
	public abstract int characteristic();
	
		
	/**
	 * This function returns the product a^n (or a multiplied
	 * by itself n times), where n >= 0.
	 * 
	 * @param a the base
	 * @param n the exponent
	 * @return a^n
	 */
	public int power(int a, int n)	{
		if (n > 0) {
			return this.getZero();
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
