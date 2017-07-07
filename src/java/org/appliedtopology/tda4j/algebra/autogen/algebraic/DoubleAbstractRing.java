package org.appliedtopology.tda4j.algebra.autogen.algebraic;


/**
 * This class defines the functionality of a ring over elements
 * of type double.
 * 
 * @author autogen
 *
 * @param <double> the underlying type
 */
public abstract class DoubleAbstractRing {
	/**
	 * Compute the sum of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a + b
	 */
	public abstract double add(double a, double b);
	
	/**
	 * Compute the difference of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a - b
	 */
	public abstract double subtract(double a, double b);
	
	/**
	 * Compute the product of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a * b
	 */
	public abstract double multiply(double a, double b);
	
	/**
	 * Compute the additive inverse of a.
	 * 
	 * @param a
	 * @return -a
	 */
	public abstract double negate(double a);
	
	/**
	 * Return the canonical value of the integer n,
	 * which is 1 + ... + 1 (n terms) if n >= 0, and
	 * -1 + ... + -1 (n terms) if (n < 0), where 1 is
	 * the multiplicative identity in the ring
	 * 
	 * @param n
	 * @return the value of n in the ring
	 */
	public abstract double valueOf(int n);
	
	/**
	 * Return the additive identity element.
	 * 
	 * @return the additive identity element, 0
	 */
	public abstract double getZero();
	
	/**
	 * Return the multiplicative identity element.
	 * 
	 * @return the multiplicative identity element
	 */
	public abstract double getOne();
	
	/**
	 * Return the additive inverse of the multiplicative
	 * identity element.
	 * 
	 * @return -1
	 */
	public double getNegativeOne() {
		return this.negate(this.getOne());
	}
	
	/**
	 * This function returns true if a has a multiplicative
	 * inverse, and false otherwise.
	 * 
	 * @param a
	 * @return true if a is a unit in the ring
	 */
	public abstract boolean isUnit(double a);
	
	/**
	 * This function returns true if a is the additive identity,
	 * and false otherwise.
	 * 
	 * @param a
	 * @return true if a is the additive identity
	 */
	public abstract boolean isZero(double a);
	
	/**
	 * This function returns true if a is the multiplicative identity,
	 * and false otherwise.
	 * 
	 * @param a
	 * @return true if a is the multiplicative identity
	 */
	public abstract boolean isOne(double a);
	
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
	public double add(int a, int b) {
		return this.add(this.valueOf(a), this.valueOf(b));
	}
	
	/**
	 * Compute the difference of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a - b
	 */
	public double subtract(int a, int b) {
		return this.subtract(this.valueOf(a), this.valueOf(b));
	}
	
	/**
	 * Compute the product of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a * b
	 */
	public double multiply(int a, int b) {
		return this.multiply(this.valueOf(a), this.valueOf(b));
	}
	
	/**
	 * Compute the sum of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a + b
	 */
	public double add(double a, int b) {
		return this.add(a, this.valueOf(b));
	}
	
	/**
	 * Compute the difference of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a - b
	 */
	public double subtract(double a, int b) {
		return this.subtract(a, this.valueOf(b));
	}
	
	/**
	 * Compute the product of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a * b
	 */
	public double multiply(double a, int b) {
		return this.multiply(a, this.valueOf(b));
	}
	
	/**
	 * Compute the sum of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a + b
	 */
	public double add(int a, double b) {
		return this.add(this.valueOf(a), b);
	}
	
	/**
	 * Compute the difference of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a - b
	 */
	public double subtract(int a, double b) {
		return this.subtract(this.valueOf(a), b);
	}
	
	/**
	 * Compute the product of two numbers.
	 * 
	 * @param a 
	 * @param b
	 * @return a * b
	 */
	public double multiply(int a, double b) {
		return this.multiply(this.valueOf(a), b);
	}
	
	/**
	 * Compute the additive inverse of a.
	 * 
	 * @param a
	 * @return -a
	 */
	public double negate(int a) {
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
	public double power(double a, int n)	{
		if (n > 0) {
			return this.getZero();
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
