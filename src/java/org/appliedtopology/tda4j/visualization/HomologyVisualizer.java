package org.appliedtopology.tda4j.visualization;

import java.awt.Color;
import java.util.List;

import processing.core.PApplet;
import org.appliedtopology.tda4j.api.Plex4;
import org.appliedtopology.tda4j.homology.barcodes.BarcodeCollection;
import org.appliedtopology.tda4j.homology.barcodes.Interval;
import org.appliedtopology.tda4j.homology.chain_basis.Simplex;
import org.appliedtopology.tda4j.homology.interfaces.AbstractPersistenceAlgorithm;
import org.appliedtopology.tda4j.streams.impl.VietorisRipsStream;

@SuppressWarnings("serial")
public class HomologyVisualizer extends AbstractVisualizer {

	public static void main(final String[] args) {
		PApplet.main(new String[] { "org.appliedtopology.tda4j.visualization.HomologyVisualizer" });
	}

	private BarcodeCollection<Double> intervals = null;

	@Override
	void computeVietorisRipsHomology() {
		final int numDivisions = 1000;
		stream = new VietorisRipsStream<double[]>(metricSpace, maxFiltrationValue, dims.maxDimension + 1, numDivisions);
		stream.finalizeStream();
		stream = Plex4.createVietorisRipsStream(pointsComp, dims.maxDimension + 1, maxFiltrationValue, numDivisions);
		fc = stream.getConverter();
		AbstractPersistenceAlgorithm<Simplex> persistenceAlgorithm = Plex4.getDefaultSimplicialAlgorithm(dims.maxDimension + 1);
		intervals = persistenceAlgorithm.computeIntervals(stream);
		println(intervals);
	}

	@Override
	boolean hasIntervals() {
		return (intervals != null);
	}

	@Override
	List<Interval<Double>> getIntervalsAtDim(int dimension) {
		return intervals.getIntervalsAtDimension(dimension);
	}

	@Override
	void drawDiagram(final ImageRegion.Int region) {
		switch (plottype) {
		case BARCODE:
			break;
		case DIAGRAM:
			for (final int visibleDim : dims.visibleDims) {
				final List<Interval<Double>> ints = intervals.getIntervalsAtDimension(visibleDim);
				for (final Interval<Double> interval : ints) {
					fill(Color.decode(colors[visibleDim][1]).getRed(), Color.decode(colors[visibleDim][1]).getGreen(), Color.decode(colors[visibleDim][1])
							.getBlue());
					if (interval.getStart() > filtrationValue)
						fill(Color.decode(colors[visibleDim][2]).getRed(), Color.decode(colors[visibleDim][2]).getGreen(), Color.decode(colors[visibleDim][2])
								.getBlue());
					final double xpos = interval.getStart() / maxFiltrationValue;
					final int x = region.xoffset + (int) (xpos * region.width);
					int y;
					if (interval.isRightInfinite())
						y = region.yoffset;
					else {
						if (interval.getEnd() < filtrationValue)
							fill(Color.decode(colors[visibleDim][0]).getRed(), Color.decode(colors[visibleDim][0]).getGreen(),
									Color.decode(colors[visibleDim][0]).getBlue());
						final double ypos = interval.getEnd() / maxFiltrationValue;
						y = region.yoffset + region.height - (int) (region.width * ypos);
					}
					ellipse(x, y, pointSize, pointSize);
				}
			}
			break;
		}
	};

	@Override
	void addTo3Dplot() {
	}

}
