# TDA4j identity: lockups, size and spacing

These are practical production defaults, not results of user-recognition or print testing. The current mark and palette decisions are recorded in [DECISIONS.md](DECISIONS.md). This pre-1.0 identity remains open to deliberate revision: a previous print failure is evidence about that treatment, not a blanket ban on a color in every role.

## Lockups and use

- **Horizontal:** `horizontal-logo.svg` and `horizontal-logo-dark.svg`. Use in wide headers, README mastheads, slides, and footers when there is room for the name. The icon retains the standard (not favicon) geometry, and the lowercase bold JetBrains Mono wordmark keeps the gold 4. Transparent background.
- **Compact horizontal:** `horizontal-logo-compact.svg` and `horizontal-logo-compact-dark.svg`. Text-led option for navigation and tighter headers: circle outlines are doubled from 3 to 6 units and the connecting line increases from 4 to 7 units, retaining the circle centers, overlap, and dots. Compare at actual size before choosing between the two horizontal signatures.
- **Stacked:** `large-logo.svg` and `large-logo-dark.svg`. Use in square-ish hero placements. Transparent background.
- **Standalone mark:** `icon.svg` and `icon-dark.svg` for compact placements; `favicon.svg` and `favicon-dark.svg` at 16–32 px, with deliberately heavier strokes.
- **Badges/stickers:** the three square `badge-*.svg` variants are complete compositions. Do not crop their text or extract their icon to replace the standard mark.

Choose light or dark treatments for their intended surfaces. Preserve the teal/gold relationship, but choose the *tone* for the role and medium: brand-mark colors (`#1a729b` / `#d6a20a`) are a bright baseline on white, while UI-safe teal/gold (`#205976` / `#866504`) are useful higher-contrast comparison colors. The selected white sticker instead uses **UI-safe teal `#205976` with brighter brand gold `#d6a20a`**. Do not silently mix tiers in existing approved masters; create a named variant and compare it side by side at identical size. The chart palette is optimized for data marks and is not automatically the logo palette.

## Color treatment: decisions versus experiments

- **Approved current assets:** retain their existing values unless an explicit revision is accepted. Their hex values are reproducible references, not immutable laws.
- **White-field decision:** the horizontal white sticker uses UI-safe teal `#205976`, brand-mark gold `#d6a20a`, and 40% fill opacity in both circles. In the side-by-side discussion, denser interiors were clearly preferred, and the hybrid palette was chosen over both uniform tiers. This is an **asset-specific selection**, not a global recoloring of the icon, docs, charts, or existing square badges. The khaki/brown *full-field* print result does not establish how `#866504` behaves as a small accent on white. Test the exact SVG colors, not a generative image approximation.
- **Full-bleed fields:** bright teal (`#1a729b`) and bright sticker gold (`#f2b705`) remain the current recipes. On the gold field, the selected wordmark is **white `tda`/`j` with UI teal `#205976` for the `4`** in both square and horizontal stickers; the circle colors, geometry, and fills remain unchanged. This makes the `4` the high-contrast accent and mirrors the teal sticker's white text / gold `4` logic. The trade-off is deliberate: white text against this gold is only about 1.8:1, so the wordmark is less robust at small sizes and in print than the previous teal-text treatment. Do not use the darker UI gold as a full sticker field without new evidence.
- **Fill opacity:** the original 0.16/0.18 translucent lenses felt washed out on white despite heavy outlines; 0.40/0.40 was preferred in the white horizontal sticker. This is a medium/layout decision, not a mandate to increase opacity in every master. In future comparisons vary opacity independently from color whenever possible.
- **Promotion rule:** record an accepted design choice separately from its verification status. The hybrid white sticker is selected for further production work, **not yet print-validated**. Once a change is adopted across the whole identity, update `DECISIONS.md`, all affected masters, and this guide together.

## Decision framework for future brand work

This is a **working identity before 1.0 and before broad launch**. Previous decisions are documented evidence, not vetoes. When a new treatment looks stronger, explain what changed, which constraints still apply, and what has actually been verified.

### What previous rounds taught us

1. **Meaning and recognizability precede geometric literalism.** The Growing Balls mark illustrates intersection producing an edge. Its overlap makes that mechanism legible; exact tangency is not the primary design goal. Avoid changes that weaken the mark at small sizes merely to depict a mathematical instant more literally.
2. **Judge the complete composition, not just hex codes.** The same gold may read khaki as a large printed field but deliberate as a small accent on white. Surface, area, adjacent colors, typography, opacity, and stroke weight all affect perceived strength.
3. **Contrast requirements are role-dependent.** UI text must remain accessible, and chart marks must meet the chart palette's visibility and color-vision checks. A logo or sticker is not automatically bound to the chart or body-text tier; neither should the logo's exceptions be imported into functional UI.
4. **Optical weight is a system.** The original horizontal proposal put a 48-unit circle diameter beside 72-unit text and looked too thin. The larger-symbol variant restored the stacked lockup's 96:56 circle-to-type relationship. A compact text-led variant remains useful, but needs stronger circle outlines and edge strokes. Keep both roles available.
5. **Translucency can weaken otherwise strong colors.** On the white sticker, 16%/18% circle interiors looked washed out. A 40%/40% lens treatment gave the mark more presence. Don't confuse a pale fill with an intrinsically weak palette.
6. **Separate application surfaces from identity colors.** Cream is effective as a docs/viewer surface but was rejected for printed stickers; white is the preferred light print field. The bright gold field `#f2b705` was chosen after a darker gold printed brown. Neither observation means cream or darker gold is forbidden in all future contexts.
7. **Scientific use imposes independent constraints.** The shift from slate to teal was motivated partly by the chart palette's OKLCH chroma floor and color-vision distinguishability. Those validations remain relevant to data visualization even when an identity treatment uses a different tone.

