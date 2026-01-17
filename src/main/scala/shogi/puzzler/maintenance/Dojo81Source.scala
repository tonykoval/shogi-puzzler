package shogi.puzzler.maintenance

import com.microsoft.playwright._
import com.microsoft.playwright.options.WaitUntilState
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import shogi.puzzler.domain.SearchGame

import scala.jdk.CollectionConverters._

object Dojo81Source extends GameSource {
  private val logger = LoggerFactory.getLogger(getClass)
  private val config = ConfigFactory.load()

  override def name: String = "81Dojo"

  override def fetchGames(playerName: String, limit: Int, userEmail: Option[String] = None): Seq[SearchGame] = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import shogi.puzzler.db.SettingsRepository

    val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
    val username = settings.dojo81Nickname
    val password = shogi.puzzler.util.CryptoUtil.decrypt(settings.dojo81Password)

    if (username.isEmpty || password.isEmpty || username == "dojo81_user") {
      logger.error(s"[81DOJO] Username or password not configured for user: ${userEmail.getOrElse("global")}")
      return Seq.empty
    }

    var playwright: Playwright = null
    var browser: Browser = null
    try {
      playwright = Playwright.create()
      browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))
      val context = browser.newContext(new Browser.NewContextOptions()
        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .setViewportSize(1280, 1024)
        .setPermissions(java.util.List.of("clipboard-read", "clipboard-write")))
      val page = context.newPage()

      logger.info("[81DOJO] Logging in...")
      val loginUrl = "https://system.81dojo.com/en/players/sign_in"
      page.navigate(loginUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD))

      // Wait for the login form to be visible with a longer timeout
      page.waitForSelector("input[name='player[name]']", new Page.WaitForSelectorOptions().setTimeout(60000))

      page.fill("input[name='player[name]']", username)
      page.fill("input[name='player[password]']", password)

      // Navigate and wait for potential redirect or stay on same page (error)
      page.waitForNavigation(new Page.WaitForNavigationOptions().setTimeout(60000), () => {
        page.click("input[type='submit']")
      })

      val currentUrl = page.url()
      logger.info(s"[81DOJO] Current URL after login: $currentUrl")

      if (currentUrl.contains("sign_in")) {
        val errorMsg = try {
          page.locator(".alert, .error, #error_explanation").innerText().trim
        } catch {
          case _: Exception => "Unknown login error"
        }
        throw new RuntimeException(s"Login failed: $errorMsg")
      }

      logger.info("[81DOJO] Login successful")

      val searchUrl = "https://system.81dojo.com/en/kifus/search/form"
      logger.info(s"[81DOJO] Navigating to search form: $searchUrl")
      page.navigate(searchUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD))

      page.fill("input[name='conditions[player1]']", playerName)
      page.click("input[type='submit']")

      // Wait for results
      try {
        page.waitForSelector("table.list tbody tr", new Page.WaitForSelectorOptions().setTimeout(60000))

        // Wait for the number of rows to stabilize (it might be loading incrementally)
        var lastCount = 0
        var currentCount = page.locator("table.list tbody tr").count()
        var stabilityCount = 0
        val maxStabilityWait = 10 // 5 seconds total (10 * 1000ms / 2? No, 10 * 1000ms)

        while (stabilityCount < 3 && stabilityCount < maxStabilityWait) {
          if (currentCount > 0 && currentCount == lastCount) {
            stabilityCount += 1
          } else {
            stabilityCount = 0
          }
          lastCount = currentCount
          page.waitForTimeout(1000)
          currentCount = page.locator("table.list tbody tr").count()
        }

        logger.info(s"[81DOJO] Rows stabilized at $currentCount")
      } catch {
        case e: Exception =>
          logger.error(s"[81DOJO] Search results (rows) not found: ${e.getMessage}")
          // Take screenshot for debugging
          val screenshotPath = java.nio.file.Paths.get("81dojo_search_error.png")
          page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath))
          throw e
      }

      val rows = page.locator("table.list tbody tr").all().asScala
      val validRows = rows.filter(r => r.locator("td").count() >= 8)
      logger.info(s"[81DOJO] Found ${rows.size} rows, ${validRows.size} valid games (limit requested: $limit)")

      val detailPage = context.newPage()
      try {
        validRows.take(limit).flatMap { row =>
          try {
            val cells = row.locator("td").all().asScala
            if (cells.size < 8) {
              None
            } else {
              val date = cells(1).innerText().split("\n").head.trim

              // Player cell contains both sente and gote
              // e.g. "☗1761  snowpiercerr\n☖1350  Tonyko"
              val playersText = cells(2).innerText()
              val players = playersText.split("\n")
              val sente = if (players.length >= 1) players(0).replaceAll("^[☗☖]", "").trim.split("\\s+").last else "Unknown"
              val gote = if (players.length >= 2) players(1).replaceAll("^[☗☖]", "").trim.split("\\s+").last else "Unknown"

              val gameDetailLink = cells(7).locator("a").first()
              val gameUrl = gameDetailLink.getAttribute("href")
              val absoluteGameUrl = {
                val base = if (gameUrl.startsWith("http")) gameUrl else "https://system.81dojo.com" + (if (gameUrl.startsWith("/")) "" else "/") + gameUrl
                if (base.contains("/en/kifus/")) base.replace("/en/kifus/", "/kifus/") + (if (base.contains("?")) "&" else "?") + "locale=en"
                else base
              }

              logger.info(s"[81DOJO] Navigating to game detail: $absoluteGameUrl")
              val headers = new java.util.HashMap[String, String]()
              headers.put("Referer", "https://system.81dojo.com/en/kifus/search/form")
              detailPage.setExtraHTTPHeaders(headers)
              detailPage.navigate(absoluteGameUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(60000))
              
              // Extract KIF
              val kif = try {
                // The 81Dojo viewer is in a cross-origin iframe named 'viewer_frame'
                // We need to click the Menu button first to make the Copy button visible
                val menuSelector = "#kifuMenuButton"
                val copySelector = "#kifuCopyButton"
                
                var foundKif: String = null
                
                // Wait for the iframe to appear
                var viewerFrame: Frame = null
                
                // Try several times to find the frame as it might load dynamically
                var attempts = 0
                while (viewerFrame == null && attempts < 10) {
                   val allFrames = detailPage.frames().asScala
                   viewerFrame = allFrames.find(f => 
                     f != detailPage.mainFrame() && (
                       (f.name() == "viewer_frame") || 
                       (f.url().contains("81dojo.com") && (f.url().contains("kifus") || f.url().contains("secure_client") || f.url().contains("viewer")))
                     )
                   ).orNull
                   
                   if (viewerFrame == null) {
                     detailPage.waitForTimeout(2000)
                     attempts += 1
                   }
                }

                if (viewerFrame == null) {
                   logger.warn(s"[81DOJO] Shogi viewer frame not found for $absoluteGameUrl after $attempts attempts")
                }
                
                if (viewerFrame != null) {
                   try {
                     // Intercept clipboard write attempts in the frame
                     val interceptScript = """() => {
                       window._lastClipboardText = null;
                       if (navigator.clipboard && !navigator.clipboard.originalWriteText) {
                         navigator.clipboard.originalWriteText = navigator.clipboard.writeText.bind(navigator.clipboard);
                       }
                       if (navigator.clipboard) {
                         navigator.clipboard.writeText = async (text) => {
                           window._lastClipboardText = text;
                           return navigator.clipboard.originalWriteText(text);
                         };
                       }
                     }"""
                     viewerFrame.evaluate(interceptScript)
                     
                     // Ensure the frame is focused and ready
                     viewerFrame.focus("body")
                     
                     logger.info(s"[81DOJO] Clicking Kifu menu in frame '${viewerFrame.name()}' for $absoluteGameUrl")
                     
                     // Try to find the button with multiple possible selectors just in case
                     val menuSelectors = Array("#kifuMenuButton", "button:has-text('Kifu')", "div:has-text('Kifu')", "button:has-text('棋譜')", "div:has-text('棋譜')")
                     var menuClicked = false
                    
                     for (selector <- menuSelectors if !menuClicked) {
                        try {
                           viewerFrame.waitForSelector(selector, new Frame.WaitForSelectorOptions().setTimeout(5000).setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE))
                           viewerFrame.click(selector)
                           menuClicked = true
                        } catch {
                           case _: Exception => // try next
                        }
                     }
                    
                     if (menuClicked) {
                        logger.info(s"[81DOJO] Clicking Copy Kifu in frame for $absoluteGameUrl")
                        val copySelectors = Array("#kifuCopyButton", "button:has-text('Copy')", "div:has-text('Copy')", "button:has-text('コピー')", "div:has-text('コピー')")
                        var copyClicked = false
                        
                        for (selector <- copySelectors if !copyClicked) {
                           try {
                              viewerFrame.waitForSelector(selector, new Frame.WaitForSelectorOptions().setTimeout(5000).setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE))
                              viewerFrame.click(selector)
                              copyClicked = true
                           } catch {
                              case _: Exception => // try next
                           }
                        }
                        
                        if (copyClicked) {
                           detailPage.waitForTimeout(2000)
                           foundKif = viewerFrame.evaluate("window._lastClipboardText").asInstanceOf[String]
                        }
                     } else {
                        logger.warn(s"[81DOJO] Could not find or click Kifu menu for $absoluteGameUrl")
                     }
                   } catch {
                     case e: Exception => 
                       logger.warn(s"[81DOJO] Error interacting with frame: ${e.getMessage}")
                       // Take a screenshot of the frame if possible
                       try {
                          val timestamp = System.currentTimeMillis()
                          viewerFrame.page().screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get(s"81dojo_frame_error_$timestamp.png")))
                       } catch { case _: Exception => }
                   }
                }
                
                // Fallback to searching all frames and shadow roots if the above failed
                val extractionScript = """async () => {
                   const extractFromDocument = async (win, doc) => {
                     // 1. Check intercepted clipboard text
                     if (win._lastClipboardText) {
                       const text = win._lastClipboardText;
                       if (text && (text.includes('Start-Date') || (text.includes('先手') && text.includes('指手')))) {
                         return text;
                       }
                     }

                     // 2. Try to read from clipboard if button was clicked
                     try {
                        if (win.navigator && win.navigator.clipboard) {
                           const clipText = await win.navigator.clipboard.readText();
                           if (clipText && (clipText.includes('Start-Date') || (clipText.includes('先手') && clipText.includes('指手')))) {
                              return clipText;
                           }
                        }
                     } catch (e) {
                        // Clipboard might fail due to focus or permissions
                     }

                     // 2. Check common textareas (including those in shadow roots)
                     const getAllTextareas = (root) => {
                       let results = Array.from(root.querySelectorAll('textarea'));
                       const all = root.querySelectorAll('*');
                       for (const el of all) {
                         try {
                           if (el.shadowRoot) {
                             results = results.concat(getAllTextareas(el.shadowRoot));
                           }
                         } catch (e) {}
                       }
                       return results;
                     };

                     const areas = getAllTextareas(doc);
                     for (const area of areas) {
                       if (area.value && (area.value.includes('Sente') || area.value.includes('Gote') || area.value.includes('Start-Date') || area.value.includes('後手') || area.value.includes('先手'))) {
                         return area.value;
                       }
                     }
                     
                     // 3. Check if it's in a div or pre that might be visible now
                     const getAllContainers = (root) => {
                       let results = Array.from(root.querySelectorAll('div, pre, code'));
                       const all = root.querySelectorAll('*');
                       for (const el of all) {
                         try {
                           if (el.shadowRoot) {
                             results = results.concat(getAllContainers(el.shadowRoot));
                           }
                         } catch (e) {}
                       }
                       return results;
                     };

                     const potentialContainers = getAllContainers(doc);
                     for (const el of potentialContainers) {
                        const text = el.textContent;
                        if (text && text.length > 100 && (text.includes('Start-Date') || (text.includes('先手') && text.includes('指手')))) {
                           return text;
                        }
                     }

                     // 4. Check global variables
                     if (win._kifu_data) return win._kifu_data;
                     if (win.kifu_data) return win.kifu_data;
                     if (win.KIF_DATA) return win.KIF_DATA;
                     
                     return null;
                   };

                   return await extractFromDocument(window, document);
                }"""

                if (foundKif == null || foundKif.isEmpty) {
                   foundKif = detailPage.evaluate(extractionScript).asInstanceOf[String]
                }
                
                if (foundKif == null || foundKif.isEmpty) {
                   // Fallback: Try evaluating in each frame separately (handles cross-origin better)
                   val allFrames = detailPage.frames().asScala
                   for (frame <- allFrames if foundKif == null || foundKif.isEmpty) {
                      try {
                        foundKif = frame.evaluate(extractionScript).asInstanceOf[String]
                        if (foundKif != null && !foundKif.isEmpty) {
                           logger.info(s"[81DOJO] KIF found in frame '${frame.name()}' for $absoluteGameUrl")
                        }
                      } catch {
                        case _: Exception => // ignore
                      }
                   }
                }
                
                if (foundKif == null || foundKif.isEmpty) {
                   logger.warn(s"[81DOJO] KIF button clicked but no KIF content found for $absoluteGameUrl")
                   // Take screenshot for debugging
                   val timestamp = System.currentTimeMillis()
                   val screenshotPath = java.nio.file.Paths.get(s"81dojo_kif_error_$timestamp.png")
                   detailPage.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath))
                }

                Option(foundKif).filter(_.nonEmpty)
              } catch {
                case e: Exception =>
                  logger.warn(s"[81DOJO] Could not extract KIF for $absoluteGameUrl: ${e.getMessage}")
                  None
              }

              // Be nice to the server and allow some time between detail page requests
              detailPage.waitForTimeout(1000)
              
              Some(SearchGame(sente, gote, date, kif, site = Some("81dojo")))
            }
          } catch {
            case e: Exception =>
              logger.error(s"[81DOJO] Error processing row: ${e.getMessage}")
              None
          }
        }.toSeq
      } finally {
        detailPage.close()
      }

    } catch {
      case e: Exception =>
        logger.error(s"[81DOJO ERROR] ${e.getMessage}", e)
        Seq.empty
    } finally {
      if (browser != null) browser.close()
      if (playwright != null) playwright.close()
    }
  }
}
