# Library enrichment sources

The importer performs platform-scoped, normalized-title exact matching only.
It does not fuzzy-match a ROM to another port or similarly named game.

- Metacritic public web backend: current critic/user aggregates, release date,
  developer, and publisher. Critic weights use the returned review count.
- GameRankings final archive snapshot (2019-12-08): historical critic aggregate,
  release year, and exact review count.
- MobyGames public platform browser capture (2026-08-03): platform-specific
  historical critic aggregate, release year, and developer. Every packaged row
  came from the public minimum-five-critic result set, so the composite uses
  five as a documented lower-bound weight rather than inventing a count.
- LaunchBox Games Database metadata: platform-exact release, developer,
  publisher, and community rating/count. Community values feed only the user
  composite, never the professional critic composite.

The displayed critic score is a 1.0–10.0 composite weighted by the documented
review count for each available professional source. User scores are normalized
separately and never substituted for missing critic data.

## Background provenance and fallback order

Every update scan records where each background came from. The importer uses
this order and never borrows media from a different title or platform:

1. A pre-audited wallpaper in `PegasusMedia/game-wallpapers`.
2. An exact first-party product-gallery image when an official provider exists.
3. An exact-title libretro `Named_Snaps` gameplay screenshot as the last resort.

Screenshot fallbacks are center-cropped to 1920×1080 without stretching and
stored separately under `PegasusMedia/game-wallpapers-screenshot-fallback`.
Pegasus metadata records `x-background-source`, `x-background-source-url`, and
`x-background-transform`. The full collection is also indexed after every scan
in `PegasusMedia/background-provenance.json`; legacy assets whose origin cannot
be proven are labeled `unclassified-existing-background`, not wallpaper.