### How to evaluate the next proposal

- **State the decision scope:** icon geometry, lockup proportions, typography, a particular sticker surface, global palette, or chart/UI theme. Don't let a local improvement silently become a universal rule.
- **Make controlled comparisons:** identical geometry, size, surface, and typeface; change one variable at a time where practical. Include the incumbent treatment and at least one meaningful alternative. Use actual SVG hex values; generative images are useful for ideation but are not reliable color or typography proofs.
- **Compare at the intended size and medium:** small navigation, README, slide, print sticker, dark mode, and plots may require different solutions. Assess both whole-composition balance and the overlap/connection detail.
- **Keep hard constraints distinct from aesthetic preferences:** preserve accessible text, data-viz contrast/CVD checks, readable small-size marks, and reproducible print assets. Treat color tier, opacity, and relative mark size as design choices open to evidence.
- **Record outcomes in three categories:** *selected* (the direction chosen), *validated* (what was actually rendered, tested, or printed), and *open* (what remains uncertain). A rendered comparison is not a print test, and a print failure in one composition is not a universal color failure.
- **Update deliberately:** retain rejected alternatives and the reason for rejection in the worklog; revise `DECISIONS.md` when a choice becomes identity-wide, and update every affected master/export. Do not rewrite historical worklogs to make the path look inevitable.

### Current decision record: gold-field stickers

**Selected:** both `badge-sticker-gold.svg` (square/vertical composition) and `sticker-horizontal-gold.svg` use the existing bright gold field `#f2b705`, white `tda`/`j`, and UI teal `#205976` for the `4`. This reverses **only the wordmark colors** from the prior gold versions; icon strokes, circle fills, geometry, type styling, and layout are unchanged. The chosen rationale is a stronger focal `4`, more playful merchandise treatment, and a clearer inverse relationship to the teal sticker.

**Trade-offs and open checks:** white-on-gold contrast is approximately 1.8:1, so four letters are less legible than in the teal-text version. The white right circle already shares this limitation. This is an intentional graphic-sticker compromise, **not** a pattern to copy into functional text or small UI labels. Review actual-size prints, material finish, and legibility at distance; no physical print proof of the reversed wordmark has been performed. Generated image previews that changed stroke widths or typography were rejected as uncontrolled evidence; the selected comparison held the SVG constant except for the text fills.

### Current decision record: white horizontal sticker

**Selected:** white field, compact heavy-stroke mark, UI-safe teal `#205976`, brighter brand gold `#d6a20a`, 40% opacity for both circle fills. This follows the user's preference for denser circle interiors and the hybrid palette in a three-way comparison. The earlier gold-print concern was specifically about a large dark-gold field; it is not evidence against using darker gold as a small accent.

**Not established:** physical print behavior, exact JetBrains Mono appearance in the preview environment, minimum legible printed size, and whether the hybrid should become the global white-background logo palette. Keep those as open questions, not implicit approvals.

**History:** see `.claude/WORKLOG-brand-teal.md` (slate-to-teal, chart chroma/CVD validation), `.claude/WORKLOG-brand-gold.md` (gold field, white versus cream, printing), and [DECISIONS.md](DECISIONS.md) (full palette, rejected concepts, and the nine-round visual artifact).

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
| `sticker-horizontal-gold.svg` | Bright sticker gold | Large symbol, white wordmark, deep-teal 4 |
| `sticker-horizontal-white.svg` | White | Selected hybrid: UI-safe teal `#205976`, bright brand gold `#d6a20a`, 40% circle lenses |
| `sticker-horizontal-dark.svg` | Dark surface | Compact heavy-stroke symbol, light-teal wordmark, light-gold 4 |

The 600 × 200 viewBox defines the intended 3:1 sticker trim, **not a print-ready bleed or cutline**. Ask the printer for bleed, safe-zone, corner-radius, color profile and cutline specifications before manufacturing; convert text to outlines and proof at actual size. The gold-field variants now use white `tda`/`j` and teal `4`; inspect the low-contrast white letters and white circle details in print rather than assuming they are safe.

## Scaling, alignment, and misuse

- Scale uniformly: preserve each SVG's aspect ratio and do not stretch or crop.
- Align lockups by their outer visual silhouette, not by a circle center or the baseline of the wordmark alone.
- Do not move the gold 4, rearrange the circles, change their overlap, or alter stroke widths independently.
- Avoid placing the mark on photographs or patterned backgrounds without a clean supporting field.
- SVGs contain live text in JetBrains Mono. A browser must load the font to match the approved wordmark; **convert text to outlines before print production**, then inspect the result. Do not assume a print vendor will resolve the web font.
- These SVGs are master assets. If a layout needs a raster export, render from the master rather than enlarging a small PNG.

## Verification still needed

Check both horizontal pairs with JetBrains Mono loaded at 180 px and at actual header sizes, on light and dark surfaces; compare visual centering and letter spacing with the stacked pair. Check clear space in real layouts. Confirm any printed use with an outlined-text proof. The numerical minimums above should be adjusted if these checks show failures.
