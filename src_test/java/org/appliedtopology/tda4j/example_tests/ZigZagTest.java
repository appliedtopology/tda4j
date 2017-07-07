package org.appliedtopology.tda4j.example_tests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.appliedtopology.tda4j.examples.SimplexStreamExamples;
import org.appliedtopology.tda4j.homology.barcodes.BarcodeCollection;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;
import org.appliedtopology.tda4j.homology.zigzag.HomologyBasisTracker;
import org.appliedtopology.tda4j.streams.interfaces.AbstractFilteredStream;
import org.appliedtopology.tda4j.utility.RandomUtility;
import edu.stanford.math.primitivelib.algebraic.impl.ModularIntField;
import edu.stanford.math.primitivelib.autogen.algebraic.IntAbstractField;

public class ZigZagTest {

	IntAbstractField intField = ModularIntField.getInstance(2);

	@Before
	public void setUp() {
		RandomUtility.initializeWithSeed(0);
	}

	@After
	public void tearDown() {}

	/**
	 * This function adds simplices to a simplicial complex, and then removes them in reverse order.
	 */
	@Test
	public void testAddAndRemove() {
		AbstractFilteredStream<Simplex> stream = SimplexStreamExamples.getZomorodianCarlssonExample();
		HomologyBasisTracker<Simplex> zz = new HomologyBasisTracker<Simplex>(intField, SimplexComparator.getInstance());

		List<Simplex> elements = new ArrayList<Simplex>();

		for (Simplex simplex: stream) {
			elements.add(simplex);
		}

		for (Simplex simplex: elements) {
			zz.add(simplex);
		}
		
		Collections.reverse(elements);

		for (Simplex simplex: elements) {
			zz.remove(simplex);
		}
		
		BarcodeCollection<Integer> collection = zz.getBarcodes();
		System.out.println(collection);
	}
}
