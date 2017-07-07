package org.appliedtopology.tda4j.algebra.autogen.algebraic;


/**
 * This class defines the functionality of a ring over elements
 * of type R.
 * 
 * @author autogen
 *
 * @param <R> the underlying type
 */
public abstract class ObjectAbstractRing<R> {
	/**
	 * Compute the sum of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a + b
	 */
	public abstract R add(R a, R b);
	
	/**
	 * Compute the difference of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a - b
	 */
	public abstract R subtract(R a, R b);
	
	/**
	 * Compute the product of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a * b
	 */
	public abstract R multiply(R a, R b);
	
	/**
	 * Compute the additive inverse of a.
	 * 
	 * @param a
	 * @return -a
	 */
	public abstract R negate(R a);
	
	/**
	 * Return the canonical value of the integer n,
	 * which is 1 + ... + 1 (n terms) if n >= 0, and
	 * -1 + ... + -1 (n terms) if (n < 0), where 1 is
	 * the multiplicative identity in the ring
	 * 
	 * @param n
	 * @return the value of n in the ring
	 */
	public abstract R valueOf(int n);
	
	/**
	 * Return the additive identity element.
	 * 
	 * @return the additive identity element, 0
	 */
	public abstract R getZero();
	
	/**
	 * Return the multiplicative identity element.
	 * 
	 * @return the multiplicative identity element
	 */
	public abstract R getOne();
	
	/**
	 * Return the additive inverse of the multiplicative
	 * identity element.
	 * 
	 * @return -1
	 */
	public R getNegativeOne() {
		return this.negate(this.getOne());
	}
	
	/**
	 * This function returns true if a has a multiplicative
	 * inverse, and false otherwise.
	 * 
	 * @param a
	 * @return true if a is a unit in the ring
	 */
	public abstract boolean isUnit(R a);
	
	/**
	 * This function returns true if a is the additive identity,
	 * and false otherwise.
	 * 
	 * @param a
	 * @return true if a is the additive identity
	 */
	public abstract boolean isZero(R a);
	
	/**
	 * This function returns true if a is the multiplicative identity,
	 * and false otherwise.
	 * 
	 * @param a
	 * @return true if a is the multiplicative identity
	 */
	public abstract boolean isOne(R a);
	
	/**
	 * This function returns the smallest number (if it exists) such that
	 * n * 1 = 0 in the ring. If it doesn't exist, it returns 0.
	 * 
	 * @return the characteristic of the ring
	 */
	public abstract int characteristic();
	
		
	/**
	 * Compute the sum of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a + b
	 */
	public R add(int a, int b) {
		return this.add(this.valueOf(a), this.valueOf(b));
	}
	
	/**
	 * Compute the difference of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a - b
	 */
	public R subtract(int a, int b) {
		return this.subtract(this.valueOf(a), this.valueOf(b));
	}
	
	/**
	 * Compute the product of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a * b
	 */
	public R multiply(int a, int b) {
		return this.multiply(this.valueOf(a), this.valueOf(b));
	}
	
	/**
	 * Compute the sum of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a + b
	 */
	public R add(R a, int b) {
		return this.add(a, this.valueOf(b));
	}
	
	/**
	 * Compute the difference of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a - b
	 */
	public R subtract(R a, int b) {
		return this.subtract(a, this.valueOf(b));
	}
	
	/**
	 * Compute the product of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a * b
	 */
	public R multiply(R a, int b) {
		return this.multiply(a, this.valueOf(b));
	}
	
	/**
	 * Compute the sum of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a + b
	 */
	public R add(int a, R b) {
		return this.add(this.valueOf(a), b);
	}
	
	/**
	 * Compute the difference of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a - b
	 */
	public R subtract(int a, R b) {
		return this.subtract(this.valueOf(a), b);
	}
	
	/**
	 * Compute the product of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a * b
	 */
	public R multiply(int a, R b) {
		return this.multiply(this.valueOf(a), b);
	}
	
	/**
	 * Compute the additive inverse of a.
	 * 
	 * @param a
	 * @return -a
	 */
	public R negate(int a) {
		return this.negate(this.valueOf(a));
	}
	
		
	/**
	 * This function returns the product a^n (or a multiplied
	 * by itself n times), where n >= 0.
	 * 
	 * @param a the base
	 * @param n the exponent
	 * @return a^n
	 */
	public R power(R a, int n)	{
		if (n > 0) {
			return this.getZero();
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
