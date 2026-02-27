package shogi.puzzler.maintenance.routes

import scalatags.Text.all._
import shogi.Color
import shogi.puzzler.db.{AppSettings, GameRepository, PuzzleRepository, SettingsRepository}
import shogi.puzzler.domain.{CpScore, MateScore, PovScore, Score}
import shogi.puzzler.engine.{EngineManager, Limit}
import shogi.puzzler.analysis.{AnalysisService, SfenUtils}
import shogi.puzzler.maintenance.routes.MaintenanceRoutes.getEngineManager
import shogi.puzzler.ui.Components
import shogi.puzzler.i18n.I18n

import scala.concurrent.Await
import scala.concurrent.duration._

object PuzzleCreatorRoutes extends BaseRoutes {

  import org.mongodb.scala.bson.collection.immutable.{Document => BsonDocument}

  @cask.get("/puzzle-creator")
  def puzzleCreatorList(game: Option[String] = None, status: Option[String] = None, source: Option[String] = None, request: cask.Request) = {
    withAuth(request, "puzzle-creator") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val pageLang = getLang(request)
      val puzzles = game match {
        case Some(hash) if hash.nonEmpty =>
          Await.result(PuzzleRepository.getPuzzlesForGame(hash), 10.seconds)
        case _ =>
          source match {
            case Some(s) if s.nonEmpty =>
              Await.result(PuzzleRepository.getPuzzlesBySource(email, s), 10.seconds)
            case _ =>
              status match {
                case Some("review") =>
                  Await.result(PuzzleRepository.getPuzzlesByStatus(email, "review"), 10.seconds)
                case Some("accepted") =>
                  Await.result(PuzzleRepository.getAcceptedPuzzles(email), 10.seconds)
                case _ =>
                  Await.result(PuzzleRepository.getUserPuzzles(email), 10.seconds)
              }
          }
      }
      val gameInfo = game.filter(_.nonEmpty).flatMap { hash =>
        Await.result(GameRepository.getGameByHash(hash), 10.seconds)
      }
      // Review counts per source (for tab badges)
      val reviewCounts: Map[String, Long] = game match {
        case Some(_) => Map.empty
        case _ =>
          import scala.concurrent.ExecutionContext.Implicits.global
          import scala.concurrent.Future
          val (gameF, repF, custF) = (
            PuzzleRepository.countReviewBySource(email, "game"),
            PuzzleRepository.countReviewBySource(email, "study"),
            PuzzleRepository.countReviewBySource(email, "custom")
          )
          Map(
            "game"        -> Await.result(gameF, 10.seconds),
            "study"       -> Await.result(repF, 10.seconds),
            "custom"      -> Await.result(custF, 10.seconds)
          )
      }
      cask.Response(
        renderPuzzleListPage(userEmail, settings, puzzles, game, gameInfo, source, reviewCounts, pageLang).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  @cask.get("/puzzle-creator/new")
  def puzzleCreatorNew(request: cask.Request, sfen: String = "", blunder: String = "", comment: String = "", prelude: String = "", rootSfen: String = "") = {
    withAuth(request, "puzzle-creator") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val pageLang = getLang(request)
      cask.Response(
        renderPuzzleEditor(userEmail, settings, None, pageLang).render,
        headers = Seq(
          "Content-Type"                 -> "text/html; charset=utf-8",
          "Cross-Origin-Opener-Policy"   -> "same-origin",
          "Cross-Origin-Embedder-Policy" -> "credentialless"
        )
      )
    }
  }

  @cask.get("/puzzle-creator/edit/:id")
  def puzzleCreatorEdit(request: cask.Request, id: String) = {
    withAuth(request, "puzzle-creator") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val pageLang = getLang(request)
      val puzzle = Await.result(PuzzleRepository.getPuzzle(id, email), 10.seconds)
      puzzle match {
        case Some(doc) =>
          cask.Response(
            renderPuzzleEditor(userEmail, settings, Some(doc), pageLang).render,
            headers = Seq(
              "Content-Type"                 -> "text/html; charset=utf-8",
              "Cross-Origin-Opener-Policy"   -> "same-origin",
              "Cross-Origin-Embedder-Policy" -> "credentialless"
            )
          )
        case None =>
          noCacheRedirect("/puzzle-creator")
      }
    }
  }

