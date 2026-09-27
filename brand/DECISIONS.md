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

**Color base: Slate & Gold.** Two-tier palette, deliberately: a "UI-safe" tier tuned for text/functional-element
contrast, and a brighter "brand-mark" tier for the logo graphic and wordmark specifically, which only needs to
clear WCAG's 3:1 non-text threshold, not 4.5:1. These are related (same hue, different lightness/saturation),
not two unrelated colors — but they ARE two different hex values, and that distinction has to survive into
`build.sbt` and any future palette doc, or the two tiers will drift back together the first time someone
"simplifies" it.

| Role | UI-safe (site theme, text/links) | Brand-mark (logo, wordmark) |
|---|---|---|
| Slate, light | `#3c5a6b` | `#456f87` |
| Slate, dark | `#8fb4c7` | `#8fb4c7` (unchanged — only light needed brightening) |
| Gold, light | `#866504` | `#a67f07` |
| Gold, dark | `#f0c647` | `#f0c647` (unchanged — see darkening note below) |

**Gold, dark, darkened from `#f3d068` to `#f0c647`**: the original value read visibly thinner/paler than the
light-mode gold (`#866504` on cream) when compared directly — light and dark golds are meant to differ in
lightness by construction, but this was more than that, closer to a genuine weight mismatch. Same hue/sat
(45°/85%), lightness dropped from 0.68 to 0.61 — a small step, not a re-hue. The binding constraint on how far
this could go is `badge-sticker-slate.svg`'s own "4" against its `#456f87` field: contrast there was 3.62:1 and
drops as this darkens, so the value was chosen to keep that at 3.32:1 (still clear of the 3:1 brand-mark floor)
rather than picked from the site-theme side, which has enormous headroom (11.6:1 → 10.6:1 against the dark-mode
background `#161b1e`, still far above the 4.5:1 UI-safe floor). Confirmed by rendering side by side against the
`#866504` light-mode reference, not by contrast numbers alone — the numbers only bounded the search.

Message colors (`@:callout(info\|warning\|error)`, wired via Laika's `messageColors`, entirely separate from
`themeColors`): one triad, hue-rotated off the same gold, so all three read as a family instead of arbitrary
picks. Teal was chosen for info specifically because 180° sits close to Slate's own ~202° hue, reading as kin;
the earlier green (148°) didn't.

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
- `badge-lowercase.svg` — light card, icon-dominant, text at real size below. For contexts where the badge is
  the whole artifact (README avatar, social preview) and needs to stand alone at a readable size.
- `badge-sticker-slate.svg` — full-bleed slate background, inverted (cream + dark-mode gold) mark and text.
  For print/stickers specifically: a light-background square, however tightly cropped, still reads as "logo on
  a white card" once printed and die-cut. Full-bleed color removes that problem outright, and costs nothing new
  — it's the already-approved dark-mode palette applied to a solid field instead of a dark background.
- `badge-sticker-gold.svg` — full-bleed brand-mark gold (`#a67f07`) background. The slate sticker's trick (reuse
  an already-approved palette pair on a solid field) does NOT transfer to gold: darkening gold enough to get a
  legible partner color makes it read as brown/olive, not gold — yellow-family hues collapse into a different
  color name at much higher lightness than blue-family hues do, verified by rendering candidates rather than
  assumed. Lightening the partner color the other way collapses it into the mark's own cream well before it
  clears contrast against the gold field. What actually works: UI-safe slate (`#3c5a6b`, unchanged) for the
  wordmark/primary ball/connecting line, cream (`#f8f7f4`, unchanged) for the "4"/accent ball — zero new colors.
  **Accepted exception**: `#3c5a6b` on `#a67f07` measures 1.97:1, under this system's own 3:1 brand-mark floor;
  kept anyway because the hue separation (cool slate vs. warm gold) carries it at logo weight/scale — confirmed
  by rendering side by side with `badge-sticker-slate.svg`, not by contrast ratio alone. A darker same-hue slate
  (`#253741`, 3.33:1) was tried first and rejected: it clears the number but reads as disconnected from the rest
  of the palette, which is the thing this whole two-tier system exists to avoid.

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
- `favicon.svg` — same geometry, opaque-ish fills and much bolder strokes, tuned for 16–32px. This is the file
  actually wired into the site (copied to `src/docs/images/favicon.svg`); regenerate both together if the mark
  ever changes.
- `badge-lowercase.svg`, `badge-sticker-slate.svg`, `badge-sticker-gold.svg` — the three square-badge variants
  above.

`src/docs/images/favicon.svg` and `src/docs/images/header-icon.svg` are copies of `favicon.svg` and `icon.svg`
respectively, placed there because that's Laika's actual input root (`Laika / sourceDirectories` in
`build.sbt`). They are copies, not symlinks — if the master files here change, re-copy them by hand; nothing
currently keeps them in sync automatically.
