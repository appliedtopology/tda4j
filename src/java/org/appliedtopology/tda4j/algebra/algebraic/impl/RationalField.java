package org.appliedtopology.tda4j.algebra.algebraic.impl;

import org.apache.commons.math.fraction.Fraction;

import org.appliedtopology.tda4j.algebra.autogen.algebraic.ObjectAbstractField;

/**
 * This class implements the algebraic operations of a field over the
 * type Fraction.
 * 
 * @author Andrew Tausz
 *
 */
public class RationalField extends ObjectAbstractField<Fraction> {
	/**
	 * Private constructor to prevent instantiation.
	 */
	private RationalField() {}

	/**
	 * The single instance.
	 */
	private static final RationalField instance = new RationalField();

	/**
	 * This function returns the single instance of the class.
	 * 
	 * @return the instance of the class
	 */
	public static RationalField getInstance() {
		return instance;
	}

	@Override
	public Fraction add(Fraction a, Fraction b) {
		return a.add(b);
	}

	@Override
	public Fraction getOne() {
		return Fraction.ONE;
	}

	@Override
	public Fraction getZero() {
		return Fraction.ZERO;
	}

	@Override
	public Fraction multiply(Fraction a, Fraction b) {
		return a.multiply(b);
	}

	@Override
	public Fraction negate(Fraction a) {
		return a.negate();
	}

	@Override
	public Fraction subtract(Fraction a, Fraction b) {
		return a.subtract(b);
	}

	@Override
	public Fraction valueOf(int n) {
		return Fraction.getReducedFraction(n, 1);
	}

	@Override
	public Fraction divide(Fraction a, Fraction b) {
		return a.divide(b);
	}

	@Override
	public Fraction invert(Fraction a) {
		return a.reciprocal();
	}

	@Override
	public boolean isUnit(Fraction a) {
		return (a.getNumerator() != 0);
	}

	@Override
	public boolean isZero(Fraction a) {
		return (a.equals(Fraction.ZERO));
	}

	@Override
	public boolean isOne(Fraction a) {
		return (a.equals(Fraction.ONE));
	}

	@Override
	public int characteristic() {
		return 0;
	}

	public int compare(Fraction o1, Fraction o2) {
		return o1.compareTo(o2);
	}

	public Fraction abs(Fraction a) {
		return a.abs();
	}
}
