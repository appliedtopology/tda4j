package org.appliedtopology.tda4j.io;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.appliedtopology.tda4j.homology.barcodes.Interval;
import org.appliedtopology.tda4j.homology.barcodes.PersistenceInvariantDescriptor;
import org.appliedtopology.tda4j.visualization.BarcodeVisualizer;


public class BarcodeWriter {
	private static final BarcodeWriter instance = new BarcodeWriter();
	
	private BarcodeWriter() {}
	
	public static BarcodeWriter getInstance() {
		return instance;
	}
	
	public <G> void writeToFile(PersistenceInvariantDescriptor<Interval<Double>, G> object, int dimension, double endPoint, String caption, String path) throws IOException {
		String label = String.format("%s (Dimension: %d)", caption, dimension);
		BufferedImage image = BarcodeVisualizer.drawBarcode(object.getIntervalsAtDimension(dimension), label, endPoint);
		BufferedImageWriter.getInstance(BufferedImageWriter.getDefaultEncoderFormat()).writeToFile(image, path);
	}

	public String getExtension() {
		return BufferedImageWriter.getDefaultEncoderFormat();
	}

}
