# Shogi Puzzler — Demo & Documentation

A walkthrough of all application features and their technical implementation.

---

## 1. Game Fetching (`/fetch-games`)

The fetch-games page lets you import games from three platforms and also accepts manual KIF uploads.

### Platform sources
| Source | Method |
|---|---|
| **Lishogi** | REST API — fetches KIF files directly |
| **ShogiWars** | Web scraping of the player's public history page |
| **81Dojo** | Playwright browser automation — logs in and exports KIF |

### KIF upload
Any `.kif` file can be uploaded manually without login. The file is validated (must parse cleanly and contain at least one move), checked for duplicates by content hash, and stored in MongoDB.

### Stored data
Each game document stores: players, date, source platform, raw KIF, SHA-256 `kif_hash` (unique index), and analysis results once processed.

---

## 2. Game Database (`/database`)

A paginated, filterable table of all stored games.

- **Filters**: source platform, analysis status, player name search, "My Games" toggle
- **Sort**: click the Date column header to toggle ascending/descending
- **Pagination**: configurable page size (10 / 25 / 50 / 100), sliding page window
- **Stats cards**: total games, analyzed count, pending count, total puzzles — updated per filter
- **Actions**: analyze a game (queues it), re-analyze, view puzzles generated from it

Date formats in the database are normalized to `YYYY-MM-DD` on write and on startup migration, ensuring correct lexicographic sort.

---

## 3. Game Viewer (`/view-game/:hash`)

Replay any game move by move with a full analysis overlay.

- **Interactive board** powered by Shogiground — click or drag pieces
- **Evaluation graph** (Chart.js) showing score per move; puzzle markers on the graph jump to that position
- **Move list** with clickable moves; moves with saved comments are highlighted
- **Engine evaluation panel** — candidates, depth, win-chance drop per position
- **Move comments** — add a personal note to any move; saved per-move in MongoDB (`move_comments` subdocument); Ctrl+Enter to save
- **Local engine analysis** (ceval) — WebAssembly YaneuraOu runs in a Web Worker (SharedArrayBuffer, COOP/COEP headers); toggle on/off; shows a vertical win-chance bar
- **Puzzle actions**: mark a position for review, create a custom puzzle at this position, or jump to an existing puzzle
- **Lishogi link** — opens the position on lishogi.org

---

## 4. Puzzle Viewer (`/viewer`)

Solve tactical puzzles extracted from analyzed games.

- **Puzzle selection** — dropdown with sort options (move number, rating, times played) and tag filter
- **Board** shows the position just before the blunder; player to move is indicated
- **Solving** — drag/click pieces; engine validates against multi-PV analysis
  - Best move → "Best move!" feedback
  - Second/third best → partial credit with hint to check the best
  - Blunder → "Blunder!" with immediate feedback
- **Hints** — reveal one move at a time
- **Win-probability bar** — vertical gauge showing evaluation before/after
- **Prelude animation** — plays the moves leading into the puzzle position
- **Continuation** — after solving, play out the full engine continuation
- **Rating** — thumbs up/down per puzzle (requires login)
- **SRS deck** — add/remove the puzzle from your spaced-repetition training deck (requires login)
- **Random** and **Position** buttons for quick navigation

---

## 5. Study Browser (`/study-viewer`)

Browse opening studies — no login required.

- **Public studies** — visible to everyone; organized into Lishogi-imported groups (collapsible, showing chapter count and author) and standalone studies (grid)
- **Authenticated users** additionally see all their own studies in the same list, including private ones — identified by a **Private 🔒** or **Mine 🌐** badge
- **Search** — filters by study name client-side
- **Source filter** — All / Lishogi Study / Manual/KIF toggle buttons
- **Study viewer** (`/study-viewer/:id`) — read-only interactive board to explore the study tree, with move comments displayed per node

---

## 6. Study Editor (`/study`) — requires auth

Build and manage opening studies.

- **Create** from scratch, from a KIF file, or by importing a Lishogi study URL
- **Lishogi import** — two-phase: fetch chapter list server-side, then import individual chapters (or all at once); chapters are grouped under the study URL
- **Auto-reload** — periodically rebuilds the study tree from your analyzed games; configurable color (Sente/Gote) and evaluation-drop threshold
- **Public/private toggle** — make individual studies or chapters publicly visible
- **Move editor** — add/delete/update moves and comments; supports multilingual comments with auto-translate
- **Puzzle flag** — mark any node as a puzzle candidate for review in Puzzle Creator

