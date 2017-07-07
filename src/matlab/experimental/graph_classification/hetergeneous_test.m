clc; clear; close all;

n = 100;
m = 10;
K = 20;

generators = cell(4, 1);

generators{1} = org.appliedtopology.tda4j.graph.random.BAGraph(n, m);
generators{2} = org.appliedtopology.tda4j.graph.random.ErdosRenyiGraph(n, 0.30);
generators{3} = org.appliedtopology.tda4j.graph.random.KNearestNeighborsGraph(n, 2, 10);
generators{4} = org.appliedtopology.tda4j.graph.random.ForestFireGraph(n, 0.3, 0.5);

%generators{3} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 1, 0.02);
%generators{5} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 2, 0.02);
%generators{6} = org.appliedtopology.tda4j.graph.random.RandomGeometricGraph(n, 3, 0.02);

[bottleneck_distances] = pairwise_graph_analysis(generators, K);

%%

label = sprintf('hetergeneous_%d_%d_%d', size(generators, 1), n, K);

[hm_handle, mds_handle] = visualize_dissimilarity_matrix(bottleneck_distances, generators, label);
