/**
 * 
 */
package org.appliedtopology.tda4j.metric.landmark;

import org.appliedtopology.tda4j.metric.interfaces.AbstractSearchableMetricSpace;
import org.appliedtopology.tda4j.utility.RandomUtility;
import gnu.trove.TIntHashSet;
import gnu.trove.TIntIterator;

/**
 * This class defines a landmark set in a finite metric space by random selection.
 * 
 * @author Andrew Tausz
 *
 * @param <T> the type of the underlying metric space
 */
public class RandomLandmarkSelector<T> extends LandmarkSelector<T> {

	/**
	 * This constructor initializes the landmark selector with a finite metric space,
	 * and a size parameter.
	 * 
	 * @param metricSpace the metric space to build the landmarks set in
	 * @param landmarkSetSize the size of the landmark set
	 */
	public RandomLandmarkSelector(AbstractSearchableMetricSpace<T> metricSpace, int landmarkSetSize) {
		super(metricSpace, landmarkSetSize);
	}
	
	@Override
	protected int[] computeLandmarkSet() {
		TIntHashSet landmarkSet = RandomUtility.randomSubset(this.landmarkSetSize, this.metricSpace.size());
		int[] indices = new int[this.landmarkSetSize];
		
		int index = 0;
		for (TIntIterator iterator = landmarkSet.iterator(); iterator.hasNext(); ) {
			indices[index] = iterator.next();
			index++;
		}
		
		return indices;
	}
}
