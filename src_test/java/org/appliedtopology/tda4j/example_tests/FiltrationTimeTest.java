package org.appliedtopology.tda4j.example_tests;

import java.util.Iterator;

import org.junit.Test;

import org.appliedtopology.tda4j.api.Plex4;
import org.appliedtopology.tda4j.homology.barcodes.BarcodeCollection;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.interfaces.AbstractPersistenceAlgorithm;
import org.appliedtopology.tda4j.streams.impl.ExplicitSimplexStream;

public class FiltrationTimeTest {
	@Test
	public void testExample() {
		
		ExplicitSimplexStream stream = Plex4.createExplicitSimplexStream(100);
		stream.addVertex(1, 17.23);
		stream.finalizeStream();
		Iterator<Simplex> iterator = stream.iterator();

		double filtrationValue = 0;

		while (iterator.hasNext()) {
			Simplex simplex = iterator.next();
			filtrationValue = stream.getFiltrationValue(simplex);
		}

		AbstractPersistenceAlgorithm<Simplex> persistence = Plex4.getModularSimplicialAlgorithm(3, 2);
		BarcodeCollection<Double> intervals = persistence.computeIntervals(stream);

		System.out.println("FiltrationValue = " + filtrationValue);
		System.out.println("intervals = " + intervals);
	}
}
