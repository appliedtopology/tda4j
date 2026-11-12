# TDA4j identity: lockups, size and spacing

These are practical production defaults, not results of user-recognition or print testing. The approved mark and palette remain defined in [DECISIONS.md](DECISIONS.md).

## Lockups and use

- **Horizontal:** `horizontal-logo.svg` and `horizontal-logo-dark.svg`. Use in wide headers, README mastheads, slides, and footers when there is room for the name. The icon retains the standard (not favicon) geometry, and the lowercase bold JetBrains Mono wordmark keeps the gold 4. Transparent background.
- **Stacked:** `large-logo.svg` and `large-logo-dark.svg`. Use in square-ish hero placements. Transparent background.
- **Standalone mark:** `icon.svg` and `icon-dark.svg` for compact placements; `favicon.svg` and `favicon-dark.svg` at 16–32 px, with deliberately heavier strokes.
- **Badges/stickers:** the three square `badge-*.svg` variants are complete compositions. Do not crop their text or extract their icon to replace the standard mark.

Choose the light asset on white or a light surface, and the dark asset on `#161b1e` or an equivalent dark surface. Do not recolor individual parts or substitute chart-safe gold for the wordmark gold.

## Clear space

Reserve an empty margin around the visible artwork of **at least one quarter of the icon's circle diameter** (the standard icon circle is 48 SVG units across, so the minimum clear space is 12 units at native scale). This is outside the asset's own viewBox; do not confuse internal SVG whitespace with external clear space.

For the horizontal lockup, this is about **12% of its icon height** on every side. For the stacked logo and standalone mark, apply the same proportion using their rendered icon circle diameter. Keep other logos, text, and container edges outside this area. More breathing room is welcome, especially for hero placements.

## Working minimums (provisional)

| Asset | Suggested minimum rendered width | Why |
| --- | ---: | --- |
| Horizontal lockup | 180 px | Wordmark remains the limiting feature; below this, use the standalone mark |
| Stacked large logo | 120 px | Keep the wordmark readable |
| Standard standalone icon | 48 px | Preserve the finer outlines and central dots |
| Favicon-specific icon | 16 px | Designed with heavier strokes for this range |
| Square badge | 128 px | Badge text is part of the composition |

These are conservative starting points, **not verified readability thresholds**. Test at final output size and medium. If the wordmark is not clearly readable, increase size or use the icon-only asset rather than redrawing it.

## Scaling, alignment, and misuse

- Scale uniformly: preserve each SVG's aspect ratio and do not stretch or crop.
- Align lockups by their outer visual silhouette, not by a circle center or the baseline of the wordmark alone.
- Do not move the gold 4, rearrange the circles, change their overlap, or alter stroke widths independently.
- Avoid placing the mark on photographs or patterned backgrounds without a clean supporting field.
- SVGs contain live text in JetBrains Mono. A browser must load the font to match the approved wordmark; **convert text to outlines before print production**, then inspect the result. Do not assume a print vendor will resolve the web font.
- These SVGs are master assets. If a layout needs a raster export, render from the master rather than enlarging a small PNG.

## Verification still needed

Check the horizontal pair with JetBrains Mono loaded at 180 px and at actual header sizes, on light and dark surfaces; compare visual centering and letter spacing with the stacked pair. Check clear space in real layouts. Confirm any printed use with an outlined-text proof. The numerical minimums above should be adjusted if these checks show failures.
