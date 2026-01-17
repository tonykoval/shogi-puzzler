# Shogi Puzzler: Demo and Documentation

This page provides a detailed walkthrough of the Shogi Puzzler application and its core workflows.

## 1. Maintenance Dashboard Demo

The **Maintenance Dashboard** is the central hub for managing your game database and engine settings.

### Key Actions:
- **Configuration**: Set your Lishogi, ShogiWars, and 81Dojo nicknames. These are used to fetch your recent games.
- **Fetching Games**: Click the "Fetch" button on any of the source cards. The app will:
    1. Connect to the platform (Lishogi API, ShogiWars web, or 81Dojo via Playwright).
    2. Download the KIF/SFEN data.
    3. Store the games in MongoDB.
- **Analysis**: Once games are fetched, the YaneuraOu engine analyzes them. You can see the evaluation graph (powered by Chart.js) to identify key moments where the evaluation swung significantly—these are prime candidates for puzzles.

## 2. Puzzle Viewer Demo

The **Puzzle Viewer** is where the actual practice happens.

### Workflow:
1. **Select a Puzzle**: Choose from the dropdown of analyzed games.
2. **The Board**: The board is powered by `Shogiground`. It displays the position just before a significant mistake was made in the actual game.
3. **Solving**: Drag and drop pieces to make the correct move. The engine validates your move against its analysis.
4. **Feedback**: If you make the correct move, you progress. If not, the UI provides feedback, and you can try again or see the hint.

## 3. Technical Deep Dive

### Game Fetching (Maintenance)
The application uses different strategies for each source:
- **Lishogi**: Standard REST API calls.
- **ShogiWars**: Web scraping of the user's public history page.
- **81Dojo**: Automated browser interaction via **Playwright** to log in and export KIF files.

### Engine Integration
The `YaneuraOu.scala` class communicates with the engine over the USI (Universal Shogi Interface) protocol. It manages:
- Process lifecycle (starting/stopping the .exe).
- Sending commands (`position sfen ...`, `go depth ...`).
- Parsing the `info` strings to extract scores and PV (Principal Variation).

### Data Persistence
Everything is stored in **MongoDB**. The `GameRepository` handles the storage of raw game data, while `SettingsRepository` stores user-specific configurations like nicknames and engine limits.

## 4. Deployment Architecture

When deployed via **Docker**, the setup becomes a robust production environment:
1. **Multi-stage Build**: 
    - Stage 1 compiles the YaneuraOu engine for Linux AVX2.
    - Stage 2 builds the Scala `.jar` using SBT.
    - Stage 3 creates a minimal OpenJDK image containing both.
2. **Environment Variables**: Use `ENGINE_PATH` and `MONGODB_URI` to point to your production resources.

---

*This project is built for Shogi enthusiasts who want to learn from their own mistakes.*
