# TDA4j brand: decisions and assets

Record of the logo/color/typography exploration conducted via the Design-canvas artifact at
https://claude.ai/artifact/S6mrkKVnT8eU4MZ6ErPqdA (nine published rounds — that link holds the full visual
history: every rejected direction rendered, not just described). This file is the durable, textual summary;
the artifact is the visual one. Point new sessions/collaborators at both.

## Confirmed

**Icon: "Growing Balls"** — two circles at the instant they touch, with the connecting edge drawn at the moment
of contact. Chosen because it depicts the actual mechanism every VR-based engine in this library computes
(balls growing until they touch = an edge forms), not a static end-state. Confirmed to hold up at 16px/24px
favicon scale with bolded strokes and no redraw — the one property that eliminated every competing concept
(see "Rejected," below).

**Color base: Teal & Gold** (2026-10-23; was Slate & Gold, see "Revision" below). Two-tier palette, deliberately: a
"UI-safe" tier tuned for text/functional-element contrast (4.5:1), and a brighter "brand-mark" tier for the logo
graphic and wordmark specifically, which is a logotype and so exempt from contrast minimums (the teal mark clears 3:1
anyway; the gold mark deliberately does not, see the 2026-10-28 revision). A third tier is for charts, where marks
need 3:1 on their surface. These are related (same hue, different lightness/saturation), not unrelated colors — but
they ARE different hex values, and that distinction has to survive into `_layouts/main.html`, `plot/Color.scala` and
any future palette doc, or the tiers will drift back together the first time someone "simplifies" them.

| Role | UI-safe (site theme, text/links) | Brand-mark (logo, wordmark) | Charts (`Palette.brand`, `fam_colors`) |
|---|---|---|---|
| Teal, light | `#205976` | `#1a729b` | `#1a729b` |
| Teal, dark | `#81bde0` | `#81bde0` (one value: dark mode needs no separate brightening) | `#4aa0c7` |
| Gold, light | `#866504` | `#d6a20a` | `#b58605` |
| Gold, dark | `#f0c647` | `#f0c647` (see darkening note below) | `#b98e1b` |

Solid gold field (the gold sticker): `#f2b705`.

Surfaces: white `#ffffff` for things that are printed (stickers, badges, PNG icons, `Palette.print`), cream `#f8f7f4`
for display (the docs site, the live viewer and `Palette.brand`'s figures), blue-black `#161b1e` for dark mode. A plot
deployed elsewhere (a paper, a slide, another page) has a transparent background, so it takes the page's color;
`fam_colors` is checked on white, the page it will most often meet. Contrast: UI teal 7.6:1 on white (7.1:1 on cream), dark teal 8.5:1 on `#161b1e`, mark teal 5.3:1 on white
(5.0:1 on cream), chart gold 3.3:1 on white (3.07:1 on cream).

**Revision, Slate → Teal (2026-10-23, project lead)**: the slate brand-mark `#456f87` has OKLCH chroma 0.06, below the
0.10 floor of the data-viz palette checks, so it read as gray next to the chart colors and could not be a slot of a
validated categorical palette (benchmark graphs use the brand color for tda4j and the gold for tda4j-js). Teal keeps
slate's hue (OKLCH 234.6°) and lightness (0.52) and raises chroma to 0.10: `#1a729b` (`#1c729a`, exactly 0.10, computes
to 0.0997 after rounding to hex and fails the floor). The other tiers are the same hue at the old tiers' lightness:
UI-safe L 0.44 / C 0.075, dark L 0.77 / C 0.08. Gold is unchanged. Superseded slate values: `#456f87` (mark), `#3c5a6b`
(UI-safe), `#8fb4c7` (dark).

**Revision, brighter gold, white not cream (2026-10-28, project lead)**: the first printed stickers (the three square
badges, still in the slate palette) showed two problems. The gold sticker, `#a67f07` as the field, printed khaki-brown,
and the gold of the white card was dull. `#a67f07` (OKLCH L 0.62, C 0.125) is about as chromatic as a gold can be in sRGB
at a lightness that holds 3:1 on white: gold at that lightness is the olive-brown that printed, so punch and 3:1 pull
apart. Decisions:
- the brand-mark gold is `#d6a20a` (L 0.74, C 0.150, hue 85°, 2.33:1 on white): same hue family, a gold that reads as
  gold. A logotype is exempt from contrast minimums, and the teal beside it carries the mark's edges;
- the gold sticker's field is `#f2b705`, with UI-safe teal `#205976` (4.2:1 on the field) for the wordmark, the left ball
  and the line, and white for the "4" and the right ball (1.8:1: accepted, large bold shapes against a field, checked by
  rendering). The earlier recipe (a darker partner on a mid gold) is gone with the brown;
- charts keep 3:1 on their surface, so the chart gold is `#b58605` (the most chromatic gold at 3.07:1 on cream, 3.3:1
  on white), up from `#a68018`, and it separates better from the brick (worst color-vision ΔE 8.5 to 11.7). It is the
  gold of `Palette.brand` and of `b` in `fam_colors`;
