package org.appliedtopology.tda4j.algebra.autogen.formal_sum;



import java.util.Iterator;

import org.appliedtopology.tda4j.algebra.autogen.matrix.BooleanMatrixEntry;
import org.appliedtopology.tda4j.algebra.autogen.matrix.BooleanSparseMatrix;
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
 * @param <boolean> the type of the underlying commutative ring
 * @param <M> the type of the generating set of the domain
 * @param <N> the type of the generating set of the codomain
 */
public class BooleanMatrixConverter<M, N> {
	protected final BooleanVectorConverter<M> domainRepresentation;
	protected final BooleanVectorConverter<N> codomainRepresentation;

	/**
	 * This constructor initializes the object with bases for the domain and codomain.
	 * 
	 * @param domainBasis a collection consisting of basis elements for the domain
	 * @param codomainBasis a collection consisting of basis elements for the codomain
	 */
	public BooleanMatrixConverter(Iterable<M> domainBasis, Iterable<N> codomainBasis) {
		this(new BooleanVectorConverter<M>(domainBasis), new BooleanVectorConverter<N>(codomainBasis));
	}

	/**
	 * This constructor initializes the object with vector converters for the domain and codomain
	 * spaces.
	 * 
	 * @param domainRepresentation the domain converter
	 * @param codomainRepresentation the codomain converter
	 */
	public BooleanMatrixConverter(BooleanVectorConverter<M> domainRepresentation, BooleanVectorConverter<N> codomainRepresentation) {
		this.domainRepresentation = domainRepresentation;
		this.codomainRepresentation = codomainRepresentation;
	}
	
	/**
	 * This function returns the domain converter
	 * 
	 * @return the domain converter
	 */
	public BooleanVectorConverter<M> getDomainRepresentation() {
		return this.domainRepresentation;
	}

	/**
	 * This function returns the codomain converter.
	 * 
	 * @return the codomain converter
	 */
	public BooleanVectorConverter<N> getCodomainRepresentation() {
		return this.codomainRepresentation;
	}

	/**
	 * This function converts a formal sum to a dense array.
	 * 	
	 * @param formalSum the formal sum to convert
	 * @return an array equivalent of the sum
	 */
	public boolean[][] toArray(BooleanSparseFormalSum<ObjectObjectPair<M, N>> formalSum) {
		boolean[][] matrix = new boolean[this.codomainRepresentation.getDimension()][this.domainRepresentation.getDimension()];		

		for (Iterator<ObjectObjectPair<M, N>> iterator = formalSum.map.iterator(); iterator.hasNext(); ) {
			ObjectObjectPair<M, N> basisMappingPair = iterator.next();
			int row = this.domainRepresentation.getIndex(basisMappingPair.getFirst());
			int column = this.codomainRepresentation.getIndex(basisMappingPair.getSecond());

			matrix[row][column] = true;
		}

		return matrix;
	}

	/**
	 * This function converts a sparse matrix to a dense array.
	 * 
	 * @param sparseMatrix the sparse matrix to convert
	 * @return an array equivalent of the sparse matrix
	 */
	public boolean[][] toArray(BooleanSparseMatrix sparseMatrix) {
		boolean[][] matrix = new boolean[this.codomainRepresentation.getDimension()][this.domainRepresentation.getDimension()];		

		for (Iterator<BooleanMatrixEntry> iterator = sparseMatrix.iterator(); iterator.hasNext(); ) {
			BooleanMatrixEntry entry = iterator.next();
			matrix[entry.getRow()][entry.getCol()] = true;
		}

		return matrix;
	}


	/**
	 * This function converts a formal sum of pairs to a sparse matrix.
	 * 
	 * @param formalSum the formal sum to convert
	 * @return the sparse matrix representation of the formal sum
	 */
	public BooleanSparseMatrix toSparseMatrix(BooleanSparseFormalSum<ObjectObjectPair<M, N>> formalSum) {
		BooleanSparseMatrix sparseMatrix = new BooleanSparseMatrix(this.codomainRepresentation.getDimension(), this.domainRepresentation.getDimension());

		for (Iterator<ObjectObjectPair<M, N>> iterator = formalSum.map.iterator(); iterator.hasNext(); ) {
			ObjectObjectPair<M, N> basisMappingPair = iterator.next();
			int row = this.domainRepresentation.getIndex(basisMappingPair.getFirst());
			int column = this.codomainRepresentation.getIndex(basisMappingPair.getSecond());

			sparseMatrix.set(row, column, true);
		}

		return sparseMatrix;
	}

	/**
	 * This function converts a dense matrix to a sparse matrix.
	 * 
	 * @param matrix the dense matrix to convert
	 * @return a sparse matrix equivalent
	 */
	public BooleanSparseMatrix toSparseMatrix(boolean[][] matrix) {
		BooleanSparseMatrix sparseMatrix = new BooleanSparseMatrix(this.codomainRepresentation.getDimension(), this.domainRepresentation.getDimension());

		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				if (matrix[i][j]) {
					sparseMatrix.set(i, j, matrix[i][j]);
				}
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
	public BooleanSparseFormalSum<ObjectObjectPair<M, N>> toFormalSum(BooleanSparseMatrix sparseMatrix) {
		BooleanSparseFormalSum<ObjectObjectPair<M, N>> formalSum = new BooleanSparseFormalSum<ObjectObjectPair<M, N>>();

		for (Iterator<BooleanMatrixEntry> iterator = sparseMatrix.iterator(); iterator.hasNext(); ) {
			BooleanMatrixEntry entry = iterator.next();
			M domainBasisElement = this.domainRepresentation.getBasisElement(entry.getRow());
			N codomainBasisElement = this.codomainRepresentation.getBasisElement(entry.getCol());
			ObjectObjectPair<M, N> basisPair = new ObjectObjectPair<M, N>(domainBasisElement, codomainBasisElement);
			formalSum.put(true, basisPair);
		}

		return formalSum;
	}

	/**
	 * This function converts a dense matrix to a formal sum of pairs
	 * 
	 * @param matrix the dense matrix to convert
	 * @return an equivalent formal sum
	 */
	public BooleanSparseFormalSum<ObjectObjectPair<M, N>> toFormalSum(boolean[][] matrix) {
		BooleanSparseFormalSum<ObjectObjectPair<M, N>> formalSum = new BooleanSparseFormalSum<ObjectObjectPair<M, N>>();

		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				if (matrix[i][j] == false) {
					continue;
				}
				M domainBasisElement = this.domainRepresentation.getBasisElement(i);
				N codomainBasisElement = this.codomainRepresentation.getBasisElement(j);
				ObjectObjectPair<M, N> basisPair = new ObjectObjectPair<M, N>(domainBasisElement, codomainBasisElement);
				formalSum.put(matrix[i][j], basisPair);
			}
		}

		return formalSum;
	}
};
