package org.appliedtopology.tda4j.io;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.appliedtopology.tda4j.homology.chain_basis.SimplexComparator;
import org.appliedtopology.tda4j.metric.impl.EuclideanMetricSpace;
import org.appliedtopology.tda4j.streams.impl.ExplicitSimplexStream;
import org.appliedtopology.tda4j.streams.impl.GeometricSimplexStream;
import org.appliedtopology.tda4j.algebra.autogen.array.DoubleArrayUtility;

public class ObjReader implements ObjectReader<GeometricSimplexStream> {

	public GeometricSimplexStream importFromFile(String path) {
		BufferedReader reader = null;
		String line = null;
		String[] entries = null;
		
		List<double[]> vertices = new ArrayList<double[]>();
		List<int[]> faces = new ArrayList<int[]>();
		try {
			reader = new BufferedReader(new FileReader(path));

			// continue reading the data
			while ((line = reader.readLine()) != null) {
				// split the line into the individual tokens
				entries = line.split(" ");
				
				if (entries.length == 0) {
					continue;
				}
				
				String prefix = entries[0];
				
				if (prefix.equals("#")) {
					// comment
					continue;
				} else if (prefix.equals("v")) {
					// vertex
					double[] vertex = new double[entries.length - 1];
					for (int i = 1; i < entries.length; i++) {
						vertex[i - 1] = Double.parseDouble(entries[i]);
					}
					vertices.add(vertex);
				} else if (prefix.equals("f")) {
					// face
					int[] face = new int[entries.length - 1];
					for (int i = 1; i < entries.length; i++) {
						face[i - 1] = Integer.parseInt(entries[i]) - 1;
					}
					faces.add(face);
				} else {
					// unsupported prefix - just continue
					continue;
				}
			}

			// close the reader if necessary
			if (reader != null) {
				reader.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		EuclideanMetricSpace metricSpace = new EuclideanMetricSpace(DoubleArrayUtility.toMatrix(vertices));
		ExplicitSimplexStream stream = new ExplicitSimplexStream(SimplexComparator.getInstance());
		
		for (int i = 0; i < vertices.size(); i++) {
			stream.addVertex(i);
		}
		
		for (int[] face: faces) {
			stream.addElement(new int[]{face[0], face[1]});
			stream.addElement(new int[]{face[0], face[2]});
			stream.addElement(new int[]{face[1], face[2]});
			stream.addElement(face);
		}
		
		return new GeometricSimplexStream(stream, metricSpace);
	}

}
