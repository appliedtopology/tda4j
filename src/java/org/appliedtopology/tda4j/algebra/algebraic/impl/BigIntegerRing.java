package org.appliedtopology.tda4j.algebra.algebraic.impl;

import java.math.BigInteger;

import org.appliedtopology.tda4j.algebra.autogen.algebraic.ObjectAbstractRing;

/**
 * This class implements the algebraic operations of a ring over the type
 * BigInteger. This class implements the singleton design pattern.
 * 
 * @author Andrew Tausz
 *
 */
public class BigIntegerRing extends ObjectAbstractRing<BigInteger> {
	/**
	 * Private constructor to prevent instantiation.
	 */
	private BigIntegerRing() {}

	/**
	 * The single instance.
	 */
	private static final BigIntegerRing instance = new BigIntegerRing();

	/**
	 * This function returns the single instance of the class.
	 * 
	 * @return the instance of the class
	 */
	public static BigIntegerRing getInstance() {
		return instance;
	}

	@Override
	public BigInteger add(BigInteger a, BigInteger b) {
		return a.add(b);
	}

	@Override
	public BigInteger getOne() {
		return BigInteger.ONE;
	}

	@Override
	public BigInteger getZero() {
		return BigInteger.ZERO;
	}

	@Override
	public boolean isUnit(BigInteger a) {
		return a.abs().equals(BigInteger.ONE);
	}

	@Override
	public boolean isZero(BigInteger a) {
		return a.equals(BigInteger.ZERO);
	}

	@Override
	public boolean isOne(BigInteger a) {
		return a.equals(BigInteger.ONE);
	}

	@Override
	public BigInteger multiply(BigInteger a, BigInteger b) {
		return a.multiply(b);
	}

	@Override
	public BigInteger negate(BigInteger a) {
		return a.negate();
	}

	@Override
	public BigInteger subtract(BigInteger a, BigInteger b) {
		return a.subtract(b);
	}

	@Override
	public BigInteger valueOf(int n) {
		return BigInteger.valueOf(n);
	}

	@Override
	public int characteristic() {
		return 0;
	}
}
