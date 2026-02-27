# Shogi Puzzler

A self-hosted Scala web application that fetches Shogi games from online platforms, analyzes them with a strong engine, and turns blunders into interactive tactical puzzles.

## Features

### Public (no login required)
- **Game Database** — browse all stored games, filter by source/status/player, sort by date, paginate
- **Game Viewer** — replay any game move by move with an interactive board, engine evaluation graph, and puzzle markers; add move comments
- **Puzzle Viewer** — solve generated puzzles interactively with hints, win-probability bars, engine analysis, and spaced-repetition deck management
- **Study Browser** — browse public opening studies with search and source filters; authenticated users additionally see all their own studies (including private ones)
- **KIF Upload** — manually import any `.kif` file into the database without logging in

### Authenticated
- **Game Fetching** — import games from Lishogi (REST API), ShogiWars (web scraping), and 81Dojo (Playwright automation); upload KIF files manually
- **Engine Analysis** — two-phase YaneuraOu analysis: shallow scan (1 s/position) → deep multi-PV (10 s) on blunder candidates
- **Study Editor** — build opening studies move by move, import Lishogi studies or KIF files, auto-reload from your games, make studies public or private
- **Puzzle Creator** — create custom puzzles from any board position, add comments and multilingual translations, accept or draft for review
- **Local Engine Analysis** — in-browser WebAssembly engine (YaneuraOu WASM) for real-time position analysis in Game Viewer and Puzzle Creator, no server round-trip needed
- **SRS Training** — spaced-repetition flashcard system for your weakest puzzle positions
- **OCR Recognition** — photograph or screenshot a Shogi board and convert it to SFEN notation
- **Configuration** — set platform nicknames, engine limits, analysis thresholds, and UI language
- **User Management** — role-based access control with per-page permissions (admin only)

### Internationalization
- Interface available in **English** and **Slovak**; additional languages require only a single JSON file

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Scala 2.13 |
| HTTP framework | [Cask](https://github.com/com-lihaoyi/cask) 0.11 |
| HTML templating | [Scalatags](https://github.com/com-lihaoyi/scalatags) |
| Database | MongoDB 5 (Scala reactive driver) |
| Shogi engine (server) | YaneuraOu NNUE |
| Shogi engine (browser) | YaneuraOu WASM (`@mizarjp/yaneuraou.k-p`) |
| Game logic | [Scalashogi](https://github.com/WandererXII/scalashogi) |
| Board rendering | [Shogiground](https://github.com/WandererXII/shogiground) |
| Browser automation | Playwright 1.49 |
| OCR | Tess4j 5.14 |
| UI framework | Bootstrap 5 (dark theme) |
| Authentication | HMAC-SHA256 session tokens + optional Google OAuth |

## Getting Started

### Prerequisites

- Java 17+
- SBT (Scala Build Tool)
- MongoDB running locally (default: `mongodb://localhost:27017`)
- YaneuraOu engine executable placed in the `engine/` directory

### Configuration

Copy the template and edit:

```bash
cp src/main/resources/application.conf.default src/main/resources/application.conf
```

Key settings:

| Key | Description |
|---|---|
| `app.mongodb.uri` | MongoDB connection string |
| `app.engine.path` | Path to the YaneuraOu executable |
| `app.fetcher.*` | Default platform nicknames |
| `app.oauth.enabled` | Toggle Google OAuth |
| `app.security.admin-email` | Admin user email |

### Running Locally

```bash
# 1. Start MongoDB
# 2. Place engine in engine/
sbt run
# Access at http://localhost:8080
```

### Running with Docker

```bash
docker build -t shogi-puzzler .
docker run -p 8080:8080 \
  -e MONGODB_URI=mongodb://host.docker.internal:27017 \
  shogi-puzzler
```

The multi-stage `Dockerfile` compiles YaneuraOu for Linux AVX2, builds the Scala fat JAR, and packages both into a minimal OpenJDK image.

## Project Structure

```
src/main/scala/shogi/puzzler/
├── maintenance/
│   ├── routes/          # Cask route handlers (one object per page/feature)
│   ├── AnalysisQueue.scala
│   └── TaskManager.scala
├── analysis/            # GameAnalyzer, PuzzleExtractor
├── engine/              # YaneuraOu USI integration, EngineManager
├── db/                  # MongoDB repositories (Game, Puzzle, Study, Settings, …)
├── domain/              # Case classes (Game, Puzzle, Score, …)
├── game/                # KIF parser (GameLoader), KifAnnotator
├── i18n/                # I18n helper, language loading
└── ui/                  # Shared Scalatags components (layout, navbar)

src/main/resources/
├── js/                  # Client-side JS (puzzle.js, study-editor.js, ceval.js, …)
├── assets/              # CSS, fonts, Shogiground assets
├── engine-wasm/         # YaneuraOu WASM bundle
└── i18n/                # en.json, sk.json
```

## MongoDB Collections

| Collection | Contents |
|---|---|
| `games` | Raw game data with KIF, analysis results, move comments |
| `custom_puzzles` | All puzzles (generated + custom) |
| `puzzles` | Legacy read-only puzzle collection |
| `repertoire` | Opening studies (nodes tree, metadata) |
| `users` | User accounts and page permissions |
| `settings` | Per-user configuration |
| `srs_cards` | Spaced-repetition flashcard state |
| `srs_attempts` | SRS review history |
| `ocr_history` | Saved OCR scans |
| `training_pieces` / `training_hands` | OCR training images |

## License

GNU General Public License v3.0 — see [LICENSE.txt](LICENSE.txt).

## Credits

Inspired by [lishogi](https://github.com/WandererXII/lishogi). Board rendering by [Shogiground](https://github.com/WandererXII/shogiground). Game logic by [Scalashogi](https://github.com/WandererXII/scalashogi). Engine by [YaneuraOu](https://github.com/yaneurao/YaneuraOu).
