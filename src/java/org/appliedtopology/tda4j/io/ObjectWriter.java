package org.appliedtopology.tda4j.io;

import java.io.IOException;

public interface ObjectWriter<T> {
	void writeToFile(T object, String path) throws IOException;
	String getExtension();
}
