package org.appliedtopology.tda4j.example_tests;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.appliedtopology.tda4j.examples.SimplexStreamExamples;
import org.appliedtopology.tda4j.homology.HomTester;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.streams.interfaces.AbstractFilteredStream;

public class HomVerificationTest {

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void dumpHomInformation() {
		AbstractFilteredStream<Simplex> domain = SimplexStreamExamples.getCircle(3);
		AbstractFilteredStream<Simplex> codomain = SimplexStreamExamples.getCircle(3);
		HomTester.dumpHomInformation(domain, codomain);
	}
}
