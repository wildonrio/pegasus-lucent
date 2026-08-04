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
