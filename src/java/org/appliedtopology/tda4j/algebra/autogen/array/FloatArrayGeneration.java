package org.appliedtopology.tda4j.algebra.autogen.array;



public class FloatArrayGeneration {
	/**
     * Generates an array consisting of the integers {start, ... , end - 1}
     * @param start The start element
     * @param end One past the last element
     * @return
     */
    public static float[] range(float start, float end) {
        int length = (int) (end - start);
        float[] values = new float[length];
        for (int i = 0; i < length; i++) {
                values[i] = start + i;
        }
        return values;
    }
    
    public static float[] range(float start, float end, float step) {
        int length = (int) ((end - start) / step);
        float[] values = new float[length];
        for (int i = 0; i < length; i ++) {
                values[i] = start + i * step;
        }
        return values;
    }
    
    public static float[] replicate(float value, int length) {
        float[] result = new float[length];
        for (int i = 0; i < length; i++) {
                result[i] = value;
        }
        return result;
    }
	
		
	/**
     * This function returns an array of equally spaced ascending points
     * starting at start and ending at end, of specified size. It is very
     * similar to the linspace function in Matlab.
     * 
     * @param start the start value
     * @param end the end value
     * @param size the size of the array to create
     * @return an array of linearly spaced points from start to end
     */
    public static float[] linspace(float start, float end, int size) {
        float multiplier = (end - start) / (size - 1);
        float[] values = new float[size];
        for (int i = 0; i < size; i++) {
                values[i] = start + i * multiplier;
        }               
        return values;
    }
	
	}
