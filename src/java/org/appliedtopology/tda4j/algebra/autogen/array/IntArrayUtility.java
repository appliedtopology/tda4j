package org.appliedtopology.tda4j.algebra.autogen.array;


import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class IntArrayUtility {
	public static int[] createArray(int length) {
		return new int[length];
	}
	
	public static int[] createArray(int length, int filler) {
		int[] result = new int[length];
		for (int i = 0; i < length; i++) {
			result[i] = filler;
		}
		return result;
	}

	public static int[][] createMatrix(int rows, int columns) {
		int[][] result = new int[rows][];
		
		for (int i = 0; i < rows; i++) {
			result[i] = new int[columns];
		}
		
		return result;
	}
	
	public static int[][] createMatrix(int rows, int columns, int filler) {
		int[][] result = new int[rows][];
		
		for (int i = 0; i < rows; i++) {
			result[i] = new int[columns];
			for (int j = 0; j < columns; j++) {
				result[i][j] = filler;
			}
		}
		
		return result;
	}
	
	public static String toString(int[] array) {
		StringBuilder builder = new StringBuilder();
		builder.append('[');
		for (int i = 0; i < array.length; i++) {
			if (i > 0) {
				builder.append(", ");
			}
			builder.append(array[i]);
		}
		builder.append("]");
		return builder.toString();		
	}
	
	public static String toString(int[][] array) {
		StringBuilder builder = new StringBuilder();
		builder.append('[');
		for (int i = 0; i < array.length; i++) {
			for (int j = 0; j < array[i].length; j++) {
				if (j > 0) {
					builder.append(", ");
				}
				builder.append(array[i][j]);
			}
			builder.append(";\n");
		}
		builder.append("]\n");
		return builder.toString();
	}
	
	public static int[][] toMatrix(Collection<int[]> collection) {
        int[][] result = new int[collection.size()][];
        int index = 0;
        
        for (int[] array: collection) {
        	result[index++] = array;
        }
        
        return result;
    }
    
    public static List<Integer> toList(int[] array) {
		List<Integer> list = new ArrayList<Integer>();
		for (int i = 0; i < array.length; i++) {
			list.add(array[i]);
		}
		return list;
	}
}
