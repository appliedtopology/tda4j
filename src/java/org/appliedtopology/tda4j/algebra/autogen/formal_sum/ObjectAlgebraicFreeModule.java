package org.appliedtopology.tda4j.algebra.autogen.formal_sum;

import java.util.Iterator;
import java.util.Map;

import org.appliedtopology.tda4j.algebra.autogen.algebraic.ObjectAbstractModule;
import org.appliedtopology.tda4j.algebra.autogen.algebraic.ObjectAbstractRing;




/**
 * This class implements the algebraic operations of a free module with generating elements of
 * type M, and over a ring with elements of type R. Elements of the free 
 * module are represented by formal sums of entries in M. The coefficient operations on 
 * the sums are inherited by those given by a ring supplied by the user.
 * 
 * @author autogen
 *
 * @param <R> the coefficient type
 * @param <M> the object type
 */
public class ObjectAlgebraicFreeModule<R, M> implements ObjectAbstractModule<R, ObjectSparseFormalSum<R, M>> {
	/**
	 * This is the ring in which to perform the coefficient operations.
	 */
	private ObjectAbstractRing<R> ring;

	/**
	 * This constructor initializes the module with a ring.
	 * 
	 * @param ring
	 */
	public ObjectAlgebraicFreeModule(ObjectAbstractRing<R> ring) {
		this.ring = ring;
	}
	
	public ObjectAbstractRing<R> getRing() {
		return this.ring;
	}
	
	public ObjectSparseFormalSum<R, M> add(ObjectSparseFormalSum<R, M> a, ObjectSparseFormalSum<R, M> b) {
		ObjectSparseFormalSum<R, M> result = null;
		
				
		Iterator<Map.Entry<M, R>> iterator = null;
		
		if (a.size() > b.size()) {
			result = this.createNewSum(a);
			iterator = b.map.entrySet().iterator();
		} else {
			result = this.createNewSum(b);
			iterator = a.map.entrySet().iterator();
		}
		
		while (iterator.hasNext()) {
			Map.Entry<M, R> entry = iterator.next();
			addObject(result, entry.getValue(), entry.getKey());
		}
		
				
		return result;
	}
	
	public ObjectSparseFormalSum<R, M> subtract(ObjectSparseFormalSum<R, M> a, ObjectSparseFormalSum<R, M> b) {
		ObjectSparseFormalSum<R, M> result = this.createNewSum(a);
		
				
		Iterator<Map.Entry<M, R>> iterator = b.map.entrySet().iterator();
		
		while (iterator.hasNext()) {
			Map.Entry<M, R> entry = iterator.next();
			addObject(result, ring.negate(entry.getValue()), entry.getKey());
		}
		
				
		return result;
	}
	
	public ObjectSparseFormalSum<R, M> multiply(R r, ObjectSparseFormalSum<R, M> a) {
		ObjectSparseFormalSum<R, M> result = this.createNewSum();
		
				
		Iterator<Map.Entry<M, R>> iterator = a.map.entrySet().iterator();;
		
		while (iterator.hasNext()) {
			Map.Entry<M, R> entry = iterator.next();
			addObject(result, ring.multiply(r, entry.getValue()), entry.getKey());
		}
		
				
		return result;
	}
	
	public ObjectSparseFormalSum<R, M> negate(ObjectSparseFormalSum<R, M> a) {
		ObjectSparseFormalSum<R, M> result = this.createNewSum();
		
				
		Iterator<Map.Entry<M, R>> iterator = a.map.entrySet().iterator();
		
		while (iterator.hasNext()) {
			Map.Entry<M, R> entry = iterator.next();
			addObject(result, ring.negate(entry.getValue()), entry.getKey());
		}
		
				
		return result;
	}
	
