package org.appliedtopology.tda4j.algebra.autogen.formal_sum;



import java.util.Iterator;

import org.appliedtopology.tda4j.algebra.autogen.algebraic.BooleanAbstractModule;

/**
 * This class implements the algebraic operations of a free module with generating elements of
 * type M, and over a ring with elements of type boolean. Elements of the free 
 * module are represented by formal sums of entries in M. The coefficient operations on 
 * the sums are simply the standard primitive operations on the type boolean.
 * 
 * @author autogen
 *
 * @param <boolean> the coefficient type
 * @param <M> the object type
 */
public class BooleanPrimitiveFreeModule<M> implements BooleanAbstractModule<BooleanSparseFormalSum<M>> {

	public BooleanPrimitiveFreeModule() {}

	public BooleanSparseFormalSum<M> add(BooleanSparseFormalSum<M> a, BooleanSparseFormalSum<M> b) {
		BooleanSparseFormalSum<M> result = null;

		Iterator<M> iterator = null;

		if (a.size() > b.size()) {
			result = this.createNewSum(a);
			iterator = b.map.iterator();
		} else {
			result = this.createNewSum(b);
			iterator = a.map.iterator();
		}

		while (iterator.hasNext()) {
			addObject(result, iterator.next());
		}

		return result;
	}

	public BooleanSparseFormalSum<M> subtract(BooleanSparseFormalSum<M> a, BooleanSparseFormalSum<M> b) {
		return add(a, b);
	}

	public BooleanSparseFormalSum<M> multiply(boolean r, BooleanSparseFormalSum<M> a) {
		if (r) {
			return a;
		} else {
			return this.createNewSum();
		}
	}

	public BooleanSparseFormalSum<M> negate(BooleanSparseFormalSum<M> a) {
		return a;
	}

	public BooleanSparseFormalSum<M> multiply(int r, BooleanSparseFormalSum<M> a) {
		if (r % 2 != 0) {
			return a;
		} else {
			return this.createNewSum();
		}
	}

	/**
	 * This function computes the inner product between two formal sums.
	 * 
	 * @param a
	 * @param b
	 * @return the inner product of a and b
	 */
	public boolean innerProduct(BooleanSparseFormalSum<M> a, BooleanSparseFormalSum<M> b) {
		boolean sum = false;
		
		Iterator<M> iterator = null;
		BooleanSparseFormalSum<M> other = null;
		
		if (a.size() > b.size()) {
			iterator = b.map.iterator();
			other = a;
		} else {
			iterator = a.map.iterator();
			other = b;
		}
		
		while (iterator.hasNext()) {
			sum ^= other.getCoefficient(iterator.next());
		}
		
		return sum;
	}
	
	/**
	 * This function computes the sum of two generating elements.
	 * 
	 * @param a
	 * @param b
	 * @return a + b
	 */
	public BooleanSparseFormalSum<M> add(M a, M b) {
		BooleanSparseFormalSum<M> sum = this.createNewSum(a);
		this.addObject(sum, b);
		return sum;
	}

	/**
	 * This function computes the sum of a formal sum and a basis element.
	 * 
	 * @param a
	 * @param b
	 * @return a + b
	 */
	public BooleanSparseFormalSum<M> add(BooleanSparseFormalSum<M> a, M b) {
		BooleanSparseFormalSum<M> sum = this.createNewSum(a);
		this.addObject(sum, b);
		return sum;
	}

	/**
	 * This function computes the difference of two generating elements.
	 * 
	 * @param a
	 * @param b
	 * @return a - b
	 */
	public BooleanSparseFormalSum<M> subtract(M a, M b) {
		BooleanSparseFormalSum<M> sum = this.createNewSum(a);
		this.addObject(sum, b);
		return sum;
	}

	/**
	 * This function computes the difference of a formal sum and a basis element.
	 * 
	 * @param a
	 * @param b
	 * @return a - b
	 */
	public BooleanSparseFormalSum<M> subtract(BooleanSparseFormalSum<M> a, M b) {
		BooleanSparseFormalSum<M> sum = this.createNewSum(a);
		this.addObject(sum, b);
		return sum;
	}
	
	/**
	 * This function performs the operation a = a + b.
	 * 
	 * @param a the value to accumulate in
	 * @param b the module element to add
	 */
	public void accumulate(BooleanSparseFormalSum<M> a, BooleanSparseFormalSum<M> b) {
		for (M element: b.map) {
			addObject(a, element);
		}
	}

