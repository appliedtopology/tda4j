clc; clear; close all;

n = 64;
m = 2;
K = 2;
k = 4;

P = [0.6, 0.1; 0.1, 0.6];
Q = [0.3, 0.04; 0.04, 0.3];

generators = cell(7, 1);

generators{1} = org.appliedtopology.tda4j.graph.random.ErdosRenyiGraph(n, 0.10);
generators{2} = org.appliedtopology.tda4j.graph.random.StochasticBlockmodel(n, P);
generators{3} = org.appliedtopology.tda4j.graph.random.StochasticBlockmodel(n, Q);
generators{4} = org.appliedtopology.tda4j.graph.random.BAGraph(n, m);
generators{5} = org.appliedtopology.tda4j.graph.random.ForestFireGraph(n, 0.3, 0.5);
generators{6} = org.appliedtopology.tda4j.graph.random.KNearestNeighborsGraph(n, 3, k);
generators{7} = org.appliedtopology.tda4j.graph.random.TorusGraph(n, 2, k);

%generators{3} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 1, 0.02);
%generators{5} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 2, 0.02);
%generators{6} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 3, 0.02);

[bottleneck_distances] = pairwise_graph_analysis(generators, K);

%%

label = sprintf('all_%d_%d_%d', size(generators, 1), n, K);

[hm_handle, mds_handle] = visualize_dissimilarity_matrix(bottleneck_distances, generators, label);
