package org.appliedtopology.tda4j.io;

import java.io.IOException;

public interface ObjectReader<T> {
	T importFromFile(String path) throws IOException;
}
