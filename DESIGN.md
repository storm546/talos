# Talos design system (2026-11-24)

Direction chosen by the owner: **instrument panel**. The phone is a calm measuring instrument for one person's
training, food and recovery. The bar is the owner's Hermes Companion app: graphite ground, surface tiers instead of
strokes, Space Grotesk for words, JetBrains Mono with tabular numerals for every number, one accent, signal colours
that carry meaning only, and motion only when data changes.

## Principles

1. **First viewport = the day.** Today opens on numbers, not on a greeting, buttons or a paragraph. A screen leads with
   what was measured, then what it means, then what to do.
2. **Number, picture, conclusion.** Every metric shows its reading, a small picture of its context (gauge segment,
   ring against target, 7-day bars, sparkline) and the server's finding in words. Never a bare number with a label.
3. **Rows, not cards.** Dense one-line instrument rows in one surface with hairline dividers. A card is reserved for
   the one panel that leads the screen (readiness). No card grids of equal tiles, no nested cards.
4. **Explain on demand.** Long text lives behind a tap: a finding's full sentence opens in a bottom sheet with "Open
   details". The model's daily summary shows three lines and expands on tap.
5. **Honest pictures.** No invented scores. Readiness is drawn as its factors (one arc segment each, coloured by
   better / usual / worse than the person's own usual).

## Tokens

- Ground and surfaces: `paper` (ground), `surface` (rows, sheets), `hero` (the leading panel, dark in both themes).
- Text: `ink` (primary), `muted` (secondary); never below 12sp on a phone.
- Accent: `accent` (orange) for the single primary action, ring fills and the current-value dot. Nothing else.
- Signal: `ready` / `hero_good` = better or done; `warning` / `signal` = watch; `line` = neutral marker.
- Type: Space Grotesk (words; 22 screen title, 17 panel title, 15 row label, 12 secondary); JetBrains Mono for
  readings (17 row, 15 vitals, ring centre scales to the ring).
- Shape: 20dp leading panel, 22dp pill actions, 14dp sheets; 44–48dp touch targets.

## Components (`app/.../Glance.kt`)

- `FactorGauge`: 240° arc, one segment per readiness factor, status word in the middle, "4 of 4" under it.
- `vitalsPanel`: gauge + vitals column (reading, ▲/▼/• against the usual) + finding title + "Not feeling 100%?".
- `DayRing` / `dayRings`: kcal of target, protein of target, 7-day load against the usual week.
- `metricRow` / `metricRows`: tone dot · label + finding · sparkline or bars · mono reading + unit.
- `Spark`: line with end dot, or 7 bars with today in accent.
- `actionChips`: horizontally scrolling pills; the first is the primary (filled accent).
- `findingSheet`: the full findings and "Open details".

## Motion

Gauges, rings and sparklines sweep in over 600 ms with `settle` (PathInterpolator .2, 0, 0, 1) when they appear;
pills scale on press. Nothing loops. All motion honours the system animator scale.

## Bans

Eyebrow labels above headings; grids of identical cards; purple or blue-violet gradients; green-and-white palettes;
glass; text under 12sp; colour used for decoration; numbers in a proportional font.