		public ObjectSparseFormalSum<R, M> multiply(int r, ObjectSparseFormalSum<R, M> a) {
		ObjectSparseFormalSum<R, M> result = this.createNewSum();
		
				
		Iterator<Map.Entry<M, R>> iterator = a.map.entrySet().iterator();
		
		while (iterator.hasNext()) {
			Map.Entry<M, R> entry = iterator.next();
			addObject(result, ring.multiply(r, entry.getValue()), entry.getKey());
		}
		
				
		return result;
	}
		
	/**
	 * This function computes the inner product between two formal sums.
	 * 
	 * @param a
	 * @param b
	 * @return the inner product of a and b
	 */
	public R innerProduct(ObjectSparseFormalSum<R, M> a, ObjectSparseFormalSum<R, M> b) {
		R sum = ring.getZero();
		
				
		Iterator<Map.Entry<M, R>> iterator = null;
		ObjectSparseFormalSum<R, M> other = null;
		
		if (a.size() > b.size()) {
			iterator = b.map.entrySet().iterator();
			other = a;
		} else {
			iterator = a.map.entrySet().iterator();
			other = b;
		}
		
		while (iterator.hasNext()) {
			Map.Entry<M, R> entry = iterator.next();
			sum = ring.add(sum, ring.multiply(entry.getValue(), other.getCoefficient(entry.getKey())));
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
	public ObjectSparseFormalSum<R, M> add(M a, M b) {
		ObjectSparseFormalSum<R, M> sum = this.createNewSum(a);
		this.addObject(sum, ring.getOne(), b);
		return sum;
	}
	
	/**
	 * This function computes the sum of a formal sum and a basis element.
	 * 
	 * @param a
	 * @param b
	 * @return a + b
	 */
	public ObjectSparseFormalSum<R, M> add(ObjectSparseFormalSum<R, M> a, M b) {
		ObjectSparseFormalSum<R, M> sum = this.createNewSum(a);
		this.addObject(sum, ring.getOne(), b);
		return sum;
	}
	
	/**
	 * This function computes the difference of two generating elements.
	 * 
	 * @param a
	 * @param b
	 * @return a - b
	 */
	public ObjectSparseFormalSum<R, M> subtract(M a, M b) {
		ObjectSparseFormalSum<R, M> sum = this.createNewSum(a);
		this.addObject(sum, ring.getNegativeOne(), b);
		return sum;
	}
	
	/**
	 * This function computes the difference of a formal sum and a basis element.
	 * 
	 * @param a
	 * @param b
	 * @return a - b
	 */
	public ObjectSparseFormalSum<R, M> subtract(ObjectSparseFormalSum<R, M> a, M b) {
		ObjectSparseFormalSum<R, M> sum = this.createNewSum(a);
		this.addObject(sum, ring.getNegativeOne(), b);
		return sum;
	}
	
	/**
	 * This function performs the operation a = a + b.
	 * 
	 * @param a the value to accumulate in
	 * @param b the module element to add
	 */
	public void accumulate(ObjectSparseFormalSum<R, M> a, ObjectSparseFormalSum<R, M> b) {
		
				
		for (Map.Entry<M, R> entry: b.map.entrySet()) {
			this.addObject(a, entry.getValue(), entry.getKey());
		}
		
			}
	
	/**
	 * This function performs the operation a = a + r * b.
	 * 
	 * @param a the value to accumulate in
	 * @param b the module element to add
	 * @param c the scalar multiplier
	 */
	public void accumulate(ObjectSparseFormalSum<R, M> a, ObjectSparseFormalSum<R, M> b, R r) {
		
				
		for (Map.Entry<M, R> entry: b.map.entrySet()) {
			this.addObject(a, ring.multiply(r, entry.getValue()), entry.getKey());
		}
		
			}
	
	/**
	 * This function performs the operation a = a + b where b is a basis element.
	 * 
	 * @param a the value to accumulate in
	 * @param b the element to add
	 */
	public void accumulate(ObjectSparseFormalSum<R, M> a, M b) {
		this.addObject(a, this.ring.getOne(), b);
	}
	
	/**
	 * This function performs the operation a = a + r * b.
	 * 
	 * @param a the value to accumulate in
	 * @param b the basis element to add
	 * @param c the scalar multiplier
	 */
	public void accumulate(ObjectSparseFormalSum<R, M> a, M b, R r) {
		this.addObject(a, r, b);
	}
	
	/**
	 * This function adds the given object to the formal sum with given coefficient. In the event
	 * that the object is already present it computes the sum of the passed in and existing 
	 * coefficients and updates the coefficient with the result. In the event that the object is
	 * not present already, it adds a new object with the given coefficient.
	 * 
	 * @param formalSum the sum to add to
	 * @param coefficient the coefficient of the object to add
	 * @param object the object (basis element) to add
	 */
	private void addObject(ObjectSparseFormalSum<R, M> formalSum, R coefficient, M object) {
		if (this.ring.isZero(coefficient)) {
			return;
		}
		
		if (formalSum.containsObject(object)) {
			R newCoefficient = this.ring.add(formalSum.getCoefficient(object), coefficient);
			if (ring.isZero(newCoefficient)) {
				formalSum.remove(object);
			} else {
				formalSum.put(newCoefficient, object);
			}
		} else {
			formalSum.put(coefficient, object);
		}
	}

	public ObjectSparseFormalSum<R, M> getAdditiveIdentity() {
		return new ObjectSparseFormalSum<R, M>();
	}

	/**
	 * This function creates an empty new formal sum.
	 * 
	 * @return an empty new formal sum
	 */
	public ObjectSparseFormalSum<R, M> createNewSum() {
		return new ObjectSparseFormalSum<R, M>();
	}

	/**
	 * This function creates a new formal sum containing a single object with default
	 * coefficient equal to the multiplicative identity of the ring.
	 * 
	 * @param object the object to initialize the sum with
	 * @return a new sum containing the supplied object
	 */
	public ObjectSparseFormalSum<R, M> createNewSum(M object) {
		return new ObjectSparseFormalSum<R, M>(this.ring.getOne(), object);
	}

	/**
	 * This function creates a new formal sum containing a single object with the
	 * supplied coefficient.
	 * 
	 * @param coefficient the coefficient of the element to add
	 * @param object the object to add
	 * @return a new formal sum initializes with a coefficient and basis element
	 */
	public ObjectSparseFormalSum<R, M> createNewSum(R coefficient, M object) {
		return new ObjectSparseFormalSum<R, M>(coefficient, object);
	}

	/**
	 * This function creates a new formal sum with the contents of another sum
	 * copied in.
	 * 
	 * @param contents the formal sum to copy from
	 * @return a new formal sum with the contents of the given one copied in
	 */
	public ObjectSparseFormalSum<R, M> createNewSum(ObjectSparseFormalSum<R, M> contents) {
		return new ObjectSparseFormalSum<R, M>(contents);
	}
	
	/**
	 * This function creates a new formal sum initialized with the given coefficient
	 * and object arrays.
	 * 
	 * @param coefficients an array of coefficients 
	 * @param objects an array of basis elements
	 * @return a new formal sum initialized with the given coefficients and basis elements
	 */
	public ObjectSparseFormalSum<R, M> createNewSum(R[] coefficients, M[] objects) {
		ObjectSparseFormalSum<R, M> sum = new ObjectSparseFormalSum<R, M>();
		
		for (int i = 0; i < coefficients.length; i++) {
			addObject(sum, coefficients[i], objects[i]);
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
	public ObjectSparseFormalSum<R, M> createNewSum(int[] coefficients, M[] objects) {
		ObjectSparseFormalSum<R, M> sum = new ObjectSparseFormalSum<R, M>();
		
		for (int i = 0; i < coefficients.length; i++) {
			addObject(sum, ring.valueOf(coefficients[i]), objects[i]);
		}
		
		return sum;
	}
	
	}
