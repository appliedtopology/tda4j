package org.appliedtopology.tda4j.algebra.autogen.io;

import java.io.IOException;
import java.io.Writer;

import org.appliedtopology.tda4j.algebra.autogen.matrix.BooleanMatrixEntry;
import org.appliedtopology.tda4j.algebra.autogen.matrix.BooleanSparseMatrix;
import org.appliedtopology.tda4j.algebra.autogen.matrix.BooleanSparseVector;
import org.appliedtopology.tda4j.algebra.autogen.matrix.BooleanVectorEntry;


/**
 * This class contains static functions used for writing objects with underlying
 * type boolean in matlab-readable formats.
 *  
 * @author autogen
 *
 */
public class BooleanMatlabOutput {

	/**
	 * This function writes the given array as a matlab array.
	 * 
	 * @param writer the Writer object to write to
	 * @param array the array to write
	 * @param separator the separator between the entries (should be either "," or ";")
	 * @throws IOException
	 */
	public static void writeAsMatlabArray(Writer writer, boolean[] array, String separator) throws IOException {
		writer.write('[');
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				writer.write(separator);
			}
			writer.write(Boolean .toString(array[i]));
		}
		writer.write("]");
	}
	
	/**
	 * This function writes the given array as a matlab row vector.
	 * 
	 * @param writer the Writer object to write to
	 * @param array the array to write
	 * @throws IOException
	 */
	public static void writeAsMatlabRow(Writer writer, boolean[] array) throws IOException {
		writeAsMatlabArray(writer, array, ",");
	}
	
	/**
	 * This function writes the given array as a matlab column vector.
	 * 
	 * @param writer the Writer object to write to
	 * @param array the array to write
	 * @throws IOException
	 */
	public static void writeAsMatlabCol(Writer writer, boolean[] array) throws IOException {
		writeAsMatlabArray(writer, array, ";");
	}
	
	/**
	 * This function writes the given array as a matlab row vector along with an assignment
	 * to to a matlab variable.
	 * 
	 * @param writer the Writer object to write to
	 * @param array the array to write
	 * @param name the variable name to assign to
	 * @throws IOException
	 */
	public static void writeAsMatlabRow(Writer writer, boolean[] array, String name) throws IOException {
		writer.write(name + " = ");
		writeAsMatlabArray(writer, array, ",");
		writer.write(";\n");
	}
	
	/**
	 * This function writes the given array as a matlab column vector along with an assignment
	 * to to a matlab variable.
	 * 
	 * @param writer the Writer object to write to
	 * @param array the array to write
	 * @param name the variable name to assign to
	 * @throws IOException
	 */
	public static void writeAsMatlabCol(Writer writer, boolean[] array, String name) throws IOException {
		writer.write(name + " = ");
		writeAsMatlabArray(writer, array, ":");
		writer.write(";\n");
	}
	
	/**
	 * This function writes the given matrix as a matlab matrix.
	 * 
	 * @param writer the Writer object to write to
	 * @param matrix the matrix to write
	 * @throws IOException
	 */
	public static void writeAsMatlabMatrix(Writer writer, boolean[][] matrix) throws IOException {
		writer.write('[');
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				if (j > 0) {
					writer.write(", ");
				}
				writer.write(Boolean .toString(matrix[i][j]));
			}
			writer.write(";");
		}
		writer.write("]");
	}
	
	/**
	 * This function writes the given matrix as a matlab matrix along with an assignment to a matlab
	 * variable.
	 * 
	 * @param writer the Writer object to write to
	 * @param matrix the matrix to write
	 * @param name the variable name to assign to
	 * @throws IOException
	 */
	public static void writeAsMatlabMatrix(Writer writer, boolean[][] matrix, String name) throws IOException {
		writer.write(name + " = ");
		writeAsMatlabMatrix(writer, matrix);
		writer.write(";\n");
	}
	
	/**
	 * This function writes the given array as a sparse matlab column along with an assignment to a matlab
	 * variable.
	 * 
	 * @param writer the Writer object to write to
	 * @param matrix the matrix to write
	 * @param name the variable name to assign to
	 * @throws IOException
	 */
	public static void writeAsMatlabSparseCol(Writer writer, boolean[] matrix, String name) throws IOException {
		int numSparseEntry = 0;
		
		for (int i = 0; i < matrix.length; i++) {
			if (matrix[i] != false) {
				numSparseEntry++;
			}
		}
		
		int[] _tmp_i = new int[numSparseEntry];
		int[] _tmp_j = new int[numSparseEntry];
		boolean[] _tmp_s = new boolean[numSparseEntry];
		
		for (int i = 0; i < matrix.length; i++) {
			if (matrix[i] != false) {
				_tmp_i[i] = i + 1;
				_tmp_j[i] = 1;
				_tmp_s[i] = matrix[i];
			}
		}
		
		writer.write("tmp_m = " + matrix.length + ";\n");
		writer.write("tmp_n = " + 1 + ";\n");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_i, "tmp_i");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_j, "tmp_j");
		writeAsMatlabRow(writer, _tmp_s, "tmp_s");
		writer.write(name + " = sparse(tmp_i, tmp_j, tmp_s, tmp_m, tmp_n);");
	}
	
	/**
	 * This function writes the given array as a sparse matlab row along with an assignment to a matlab
	 * variable.
	 * 
	 * @param writer the Writer object to write to
	 * @param matrix the matrix to write
	 * @param name the variable name to assign to
	 * @throws IOException
	 */
	public static void writeAsMatlabSparseRow(Writer writer, boolean[] matrix, String name) throws IOException {
		int numSparseEntry = 0;
		
		for (int i = 0; i < matrix.length; i++) {
			if (matrix[i] != false) {
				numSparseEntry++;
			}
		}
		
		int[] _tmp_i = new int[numSparseEntry];
		int[] _tmp_j = new int[numSparseEntry];
		boolean[] _tmp_s = new boolean[numSparseEntry];
		
		for (int i = 0; i < matrix.length; i++) {
			if (matrix[i] != false) {
				_tmp_i[i] = 1;
				_tmp_j[i] = i + 1;
				_tmp_s[i] = matrix[i];
			}
		}
		
		writer.write("tmp_m = " + 1 + ";\n");
		writer.write("tmp_n = " + matrix.length + ";\n");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_i, "tmp_i");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_j, "tmp_j");
		writeAsMatlabRow(writer, _tmp_s, "tmp_s");
		writer.write(name + " = sparse(tmp_i, tmp_j, tmp_s, tmp_m, tmp_n);");
	}
	
	/**
	 * This function writes the given array as a sparse matlab matrix along with an assignment to a matlab
	 * variable.
	 * 
	 * @param writer the Writer object to write to
	 * @param matrix the matrix to write
	 * @param name the variable name to assign to
	 * @throws IOException
	 */
	public static void writeAsMatlabSparseMatrix(Writer writer, boolean[][] matrix, String name) throws IOException {
		int numSparseEntry = 0;
		
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				if (matrix[i][j] != false) {
					numSparseEntry++;
				}
			}
		}
		
		int[] _tmp_i = new int[numSparseEntry];
		int[] _tmp_j = new int[numSparseEntry];
		boolean[] _tmp_s = new boolean[numSparseEntry];
		
		for (int i = 0; i < matrix.length; i++) {
			for (int j = 0; j < matrix[i].length; j++) {
				if (matrix[i][j] != false) {
					_tmp_i[i] = i + 1;
					_tmp_j[i] = j + 1;
					_tmp_s[i] = matrix[i][j];
				}
			}
		}
		
		writer.write("tmp_m = " + matrix.length + ";\n");
		writer.write("tmp_n = " + matrix[0].length + ";\n");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_i, "tmp_i");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_j, "tmp_j");
		writeAsMatlabRow(writer, _tmp_s, "tmp_s");
		writer.write(name + " = sparse(tmp_i, tmp_j, tmp_s, tmp_m, tmp_n);");
	}

	/**
	 * This function writes the given sparse vector as a sparse matlab column along with an assignment to a matlab
	 * variable.
	 * 
	 * @param writer the Writer object to write to
	 * @param vector the vector to write
	 * @param name the variable name to assign to
	 * @throws IOException
	 */
	public static void writeAsMatlabSparseCol(Writer writer, BooleanSparseVector vector, String name) throws IOException {
		int numSparseEntry = vector.getNumNonzeroElements();
		
		int[] _tmp_i = new int[numSparseEntry];
		int[] _tmp_j = new int[numSparseEntry];
		boolean[] _tmp_s = new boolean[numSparseEntry];
		
		int i = 0;
		for (BooleanVectorEntry pair: vector) {
			_tmp_i[i] = pair.getIndex() + 1;
			_tmp_j[i] = 1;
			_tmp_s[i] = pair.getValue();
			i++;
		}
		
		writer.write("tmp_m = " + vector.getLength() + ";\n");
		writer.write("tmp_n = " + 1 + ";\n");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_i, "tmp_i");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_j, "tmp_j");
		writeAsMatlabRow(writer, _tmp_s, "tmp_s");
		writer.write(name + " = sparse(tmp_i, tmp_j, tmp_s, tmp_m, tmp_n);");
	}
	
	/**
	 * This function writes the given sparse vector as a sparse matlab row along with an assignment to a matlab
	 * variable.
	 * 
	 * @param writer the Writer object to write to
	 * @param vector the vector to write
	 * @param name the variable name to assign to
	 * @throws IOException
	 */
	public static void writeAsMatlabSparseRow(Writer writer, BooleanSparseVector vector, String name) throws IOException {
		int numSparseEntry = vector.getNumNonzeroElements();
		
		int[] _tmp_i = new int[numSparseEntry];
		int[] _tmp_j = new int[numSparseEntry];
		boolean[] _tmp_s = new boolean[numSparseEntry];
		
		int i = 0;
		for (BooleanVectorEntry pair: vector) {
			_tmp_i[i] = 1;
			_tmp_j[i] = pair.getIndex() + 1;
			_tmp_s[i] = pair.getValue();
			i++;
		}
		
		writer.write("tmp_m = " + 1 + ";\n");
		writer.write("tmp_n = " + vector.getLength() + ";\n");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_i, "tmp_i");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_j, "tmp_j");
		writeAsMatlabRow(writer, _tmp_s, "tmp_s");
		writer.write(name + " = sparse(tmp_i, tmp_j, tmp_s, tmp_m, tmp_n);");
	}
	
	/**
	 * This function writes the given sparse matrix as a sparse matlab matrix along with an assignment to a matlab
	 * variable.
	 * 
	 * @param writer the Writer object to write to
	 * @param vector the vector to write
	 * @param name the variable name to assign to
	 * @throws IOException
	 */
	public static void writeAsMatlabSparseMatrix(Writer writer, BooleanSparseMatrix matrix, String name) throws IOException {
		int numSparseEntry = matrix.getNumNonzeroElements();
		
		int[] _tmp_i = new int[numSparseEntry];
		int[] _tmp_j = new int[numSparseEntry];
		boolean[] _tmp_s = new boolean[numSparseEntry];
		
		int i = 0;
		for (BooleanMatrixEntry entry: matrix) {
			_tmp_i[i] = entry.getRow() + 1;
			_tmp_j[i] = entry.getCol() + 1;
			_tmp_s[i] = entry.getValue();
			i++;
		}
		
		writer.write("tmp_m = " + matrix.getNumRows() + ";\n");
		writer.write("tmp_n = " + matrix.getNumColumns() + ";\n");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_i, "tmp_i");
		IntMatlabOutput .writeAsMatlabRow(writer, _tmp_j, "tmp_j");
		writeAsMatlabRow(writer, _tmp_s, "tmp_s");
		writer.write(name + " = sparse(tmp_i, tmp_j, tmp_s, tmp_m, tmp_n);");
	}
}
