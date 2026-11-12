# TDA4j identity: lockups, size and spacing

These are practical production defaults, not results of user-recognition or print testing. The current mark and palette decisions are recorded in [DECISIONS.md](DECISIONS.md). This pre-1.0 identity remains open to deliberate revision: a previous print failure is evidence about that treatment, not a blanket ban on a color in every role.

## Lockups and use

- **Horizontal:** `horizontal-logo.svg` and `horizontal-logo-dark.svg`. Use in wide headers, README mastheads, slides, and footers when there is room for the name. The icon retains the standard (not favicon) geometry, and the lowercase bold JetBrains Mono wordmark keeps the gold 4. Transparent background.
- **Compact horizontal:** `horizontal-logo-compact.svg` and `horizontal-logo-compact-dark.svg`. Text-led option for navigation and tighter headers: circle outlines are doubled from 3 to 6 units and the connecting line increases from 4 to 7 units, retaining the circle centers, overlap, and dots. Compare at actual size before choosing between the two horizontal signatures.
- **Stacked:** `large-logo.svg` and `large-logo-dark.svg`. Use in square-ish hero placements. Transparent background.
- **Standalone mark:** `icon.svg` and `icon-dark.svg` for compact placements; `favicon.svg` and `favicon-dark.svg` at 16–32 px, with deliberately heavier strokes.
- **Badges/stickers:** the three square `badge-*.svg` variants are complete compositions. Do not crop their text or extract their icon to replace the standard mark.

Choose light or dark treatments for their intended surfaces. Preserve the teal/gold relationship, but choose the *tone* for the role and medium: brand-mark colors (`#1a729b` / `#d6a20a`) are a bright baseline on white, while UI-safe teal/gold (`#205976` / `#866504`) are a legitimate higher-contrast **white-field logo/sticker exploration**. Do not silently mix tiers in existing approved masters; create a named variant and compare it side by side at identical size. The chart palette is optimized for data marks and is not automatically the logo palette.

## Color treatment: decisions versus experiments

- **Approved current assets:** retain their existing values unless an explicit revision is accepted. Their hex values are reproducible references, not immutable laws.
- **White-field experiments:** darker UI-safe teal and gold can give the mark and bold wordmark more apparent weight. The khaki/brown *full-field* print result does not establish how `#866504` behaves as a small accent on white. Test the exact SVG colors, not a generative image approximation.
- **Full-bleed fields:** bright teal (`#1a729b`) and bright sticker gold (`#f2b705`) remain the current recipes. Do not use the darker UI gold as a full sticker field without new evidence.
- **Fill opacity:** the existing 0.16/0.18 translucent circle lenses can feel pale on white even with strong outlines. Compare opacity independently from color; avoid changing both at once and attributing the result to one cause.
- **Promotion rule:** keep experiments explicitly labeled until reviewed at actual size and, for physical media, print-proofed. Once chosen, update `DECISIONS.md`, master assets, and this guide together.

## Clear space

Reserve an empty margin around the visible artwork of **at least one quarter of the icon's circle diameter** (the standard icon circle is 48 SVG units across, so the minimum clear space is 12 units at native scale). This is outside the asset's own viewBox; do not confuse internal SVG whitespace with external clear space.

For the horizontal lockup, this is about **12% of its icon height** on every side. For the stacked logo and standalone mark, apply the same proportion using their rendered icon circle diameter. Keep other logos, text, and container edges outside this area. More breathing room is welcome, especially for hero placements.

## Working minimums (provisional)

| Asset | Suggested minimum rendered width | Why |
| --- | ---: | --- |
| Horizontal lockup (either variant) | 180 px | Wordmark remains the limiting feature; below this, use the standalone mark |
| Stacked large logo | 120 px | Keep the wordmark readable |
| Standard standalone icon | 48 px | Preserve the finer outlines and central dots |
| Favicon-specific icon | 16 px | Designed with heavier strokes for this range |
| Square badge | 128 px | Badge text is part of the composition |

These are conservative starting points, **not verified readability thresholds**. Test at final output size and medium. If the wordmark is not clearly readable, increase size or use the icon-only asset rather than redrawing it.

## Horizontal sticker concepts

Four 3:1 rounded-rectangle compositions are available as editable SVG masters. Cream is reserved for docs/viewer surfaces, **not sticker fields**, as recorded in `.claude/WORKLOG-brand-gold.md`:

| File | Field | Composition |
| --- | --- | --- |
| `sticker-horizontal-teal.svg` | Brand teal | Large symbol, white wordmark, warm-gold 4 |
| `sticker-horizontal-gold.svg` | Bright sticker gold | Large symbol, deep-teal wordmark, white 4 |
| `sticker-horizontal-white.svg` | White | Compact heavy-stroke symbol, brand-mark teal/gold; compare with UI-tone study |
| `sticker-horizontal-dark.svg` | Dark surface | Compact heavy-stroke symbol, light-teal wordmark, light-gold 4 |

The 600 × 200 viewBox defines the intended 3:1 sticker trim, **not a print-ready bleed or cutline**. Ask the printer for bleed, safe-zone, corner-radius, color profile and cutline specifications before manufacturing; convert text to outlines and proof at actual size. The gold-field variant intentionally uses the existing white 4 and gold circle; inspect those low-contrast elements in print rather than assuming they are safe.

## Scaling, alignment, and misuse

- Scale uniformly: preserve each SVG's aspect ratio and do not stretch or crop.
- Align lockups by their outer visual silhouette, not by a circle center or the baseline of the wordmark alone.
- Do not move the gold 4, rearrange the circles, change their overlap, or alter stroke widths independently.
- Avoid placing the mark on photographs or patterned backgrounds without a clean supporting field.
- SVGs contain live text in JetBrains Mono. A browser must load the font to match the approved wordmark; **convert text to outlines before print production**, then inspect the result. Do not assume a print vendor will resolve the web font.
- These SVGs are master assets. If a layout needs a raster export, render from the master rather than enlarging a small PNG.

## Verification still needed

Check both horizontal pairs with JetBrains Mono loaded at 180 px and at actual header sizes, on light and dark surfaces; compare visual centering and letter spacing with the stacked pair. Check clear space in real layouts. Confirm any printed use with an outlined-text proof. The numerical minimums above should be adjusted if these checks show failures.
