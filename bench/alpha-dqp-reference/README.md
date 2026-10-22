# Reference implementation of the dual active-set alpha complex

`ref_alpha.py` follows Carlsson and Carlsson (2024), "Computing the alpha complex using dual active set quadratic
programming", with the same variable names and control flow as the Scala port (`alpha/AlphaComplexDQP`), using dense
arrays only. The two test files check it against a Delaunay-based ground truth and a stress set:

```
cd bench/alpha-dqp-reference
python3 -m pytest test_alpha_dualqp.py test_stress_dualqp.py
```

Needs `numpy`, `scipy` and `pytest`.
