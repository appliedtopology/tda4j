package org.appliedtopology.tda4j.unit_tests;

import junit.framework.*;
import junit.framework.Assert.*;

import org.appliedtopology.tda4j.streams.SimplexStream;
import org.appliedtopology.tda4j.streams.VietorisRipsStream;
import org.appliedtopology.tda4j.Simplex;
import org.appliedtopology.tda4j.homology.IntegerPersistentHomology;
import org.appliedtopology.tda4j.homology.BarcodeCollection;
import org.appliedtopology.tda4j.homology.Barcode;

/**
 * Created by mik on 7/7 ,2017.
 */
public class VietorisRipsTest {
    @Test
    public void testVietorisRipsHomology() throws Exception {
        Double[][] points = {{0.0, 0.0}, {0.0, 1.0}, {1.0, 0.0}, {1.0, 1.0}};

        SimplexStream stream = VietorisRipsStream(points, 3);

        BarcodeCollection barcode = IntegerPersistentHomology(stream);

        assertTrue(stream.size() == 15);
        assertTrue(barcode.size() == 15);

        assertTrue(barcode.contains(new Barcode(0, 0, 1)));
        assertTrue(barcode.contains(new Barcode(1,1,Math.sqrt(2))));
    }
}
