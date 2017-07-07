package org.appliedtopology.tda4j.unit_tests;

import junit.framework.*;

import org.appliedtopology.tda4j.streams.SimplexStream;
import org.appliedtopology.tda4j.streams.ExplicitSimplexStream;
import org.appliedtopology.tda4j.Simplex;
import org.appliedtopology.tda4j.homology.IntegerPersistentHomology;
import org.appliedtopology.tda4j.homology.BarcodeCollection;
import org.appliedtopology.tda4j.homology.Barcode;


/**
 * Created by mik on 7/7 ,2017.
 */
public class ExplicitStreamTest extends TestCase {
    @Test
    public void testExplicitStreamHomology() throws Exception {
        SimplexStream stream = new ExplicitSimplexStream();

        stream.add(new Simplex(0));
        stream.add(new Simplex(0,1,2), 1);
        stream.add(new Simplex(0,3), 3);
        stream.add(new Simplex(1,3), 4);

        assertTrue(stream.size() == 10);

        BarcodeCollection barcode = IntegerPersistentHomology(stream);

        assertTrue(barcode.contains(new Barcode(1, 4, null)));
        assertTrue(barcode.contains(new Barcode(0, 0, 3)));
    }
}