- white replaces cream wherever something is printed or published: the white card's background and the teal sticker's
  whites were cream `#f8f7f4`. Cream stays the docs site's and the live viewer's page color.

**Gold, dark, darkened from `#f3d068` to `#f0c647`**: the original value read visibly thinner/paler than the
light-mode gold (`#866504` on cream) when compared directly — light and dark golds are meant to differ in
lightness by construction, but this was more than that, closer to a genuine weight mismatch. Same hue/sat
(45°/85%), lightness dropped from 0.68 to 0.61 — a small step, not a re-hue. The binding constraint on how far
this could go is `badge-sticker-teal.svg`'s own "4" against its `#1a729b` field (3.28:1 now; it was 3.32:1 against the
slate field this value was chosen on), which must stay clear of the 3:1 brand-mark floor, rather than the site-theme
side, which has enormous headroom (10.6:1 against the dark-mode background `#161b1e`, still far above the 4.5:1
UI-safe floor). Confirmed by rendering side by side against the `#866504` light-mode reference, not by contrast
numbers alone — the numbers only bounded the search.

Message colors (callouts: the docs site used to configure them through Laika's `messageColors`, which the scaladoc
site does not read; they stay as the reference values for any callout styling): one triad, hue-rotated off the same
gold, so all three read as a family instead of arbitrary picks. Teal was chosen for info specifically because 180°
sits close to the brand teal's own ~199° hue, reading as kin (the UI-safe teal and the info teal are 7.5 ΔE apart in
OKLab ×100, enough to tell as text colors); the earlier green (148°) didn't.

| Role | Light | Dark |
|---|---|---|
| info (teal) | `#206f6f` | `#75c7c7` |
| warning (= UI-safe gold) | `#866504` | `#f0c647` |
| error | `#812318` | `#d88279` |

Tinted callout backgrounds (`infoLight`/`warningLight`/`errorLight`), derived the same way as `themeColors`'
own `primaryLight` (very high lightness, moderate saturation, same hue as the accent):

| Light | Dark |
|---|---|
| info: `#eef5f5` | `#1a2b2b` |
| warning: `#f5f3ee` | `#2b271a` |
| error: `#f5efee` | `#2b1c1a` |

All of the above checked at 4.5:1+ (accent-on-tint and body-text-on-tint both), except the brand-mark tier,
which is checked at 3:1+ only (the graphical, non-text WCAG threshold) — it is not meant for body text.

**Wordmark: lowercase, JetBrains Mono, bold.** `tda` + `4` (gold) + `j`. JetBrains Mono is OFL 1.1 licensed —
free for any use, no attribution beyond keeping the license file with redistributed font files (moot here,
loaded from Google Fonts). Chosen over Space Mono; the "shares a foundry with Space Grotesk" argument for Space
Mono didn't hold up since Space Grotesk isn't used as body/heading type anywhere near this wordmark.

**Square badge: three live variants**, not one, because they serve different jobs:
- `badge-lowercase.svg` — white card, icon-dominant, text at real size below. For contexts where the badge is
  the whole artifact (README avatar, social preview) and needs to stand alone at a readable size.
- `badge-sticker-teal.svg` — full-bleed brand-mark teal background, inverted (white + dark-mode gold) mark and text.
  For print/stickers specifically: a light-background square, however tightly cropped, still reads as "logo on
  a white card" once printed and die-cut. Full-bleed color removes that problem outright, and costs nothing new
  — it's the already-approved dark-mode palette applied to a solid field instead of a dark background.
- `badge-sticker-gold.svg` — full-bleed gold (`#f2b705`) background. The teal sticker's trick (reuse an
  already-approved palette pair on a solid field) does NOT transfer to gold: the first print used the mid gold
  `#a67f07` as the field and came out khaki-brown, because darkening gold enough to hold a partner color turns it
  olive. A bright field and a dark partner works: UI-safe teal (`#205976`) for the wordmark, left ball and connecting
  line, white for the "4" and the right ball, white lenses in both balls. Rendered next to the other two before it was
  chosen; a thin teal outline on the white "4" was tried and rejected (it reads as a double edge).
  **Accepted exception**: white on `#f2b705` measures 1.8:1; the "4" is 56 units of bold monospace on a 192 field.

