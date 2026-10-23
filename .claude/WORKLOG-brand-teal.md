# Brand: Slate & Gold to Teal & Gold (2026-10-23)

Point-in-time snapshot. The project lead moved the brand from slate `#456f87` to a teal built on `#1a729b`, kept the
gold, and asked for a dark-mode version in place. Rule and values: `brand/DECISIONS.md`.

## Why teal

The benchmark graphs color tool families with seven colors, `a` = tda4j (brand), `b` = tda4j-js (gold). Run through the
dataviz validator, the slate failed the chroma floor (OKLCH C 0.06, floor 0.10), and the five other families, picked
darker than the brand, were hard to tell apart. Pushing the slate to C 0.10 at its own hue (234.6°) and lightness (0.52)
gives `#1a729b`; `#1c729a`, the exact 0.10, computes to 0.0997 after hex rounding and fails, hence `#1a729b` (0.101).
With the old `c`-`g`, the purple fell to ΔE 6.2 from the new `a` under deuteranopia, so those five were searched again
(OKLCH grid inside one hue window per family, objective: worst pair under protan/deutan and normal vision). The result
passes every validator check on all pairs (light, white: CVD 9.5, normal 15.9, contrast >= 3:1; dark `#161b1e`: CVD 9.9,
normal 16.5, contrast >= 3:1). Dark `c`-`g` needed the 3:1 floor, not 4:1: at 4:1 inside the dark lightness band the
worst pair fell to CVD 7.6 / normal 12.5.

## Tiers

Same hue, the old tiers' lightness: UI-safe L 0.44 C 0.075 `#205976` (7.1:1 on cream), dark L 0.77 C 0.08 `#81bde0`
(8.5:1 on `#161b1e`). Higher chroma than slate made the body text (`--text-primary`) noticeably bluer; C 0.075 was the
compromise against C 0.095 (`#005d82`), which read as colored text.

## Where the brand colors were

Found by searching every tracked text file for each value of the old palette (and its chart and chrome derivatives),
not only the directories first named. `build.sbt` holds no color: it names the icon files (`-project-logo`, and the
favicon `<link>`s `patchDocFavicon` injects), so the change there is the dark favicon links. Changed: `brand/*.svg`
(`badge-sticker-slate.svg` renamed `badge-sticker-teal.svg`), `_assets/images/*.svg` and all PNGs, `_layouts/main.html`
(also its dark gold, `#f3d068`, which `DECISIONS.md` had darkened to `#f0c647` but the layout never took), the web
manifests, `Palette.brand` and the viewer in `plot/`, and the docs sentences that say "slate". The tutorial slides
(`_assets/presentations`) load the logo from the live site and have their own theme. Older worklogs keep their slate
values.

## Dark mode

Existing: `icon-dark.svg` / `header-icon-dark.svg`, dark manifest. Added: `favicon-dark.svg` and `favicon-dark-N.png`
(the dark `<link>`s had reused the light favicon PNGs), `large-logo-dark.svg` (front page: `.logo-light` / `.logo-dark`
images, shown by `:root.theme-dark`). Defects fixed on the way: the `header-icon-dark-*.png` were rendered on white,
and `manifest-dark.json` had white `theme_color` / `background_color`; both are on `#161b1e` now.

## PNGs

`brand/build-assets.cjs` (Playwright + the bundled Chromium) renders them; run with
`NODE_PATH=<dir with node_modules/playwright> node brand/build-assets.cjs`. Light PNGs on white (as before), dark on
`#161b1e`, opaque.

## Verified / not verified

Verified: validator runs above; `sbt "tda4j/testOnly *Plot* *Palette* *PlotLook* *Viewer*"` (248 examples, 0 failures);
rendered icons, badges and logos looked at. Not verified: the scaladoc site itself (`sbt doc` with the docs pin), so the
`<img>` logo toggle, the dark favicon links and the layout colors are untested in a browser.
