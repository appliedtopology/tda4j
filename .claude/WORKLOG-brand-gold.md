# Brand: brighter gold, white not cream (2026-10-28)

Point-in-time snapshot. After the teal change (`WORKLOG-brand-teal.md`) the first printed stickers arrived (the three
square badges, still in the slate palette). The project lead: the gold sticker, with the light-mode gold `#a67f07` as its
field, "looks much too brown"; that gold on a cream card "doesn't look punchy enough"; and cream should not get as much
use as it does: white on stickers, as on plots for publication. Values and rules: `brand/DECISIONS.md`.

## Why the gold printed brown

`#a67f07` is OKLCH L 0.62, C 0.125, hue 87. At that lightness (the one that holds 3:1 on white) it is about as chromatic
as sRGB allows: the in-gamut maximum chroma at L 0.66 is 0.134-0.142 over hues 70-85. So a gold that holds 3:1 on white is
a dark yellow, and dark yellow reads olive-brown, most of all as a printed field. The fix is to stop asking the logo's
gold to hold 3:1 (a logotype is exempt) and let charts, which need it, keep it.

## Choices (all rendered side by side before being chosen)

- Logo gold on white: candidates `#c79608` (2.69:1), `#d6a20a` (2.33), `#dc9e08`, `#e5ae0d` (2.02), `#f0c647`; `#d6a20a`
  is the gold that still has the old hue (85°) and clearly reads as gold.
- Gold sticker field: `#f0c647` looked soft, `#f5c518` lemon, `#f2b705` rich and punchy. Partner colors: UI teal `#205976`
  (4.2:1), white for the "4" and right ball (1.8:1). A thin teal outline on the white "4" was tried and looked like a double
  edge. Both balls get a white 0.4 lens: the old teal-tinted fill over gold turned olive.
- Chart gold: the most chromatic gold that is 3:1 on cream is `#b58605` (3.07; 3.3 on white). `Palette.brand` series 1 and the
  gold slot of `fam_colors` use it; validated (light, cream, first three slots on all pairs, all eight adjacent; and
  `fam_colors` on white, all pairs: CVD 8.7, normal 16.7). It also separates from the brick slot better: 8.5 -> 11.7.
- `#bd8703` / `#c38406` (3.17 on white) would pass on white but are 2.96:1 on cream, so they cannot be the default
  palette's gold.

## White instead of cream

`badge-lowercase.svg`, `badge-sticker-teal.svg`, `large-logo*.svg` use `#ffffff` where they used `#f8f7f4`. Not changed, and
named in `DECISIONS.md`: the docs site's `--background-default`, the live viewer's page, and `Palette.brand`'s light surface
(cream). `PlotGrammarSpec` pins that `Palette.print` is white and not the brand's surface, so flipping the default surface is
a decision of its own (#253), not a side effect of this one.

## Not verified

Print: nothing here was printed. The text of the badges is still live `<text>` (outline it before sending to a printer,
as `DECISIONS.md` says).

## Addendum (2026-10-29, same PR, before merge)

The project lead on the cream question: cream is fine as a display choice; a plot deployed elsewhere should have a
transparent background. `Style(transparent = true)` (`Style.clear`, `Figure.withTransparentBackground()`) draws no
background rect, but it is off by default, so a saved `.svg` of `Palette.brand` still has the cream rect. Whether saved
files should default to transparent is the open question of #253 (renamed). `DECISIONS.md` states the rule.
