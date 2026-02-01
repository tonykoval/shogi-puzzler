package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{SettingsRepository, AppSettings}
import shogi.puzzler.ui.Components
import shogi.puzzler.domain.SearchGame
import scala.concurrent.Await
import scala.concurrent.duration._

object DemoRoutes extends BaseRoutes {

  @cask.get("/demo")
  def demo(request: cask.Request) = {
    withAuth(request, "demo") { email =>
      val effectiveEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(effectiveEmail), 10.seconds)
      
      cask.Response(
        renderDemoPage(effectiveEmail, settings).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderDemoPage(userEmail: Option[String], settings: AppSettings) = {
    Components.layout(
      "Maintenance Demo", 
      userEmail, 
      settings,
      appVersion,
      scripts = Seq(
        script(src := "https://cdn.jsdelivr.net/npm/chart.js"),
        script(src := "/js/maintenance.js")
      )
    )(
      h1(cls := "mb-4")("Maintenance Demo"),
      div(cls := "row")(
        div(cls := "col-12")(
          div(cls := "alert alert-info")(
            h4(cls := "alert-heading")("Welcome to the Maintenance Demo!"),
            p("This page demonstrates how games are fetched, analyzed, and turned into puzzles."),
            hr(),
            p(cls := "mb-0")("Follow the steps below to see the process in action.")
          )
        )
      ),
      div(cls := "row mb-4")(
        div(cls := "col-md-12")(
          div(cls := "card bg-dark text-light border-secondary")(
            div(cls := "card-header border-secondary")(
              h3("Step 1: Fetch a Game")
            ),
            div(cls := "card-body")(
              p("Normally you would fetch many games from a source like Lishogi or ShogiWars. For this demo, we'll fetch one specific interesting game."),
              button(cls := "btn btn-primary", onclick := "fetchDemoGame()")(id := "fetchDemoBtn")("Fetch Demo Game")
            )
          )
        )
      ),
      div(id := "demo-results-container", cls := "row mb-4", style := "display: none;")(
        div(cls := "col-md-12")(
          div(cls := "card bg-dark text-light border-secondary")(
            div(cls := "card-header border-secondary")(
              h3("Step 2: Analyze & Extract Puzzles")
            ),
            div(cls := "card-body")(
              div(id := "demo-game-item")
            )
          )
        )
      ),
      div(cls := "row")(
        div(cls := "col-12 text-center")(
          a(href := "/viewer", cls := "btn btn-lg btn-outline-info")("Go to Puzzle Viewer")
        )
      ),
      script(raw("""
        function fetchDemoGame() {
          $('#fetchDemoBtn').prop('disabled', true).text('Fetching...');
          $.get('/demo-fetch', function(data) {
            $('#demo-results-container').show();
            $('#demo-game-item').html(data);
            $('#fetchDemoBtn').text('Game Fetched').addClass('btn-success').removeClass('btn-primary');
          });
        }

        function analyzeDemoGame(btn, source, player, kif) {
          console.log('Starting demo analysis for', player);
          $(btn).prop('disabled', true).text('Analyzing...');
          
          // Unicode-safe btoa replacement
          function utob(str) {
            return btoa(encodeURIComponent(str).replace(/%([0-9A-F]{2})/g, function(match, p1) {
              return String.fromCharCode('0x' + p1);
            }));
          }
          
          const kifHash = utob(kif).substring(0, 16); // Mock hash for demo
          
          $.ajax({
            url: '/maintenance-analyze',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
              source: source,
              player: player,
              kif: kif
            }),
            success: function(data) {
              console.log('Analysis successful:', data);
              $(btn).text('Analyzed').addClass('btn-success').removeClass('btn-primary');
              // After analysis, we show the link to puzzles
              const puzzlesLink = '<div class="mt-3"><a href="/viewer" class="btn btn-info">Watch Puzzles</a></div>';
              $(btn).after(puzzlesLink);
            },
            error: function(xhr) {
              console.error('Analysis failed:', xhr.responseText);
              $(btn).prop('disabled', false).text('Analyze Again');
              alert('Analysis failed: ' + xhr.responseText + '. Make sure the engine is configured in Config.');
            }
          });
        }
      """))
    )
  }

  @cask.get("/demo-fetch")
  def demoFetch() = {
    val kif = """|開始日時：2023/10/21 10:00:00
                |手合割：平手
                |先手：DemoSente
                |後手：DemoGote
                |
                |1 ７六歩(77) ( 0:01/00:00:01)
                |2 ３四歩(33) ( 0:01/00:00:01)
                |3 ２六歩(27) ( 0:01/00:00:01)
                |4 ４四歩(43) ( 0:01/00:00:01)
                |5 ２五歩(26) ( 0:01/00:00:01)
                |6 ３三角(22) ( 0:01/00:00:01)
                |7 ４八銀(39) ( 0:01/00:00:01)
                |8 ４二飛(82) ( 0:01/00:00:01)
                |9 ６八玉(59) ( 0:01/00:00:01)
                |10 ９四歩(93) ( 0:01/00:00:01)
                |11 ７八玉(68) ( 0:01/00:00:01)
                |12 ９五歩(94) ( 0:01/00:00:01)
                |13 ５八金(49) ( 0:01/00:00:01)
                |14 ７二銀(71) ( 0:01/00:00:01)
                |15 ７七角(88) ( 0:01/00:00:01)
                |16 ６二玉(51) ( 0:01/00:00:01)
                |17 ８八玉(78) ( 0:01/00:00:01)
                |18 ７一玉(62) ( 0:01/00:00:01)
                |19 ９八香(99) ( 0:01/00:00:01)
                |20 ８二玉(71) ( 0:01/00:00:01)
                |21 ９九玉(88) ( 0:01/00:00:01)
                |22 ７一金(61) ( 0:01/00:00:01)
                |23 ８八銀(79) ( 0:01/00:00:01)
                |24 ５二金(41) ( 0:01/00:00:01)
                |25 ７九金(58) ( 0:01/00:00:01)
                |26 ６四歩(63) ( 0:01/00:00:01)
                |27 ６九金(61) ( 0:01/00:00:01)
                |28 ７四歩(73) ( 0:01/00:00:01)
                |29 ５九銀(48) ( 0:01/00:00:01)
                |30 ６三銀(72) ( 0:01/00:00:01)
                |31 ６六歩(67) ( 0:01/00:00:01)
                |32 ５四銀(63) ( 0:01/00:00:01)
                |33 ６七金(69) ( 0:01/00:00:01)
                |34 ４五歩(44) ( 0:01/00:00:01)
                |35 ３六歩(37) ( 0:01/00:00:01)
                |36 ７三桂(81) ( 0:01/00:00:01)
                |37 ２四歩(25) ( 0:01/00:00:01)
                |38 同　歩(23) ( 0:01/00:00:01)
                |39 同　飛(28) ( 0:01/00:00:01)
                |40 ２三歩打 ( 0:01/00:00:01)
                |41 ２八飛(24) ( 0:01/00:00:01)
                |42 ６五歩(64) ( 0:01/00:00:01)
                |43 同　歩(66) ( 0:01/00:00:01)
                |44 同　桂(73) ( 0:01/00:00:01)
                |45 ６六歩打 ( 0:01/00:00:01)
                |46 ７五歩(74) ( 0:01/00:00:01)
                |47 同　歩(76) ( 0:01/00:00:01)
                |48 ６四歩打 ( 0:01/00:00:01)
                |49 ７四歩(75) ( 0:01/00:00:01)
                |50 同　銀(54) ( 0:01/00:00:01)
                |51 ７五歩打 ( 0:01/00:00:01)
                |52 ６三銀(74) ( 0:01/00:00:01)
                |53 ２四歩打 ( 0:01/00:00:01)
                |54 同　歩(23) ( 0:01/00:00:01)
                |55 ２五歩打 ( 0:01/00:00:01)
                |56 同　歩(24) ( 0:01/00:00:01)
                |57 同　飛(28) ( 0:01/00:00:01)
                |58 ２四歩打 ( 0:01/00:00:01)
                |59 ２八飛(25) ( 0:01/00:00:01)
                |60 ８四歩(83) ( 0:01/00:00:01)
                |61 ４六歩(47) ( 0:01/00:00:01)
                |62 ４四飛(42) ( 0:01/00:00:01)
                |63 ４五歩(46) ( 0:01/00:00:01)
                |64 同　飛(44) ( 0:01/00:00:01)
                |65 ４七歩打 ( 0:01/00:00:01)
                |66 ４二飛(45) ( 0:01/00:00:01)
                |67 ３五歩(36) ( 0:01/00:00:01)
                |68 ５四銀(63) ( 0:01/00:00:01)
                |69 ３六歩(35) ( 0:01/00:00:01)
                |70 ４三銀(54) ( 0:01/00:00:01)
                |71 ３三歩成(34) ( 0:01/00:00:01)
                |72 同　銀(43) ( 0:01/00:00:01)
                |73 ３四歩打 ( 0:01/00:00:01)
                |74 ４四銀(33) ( 0:01/00:00:01)
                |75 ２四飛(28) ( 0:01/00:00:01)
                |76 ２三歩打 ( 0:01/00:00:01)
                |77 ７四飛(24) ( 0:01/00:00:01)
                |78 ７三歩打 ( 0:01/00:00:01)
                |79 ２四飛(74) ( 0:01/00:00:01)
                |80 ２三銀打 ( 0:01/00:00:01)
                |81 ２八飛(24) ( 0:01/00:00:01)
                |82 ３三銀(44) ( 0:01/00:00:01)
                |83 ３一角打 ( 0:01/00:00:01)
                |84 ４一飛(42) ( 0:01/00:00:01)
                |85 ４二歩打 ( 0:01/00:00:01)
                |86 同　飛(41) ( 0:01/00:00:01)
                |87 ５三銀打 ( 0:01/00:00:01)
                |88 同　金(52) ( 0:01/00:00:01)
                |89 ４二角成(31) ( 0:01/00:00:01)
                |90 同　金(53) ( 0:01/00:00:01)
                |91 ４三歩打 ( 0:01/00:00:01)
                |92 ３二金(42) ( 0:01/00:00:01)
                |93 ３三歩成(34) ( 0:01/00:00:01)
                |94 同　桂(21) ( 0:01/00:00:01)
                |95 ３四銀打 ( 0:01/00:00:01)
                |96 ２二金(32) ( 0:01/00:00:01)
                |97 ４二飛打 ( 0:01/00:00:01)
                |98 ３一金打 ( 0:01/00:00:01)
                |99 ４一飛成(42) ( 0:01/00:00:01)
                |100 ４二金打 ( 0:01/00:00:01)
                |101 ５一龍(41) ( 0:01/00:00:01)
                |102 ５二歩打 ( 0:01/00:00:01)
                |103 ３一龍(51) ( 0:01/00:00:01)
                |104 ４一金(42) ( 0:01/00:00:01)
                |105 ４二金打 ( 0:01/00:00:01)
                |106 ５一金(41) ( 0:01/00:00:01)
                |107 ３二龍(31) ( 0:01/00:00:01)
                |108 ４一歩打 ( 0:01/00:00:01)
                |109 ４三歩成(43) ( 0:01/00:00:01)
                |110 ４二金(51) ( 0:01/00:00:01)
                |111 同　と(43) ( 0:01/00:00:01)
                |112 同　歩(41) ( 0:01/00:00:01)
                |113 ４三歩打 ( 0:01/00:00:01)
                |114 同　歩(42) ( 0:01/00:00:01)
                |115 ４二歩打 ( 0:01/00:00:01)
                |116 ４四金打 ( 0:01/00:00:01)
                |117 ４一歩成(42) ( 0:01/00:00:01)
                |118 ９六歩(95) ( 0:01/00:00:01)
                |119 同　歩(97) ( 0:01/00:00:01)
                |120 ９七歩打 ( 0:01/00:00:01)
                |121 同　香(98) ( 0:01/00:00:01)
                |122 ９六歩打 ( 0:01/00:00:01)
                |123 ５二龍(32) ( 0:01/00:00:01)
                |124 ９七歩成(96) ( 0:01/00:00:01)
                |125 同　銀(88) ( 0:01/00:00:01)
                |126 ８五桂(77) ( 0:01/00:00:01)
                |127 同　角(77) ( 0:01/00:00:01)
                |128 ７六銀打 ( 0:01/00:00:01)
                |129 同　金(67) ( 0:01/00:00:01)
                |130 同　歩(75) ( 0:01/00:00:01)
                |131 ８六角(85) ( 0:01/00:00:01)
                |132 ９五香打 ( 0:01/00:00:01)
                |133 ９六歩打 ( 0:01/00:00:01)
                |134 同　香(95) ( 0:01/00:00:01)
                |135 同　銀(97) ( 0:01/00:00:01)
                |136 ９五歩打 ( 0:01/00:00:01)
                |137 同　角(86) ( 0:01/00:00:01)
                |138 ９六香打 ( 0:01/00:00:01)
                |139 同　角(95) ( 0:01/00:00:01)
                |140 ９五歩打 ( 0:01/00:00:01)
                |141 ８六角打 ( 0:01/00:00:01)
                |142 ９六歩(95) ( 0:01/00:00:01)
                |143 ７五歩打 ( 0:01/00:00:01)
                |144 同　金(84) ( 0:01/00:00:01)
                |145 ９七歩打 ( 0:01/00:00:01)
                |146 ９五香打 ( 0:01/00:00:01)
                |147 ９六歩(97) ( 0:01/00:00:01)
                |148 同　香(95) ( 0:01/00:00:01)
                |149 同　銀(99) ( 0:01/00:00:01)
                |150 投了
                |""".stripMargin

    val g = SearchGame(
      sente = "DemoSente",
      gote = "DemoGote",
      date = "2023-10-21",
      kif = Some(kif),
      existsInDb = false,
      isAnalyzed = false,
      puzzleCount = 0,
      site = Some("lishogi")
    )

    cask.Response(
      div(
        h3("Fetched Game"),
        div(cls := "table-responsive")(
          table(cls := "table table-dark table-hover")(
            thead(
              tr(th("Sente"), th("Gote"), th("Date"), th("Actions"))
            ),
            tbody(
              tr(
                td(g.sente),
                td(g.gote),
                td(g.date),
                td({
                  val kifStr = g.kif.getOrElse("")
                  button(cls := "btn btn-sm btn-warning", 
                         onclick := s"analyzeDemoGame(this, 'demo', 'DemoSente', ${ujson.write(kifStr)})")("Analyze")
                })
              )
            )
          )
        )
      ).render,
      headers = Seq("Content-Type" -> "text/html; charset=utf-8")
    )
  }

  initialize()
}