  def renderPuzzleListPage(userEmail: Option[String], settings: AppSettings, puzzles: Seq[BsonDocument], gameFilter: Option[String] = None, gameInfo: Option[org.mongodb.scala.Document] = None, sourceFilter: Option[String] = None, reviewCounts: Map[String, Long] = Map.empty, pageLang: String = I18n.defaultLang) = {
    Components.layout(
      "Puzzle Editor",
      userEmail,
      settings,
      appVersion,
      lang = pageLang,
      scripts = Seq(
        raw(tag("style")("""
          .puzzle-card {
            background: #2e2a24;
            border: 1px solid #444;
            border-radius: 8px;
            margin-bottom: 20px;
            overflow: hidden;
            display: flex;
            min-height: 120px;
          }
          @media (max-width: 768px) {
            .puzzle-card {
              flex-direction: column;
              min-height: auto;
            }
            .puzzle-card-icon {
              width: 100% !important;
              height: 60px !important;
              border-right: none !important;
              border-bottom: 1px solid #444;
            }
            .puzzle-card-content {
              padding: 10px;
            }
            .sfen-container {
              flex-wrap: wrap;
            }
            .sfen-input {
              width: 100%;
              margin-bottom: 5px;
            }
          }
          .puzzle-card-icon {
            width: 80px;
            min-height: 120px;
            display: flex;
            align-items: center;
            justify-content: center;
            background: #1a1712;
            border-right: 1px solid #444;
            font-size: 2em;
            color: #d5ae39;
          }
          .puzzle-card-content {
            padding: 15px;
            flex-grow: 1;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
          }
          .puzzle-card-name {
            font-size: 1.1em;
            font-weight: bold;
            color: #eee;
            margin-bottom: 4px;
          }
          .puzzle-card-date {
            font-size: 0.8em;
            color: #888;
          }
          .puzzle-card-comments {
            font-style: italic;
            color: #ccc;
            margin-top: 4px;
            font-size: 0.9em;
          }
          .puzzle-card-actions {
            display: flex;
            gap: 10px;
            align-items: center;
          }
          .badge-public {
            background: #198754;
            color: #fff;
            font-size: 0.75em;
            padding: 2px 8px;
            border-radius: 10px;
            margin-left: 8px;
          }
          .sfen-container {
            display: flex;
            gap: 5px;
            margin-top: 5px;
            background: #1a1712;
            padding: 5px;
            border-radius: 4px;
            align-items: center;
          }
          .sfen-input {
            background: transparent;
            border: none;
            color: #aaa;
            font-family: monospace;
            font-size: 0.85em;
            flex-grow: 1;
            outline: none;
          }
          .copy-btn {
            background: #444;
            border: none;
            color: #fff;
            padding: 2px 6px;
            border-radius: 3px;
            font-size: 0.75em;
            cursor: pointer;
          }
          .copy-btn:hover {
            background: #555;
          }
          .lishogi-link {
            color: #d5ae39;
            font-size: 0.85em;
            text-decoration: none;
          }
          .lishogi-link:hover {
            text-decoration: underline;
          }
          .badge-review {
            background: #dc3545;
            color: #fff;
            font-size: 0.75em;
            padding: 2px 8px;
            border-radius: 10px;
            margin-left: 8px;
          }
          .badge-source {
            background: #2c4a6e;
            color: #a8c8f0;
            font-size: 0.72em;
            padding: 2px 7px;
            border-radius: 10px;
            margin-left: 6px;
          }
          .filter-tabs .nav-link {
            color: #aaa;
          }
          .filter-tabs .nav-link.active {
            color: #fff;
            background: #444;
            border-color: #555;
          }
          .filter-tabs .nav-link:hover:not(.active) {
            color: #ddd;
            border-color: #555;
          }
          /* ── Puzzle filter bar ──────────────────────────────────── */
          .pc-filter-bar {
            display: flex;
            gap: 10px;
            align-items: center;
            flex-wrap: wrap;
            margin-bottom: 16px;
            padding: 10px 14px;
            background: #26231f;
            border-radius: 8px;
            border: 1px solid #3a3835;
          }
          .pc-search-wrap {
            flex: 1;
            min-width: 180px;
            position: relative;
          }
          .pc-search-wrap .bi-search {
            position: absolute;
            left: 10px;
            top: 50%;
            transform: translateY(-50%);
            color: #666;
            pointer-events: none;
            font-size: .85rem;
          }
          .pc-search-input {
            width: 100%;
            background: #1a1917;
            border: 1px solid #3a3835;
            color: #ddd;
            border-radius: 5px;
            padding: 6px 10px 6px 30px;
            font-size: .85rem;
            outline: none;
            transition: border-color .15s;
          }
          .pc-search-input:focus { border-color: #5a8fff; }
          .pc-search-input::placeholder { color: #555; }
          .pc-status-group { display: flex; gap: 4px; flex-shrink: 0; }
          .pc-status-btn {
            background: #2e2a24;
            border: 1px solid #444;
            color: #aaa;
            border-radius: 5px;
            padding: 5px 12px;
            font-size: .8rem;
            cursor: pointer;
            transition: background .12s, color .12s, border-color .12s;
            white-space: nowrap;
          }
          .pc-status-btn:hover { background: #3a3530; color: #ddd; }
          .pc-status-btn.active { background: #444; color: #fff; border-color: #666; }
          .pc-status-btn.active.btn-review { background: #7a1a22; border-color: #dc3545; color: #ffa8af; }
          .pc-status-btn.active.btn-accepted { background: #1a4a2a; border-color: #198754; color: #a0e8b8; }
          .pc-status-btn.active.btn-public   { background: #1a3a4a; border-color: #0d6efd; color: #90c8f8; }
          .pc-filter-count {
            font-size: .78rem;
            color: #666;
            white-space: nowrap;
            flex-shrink: 0;
            align-self: center;
          }
          .pc-no-results {
            text-align: center;
            padding: 40px 20px;
            color: #666;
            font-size: .9rem;
            display: none;
          }
          .puzzle-card[hidden] { display: none !important; }
        """).render)
      )
    )(
      div(cls := "mt-4")(
        gameFilter.filter(_.nonEmpty).map { _ =>
          div(cls := "mb-3")(
            a(href := "/database", cls := "btn btn-outline-secondary btn-sm")(
              i(cls := "bi bi-arrow-left me-1"), "Back to Database"
            )
          ): Frag
        }.getOrElse(frag()),
        gameInfo.map { doc =>
          val sente = doc.get("sente").map(_.asString().getValue).getOrElse("Unknown")
          val gote = doc.get("gote").map(_.asString().getValue).getOrElse("Unknown")
          val date = doc.get("date").map(_.asString().getValue).getOrElse("")
          div(cls := "alert alert-secondary")(
            h5(cls := "mb-1")(s"$sente vs $gote"),
            if (date.nonEmpty) small(cls := "text-muted")(date) else ()
          ): Frag
        }.getOrElse(frag()),
        div(cls := "d-flex justify-content-between align-items-center mb-4")(
          h2("Puzzle Editor"),
          a(href := "/puzzle-creator/new", cls := "btn btn-success")(i(cls := "bi bi-plus-lg me-2"), "New Puzzle")
        ),
        if (gameFilter.forall(_.isEmpty)) {
          val totalReview = reviewCounts.values.sum
          def tabBadge(count: Long): Frag = if (count > 0) tag("span")(cls := "badge bg-danger ms-1")(count.toString) else frag()
          tag("ul")(cls := "nav nav-tabs filter-tabs mb-3")(
            tag("li")(cls := "nav-item")(
              a(cls := s"nav-link${if (sourceFilter.isEmpty) " active" else ""}", href := "/puzzle-creator")(
                "All",
                tabBadge(totalReview)
              )
            ),
            tag("li")(cls := "nav-item")(
              a(cls := s"nav-link${if (sourceFilter.contains("game")) " active" else ""}", href := "/puzzle-creator?source=game")(
                i(cls := "bi bi-controller me-1"),
                "From Games",
                tabBadge(reviewCounts.getOrElse("game", 0L))
              )
            ),
            tag("li")(cls := "nav-item")(
              a(cls := s"nav-link${if (sourceFilter.contains("study")) " active" else ""}", href := "/puzzle-creator?source=study")(
                i(cls := "bi bi-diagram-3 me-1"),
                "From Study",
                tabBadge(reviewCounts.getOrElse("study", 0L))
              )
            ),
            tag("li")(cls := "nav-item")(
              a(cls := s"nav-link${if (sourceFilter.contains("custom")) " active" else ""}", href := "/puzzle-creator?source=custom")(
                i(cls := "bi bi-pencil-square me-1"),
                "Custom",
                tabBadge(reviewCounts.getOrElse("custom", 0L))
              )
            )
          ): Frag
        } else frag(),
        if (puzzles.isEmpty) {
          div(cls := "alert alert-info")("No puzzles created yet. Click \"New Puzzle\" to get started!")
        } else {
          div(
            // ── Filter bar ──────────────────────────────────────────
            div(cls := "pc-filter-bar")(
              div(cls := "pc-search-wrap")(
                i(cls := "bi bi-search"),
                input(
                  `type` := "search", id := "pc-search",
                  cls := "pc-search-input",
                  placeholder := "Search by name…",
                  attr("autocomplete") := "off"
                )
              ),
              div(cls := "pc-status-group")(
                button(cls := "pc-status-btn active", attr("data-status") := "all")("All"),
                button(cls := "pc-status-btn btn-review", attr("data-status") := "review")(
                  i(cls := "bi bi-hourglass-split me-1"), "Needs Review"
                ),
                button(cls := "pc-status-btn btn-accepted", attr("data-status") := "accepted")(
                  i(cls := "bi bi-check-circle me-1"), "Accepted"
                )
              ),
              scalatags.Text.all.span(style := "width:1px;height:24px;background:#3a3835;flex-shrink:0;align-self:center"),
              div(cls := "pc-status-group")(
                button(cls := "pc-status-btn btn-public", attr("data-public") := "true")(
                  i(cls := "bi bi-globe me-1"), "Public"
                )
              ),
              scalatags.Text.all.span(id := "pc-count", cls := "pc-filter-count")()
            ),
            // ── Cards ───────────────────────────────────────────────
            div(id := "pc-cards")(
            puzzles.map { doc =>
              val puzzleId = doc.getObjectId("_id").toHexString
              val puzzleName = doc.getString("name")
              val sfen = doc.getString("sfen")
              val puzzleComments = doc.getString("comments")
              val isPublic = doc.getBoolean("is_public", false)
              val puzzleStatus = doc.get("status").map(_.asString().getValue).getOrElse("accepted")
              val isReview = puzzleStatus == "review"
              val createdAt = doc.getLong("created_at")
              val dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(createdAt))
              // Determine source (with backward compat)
              val rawSource = doc.get("source").map(_.asString().getValue).getOrElse("")
              val hasKifHash = doc.get("game_kif_hash").exists(v => v.asString().getValue.nonEmpty)
              val puzzleSource = if (rawSource.nonEmpty) rawSource else if (hasKifHash) "game" else "custom"
              val (sourceIcon, sourceLabel) = puzzleSource match {
                case "game"        => ("bi-controller", "From Game")
                case "study"       => ("bi-diagram-3",  "Study")
                case _             => ("bi-pencil-square", "Custom")
              }

              div(
                cls := "puzzle-card",
                attr("data-name")   := puzzleName.toLowerCase,
                attr("data-status") := puzzleStatus,
                attr("data-public") := isPublic.toString
              )(
                div(cls := "puzzle-card-icon")(
                  i(cls := s"bi $sourceIcon")
                ),
                div(cls := "puzzle-card-content")(
                  div(
                    div(
                      tag("span")(cls := "puzzle-card-name")(puzzleName),
                      if (isReview) tag("span")(cls := "badge-review")("Needs Review") else (),
                      if (isPublic) tag("span")(cls := "badge-public")("Public") else (),
                      // Show source badge only on "All" tab
                      if (sourceFilter.isEmpty) tag("span")(cls := "badge-source")(i(cls := s"bi $sourceIcon me-1"), sourceLabel) else ()
                    ),
                    div(cls := "puzzle-card-date")(dateStr),
                    if (puzzleComments.nonEmpty) div(cls := "puzzle-card-comments")(puzzleComments) else (),
                    if (sfen.nonEmpty) {
                      div(cls := "sfen-container")(
                        input(cls := "sfen-input", value := sfen, readonly := true),
                        button(cls := "copy-btn", onclick := s"window.copySfen('${sfen.replace("'", "\\'")}')")("Copy"),
                        a(cls := "lishogi-link", href := s"https://lishogi.org/analysis/${sfen.replace(" ", "_")}", target := "_blank", rel := "noopener")("Lishogi")
                      )
                    } else div()
                  ),
                  div(cls := "puzzle-card-actions")(
                    a(href := s"/puzzle-creator/edit/$puzzleId", cls := "btn btn-primary btn-sm")(i(cls := "bi bi-pencil me-1"), "Edit"),
                    button(cls := "btn btn-danger btn-sm", onclick := s"window.deletePuzzle('$puzzleId')")(i(cls := "bi bi-trash me-1"), "Delete")
                  )
                )
              ): Frag
            }
            ),  // end #pc-cards
            div(id := "pc-no-results", cls := "pc-no-results")(
              i(cls := "bi bi-search me-2"), "No puzzles match your filters."
            )
          )
        },
        script(raw("""
          window.deletePuzzle = function(id) {
            if (confirm('Are you sure you want to delete this puzzle?')) {
              fetch('/puzzle-creator/delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: id })
              })
                .then(r => r.json())
                .then(data => {
                  if (data.success) location.reload();
                  else alert('Delete failed: ' + (data.error || data.message));
                });
            }
          }
          window.copySfen = function(sfen) {
            navigator.clipboard.writeText(sfen).then(() => {
              console.log('SFEN copied to clipboard');
            });
          };

          // ── Client-side filtering ──────────────────────────────
          (function () {
            var activeStatus = 'all';
            var publicOnly   = false;
            var searchVal    = '';

            function applyFilters() {
              var cards   = document.querySelectorAll('#pc-cards .puzzle-card');
              var visible = 0;
              cards.forEach(function (card) {
                var nameMatch   = !searchVal || card.dataset.name.includes(searchVal);
                var statusMatch = activeStatus === 'all' || card.dataset.status === activeStatus;
                var publicMatch = !publicOnly || card.dataset.public === 'true';
                var show = nameMatch && statusMatch && publicMatch;
                card.hidden = !show;
                if (show) visible++;
              });
              var total = cards.length;
              var countEl = document.getElementById('pc-count');
              if (countEl) countEl.textContent = visible < total ? visible + ' / ' + total : total + ' puzzle' + (total !== 1 ? 's' : '');
              var noResults = document.getElementById('pc-no-results');
              if (noResults) noResults.style.display = visible === 0 ? 'block' : 'none';
            }

            // Search input
            var searchInput = document.getElementById('pc-search');
            if (searchInput) {
              searchInput.addEventListener('input', function () {
                searchVal = this.value.trim().toLowerCase();
                applyFilters();
              });
            }

            // Status buttons (mutually exclusive group)
            document.querySelectorAll('.pc-status-btn[data-status]').forEach(function (btn) {
              btn.addEventListener('click', function () {
                document.querySelectorAll('.pc-status-btn[data-status]').forEach(function (b) { b.classList.remove('active'); });
                this.classList.add('active');
                activeStatus = this.dataset.status;
                applyFilters();
              });
            });

            // Public toggle (independent)
            var publicBtn = document.querySelector('.pc-status-btn.btn-public');
            if (publicBtn) {
              publicBtn.addEventListener('click', function () {
                publicOnly = !publicOnly;
                this.classList.toggle('active', publicOnly);
                applyFilters();
              });
            }

            // Init count
            applyFilters();
          })();
        """))
      )
    )
  }

  def renderPuzzleEditor(userEmail: Option[String] = None, settings: AppSettings, puzzle: Option[BsonDocument] = None, pageLang: String = I18n.defaultLang) = {
    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", content := "#2e2a24"),
        tag("title")(if (puzzle.isDefined) "Puzzle Editor - Edit" else "Puzzle Editor - New"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"),
        link(rel := "stylesheet", href := "/assets/css/shogiground.css"),
        link(rel := "stylesheet", href := "/assets/css/portella.css"),
        link(rel := "stylesheet", href := "/assets/css/site.css"),
        link(rel := "stylesheet", href := "/assets/css/puzzle.css"),
        link(rel := "stylesheet", href := "/assets/css/common.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.10.3/font/bootstrap-icons.css"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js")
      ),
      body(cls := "wood coords-out playing online")(
        Components.renderHeader(userEmail, settings, appVersion, pageLang),
        div(id := "main-wrap")(
          tag("main")(cls := "puzzle puzzle-play")(
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(id := "hand-top"),
                div(id := "dirty"),
                tag("sg-hand-wrap")(id := "hand-bottom")
              )
            ),
            div(cls := "puzzle__controls")(
              div(cls := "container-fluid p-0")(
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    a(href := "/puzzle-creator", cls := "btn btn-outline-secondary btn-sm mb-2")(
                      i(cls := "bi bi-arrow-left me-1"), "Back to list"
                    )
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    label(cls := "form-label text-light")("Puzzle Name"),
                    input(cls := "form-control form-control-sm", id := "puzzle-name", `type` := "text", placeholder := "Enter puzzle name")
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    label(cls := "form-label text-light")("SFEN (optional - leave empty for initial board)"),
                    textarea(cls := "form-control form-control-sm", id := "puzzle-sfen", rows := "3", placeholder := "Enter SFEN notation")
                  )
                ),
                div(cls := "row g-2 mb-2")(
                  div(cls := "col-12")(
                    label(cls := "form-label text-light small mb-1")(
                      i(cls := "bi bi-collection-play me-1 text-warning"), "Prelude Moves"
                    ),
                    div(id := "prelude-moves-visual", cls := "d-flex flex-wrap gap-1 mb-1 small")(),
                    input(cls := "form-control form-control-sm font-monospace mb-1", id := "puzzle-prelude", `type` := "text",
                      placeholder := "USI sequence before puzzle (e.g. 7g7f 3c3d 8h2b+)")
                  )
                ),
                div(cls := "row g-2 mb-1")(
                  div(cls := "col-12")(
                    input(cls := "form-control form-control-sm font-monospace", id := "puzzle-root-sfen", `type` := "text",
                      style := "color:#888;font-size:0.8em;",
                      placeholder := "Root SFEN (starting position before prelude)"),
                    small(cls := "text-muted")("Root position from which the prelude starts")
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col")(
                    div(cls := "form-check mt-1")(
                      input(cls := "form-check-input", id := "puzzle-play-prelude", `type` := "checkbox"),
                      label(cls := "form-check-label text-light small", `for` := "puzzle-play-prelude")(
                        "Play prelude animation before puzzle"
                      )
                    )
                  ),
                  div(cls := "col-auto")(
                    button(cls := "btn btn-sm btn-outline-info", id := "preview-prelude", `type` := "button")(
                      i(cls := "bi bi-play-fill me-1"), "Preview"
                    )
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    label(cls := "form-label text-light")("Comments (optional)"),
                    textarea(cls := "form-control form-control-sm", id := "puzzle-comments", rows := "2", placeholder := "Add notes about this puzzle")
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    div(cls := "form-check")(
                      input(cls := "form-check-input", id := "puzzle-public", `type` := "checkbox"),
                      label(cls := "form-check-label text-light", `for` := "puzzle-public")("Make this puzzle public")
                    )
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-6")(
                    button(cls := "btn btn-success w-100", id := "save-puzzle")(
                      i(cls := "bi bi-save me-2"), "Save"
                    )
                  ),
                  div(cls := "col-6")(
                    button(cls := "btn btn-outline-warning w-100", id := "save-draft")(
                      i(cls := "bi bi-pencil-square me-2"), "Save as Draft"
                    )
                  )
                ),
                puzzle.filter { doc =>
                  doc.get("status").exists(_.asString().getValue == "review")
                }.map { doc =>
                  val puzzleId = doc.getObjectId("_id").toHexString
                  div(cls := "row g-2 mb-3")(
                    div(cls := "col-12")(
                      button(cls := "btn btn-outline-success w-100", id := "accept-puzzle", attr("data-id") := puzzleId)(
                        i(cls := "bi bi-check-lg me-2"), "Accept Puzzle"
                      )
                    )
                  ): Frag
                }.getOrElse(frag()),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-8")(
                    button(cls := "btn btn-success w-100", id := "analyze-sequence")(
                      i(cls := "bi bi-play-fill me-2"), "Analyze"
                    )
                  ),
                  div(cls := "col-4")(
                    button(cls := "btn btn-outline-success w-100", id := "ceval-toggle-btn", title := "Local engine analysis in browser")(
                      i(cls := "bi bi-cpu-fill me-1"), "Local"
                    )
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-6")(
                    button(cls := "btn btn-danger w-100", id := "stop-analysis", style := "display:none;")(
                      i(cls := "bi bi-stop-fill me-2"), "Stop"
                    )
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    button(cls := "btn btn-info w-100", id := "toggle-arrows")(
                      i(cls := "bi bi-eye me-2"), "Show/Hide Arrows & Reset"
                    )
                  )
                ),
                div(cls := "row g-2 mb-2")(
                  div(cls := "col-3")(
                    label(cls := "form-label text-light small mb-1")("Depth"),
                    input(cls := "form-control form-control-sm", id := "analysis-depth", `type` := "number", value := "15", min := "1", max := "50")
                  ),
                  div(cls := "col-3")(
                    label(cls := "form-label text-light small mb-1")("Time (s)"),
                    input(cls := "form-control form-control-sm", id := "analysis-time", `type` := "number", value := "0", min := "0", max := "300", placeholder := "0=off")
                  ),
                  div(cls := "col-3")(
                    label(cls := "form-label text-light small mb-1")("Moves"),
                    input(cls := "form-control form-control-sm", id := "analysis-moves", `type` := "number", value := "3", min := "1", max := "10")
                  ),
                  div(cls := "col-3")(
                    label(cls := "form-label text-light small mb-1")("Candidates"),
                    input(cls := "form-control form-control-sm", id := "analysis-candidates", `type` := "number", value := "1", min := "1", max := "10")
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    small(cls := "text-muted")("Depth: search plies. Time: seconds per move (0=depth only). Both can be combined.")
                  )
                )
              )
            ),
            div(cls := "puzzle__side")(
              div(cls := "puzzle__side__box")(
                div(cls := "puzzle__feedback")(
                  div(cls := "content")(if (puzzle.isDefined) "Edit your puzzle!" else "Create a new puzzle!"),
                  div(id := "turn-text", cls := "badge bg-secondary mb-1")("Sente to move")
                )
              )
            )
          )
        ),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(src := "https://cdn.jsdelivr.net/npm/sweetalert2@11"),
        script(src := "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.6.3/jquery.min.js", attr("integrity") := "sha512-STof4xm1wgkfm7heWqFJVn58Hm3EtS31XFaagaa8VMReCXAkQnJZ+jEy8PCC/iT18dFy95WcExNHFTqLyp72eQ==", attr("crossorigin") := "anonymous", attr("referrerpolicy") := "no-referrer"),
        puzzle.map { doc =>
          script(raw(s"window.__puzzleData = ${ujson.write(ujson.read(doc.toJson()))};"))
        }.getOrElse(()),
        script(raw("""
          document.addEventListener('DOMContentLoaded', function() {
            var btn = document.getElementById('accept-puzzle');
            if (btn) {
              btn.addEventListener('click', function() {
                var id = btn.getAttribute('data-id');
                fetch('/puzzle-creator/accept', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ id: id })
                })
                  .then(function(r) { return r.json(); })
                  .then(function(data) {
                    if (data.success) window.location.href = '/puzzle-creator';
                    else alert('Accept failed: ' + (data.error || data.message));
                  });
              });
            }
          });
        """)),
        script(src := "/js/ceval.js"),
        script(src := "/js/puzzle-creator.js")
      )
    )
  }

  @cask.get("/puzzle-creator/list")
  def listCustomPuzzles(game: Option[String] = None, request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val puzzles = game match {
        case Some(hash) if hash.nonEmpty =>
          Await.result(PuzzleRepository.getPuzzlesForGame(hash), 10.seconds)
        case _ =>
          Await.result(PuzzleRepository.getUserPuzzles(email), 10.seconds)
      }

      val jsonArray = puzzles.map { doc =>
        ujson.read(doc.toJson())
      }
      cask.Response(
        ujson.Arr(jsonArray: _*),
        headers = Seq("Content-Type" -> "application/json")
      )
    }
  }

  @cask.post("/puzzle-creator/save")
  def saveCustomPuzzle(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val json = ujson.read(request.text())
      val name = json("name").str
      val sfen = json("sfen").str
      val isPublic = json.obj.get("isPublic").map(_.bool).getOrElse(false)
      val comments = json.obj.get("comments").map(_.str)
      val selectedSequence = json.obj.get("selectedSequence").map { seq =>
        seq.arr.map(_.str).toSeq
      }
      // Parse move comments as a map
      val moveComments = json.obj.get("moveComments").map { mc =>
        mc.obj.map { case (k, v) => k -> v.str }.toMap
      }
      val analysisData = json.obj.get("analysisData").map(_.str)
      val selectedCandidates = json.obj.get("selectedCandidates").map { sc =>
        sc.arr.map(_.num.toInt).toSeq
      }
      val blunderMoves = json.obj.get("blunderMoves").map { bm =>
        bm.arr.map(_.str).toSeq
      }
      val tags = json.obj.get("tags").map { t =>
        t.arr.map(_.str).toSeq
      }
      val blunderAnalyses = json.obj.get("blunderAnalyses").flatMap(v => if (v.isNull) None else Some(v.str))
      val id = json.obj.get("id").map(_.str)
      val status = json.obj.get("status").map(_.str).getOrElse("accepted")
      val prelude = json.obj.get("prelude").map(v => if (v.isNull) "" else v.str)
      val rootSfen = json.obj.get("rootSfen").map(v => if (v.isNull) "" else v.str)
      val playPrelude = json.obj.get("playPrelude").map(_.bool)
      val gameKifHash = json.obj.get("gameKifHash").flatMap(v => if (v.isNull) None else Some(v.str)).filter(_.nonEmpty)
      val moveNumber = json.obj.get("moveNumber").flatMap(v => scala.util.Try(v.num.toInt).toOption)
      val source = json.obj.get("source").map(_.str).filter(_.nonEmpty).getOrElse("custom")

      val result = id match {
        case Some(puzzleId) =>
          Await.result(PuzzleRepository.updatePuzzle(puzzleId, name, sfen, email, isPublic, comments, selectedSequence, moveComments, analysisData, selectedCandidates, blunderMoves, tags, blunderAnalyses, prelude, rootSfen, playPrelude), 10.seconds)
          ujson.Obj("success" -> true, "message" -> "Puzzle updated successfully")
        case None =>
          val insertResult = Await.result(PuzzleRepository.savePuzzle(name, sfen, email, isPublic, comments, selectedSequence, moveComments, analysisData, selectedCandidates, gameKifHash = gameKifHash, blunderMoves = blunderMoves, status = status, tags = tags, moveNumber = moveNumber, blunderAnalyses = blunderAnalyses, prelude = prelude, rootSfen = rootSfen, playPrelude = playPrelude, source = source), 10.seconds)
          val newId = scala.util.Try(insertResult.getInsertedId.asObjectId().getValue.toHexString).getOrElse("")
          ujson.Obj("success" -> true, "message" -> (if (status == "review") "Puzzle saved as draft" else "Puzzle saved successfully"), "id" -> newId)
      }
      
      cask.Response(result, headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.post("/puzzle-creator/delete")
  def deleteCustomPuzzle(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val json = ujson.read(request.text())
      val id = json("id").str

      Await.result(PuzzleRepository.deletePuzzle(id, email), 10.seconds)
      cask.Response(
        ujson.Obj("success" -> true, "message" -> "Puzzle deleted successfully"),
        headers = Seq("Content-Type" -> "application/json")
      )
    }
  }

  @cask.post("/puzzle-creator/accept")
  def acceptCustomPuzzle(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val json = ujson.read(request.text())
      val id = json("id").str

      Await.result(PuzzleRepository.updatePuzzleStatus(id, email, "accepted"), 10.seconds)
      cask.Response(
        ujson.Obj("success" -> true, "message" -> "Puzzle accepted"),
        headers = Seq("Content-Type" -> "application/json")
      )
    }
  }

  @cask.post("/puzzle-creator/analyze")
  def analyzePosition(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      val json = ujson.read(request.text())
      val sfen = json("sfen").str
      val multiPv = json.obj.get("multiPv").map(_.num.toInt).getOrElse(3)
      val depth = json.obj.get("depth").map(_.num.toInt).getOrElse(15)
      val time = json.obj.get("time").map(_.num.toInt).filter(_ > 0) // milliseconds from frontend

      try {
        val engineManager = getEngineManager(settings.enginePath)
        val movesArray = AnalysisService.analyzePosition(engineManager, sfen, depth, multiPv, time, Color.Sente)

        cask.Response(
          ujson.Obj("success" -> true, "moves" -> ujson.Arr(movesArray: _*)),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: Exception =>
          cask.Response(
            ujson.Obj("success" -> false, "error" -> e.getMessage),
            headers = Seq("Content-Type" -> "application/json"),
            statusCode = 500
          )
      }
    }
  }

  @cask.post("/puzzle-creator/analyze-sequence")
  def analyzeSequence(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      val json = ujson.read(request.text())
      val sfen = json("sfen").str
      val numMoves = json.obj.get("numMoves").map(_.num.toInt).getOrElse(3)
      val multiPv = json.obj.get("multiPv").map(_.num.toInt).getOrElse(1)
      
      try {
        val engineManager = getEngineManager(settings.enginePath)

        val depth = json.obj.get("depth").map(_.num.toInt).getOrElse(15)
        val time = json.obj.get("time").map(_.num.toInt).filter(_ > 0)
        val sequences = AnalysisService.analyzeMoveSequences(engineManager, sfen, numMoves, depth, multiPv, time)

        cask.Response(
          ujson.Obj("success" -> true, "sequences" -> ujson.Arr(sequences.map(seq => ujson.Arr(seq: _*)): _*)),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: Exception =>
          cask.Response(
            ujson.Obj("success" -> false, "error" -> e.getMessage),
            headers = Seq("Content-Type" -> "application/json"),
            statusCode = 500
          )
      }
    }
  }

  @cask.post("/puzzle-creator/analyze-blunders")
  def analyzeBlunders(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      val json = ujson.read(request.text())
      val sfen = json("sfen").str
      val blunderMoves = json.obj.get("blunderMoves").map { bm =>
        bm.arr.map(_.str).toSeq
      }.getOrElse(Seq.empty)
      
      val depth = json.obj.get("depth").map(_.num.toInt).getOrElse(15)
      val time = json.obj.get("time").map(_.num.toInt).filter(_ > 0)
      val numMoves = json.obj.get("numMoves").map(_.num.toInt).getOrElse(3)

      if (blunderMoves.isEmpty) {
        cask.Response(
          ujson.Obj("success" -> false, "error" -> "No blunder moves provided"),
          headers = Seq("Content-Type" -> "application/json"),
          statusCode = 400
        )
      } else {
        try {
          val engineManager = getEngineManager(settings.enginePath)
          
          // Analyze each blunder move - for each blunder, generate a multi-move sequence
          val blunderAnalyses = blunderMoves.map { blunderMove =>
            // Get the sequence starting from position after the blunder move
            // Use analyzeMoveSequences logic but starting from sfen + blunderMove
            val limit = Limit(depth = Some(depth), time = time.map(_ * 1000))
            
            // First, get the initial score after the blunder
            val initialResults = engineManager.analyzeWithMoves(sfen, Seq(blunderMove), limit, 1)
            val colorToMove = SfenUtils.sfenTurnToColor(sfen)
            // After the blunder move, the opponent is to move, so the engine
            // score is from the opponent's perspective.  Normalize to sente.
            val afterBlunderColor = if (colorToMove == Color.Sente) Color.Gote else Color.Sente

            // Build the sequence: blunder move + engine best response(s)
            val sequence = scala.collection.mutable.ArrayBuffer[ujson.Obj]()

            // First add the blunder move (score normalized to sente perspective)
            val (blunderScoreKind, blunderScoreValue) = if (initialResults.nonEmpty && initialResults.head.contains("score")) {
              val povScore = Score.fromEngine(initialResults.head.get("score"), afterBlunderColor)
              val senteScore = povScore.forPlayer(Color.Sente)
              senteScore match {
                case CpScore(cp) => ("cp", cp)
                case MateScore(m) => ("mate", m)
                case _ => ("cp", 0)
              }
            } else {
              ("cp", 0)
            }
            
            sequence += ujson.Obj(
              "moveNum" -> 1,
              "usi" -> blunderMove,
              "score" -> ujson.Obj("kind" -> blunderScoreKind, "value" -> blunderScoreValue),
              "depth" -> depth
            )
            
            // Now get best continuation moves
            val moveHistory = scala.collection.mutable.ArrayBuffer[String](blunderMove)
            
            for (moveNum <- 1 until numMoves) {
              val results = engineManager.analyzeWithMoves(sfen, moveHistory.toSeq, limit, 1)
              
              if (results.nonEmpty && results.head.contains("pv")) {
                val pv = results.head("pv").asInstanceOf[List[String]]
                if (pv.isEmpty) {
                  // No more moves available, break
                  
                } else {
                  val bestMove = pv.head
                  
                  // Determine whose turn it is
                  val isCurrentSente = SfenUtils.isSenteTurn(moveNum + 1, sfen)
                  val currentColorToMove = if (isCurrentSente) Color.Sente else Color.Gote
                  
                  val povScore = Score.fromEngine(results.head.get("score"), currentColorToMove)
                  val senteScore = povScore.forPlayer(Color.Sente)
                  
                  val (scoreKind, scoreValue) = senteScore match {
                    case CpScore(cp) => ("cp", cp)
                    case MateScore(m) => ("mate", m)
                    case _ => ("cp", 0)
                  }
                  
                  sequence += ujson.Obj(
                    "moveNum" -> (moveNum + 1),
                    "usi" -> bestMove,
                    "score" -> ujson.Obj("kind" -> scoreKind, "value" -> scoreValue),
                    "depth" -> depth,
                    "pv" -> pv.mkString(" ")
                  )
                  
                  moveHistory += bestMove
                }
              } else {
                // No analysis result, break
              }
            }
            
            ujson.Obj(
              "blunder" -> blunderMove,
              "sequence" -> ujson.Arr(sequence.toSeq: _*)
            )
          }

          cask.Response(
            ujson.Obj(
              "success" -> true,
              "sfen" -> sfen,
              "blunderAnalyses" -> ujson.Arr(blunderAnalyses: _*)
            ),
            headers = Seq("Content-Type" -> "application/json")
          )
        } catch {
          case e: Exception =>
            cask.Response(
              ujson.Obj("success" -> false, "error" -> e.getMessage),
              headers = Seq("Content-Type" -> "application/json"),
              statusCode = 500
            )
        }
      }
    }
  }

  @cask.post("/puzzle-creator/translate")
  def translatePuzzle(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { _ =>
      try {
        val json = ujson.read(request.text())
        val id      = json("id").str
        val lang    = json("lang").str
        val comment = json.obj.get("comment").map(_.str).filter(_.nonEmpty)
        val moveComments = json.obj.get("moveComments")
          .flatMap(mc => scala.util.Try(mc.obj.map { case (k, v) => k -> v.str }.toMap).toOption)
          .getOrElse(Map.empty[String, String])

        Await.result(PuzzleRepository.savePuzzleTranslation(id, lang, comment, moveComments), 10.seconds)
        cask.Response(
          ujson.Obj("success" -> true),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: Exception =>
          cask.Response(
            ujson.Obj("error" -> Option(e.getMessage).getOrElse("Unknown error")),
            statusCode = 500,
            headers = Seq("Content-Type" -> "application/json")
          )
      }
    }
  }

  initialize()
}