**Print caveat, stated plainly**: these SVGs use live `<text>` styled with `font-family: 'JetBrains Mono'`, which
renders correctly in a browser (where the Google Fonts stylesheet is loaded) but will NOT render correctly in
most print/sticker vendor workflows, which don't reliably embed web fonts. Before sending any badge to print,
convert the text to outlines/paths in a vector editor (Illustrator's "Create Outlines," Inkscape's "Object to
Path") first. Not done here — no vector editor available in this environment to verify the outlined result
looks right, and a botched auto-outline is worse than an honest gap.

## Rejected, with why (don't re-litigate without new information)

- **Persistence Bars** (a literal barcode icon) — genuinely close second. Killed specifically because the
  full 6-bar version blurs into a solid block below ~32px, requiring a second, simplified small-size variant
  (a `badge`/`favicon`-specific redraw) to stay legible — real ongoing maintenance that Growing Balls doesn't
  need (same geometry from 16px up, just bolder strokes). If a strong future reason favors Bars anyway, a
  4-bar simplified variant was sketched and holds up; it was never fully finished.
- **Simplex Node** (a plain triangle) — factually misleading once cubical complex support existed: this
  library is no longer simplicial-only, so a triangle-only mark actively misrepresents it.
- **Simplex + Cube** — the honest fix for the above, but visually flat/uninteresting in every color tried.
- **Persistent Loop** (a ring + one marked point) — decorative rather than conceptual; read as jewelry design,
  not topology.
- **Point Cloud → Complex** (points + partial graph) — told the right story but the first version had a filled
  triangle with one facet drawn fainter than the other two, which describes an object that can't exist in a
  simplicial complex (closed under faces). Fixed once, then dropped anyway once Growing Balls won on the
  small-size test — the fix is preserved in the artifact's round-2 history if this direction ever comes back.
- **Simplicial "4" glyph** (the numeral drawn as 5 vertices/4 edges) — clever on paper, but asking one shape to
  be both "brand icon" and "legible numeral" is exactly the double duty that broke this concept: at 16px it
  degrades to a blocky asterisk, not a recognizable 4.
- **"4j" as the entire logo, flat typography, no wordmark around it** — undercuts the actual thing "4j" refers
  to: a whole JVM-library naming convention (Neo4j and others), not a name that stands alone. Neo4j itself
  doesn't brand off "4j" in isolation, for the same reason.
- **Indigo & Ochre**, **Plum & Gold**, **Moss & Copper** — all viable for the *docs site theme* in the abstract,
  but Plum & Gold specifically read poorly once tested in the actual logo mark (plum and gold sit too close in
  lightness, so the two shapes fight for attention). Slate & Gold won on a direct three-way comparison holding
  the mark's shape constant.

## Assets in this directory

- `icon.svg` / `icon-dark.svg` — the confirmed mark, light/dark-mode mark-tier colors, translucent fills (the
  "standard" rendering, used at ~50px+ — nav bar, docs mockups, anywhere the overlap lens reads fine).
- `favicon.svg` / `favicon-dark.svg` — same geometry, opaque-ish fills and much bolder strokes, tuned for 16–32px.
- `large-logo.svg` / `large-logo-dark.svg` — mark plus wordmark, no background, for the docs front page (the layout
  shows the one that matches the reader's theme).
- `badge-lowercase.svg`, `badge-sticker-teal.svg`, `badge-sticker-gold.svg` — the three square-badge variants above.
  The teal sticker is the dark-mode palette on a solid field, so there is no separate dark badge.
- `build-assets.cjs` — copies the masters above into `_assets/images/` (`icon` → `header-icon`, `icon-dark` →
  `header-icon-dark`, the others under their own names) and renders `favicon-N.png`, `favicon-dark-N.png`,
  `header-icon-N.png` and `header-icon-dark-N.png` for N in 16, 32, 48, 64, 96, 128, 180, 192, 256, 512. Light PNGs
  are on white, dark PNGs on `#161b1e`, opaque (apple-touch-icon and the web-manifest icons must be). Run it after
  changing any master; nothing else keeps the copies in sync. `build.sbt` wires the files into the scaladoc pages
  (`patchDocFavicon`) and `_layouts/main.html` carries the theme colors; no color value lives in `build.sbt`.

## Colors outside the site

- The plot defaults (`Palette.brand`, the live viewer's accent) use the same teal and gold, stepped for charts: light
  series 0 is the brand-mark teal `#1a729b`, series 1 the chart gold `#b58605`, secondary ink the UI-safe `#205976`,
  dark secondary ink `#81bde0`. `Palette.brand`'s light surface is the cream, a display choice. A figure deployed
  elsewhere has no background: `fig.withTransparentBackground()`, `Style(transparent = true)` or `--style clear` (that
  style is not on by default, and `Palette.clear`, its pair, is black and white, not the brand colors).
- Benchmark graphs color tool families with `fam_colors`; `a` is tda4j (brand teal) and `b` is tda4j-js (brand gold).
  Light (white background; every pair at least 8.7 ΔE under protanopia/deuteranopia and 16.7 normally, 3:1 contrast):

  ```r
  fam_colors = c(a = "#1a729b", b = "#b58605", c = "#4b8efe", d = "#0c9e71", e = "#8443d3", f = "#b63039", g = "#c27cba")
  ```

  Dark (surface `#161b1e`, lightness band 0.48–0.67 of the dark charts, so `a` and `b` are the charts' dark steps
  `#4aa0c7` and `#b98e1b`, not the icon's `#81bde0`/`#f0c647`; every pair at least 9.9 ΔE and 16.5 normally):

  ```r
  fam_colors_dark = c(a = "#4aa0c7", b = "#b98e1b", c = "#295fd4", d = "#307a4f", e = "#9677fb", f = "#cf3103", g = "#904e95")
  ```

  Any reassignment of `c` to `g` passes (the checks are over all pairs). Under tritanopia the worst pair is about 5 ΔE:
  label directly or add a second encoding where that matters.
