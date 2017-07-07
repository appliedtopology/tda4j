package org.appliedtopology.tda4j.algebra.autogen.formal_sum;


import java.util.Iterator;
import java.util.Map;

import org.appliedtopology.tda4j.algebra.autogen.matrix.ObjectMatrixEntry;
import org.appliedtopology.tda4j.algebra.autogen.matrix.ObjectSparseMatrix;
import org.appliedtopology.tda4j.algebra.autogen.pair.ObjectObjectPair;



/**
 * <p>This class computes matrix representations of module homomorphisms
 * T: F(M) -> F(N), where F(M) and F(N) are free modules on sets with 
 * underlying type M and N.</p>
 * 
 * <p>Let V = F(M), and W = F(N)</p>
 * <p>Since Hom(V, W) ~= V^* tensor W</p>, one representation of a morphism V -> W is 
 * a formal sum over tensors (v^* tensor w). In practise, we represent these
 * by elements of type ObjectObjectPair<M, N>.</p>
 * 
 * @author autogen
 *
 * @param <R> the type of the underlying commutative ring
 * @param <M> the type of the generating set of the domain
 * @param <N> the type of the generating set of the codomain
 */
public class ObjectMatrixConverter<R, M, N> {
	protected final ObjectVectorConverter<R, M> domainRepresentation;
	protected final ObjectVectorConverter<R, N> codomainRepresentation;
	
	/**
	 * This constructor initializes the object with bases for the domain and codomain.
	 * 
	 * @param domainBasis a collection consisting of basis elements for the domain
	 * @param codomainBasis a collection consisting of basis elements for the codomain
	 */
	public ObjectMatrixConverter(Iterable<M> domainBasis, Iterable<N> codomainBasis) {
		this(new ObjectVectorConverter<R, M>(domainBasis), new ObjectVectorConverter<R, N>(codomainBasis));
	}
	
	/**
	 * This constructor initializes the object with vector converters for the domain and codomain
	 * spaces.
	 * 
	 * @param domainRepresentation the domain converter
	 * @param codomainRepresentation the codomain converter
	 */
	public ObjectMatrixConverter(ObjectVectorConverter<R, M> domainRepresentation, ObjectVectorConverter<R, N> codomainRepresentation) {
		this.domainRepresentation = domainRepresentation;
		this.codomainRepresentation = codomainRepresentation;
	}
	
	/**
	 * This function returns the domain converter
	 * 
	 * @return the domain converter
	 */
	public ObjectVectorConverter<R, M> getDomainRepresentation() {
		return this.domainRepresentation;
	}
	
	/**
	 * This function returns the codomain converter.
	 * 
	 * @return the codomain converter
	 */
	public ObjectVectorConverter<R, N> getCodomainRepresentation() {
		return this.codomainRepresentation;
	}
	
		
		
	/**
	 * This function converts a formal sum of pairs to a sparse matrix.
	 * 
	 * @param formalSum the formal sum to convert
	 * @return the sparse matrix representation of the formal sum
	 */
	public ObjectSparseMatrix<R> toSparseMatrix(ObjectSparseFormalSum<R, ObjectObjectPair<M, N>> formalSum) {
		ObjectSparseMatrix<R> sparseMatrix = new ObjectSparseMatrix<R>(this.codomainRepresentation.getDimension(), this.domainRepresentation.getDimension());
		
		for (Map.Entry<ObjectObjectPair<M, N>, R> entry : formalSum.map.entrySet()) {
			ObjectObjectPair<M, N> basisMappingPair = entry.getKey();
			int column = this.domainRepresentation.getIndex(basisMappingPair.getFirst());
			int row = this.codomainRepresentation.getIndex(basisMappingPair.getSecond());
			
			sparseMatrix.set(row, column, entry.getValue());
		}
		
		return sparseMatrix;
	}
	
		
	/**
	 * This function converts a dense matrix to a sparse matrix.
	 * 
	 * @param matrix the dense matrix to convert
	 * @return a sparse matrix equivalent
	 */
	public ObjectSparseMatrix<R> toSparseMatrix(R[][] matrix) {
		ObjectSparseMatrix<R> sparseMatrix = new ObjectSparseMatrix<R>(this.codomainRepresentation.getDimension(), this.domainRepresentation.getDimension());
		
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
								if (matrix[i][j] == null) {
					continue;
				}
								sparseMatrix.set(i, j, matrix[i][j]);
			}
		}
		
		return sparseMatrix;
	}
	
	/**
	 * This function converts a sparse matrix to a formal sum of pairs.
	 * 
	 * @param sparseMatrix the sparse matrix to convert
	 * @return a formal sum equivalent
	 */
	public ObjectSparseFormalSum<R, ObjectObjectPair<M, N>> toFormalSum(ObjectSparseMatrix<R> sparseMatrix) {
		ObjectSparseFormalSum<R, ObjectObjectPair<M, N>> formalSum = new ObjectSparseFormalSum<R, ObjectObjectPair<M, N>>();
		
		for (Iterator<ObjectMatrixEntry<R>> iterator = sparseMatrix.iterator(); iterator.hasNext(); ) {
			ObjectMatrixEntry<R> entry = iterator.next();
			M domainBasisElement = this.domainRepresentation.getBasisElement(entry.getCol());
			N codomainBasisElement = this.codomainRepresentation.getBasisElement(entry.getRow());
			ObjectObjectPair<M, N> basisPair = new ObjectObjectPair<M, N>(domainBasisElement, codomainBasisElement);
			formalSum.put(entry.getValue(), basisPair);
		}
		
		return formalSum;
	}
	
	/**
	 * This function converts a dense matrix to a formal sum of pairs
	 * 
	 * @param matrix the dense matrix to convert
	 * @return an equivalent formal sum
	 */
	public ObjectSparseFormalSum<R, ObjectObjectPair<M, N>> toFormalSum(R[][] matrix) {
		ObjectSparseFormalSum<R, ObjectObjectPair<M, N>> formalSum = new ObjectSparseFormalSum<R, ObjectObjectPair<M, N>>();
		
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
								if (matrix[i][j] == null) {
					continue;
				}
								M domainBasisElement = this.domainRepresentation.getBasisElement(j);
				N codomainBasisElement = this.codomainRepresentation.getBasisElement(i);
				ObjectObjectPair<M, N> basisPair = new ObjectObjectPair<M, N>(domainBasisElement, codomainBasisElement);
				formalSum.put(matrix[i][j], basisPair);
			}
		}
		
		return formalSum;
	}
}
