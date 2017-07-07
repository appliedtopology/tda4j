package org.appliedtopology.tda4j.algebra.autogen.array;



public class LongArrayGeneration {
	/**
     * Generates an array consisting of the integers {start, ... , end - 1}
     * @param start The start element
     * @param end One past the last element
     * @return
     */
    public static long[] range(long start, long end) {
        int length = (int) (end - start);
        long[] values = new long[length];
        for (int i = 0; i < length; i++) {
                values[i] = start + i;
        }
        return values;
    }
    
    public static long[] range(long start, long end, long step) {
        int length = (int) ((end - start) / step);
        long[] values = new long[length];
        for (int i = 0; i < length; i ++) {
                values[i] = start + i * step;
        }
        return values;
    }
    
    public static long[] replicate(long value, int length) {
        long[] result = new long[length];
        for (int i = 0; i < length; i++) {
                result[i] = value;
        }
        return result;
    }
	
	}
