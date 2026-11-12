# Site header: large-ring horizontal identity (2026-11-12)

Started from a clean clone of `scala` at `ec25fa71`, the merge of #327. GitHub confirmed #327 merged
2026-11-12 04:21:31 UTC. Read CLAUDE.md, the brand teal/gold worklogs, docs migration/fixes and current
subsystem rules, and the brand guidelines/decision record before changing the header.

The project lead refined the request during implementation: retain the existing HTML `tda4j` in the
header and adapt the symbol/text composition toward the large-ring horizontal lockup.

## Implementation

- `brand/build-assets.cjs` extracts the symbol from the approved large-ring horizontal masters,
  removing their text and cropping to `viewBox="0 20 184 112"`. Geometry, strokes and fills are unchanged.
  The standalone icon/favicon masters and PNGs keep their own roles.
- The exported `header-mark.svg` / `header-mark_dark.svg` pair supplies `-project-logo`. ScalaDoc 3.8.4
  discovers dark siblings with `_dark`, not the masters' `-dark` convention (checked in its Resources class).
- Keep the header name as live text; wrap its `4` for the gold accent. Scope styling to the header logo:
  73.6 × 44.8 px symbol, 22.4 px bold monospace name, 7.2 px gap. This preserves the horizontal master's
  96:56 circle-diameter/type-size ratio at 0.4 scale. Prefer JetBrains Mono when available, monospace otherwise.
- Reuse `patchDocFavicon` to apply the header rules on guide and API pages. Match relative favicon paths
  as well as the root path: the old exact-root match skipped nested pages. Keep the version dropdown,
  navigation, theme controls, hero, and favicon asset selection.

## Verification

- Java 21 / Scala 3.8.4 docs build and all three scalafmt checks pass; `scalafmtAll` makes no unrelated changes.
- Both committed symbol exports reproduce byte-for-byte from the asset generator's SVG export section.
- Chromium checks home, user-guide, and nested API pages in light/dark at 1280, 768, 375, and 320 px.
  Confirm one visible loaded symbol, live name and theme-specific accent colors, preserved proportions,
  no header clipping, and no horizontal page overflow. Inspect desktop and phone screenshots.
- Changelog fragment validation, asset-generator syntax check, and `git diff --check` pass.
