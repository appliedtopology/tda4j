package org.appliedtopology.tda4j.algebra.autogen.formal_sum;


import java.util.Iterator;

import org.appliedtopology.tda4j.algebra.autogen.matrix.DoubleMatrixEntry;
import org.appliedtopology.tda4j.algebra.autogen.matrix.DoubleSparseMatrix;
import org.appliedtopology.tda4j.algebra.autogen.pair.ObjectObjectPair;
import gnu.trove.TObjectDoubleIterator;



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
 * @param <double> the type of the underlying commutative ring
 * @param <M> the type of the generating set of the domain
 * @param <N> the type of the generating set of the codomain
 */
public class DoubleMatrixConverter<M, N> {
	protected final DoubleVectorConverter<M> domainRepresentation;
	protected final DoubleVectorConverter<N> codomainRepresentation;
	
	/**
	 * This constructor initializes the object with bases for the domain and codomain.
	 * 
	 * @param domainBasis a collection consisting of basis elements for the domain
	 * @param codomainBasis a collection consisting of basis elements for the codomain
	 */
	public DoubleMatrixConverter(Iterable<M> domainBasis, Iterable<N> codomainBasis) {
		this(new DoubleVectorConverter<M>(domainBasis), new DoubleVectorConverter<N>(codomainBasis));
	}
	
	/**
	 * This constructor initializes the object with vector converters for the domain and codomain
	 * spaces.
	 * 
	 * @param domainRepresentation the domain converter
	 * @param codomainRepresentation the codomain converter
	 */
	public DoubleMatrixConverter(DoubleVectorConverter<M> domainRepresentation, DoubleVectorConverter<N> codomainRepresentation) {
		this.domainRepresentation = domainRepresentation;
		this.codomainRepresentation = codomainRepresentation;
	}
	
	/**
	 * This function returns the domain converter
	 * 
	 * @return the domain converter
	 */
	public DoubleVectorConverter<M> getDomainRepresentation() {
		return this.domainRepresentation;
	}
	
	/**
	 * This function returns the codomain converter.
	 * 
	 * @return the codomain converter
	 */
	public DoubleVectorConverter<N> getCodomainRepresentation() {
		return this.codomainRepresentation;
	}
	
		
	/**
	 * This function converts a formal sum to a dense array.
	 * 	
	 * @param formalSum the formal sum to convert
	 * @return an array equivalent of the sum
	 */
	public double[][] toArray(DoubleSparseFormalSum<ObjectObjectPair<M, N>> formalSum) {
		double[][] matrix = new double[this.codomainRepresentation.getDimension()][this.domainRepresentation.getDimension()];		
		
		for (TObjectDoubleIterator<ObjectObjectPair<M, N>> iterator = formalSum.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			ObjectObjectPair<M, N> basisMappingPair = iterator.key();
			int column = this.domainRepresentation.getIndex(basisMappingPair.getFirst());
			int row = this.codomainRepresentation.getIndex(basisMappingPair.getSecond());
			
			matrix[row][column] = iterator.value();
		}
		
		return matrix;
	}
	
	/**
	 * This function converts a sparse matrix to a dense array.
	 * 
	 * @param sparseMatrix the sparse matrix to convert
	 * @return an array equivalent of the sparse matrix
	 */
	public double[][] toArray(DoubleSparseMatrix sparseMatrix) {
		double[][] matrix = new double[this.codomainRepresentation.getDimension()][this.domainRepresentation.getDimension()];		
		
		for (Iterator<DoubleMatrixEntry> iterator = sparseMatrix.iterator(); iterator.hasNext(); ) {
			DoubleMatrixEntry entry = iterator.next();
			matrix[entry.getRow()][entry.getCol()] = entry.getValue();
		}
		
		return matrix;
	}
	
		
		
	/**
	 * This function converts a formal sum of pairs to a sparse matrix.
	 * 
	 * @param formalSum the formal sum to convert
	 * @return the sparse matrix representation of the formal sum
	 */
	public DoubleSparseMatrix toSparseMatrix(DoubleSparseFormalSum<ObjectObjectPair<M, N>> formalSum) {
		DoubleSparseMatrix sparseMatrix = new DoubleSparseMatrix(this.codomainRepresentation.getDimension(), this.domainRepresentation.getDimension());
		
		for (TObjectDoubleIterator<ObjectObjectPair<M, N>> iterator = formalSum.map.iterator(); iterator.hasNext(); ) {
			iterator.advance();
			ObjectObjectPair<M, N> basisMappingPair = iterator.key();
			int column = this.domainRepresentation.getIndex(basisMappingPair.getFirst());
			int row = this.codomainRepresentation.getIndex(basisMappingPair.getSecond());
			
			sparseMatrix.set(row, column, iterator.value());
		}
		
		return sparseMatrix;
	}
	
		
	/**
	 * This function converts a dense matrix to a sparse matrix.
	 * 
	 * @param matrix the dense matrix to convert
	 * @return a sparse matrix equivalent
	 */
	public DoubleSparseMatrix toSparseMatrix(double[][] matrix) {
		DoubleSparseMatrix sparseMatrix = new DoubleSparseMatrix(this.codomainRepresentation.getDimension(), this.domainRepresentation.getDimension());
		
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
								if (matrix[i][j] == 0) {
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
	public DoubleSparseFormalSum<ObjectObjectPair<M, N>> toFormalSum(DoubleSparseMatrix sparseMatrix) {
		DoubleSparseFormalSum<ObjectObjectPair<M, N>> formalSum = new DoubleSparseFormalSum<ObjectObjectPair<M, N>>();
		
		for (Iterator<DoubleMatrixEntry> iterator = sparseMatrix.iterator(); iterator.hasNext(); ) {
			DoubleMatrixEntry entry = iterator.next();
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
	public DoubleSparseFormalSum<ObjectObjectPair<M, N>> toFormalSum(double[][] matrix) {
		DoubleSparseFormalSum<ObjectObjectPair<M, N>> formalSum = new DoubleSparseFormalSum<ObjectObjectPair<M, N>>();
		
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
								if (matrix[i][j] == 0) {
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
