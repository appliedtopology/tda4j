# Build the TDA4j papers. Needs a TeX Live with latexmk and bibtex.
#   make journal   the journal article  (papers/journal/main.pdf)
#   make todo      everything still pending: red/blue/orange markers, missing benchmark keys, unchecked citations
#   make results RESULTS="path/to/results-dir [more dirs]"   regenerate common/results.tex from the harness output
#   make clean

.PHONY: journal todo results clean

journal:
	cd journal && latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex

todo:
	python3 -I tools/todo-inventory.py

results:
	python3 -I tools/bench-to-tex.py $(RESULTS)

clean:
	cd journal && latexmk -C main.tex