	/**
	 * This function performs the operation a = a + r * b.
	 * 
	 * @param a the value to accumulate in
	 * @param b the module element to add
	 * @param c the scalar multiplier
	 */
	public void accumulate(BooleanSparseFormalSum<M> a, BooleanSparseFormalSum<M> b, boolean r) {
		if (r) {
			for (M element: b.map) {
				addObject(a, element);
			}
		}
	}

	/**
	 * This function performs the operation a = a + b where b is a basis element.
	 * 
	 * @param a the value to accumulate in
	 * @param b the element to add
	 */
	public void accumulate(BooleanSparseFormalSum<M> a, M b) {
		this.addObject(a, b);
	}

	/**
	 * This function performs the operation a = a + r * b.
	 * 
	 * @param a the value to accumulate in
	 * @param b the basis element to add
	 * @param r the scalar multiplier
	 */
	public void accumulate(BooleanSparseFormalSum<M> a, M b, boolean r) {
		if (r) {
			this.addObject(a, b);
		}
	}

	/**
	 * This function adds the given object to the formal sum with given coefficient. In the event
	 * that the object is already present it computes the sum of the passed in and existing 
	 * coefficients and updates the coefficient with the result. In the event that the object is
	 * not present already, it adds a new object with the given coefficient.
	 * 
	 * @param formalSum the sum to add to
	 * @param object the object (basis element) to add
	 */
	private void addObject(BooleanSparseFormalSum<M> formalSum, M object) {
		if (formalSum.containsObject(object)) {
			formalSum.remove(object);
		} else {
			formalSum.put(true, object);
		}
	}
	
	public BooleanSparseFormalSum<M> getAdditiveIdentity() {
		return new BooleanSparseFormalSum<M>();
	}
	
	/**
	 * This function creates an empty new formal sum.
	 * 
	 * @return an empty new formal sum
	 */
	public BooleanSparseFormalSum<M> createNewSum() {
		return new BooleanSparseFormalSum<M>();
	}

	/**
	 * This function creates a new formal sum containing a single object with default
	 * coefficient equal to the multiplicative identity of the ring.
	 * 
	 * @param object the object to initialize the sum with
	 * @return a new sum containing the supplied object
	 */
	public BooleanSparseFormalSum<M> createNewSum(M object) {
		return new BooleanSparseFormalSum<M>(true, object);
	}

	/**
	 * This function creates a new formal sum containing a single object with the
	 * supplied coefficient.
	 * 
	 * @param coefficient the coefficient of the element to add
	 * @param object the object to add
	 * @return a new formal sum initializes with a coefficient and basis element
	 */
	public BooleanSparseFormalSum<M> createNewSum(boolean coefficient, M object) {
		return new BooleanSparseFormalSum<M>(coefficient, object);
	}

	/**
	 * This function creates a new formal sum with the contents of another sum
	 * copied in.
	 * 
	 * @param contents the formal sum to copy from
	 * @return a new formal sum with the contents of the given one copied in
	 */
	public BooleanSparseFormalSum<M> createNewSum(BooleanSparseFormalSum<M> contents) {
		return new BooleanSparseFormalSum<M>(contents);
	}

	/**
	 * This function creates a new formal sum initialized with the given coefficient
	 * and object arrays.
	 * 
	 * @param coefficients an array of coefficients 
	 * @param objects an array of basis elements
	 * @return a new formal sum initialized with the given coefficients and basis elements
	 */
	public BooleanSparseFormalSum<M> createNewSum(boolean[] coefficients, M[] objects) {
		BooleanSparseFormalSum<M> sum = new BooleanSparseFormalSum<M>();

		for (int i = 0; i < coefficients.length; i++) {
			if (coefficients[i]) {
				addObject(sum, objects[i]);
			}
		}

		return sum;
	}

	/**
	 * This function creates a new formal sum initialized with the given coefficient
	 * and object arrays.
	 * 
	 * @param coefficients an array of coefficients 
	 * @param objects an array of basis elements
	 * @return a new formal sum initialized with the given coefficients and basis elements
	 */
	public BooleanSparseFormalSum<M> createNewSum(int[] coefficients, M[] objects) {
		BooleanSparseFormalSum<M> sum = new BooleanSparseFormalSum<M>();

		for (int i = 0; i < coefficients.length; i++) {
			if ((coefficients[i] % 2) != 0) {
				addObject(sum, objects[i]);
			}
		}

		return sum;
	}
};
