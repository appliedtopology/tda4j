package org.appliedtopology.tda4j.algebra.autogen.array;


import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class FloatArrayUtility {
	public static float[] createArray(int length) {
		return new float[length];
	}
	
	public static float[] createArray(int length, float filler) {
		float[] result = new float[length];
		for (int i = 0; i < length; i++) {
			result[i] = filler;
		}
		return result;
	}

	public static float[][] createMatrix(int rows, int columns) {
		float[][] result = new float[rows][];
		
		for (int i = 0; i < rows; i++) {
			result[i] = new float[columns];
		}
		
		return result;
	}
	
	public static float[][] createMatrix(int rows, int columns, float filler) {
		float[][] result = new float[rows][];
		
		for (int i = 0; i < rows; i++) {
			result[i] = new float[columns];
			for (int j = 0; j < columns; j++) {
				result[i][j] = filler;
			}
		}
		
		return result;
	}
	
	public static String toString(float[] array) {
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
	
	public static String toString(float[][] array) {
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
	
	public static float[][] toMatrix(Collection<float[]> collection) {
        float[][] result = new float[collection.size()][];
        int index = 0;
        
        for (float[] array: collection) {
        	result[index++] = array;
        }
        
        return result;
    }
    
    public static List<Float> toList(float[] array) {
		List<Float> list = new ArrayList<Float>();
		for (int i = 0; i < array.length; i++) {
			list.add(array[i]);
		}
		return list;
	}
}