---

## 7. Puzzle Creator (`/puzzle-creator`) — requires auth

Create and curate custom puzzles.

- **New puzzle** — set initial SFEN (or use starting position), define blunder moves, add a name and comments
- **Edit** — modify any existing custom puzzle
- **Local engine analysis** — same WASM engine as Game Viewer for real-time position evaluation while building puzzles
- **Source tabs** — filter puzzles by origin: From Games / From Study / Custom; badge counts show how many need review per tab
- **Review workflow** — accept or leave as draft; accepted puzzles appear in the public Puzzle Viewer
- **Translations** — add comments in multiple languages; auto-translate via external API

---

## 8. SRS Training (`/training`) — requires auth

Spaced-repetition flashcard system for puzzle positions.

- Puzzles added to your deck are scheduled using an SRS algorithm (interval, ease factor)
- **Dashboard** shows: due today, total cards, current streak, success rate
- Puzzles due are presented one by one; correct/incorrect responses adjust the next review interval
- Session summary shows solved count and accuracy

---

## 9. OCR Recognition (`/ocr`) — requires auth

Convert a board photograph or screenshot to SFEN notation.

- Upload an image; Tess4j OCR reads piece positions
- Result is displayed as an interactive board for verification
- Sente/Gote hand pieces editable manually
- Save to OCR library with an optional comment
- Library shows history with copy/edit/delete actions; SFEN copied to clipboard with one click

---

## 10. Engine Analysis Pipeline

```
Fetch KIF
  └─ GameLoader.parseKif()       — validate, extract metadata, moves
       └─ GameAnalyzer.analyzeShallow()   — 1 s/position, all moves
            └─ PuzzleExtractor.extract()  — find win-chance drops > threshold
                 └─ GameAnalyzer.analyzeDeep()  — 10 s, MultiPV=3 on candidates
                      └─ store puzzles in MongoDB
```

The `AnalysisQueue` serializes analysis jobs. `TaskManager` tracks running tasks and exposes progress via SSE or polling. `EngineManager` holds the `YaneuraOu` process behind a `ReentrantLock`.

---

## 11. Local Browser Engine (ceval)

Available in Game Viewer and Puzzle Creator without a server round-trip.

- **Engine**: `@mizarjp/yaneuraou.k-p` v7.6.3-alpha.0 (WASM + Web Worker)
- **Threading**: SharedArrayBuffer enabled via `Cross-Origin-Opener-Policy: same-origin` + `Cross-Origin-Embedder-Policy: credentialless` (Chrome 96+, Firefox 119+)
- **API**: `ClientEval` class manages USI lifecycle; `CevalBar` renders the vertical win-chance gauge
- **Toggle**: button in the toolbar starts/stops the worker; analysis restarts automatically on move navigation

---

## 12. Authentication & Authorization

- **Session tokens**: HMAC-SHA256 signed, stored in HTTP-only cookies; no server-side session state
- **Google OAuth**: optional; enabled via `app.oauth.enabled` in config
- **Role-based access**: each user has a list of allowed page keys (e.g. `"repertoire"`, `"puzzle-creator"`, `"ocr"`) stored in the `users` MongoDB collection; managed via `/admin/users`
- **Admin**: the email in `app.security.admin-email` always has full access

---

## 13. Deployment

### Docker (recommended)

Multi-stage `Dockerfile`:

1. **Stage 1** — clone and compile YaneuraOu for Linux AVX2
2. **Stage 2** — `sbt assembly` produces a fat JAR
3. **Stage 3** — minimal OpenJDK 17 image with engine + JAR

```bash
docker build -t shogi-puzzler .
docker run -p 8080:8080 \
  -e MONGODB_URI=mongodb://host.docker.internal:27017 \
  -e ENGINE_PATH=/app/engine/YaneuraOu \
  shogi-puzzler
```

All `application.conf` values support `${?ENV_VAR}` overrides for Docker/CI.

---

*Built for Shogi players who want to learn from their own mistakes.*
