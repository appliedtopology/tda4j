% matlab-smoke-test.m
%
% Illustrative usage of the TDA4j MATLAB facade (org.appliedtopology.tda4j.matlab.Tda4j).
%
% NOT YET RUN AGAINST REAL MATLAB -- see WORKLOG-matlab-api.md's "What's NOT verified" section.
% This session could not drive the locally installed MATLAB R2024b headlessly to confirm that
% double[][]/String[] actually marshal the way this script assumes. Run this yourself and report
% back what breaks, if anything, before trusting the facade end to end.

% Point the JVM MATLAB embeds at the library's fat jar (built via `sbt assembly`).
javaaddpath('target/scala-3.8.4/TDA4j-0.1.3-SNAPSHOT-assembly.jar');

import org.appliedtopology.tda4j.matlab.Tda4j

% A small point cloud: unit square plus two points that stick out, so there's some interesting
% (if not hand-derived) topology.
points = [0.0 0.0; 1.0 0.0; 1.0 1.0; 0.0 1.0; 0.5 2.0; 2.0 0.5];

% Defaults: complex=vr, engine=ripser, field=Z (mod 2), maxDimension=2.
result = Tda4j.computeFromPoints(points);
barcode = result.toArray();  % N-by-3: [dimension, birth, death]
disp('Default (ripser, Z/2) barcode:');
disp(barcode);

% Same computation via the reference-grade "naive" engine -- should agree with the above.
naiveResult = Tda4j.computeFromPoints(points, {'engine', 'naive'});
disp('engine=naive barcode (should match the above, order may differ):');
disp(naiveResult.toArray());

% Distance-matrix entry point, fed this cloud's own Euclidean distances -- should also agree.
n = size(points, 1);
dist = zeros(n, n);
for i = 1:n
  for j = 1:n
    dist(i, j) = norm(points(i, :) - points(j, :));
  end
end
distResult = Tda4j.computeFromDistanceMatrix(dist);
disp('computeFromDistanceMatrix barcode (should match computeFromPoints):');
disp(distResult.toArray());

% Representative cycle for the first bar, where available.
try
  verts = result.cycleVertices(0);
  coeffs = result.cycleCoefficients(0);
  disp('Representative chain for bar 0 (simplices as vertex arrays, with coefficients):');
  disp(verts);
  disp(coeffs);
catch err
  fprintf('No representative chain for bar 0: %s\n', err.message);
end

% Alpha complex, naive engine (the only engine currently offered for complex=alpha).
alphaResult = Tda4j.computeFromPoints(points, {'complex', 'alpha'});
disp('Alpha complex (Helix backend, engine=naive) barcode:');
disp(alphaResult.toArray());

% An intentionally invalid combination -- should throw with a clear message, not hang or crash.
try
  Tda4j.computeFromPoints(points, {'complex', 'alpha', 'engine', 'ripser'});
  disp('ERROR: expected an exception for complex=alpha + engine=ripser, got none');
catch err
  fprintf('Got the expected error for complex=alpha + engine=ripser: %s\n', err.message);
end
