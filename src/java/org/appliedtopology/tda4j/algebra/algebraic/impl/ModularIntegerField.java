package org.appliedtopology.tda4j.algebra.algebraic.impl;

import java.util.HashMap;
import java.util.Map;

import org.appliedtopology.tda4j.algebra.autogen.algebraic.ObjectAbstractField;
import org.appliedtopology.tda4j.algebra.utility.MathUtility;

/**
 * This class implements the algebraic operations of the field Z/pZ for p prime.
 * The underlying type of the field is Integer.
 * 
 * @author Andrew Tausz
 *
 */
public class ModularIntegerField extends ObjectAbstractField<Integer> {
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
	private static Map<Integer, ModularIntegerField> map = new HashMap<Integer, ModularIntegerField>();

	/**
	 * This static function returns the single instance of the class for the specified prime p.
	 * 
	 * @param p the prime
	 * @return the single instance of the class for the specified prime 
	 */
	public static ModularIntegerField getInstance(Integer p) {
		if (map.containsKey(p)) {
			return map.get(p);
		} else {
			ModularIntegerField finiteField = new ModularIntegerField(p);
			map.put(p, finiteField);
			return finiteField;
		}
	}

	/**
	 * Private constructor which prevents instantiation.
	 * 
	 * @param p the prime to initialize with
	 */
	private ModularIntegerField(Integer p) {
		this.p = p;
		this.inverses = MathUtility.modularInverses(p);
	}

	@Override
	public Integer divide(Integer a, Integer b) {
		if ((b % p) == 0) {
			throw new ArithmeticException();
		}
		Integer index = b % p;
		if (index < 0) {
			index += p;
		}
		return ((a * this.inverses[index]) % p);
	}

	@Override
	public Integer invert(Integer a) {
		if ((a % p) == 0) {
			throw new ArithmeticException();
		}
		Integer r = a % p;
		if (r < 0) {
			r += p;
		}
		return this.inverses[r];
	}

	@Override
	public Integer add(Integer a, Integer b) {
		Integer r = (a + b) % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public Integer getOne() {
		return 1;
	}

	@Override
	public Integer getZero() {
		return 0;
	}

	@Override
	public Integer multiply(Integer a, Integer b) {
		Integer r = (a * b) % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public Integer negate(Integer a) {
		Integer r = (-a) % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public Integer subtract(Integer a, Integer b) {
		Integer r = (a - b) % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public Integer valueOf(int n) {
		Integer r = n % p;
		if (r < 0) {
			r += p;
		}
		return r;
	}

	@Override
	public boolean isUnit(Integer a) {
		return (a % p != 0);
	}

	@Override
	public boolean isZero(Integer a) {
		return (a % p == 0);
	}

	@Override
	public boolean isOne(Integer a) {
		return (a % p == 1);
	}

	@Override
	public int characteristic() {
		return this.p;
	}
}
