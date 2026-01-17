# Shogi Puzzler

Shogi Puzzler is a Scala-based web application designed to fetch Shogi games from various online platforms, analyze them using the YaneuraOu engine, and present them as interactive puzzles.

## Features

- **Game Fetching**: Import games from Lishogi, ShogiWars, and 81Dojo.
- **Engine Analysis**: Uses the YaneuraOu engine (NNUE) to analyze positions and find optimal moves.
- **Maintenance Dashboard**: A web interface to manage nicknames, fetch games, and monitor analysis progress.
- **Puzzle Viewer**: An interactive board to view and solve generated Shogi puzzles.
- **Docker Support**: Easy deployment using Docker with multi-stage builds.
- **Authentication**: Supports Google OAuth for secure access.

## Tech Stack

- **Language**: Scala 2.13
- **Web Framework**: [Cask](https://com-lihaoyi.github.io/cask/)
- **Frontend**: Scalatags, Chart.js, Bootstrap 5, Shogiground
- **Database**: MongoDB
- **Shogi Engine**: YaneuraOu (NNUE)
- **Library**: [Scalashogi](https://github.com/wandererxii/scalashogi)
- **Automation**: Playwright (for fetching from some sources)

## Getting Started

### Prerequisites

- Java 17+
- SBT (Scala Build Tool)
- MongoDB running locally or accessible via URI
- YaneuraOu engine executable (placed in `engine/` directory)

### Configuration

The application is configured via `src/main/resources/application.conf`. You can use `src/main/resources/application.conf.default` as a template. Key settings include:

- `app.mongodb.uri`: Connection string for MongoDB.
- `app.engine.path`: Path to the YaneuraOu executable.
- `app.fetcher.*`: Default nicknames for Shogi platforms.
- `app.oauth.enabled`: Toggle for Google OAuth.

### Running Locally

1. Start MongoDB.
2. Place the YaneuraOu executable in the `engine/` folder.
3. Run the application using SBT:
   ```bash
   sbt run
   ```
4. Access the dashboard at `http://localhost:8080`.

### Running with Docker

You can build and run the application using the provided `Dockerfile`. This will also build the YaneuraOu engine from source for Linux.

```bash
docker build -t shogi-puzzler .
docker run -p 8080:8080 -e MONGODB_URI=mongodb://host.docker.internal:27017 shogi-puzzler
```

## Documentation and Demo

For a detailed walkthrough of the application features and technical implementation, please see [DEMO.md](DEMO.md).

## Project Structure

- `src/main/scala/shogi/puzzler`: Main application logic.
    - `maintenance/`: Game fetching and dashboard routes.
    - `engine/`: YaneuraOu engine integration.
    - `db/`: MongoDB repositories.
    - `ui/`: UI components using Scalatags.
- `src/main/resources`: Static assets (JS, CSS) and HTML templates.
- `engine/`: Directory for the Shogi engine.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE.txt](LICENSE.txt) file for details.

## Credits

This project is heavily inspired by [lishogi](https://github.com/WandererXII/lishogi) and [shogiground](https://github.com/WandererXII/shogiground).
