package org.appliedtopology.tda4j.algebra.algebraic.impl;

import java.util.HashMap;
import java.util.Map;

import org.appliedtopology.tda4j.algebra.autogen.algebraic.IntAbstractField;
import org.appliedtopology.tda4j.algebra.utility.MathUtility;

/**
 * This class implements the algebraic operations of the field Z/pZ for p prime.
 * The underlying type of the field is int.
 * 
 * @author Andrew Tausz
 *
 */
public class ModularIntField extends IntAbstractField {
	private final int p;
	
	/**
	 * This array stores the inverses of the elements {0, ..., p - 1}. Note that the 0-th 
	 * index is simply set to zero for convenience. We pre-compute the inverses so that the
	 * field operations can be executed efficiently.
	 */
	private final int[] inverses;
	
	/**
	 * This map contains the instances the class for each prime p.
	 */
	private static Map<Integer, ModularIntField> map = new HashMap<Integer, ModularIntField>();
	
	/**
	 * This static function returns the single instance of the class for the specified prime p.
	 * 
	 * @param p the prime
	 * @return the single instance of the class for the specified prime 
	 */
	public static ModularIntField getInstance(int p) {
		if (map.containsKey(p)) {
			return map.get(p);
		} else {
			ModularIntField finiteField = new ModularIntField(p);
			map.put(p, finiteField);
			return finiteField;
		}
	}
	
	/**
	 * Private constructor which prevents instantiation.
	 * 
	 * @param p the prime to initialize with
	 */
	private ModularIntField(int p) {
		this.p = p;
		this.inverses = MathUtility.modularInverses(p);
	}

	@Override
	public int divide(int a, int b) {
		if ((b % p) == 0) {
			throw new ArithmeticException();
		}
		int index = b % p;
		if (index < 0) {
			index += p;
		}
		return ((a * this.inverses[index]) % p);
	}

	@Override
	public int invert(int a) {
		if ((a % p) == 0) {
			throw new ArithmeticException();
		}
		int r = a % p;
		if (r < 0) {
			r += p;
		}
		return this.inverses[r];
	}

	@Override
	public int add(int a, int b) {
		int r = (a + b) % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public int getOne() {
		return 1;
	}

	@Override
	public int getZero() {
		return 0;
	}

	@Override
	public int multiply(int a, int b) {
		int r = (a * b) % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public int negate(int a) {
		int r = (-a) % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public int subtract(int a, int b) {
		int r = (a - b) % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public int valueOf(int n) {
		int r = n % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public boolean isUnit(int a) {
		return (a % p != 0);
	}
	
	@Override
	public boolean isZero(int a) {
		return (a % p == 0);
	}
	
	@Override
	public boolean isOne(int a) {
		return (a % p == 1);
	}

	@Override
	public int characteristic() {
		return this.p;
	}
}
