let data, ids, sg, selected, selectedData
let isPlayingSequence = false;
let currentSequenceMoves = [];
let currentSequenceIndex = -1;
let autoplayInterval = null;
let areHintsVisible = false;
let isPieceHintVisible = false;
let playCountIncremented = false;
// Sequence starting position (for PV replay from arbitrary SFEN)
let sequenceStartSfen = null;
let sequenceStartHands = null;
let sequenceStartTurn = null;
// Current dialog context for eval bar updates
let dialogPos = null;
let dialogHighlight = null;

// Mapping between backend SFEN role notation and shogiops role notation
const sfenToShogiopsRole = {
    'K': 'king', 'R': 'rook', 'B': 'bishop', 'G': 'gold', 'S': 'silver',
    'N': 'knight', 'L': 'lance', 'P': 'pawn',
    '+R': 'promotedRook', '+B': 'promotedBishop', '+S': 'promotedSilver',
    '+N': 'promotedKnight', '+L': 'promotedLance', '+P': 'promotedPawn'
};

// Convert SFEN role (e.g., "FU", "fu", "+P") to shogiops role (e.g., "pawn")
function sfenRoleToShogiopsRole(sfenRole) {
    // Remove any case prefixes and get the base role
    const normalizedRole = sfenRole.replace(/^[+-]?/, '');
    const upperRole = normalizedRole.toUpperCase();
    return sfenToShogiopsRole[upperRole] || normalizedRole.toLowerCase();
}

const games = $(".games")
const urlParams = new URLSearchParams(window.location.search);
const hash = urlParams.get('hash');
const isAuthenticated = document.body.dataset.authenticated === "true";
const userLang = document.body.dataset.lang || 'en';

const apiUrl = hash ? "data?hash=" + hash : "data";
const cacheKey = hash ? "puzzles_" + hash : (isAuthenticated ? "puzzles_all" : "puzzles_public");

function loadData(forceReload = false) {
    if (!forceReload) {
        const cachedData = localStorage.getItem(cacheKey);
        if (cachedData) {
            try {
                const json = JSON.parse(cachedData);
                if (Array.isArray(json)) {
                    initPuzzles(json);
                    return;
                } else {
                    console.warn("Cached data is not an array, ignoring.");
                    localStorage.removeItem(cacheKey);
                }
            } catch (e) {
                console.error("Error parsing cached data", e);
                localStorage.removeItem(cacheKey);
            }
        }
    }

    const reloadBtn = $(".reload-data");
    const originalHtml = reloadBtn.html();
    reloadBtn.prop('disabled', true).html('<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>');

    $.ajax({
        url: apiUrl,
        dataType: 'json',
        success: function(json) {
            if (Array.isArray(json)) {
                localStorage.setItem(cacheKey, JSON.stringify(json));
            }
            initPuzzles(json);
            if (forceReload) {
                const i18n = window.i18n || {};
                Swal.fire({
                    icon: 'success',
                    title: i18n['viewer.dataReloaded'] || 'Data Reloaded',
                    text: i18n['viewer.puzzlesUpdated'] || 'Puzzles have been updated from the database.',
                    timer: 1500,
                    showConfirmButton: false
                });
            }
        },
        error: function(xhr, status, error) {
            const i18n = window.i18n || {};
            console.error("Error fetching data", error);
            Swal.fire({
                icon: 'error',
                title: i18n['viewer.errorTitle'] || 'Error',
                text: i18n['viewer.fetchFailed'] || 'Failed to fetch data from the server.'
            });
        },
        complete: function() {
            reloadBtn.prop('disabled', false).html(originalHtml);
        }
    });
}

function initPuzzles(json) {
    if (typeof json === 'string') {
        try {
            json = JSON.parse(json);
        } catch (e) {
            console.error("initPuzzles: Failed to parse string as JSON", e);
        }
    }
    if (!Array.isArray(json)) {
        console.error("initPuzzles expected array but received:", typeof json, json);
        if (json && json.error) {
            Swal.fire({
                icon: 'error',
                title: (window.i18n && window.i18n['viewer.accessDenied']) || 'Access Denied',
                text: json.error
            });
        }
        return;
    }
    data = json
    selectedData = data
    ids = createIds(data)

    // Populate tag filter dropdown with unique tags from all puzzles
    const tagFilter = $('#tag-filter');
    if (tagFilter.length) {
        const allTags = new Set();
        data.forEach(p => {
            if (p.tags && Array.isArray(p.tags)) {
                p.tags.forEach(t => allTags.add(t));
            }
        });
        tagFilter.find('option:not(:first)').remove();
        Array.from(allTags).sort().forEach(t => {
            tagFilter.append($('<option>').val(t).text(t));
        });
        // Hide filter if no tags exist
        tagFilter.closest('.row').toggle(allTags.size > 0);
    }

    // Clear existing select2 data if any
    if (games.data('select2')) {
        games.empty();
    }

    games.select2({
        data: ids,
        templateSelection: formatPuzzle,
        templateResult: formatPuzzle
    })

    if (selectedData.length > 0) {
        selectSituation(randomNumber(0, selectedData.length - 1), selectedData)
    }
}

loadData();

$(".reload-data").click(function() {
    loadData(true);
});

function applySortAndRebuild() {
    const selectedTag = $('#tag-filter').val();
    const sortBy = $('#sort-order').val() || 'move';

    if (selectedTag) {
        selectedData = data.filter(p => p.tags && Array.isArray(p.tags) && p.tags.includes(selectedTag));
    } else {
        selectedData = [...data];
    }

    if (sortBy === 'rating') {
        selectedData.sort((a, b) => (b.avg_rating || 0) - (a.avg_rating || 0));
    } else if (sortBy === 'played') {
        selectedData.sort((a, b) => (b.play_count || 0) - (a.play_count || 0));
    }
    // 'move' keeps server default order

    ids = createIds(selectedData);

    if (games.data('select2')) {
        games.empty();
    }
    games.select2({
        data: ids,
        templateSelection: formatPuzzle,
        templateResult: formatPuzzle
    });

    if (selectedData.length > 0) {
        selectSituation(randomNumber(0, selectedData.length - 1), selectedData);
    }
}

$('#tag-filter').on('change', function() {
    applySortAndRebuild();
});

$('#sort-order').on('change', function() {
    applySortAndRebuild();
});

function formatPuzzle (state) {
    if (!state.id) {
        return state.text;
    }
    const d = selectedData[state.id];
    let indicators = '';
    if (d) {
        if (d.avg_rating > 0) indicators += ' <small style="color:#ffc107;">★' + d.avg_rating.toFixed(1) + '</small>';
        if (d.play_count > 0) indicators += ' <small style="color:#888;">▶' + d.play_count + '</small>';
    }
    if (d && d.is_puzzle) {
        return $('<span><i class="bi bi-puzzle-fill me-1" style="color: #ffc107;"></i>' + state.text + indicators + '</span>');
    } else if (d && (d.sente || d.gote)) {
        return $('<span>' + state.text + ' <small style="color: #888">(' + (d.sente || "?") + ' vs ' + (d.gote || "?") + ')</small>' + indicators + '</span>');
    }
    return $('<span>' + state.text + indicators + '</span>');
};

games.on('select2:select', function (e) {
    selectSituation(e.params.data.id, selectedData)
});

$(".random").click( function () {
    if (!Array.isArray(data) || data.length === 0) return;
    selectedData = data
    selectSituation(randomNumber(0, selectedData.length - 1), selectedData)
});

$(".lishogi-game").click( function () {
    if (selected && selected.id) {
        const puzzleId = selected.id;
        const gameHash = puzzleId.split('#')[0];
        if (gameHash && gameHash !== "unknown") {
            window.open("/lishogi-redirect?hash=" + gameHash, "_blank");
            return;
        }
    }
    
    if (selected && selected.site && selected.site.startsWith("http")) {
        window.open(selected.site, "_blank");
    } else {
        // Fallback for cases where site might be just "lishogi" or "shogiwars"
        console.warn("No valid game URL found in selected.site:", selected.site);
        Swal.fire({
            icon: 'info',
            title: (window.i18n && window.i18n['viewer.gameLinkUnavailable']) || 'Game Link Unavailable',
            text: (window.i18n && window.i18n['viewer.gameLinkUnavailableText']) || 'A direct link to this game is not available. You can try searching for it on Lishogi.',
            confirmButtonColor: '#3085d6'
        });
    }
});

$("#isPublicCheckbox").change(function() {
    if (selected && selected._id && selected._id.$oid) {
        const isPublic = $(this).is(":checked");
        const puzzleId = selected._id.$oid;
        
        $.ajax({
            url: "/viewer/toggle-public",
            type: "POST",
            contentType: "application/json",
            data: JSON.stringify({ id: puzzleId, isPublic: isPublic }),
            success: function(response) {
                // Update local data
                selected.is_public = isPublic;
                localStorage.setItem(cacheKey, JSON.stringify(data));
                
                const Toast = Swal.mixin({
                    toast: true,
                    position: 'top-end',
                    showConfirmButton: false,
                    timer: 2000,
                    timerProgressBar: true
                });
                const i18n = window.i18n || {};
                Toast.fire({
                    icon: 'success',
                    title: isPublic ? (i18n['viewer.puzzlePublic'] || 'Puzzle is now public') : (i18n['viewer.puzzlePrivate'] || 'Puzzle is now private')
                });
            },
            error: function() {
                const i18n = window.i18n || {};
                Swal.fire({
                    icon: 'error',
                    title: i18n['viewer.errorTitle'] || 'Error',
                    text: i18n['viewer.failedVisibility'] || 'Failed to update puzzle visibility.'
                });
            }
        });
    }
});

function clearShapes() {
    if (sg) {
        sg.setAutoShapes([]);
        areHintsVisible = false;
        isPieceHintVisible = false;
        const i18n = window.i18n || {};
        $("#show-hint").html('<i class="bi bi-question-circle-fill me-1"></i>' + (i18n['viewer.hint'] || 'Hint'));
    }
}

function stopAutoplay() {
    if (autoplayInterval) {
        clearInterval(autoplayInterval);
        autoplayInterval = null;
    }
    $("#continuation-autoplay").html('<i class="bi bi-play-fill"></i>').removeClass('btn-success').addClass('btn-outline-success');
}

function updateContinuationControls() {
    $("#continuation-back").prop('disabled', currentSequenceIndex < 0);
    $("#continuation-next").prop('disabled', currentSequenceIndex >= currentSequenceMoves.length - 1);
}

function playNextMove() {
    if (currentSequenceIndex < currentSequenceMoves.length - 1) {
        currentSequenceIndex++;
        const moveUsi = currentSequenceMoves[currentSequenceIndex];
        
        // Ensure animation is enabled for single step forward
        sg.set({ animation: { enabled: true } });
        
        applyMove(moveUsi, currentSequenceIndex, true);
        
        updateContinuationControls();
        return true;
    }
    stopAutoplay();
    return false;
}

function playBackMove() {
    if (currentSequenceIndex >= 0) {
        currentSequenceIndex--;
        
        // Temporarily disable animation for the entire playback process
        const wasAnimationEnabled = sg.state.animation.enabled;
        sg.set({ animation: { enabled: false } });

        // Re-render board to starting position
        sg.set({
            sfen: {
                board: sequenceStartSfen || selected.sfen,
                hands: sequenceStartHands !== null ? sequenceStartHands : selected.hands,
            },
            lastDests: sequenceStartSfen ? undefined : selected.opponentLastMovePosition,
            turnColor: sequenceStartTurn || selected.player
        });
        
        // Play moves up to current index without animation
        for (let i = 0; i <= currentSequenceIndex; i++) {
            applyMove(currentSequenceMoves[i], i, false);
        }
        
        // Restore animation setting
        sg.set({ animation: { enabled: wasAnimationEnabled } });
        
        updateContinuationControls();
    }
}

function opposite(c) {
    return c === "sente" ? "gote" : "sente";
}

function applyMove(moveUsi, index, animate = true) {
    clearShapes();
    if (!sg) return;

    // Reset board to exactly where it should be if we are skipping animations
    // to avoid any cumulative state errors
    if (animate && index > 0) {
        // We might want to ensure the board state is correct before playing next move
    }

    // Determine whose turn it is based on the move index
    // index 0, 2, 4... are for the starting color (sequenceStartTurn or selected.player)
    // index 1, 3, 5... are for the opposite color
    const startColor = sequenceStartTurn || selected.player;
    const isStartColorTurn = (index % 2 === 0);
    const currentColor = isStartColorTurn ? startColor : opposite(startColor);

    console.log(`[SEQUENCE] Applying move ${index}: ${moveUsi} for ${currentColor} (animate: ${animate})`);

    // Force piece count in hand if it's a drop to avoid Shogiground blocking it
    if (moveUsi.includes('*')) {
        const roleStr = moveUsi[0].toLowerCase();
        // Convert USI role to Shogiground role
        const roleMap = {
            'r': 'rook', 'b': 'bishop', 'g': 'gold', 's': 'silver', 
            'n': 'knight', 'l': 'lance', 'p': 'pawn'
        };
        const role = roleMap[roleStr] || roleStr;
        const dest = moveUsi.substring(2);

        const hand = sg.state.hands.handMap.get(currentColor);
        const count = hand ? (hand.get(role) || 0) : 0;
        
        if (count <= 0) {
            console.warn(`[SEQUENCE] Piece ${role} not found in ${currentColor} hand. Forcing add to allow drop.`);
            sg.addToHand({role, color: currentColor}, 1);
        }
        
        sg.set({ turnColor: currentColor });
        // Use true for 'spare' parameter to allow dropping even if piece is not in hand
        // but since we manually added it, it should work either way.
        // Also setting the role explicitly as it might be 'r' instead of 'rook' if not mapped
        sg.drop({ role, color: currentColor }, dest, false, true);
        
        // Ensure piece is removed from hand after drop
        sg.removeFromHand({ role, color: currentColor }, 1);
    } else {
        const orig = moveUsi.substring(0, 2);
        const dest = moveUsi.substring(2, 4);
        const prom = moveUsi.endsWith('+');
        
        sg.set({ turnColor: currentColor });
        
        // Check for capture before move
        const capturedPiece = sg.state.pieces.get(dest);
        
        sg.move(orig, dest, prom);

        if (capturedPiece) {
            console.log(`[SEQUENCE] Captured ${capturedPiece.role} for ${currentColor}`);
            // In Shogi, captured piece changes color and goes to capturer's hand
            // We need to unpromote it if it was promoted
            const unpromotedRole = Shogiops.variantUtil.unpromote("standard")(capturedPiece.role) || capturedPiece.role;
            sg.addToHand({ role: unpromotedRole, color: currentColor }, 1);
        }
    }
    
    // Toggle turn color for next potential interaction
    sg.set({ turnColor: opposite(currentColor) });
}

function initSequence(usiString, startingSfen, startingHands, startingTurn) {
    if (!usiString) return;
    currentSequenceMoves = usiString.split(' ');
    currentSequenceIndex = -1;

    // Store starting position (defaults to puzzle start)
    sequenceStartSfen = startingSfen || selected.sfen;
    sequenceStartHands = startingHands !== undefined ? startingHands : selected.hands;
    sequenceStartTurn = startingTurn || selected.player;

    isPlayingSequence = true;
    clearShapes();
    stopAutoplay();

    // Reset board to starting position
    sg.set({
        sfen: {
            board: sequenceStartSfen,
            hands: sequenceStartHands,
        },
        lastDests: startingSfen ? undefined : selected.opponentLastMovePosition,
        turnColor: sequenceStartTurn,
        animation: { enabled: false }
    });

    // Reset turn color and movable state to ensure clean start
    sg.set({ turnColor: sequenceStartTurn, animation: { enabled: true } });

    $("#continuation-controls").show();
    updateContinuationControls();
}

function startAutoplay() {
    if (autoplayInterval) return;
    
    $("#continuation-autoplay").html('<i class="bi bi-pause-fill"></i>').removeClass('btn-outline-success').addClass('btn-success');
    
    autoplayInterval = setInterval(() => {
        if (!playNextMove()) {
            stopAutoplay();
        }
    }, 1000);
}

function playSequence(usiString) {
    initSequence(usiString);
    // Add delay to ensure initial position is rendered and first move animation is visible
    setTimeout(() => {
        if (isPlayingSequence) {
            playNextMove(); // Play the first move immediately
            startAutoplay();
        }
    }, 200);
}

/**
 * Play engine PV on the board starting from the analyzed SFEN position.
 * Closes the Swal dialog and uses the existing continuation controls.
 */
function playPvOnBoard(pvString, sfen) {
    if (!pvString || !sfen) return;

    Swal.close();

    // Parse the full SFEN to extract board, turn, hands
    const parts = sfen.split(' ');
    const boardSfen = parts[0];
    const turnChar = parts.length >= 2 ? parts[1] : 'b';
    const handsSfen = parts.length >= 3 ? parts[2] : '-';
    const turnColor = turnChar === 'b' ? 'sente' : 'gote';

    // For Shogiground, pass the full SFEN as board (it parses internally)
    initSequence(pvString, sfen, handsSfen, turnColor);

    setTimeout(() => {
        if (isPlayingSequence) {
            playNextMove();
            startAutoplay();
        }
    }, 200);
}

$("#continuation-next").click(function() {
    stopAutoplay();
    playNextMove();
});

$("#continuation-back").click(function() {
    stopAutoplay();
    playBackMove();
});

$("#continuation-autoplay").click(function() {
    if (autoplayInterval) {
        stopAutoplay();
    } else {
        if (currentSequenceIndex >= currentSequenceMoves.length - 1) {
            // Restart if at end
            initSequence(currentSequenceMoves.join(' '));
        }
        startAutoplay();
    }
});

$(document).on('click', ".play-continuation-btn", function() {
    const type = $(this).data('type');
    if (type === 'blunder_continuation') {
        const idx = parseInt($(this).data('blunder-index'));
        if (selected && selected.blunder_continuations && selected.blunder_continuations[idx] && selected.blunder_continuations[idx].usi) {
            playSequence(selected.blunder_continuations[idx].usi);
        }
    } else if (selected && selected[type] && selected[type].usi) {
        playSequence(selected[type].usi);
    }
});

$("#play-continuation").click(function() {
    if (selected && selected.best && selected.best.usi) {
        playSequence(selected.best.usi);
    }
});

$("#show-hints").click(function() {
    if (selected) {
        if (areHintsVisible) {
            clearShapes();
        } else {
            stopAutoplay();
            isPlayingSequence = false;
            $("#continuation-controls").hide();
            
            // Reset board to initial puzzle state
            sg.set({
                sfen: {
                    board: selected.sfen,
                    hands: selected.hands,
                },
                lastDests: selected.opponentLastMovePosition,
            });
            
            setHints(selected);
        }
    }
});

// Hint button - circles the piece that should be moved for the best move
$("#show-hint").click(function() {
    if (selected) {
        if (isPieceHintVisible) {
            clearPieceHint();
        } else {
            stopAutoplay();
            isPlayingSequence = false;
            $("#continuation-controls").hide();
            
            // Reset board to initial puzzle state
            sg.set({
                sfen: {
                    board: selected.sfen,
                    hands: selected.hands,
                },
                lastDests: selected.opponentLastMovePosition,
            });
            
            showPieceHint(selected);
        }
    }
});

$(".lishogi-position").click( function () {
    const lishogiSfen = selected.sfen.replace(/ /g, '_');
    window.open("https://lishogi.org/analysis/" + lishogiSfen, "_blank");
});

// Engine analysis handler for dynamically added buttons in Swal dialogs
$(document).on('click', '.engine-analysis-btn', function() {
    const sfen = $(this).data('sfen');
    const playerColor = $(this).data('player');
    const resultContainer = document.getElementById('engine-result');

    // Disable button and show loading
    const btn = $(this);
    btn.prop('disabled', true).html('<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span> ' + ((window.i18n && window.i18n['common.analyzing']) || 'Analyzing...'));

    runEngineAnalysis(sfen, playerColor, resultContainer);

    // Re-enable button after a timeout (will be re-enabled when modal closes anyway)
    setTimeout(() => {
        btn.prop('disabled', false).html('<i class="bi bi-cpu me-1"></i>' + ((window.i18n && window.i18n['puzzle.engineAnalysis']) || 'Engine Analysis'));
    }, 5000);
});

// Play PV on board handler for dynamically added buttons in Swal dialogs
$(document).on('click', '.play-pv-btn', function() {
    const pv = $(this).data('pv');
    const sfen = $(this).data('sfen');
    playPvOnBoard(pv, sfen);
});

$(".prev-puzzle").click( function () {
    const currentId = parseInt(games.val());
    if (currentId > 0 && Array.isArray(selectedData)) {
        selectSituation(currentId - 1, selectedData);
    }
});

$(".next-puzzle").click( function () {
    const currentId = parseInt(games.val());
    if (Array.isArray(selectedData) && currentId < selectedData.length - 1) {
        selectSituation(currentId + 1, selectedData);
    }
});

function createIds(data) {
    if (!Array.isArray(data)) {
        console.error("createIds expected array, got:", typeof data, data);
        return [];
    }
    return data.map( (value, index) => {
            let obj = {}
            obj["id"] = index
            let label = value.id
            if (value.is_puzzle && value.puzzle_name) {
                // Show custom puzzle name
                label = value.puzzle_name
            } else if (value.move_number) {
                label = "Move " + value.move_number
            } else if (value.ply) {
                label = "Move " + (value.ply + 1)
            }
            if (value.tags && Array.isArray(value.tags) && value.tags.length > 0) {
                label += " [" + value.tags.join(", ") + "]"
            }
            obj["text"] = label
            return obj
        }
    )
}

function selectSituation(id, data) {
    id = parseInt(id);
    games.val(id)
    games.trigger('change.select2');
    $('.content').html('<b>' + ((window.i18n && window.i18n['viewer.playCorrectMove']) || 'Play the correct move!') + '</b>');
    $('.translate-puzzle-btn').remove();
    $('#play-continuation').hide();
    $('#continuation-options').hide().empty();
    $('#continuation-controls').hide();
    $('#show-hints').hide();
    $('#show-hint').show();
    stopAutoplay();
    isPlayingSequence = false;
    sequenceStartSfen = null;
    sequenceStartHands = null;
    sequenceStartTurn = null;
    dialogPos = null;
    dialogHighlight = null;
    $('#engine-result').hide().empty();
    selected = data[id]
    playCountIncremented = false;
    console.log("[PUZZLE] Selected puzzle data:", selected);
    
    // Update puzzle info panel
    if (selected) {
        $('#turn-text').text(selected.player === "sente" ? (window.i18n && window.i18n['puzzle.senteToMove'] ? window.i18n['puzzle.senteToMove'] : 'Sente to play') : (window.i18n && window.i18n['puzzle.goteToMove'] ? window.i18n['puzzle.goteToMove'] : 'Gote to play'));
        
        // Disable "Game" button if no valid URL and no valid hash
        const gameHash = selected.id ? selected.id.split('#')[0] : null;
        const hasValidHash = gameHash && gameHash !== "unknown";
        const hasValidUrl = selected.site && selected.site.startsWith("http");
        
        if (!hasValidHash && !hasValidUrl) {
            $(".lishogi-game").addClass('disabled').attr('title', 'Game link unavailable');
        } else {
            $(".lishogi-game").removeClass('disabled').attr('title', 'View on Lishogi');
        }

        // Apply i18n comment if a translation exists for the user's language
    if (userLang !== 'en' && selected.comments_i18n && selected.comments_i18n[userLang]) {
            selected.comment = selected.comments_i18n[userLang];
        }

        // Hide comment until move is played
        $('.content').html('<b>' + ((window.i18n && window.i18n['viewer.playCorrectMove']) || 'Play the correct move!') + '</b>');

        // Basic material count if hands are available
        if (selected.hands) {
             $('#material-text').text(selected.hands);
        } else {
             $('#material-text').text("-");
        }

        // Update nav buttons state
        $(".prev-puzzle").prop('disabled', id === 0);
        $(".next-puzzle").prop('disabled', id === data.length - 1);

        // Update public checkbox
        if ($("#isPublicCheckbox").length) {
            $("#isPublicCheckbox").prop('checked', !!selected.is_public);
        }

        // Show puzzle stats (play count + avg rating visible, stars hidden until move played)
        $('#puzzle-stats').show();
        $('#star-rating').hide();
        const pc = selected.play_count || 0;
        $('.play-count-value').text(pc);
        $('#play-count-badge').show();
        $('.avg-rating-value').text((selected.avg_rating || 0).toFixed(1));
        $('.rating-count-value').text('(' + (selected.rating_count || 0) + ')');
        updateStarDisplay(selected.my_rating || 0);
    }

    sg = Shogiground();
    sg.set(generateConfig(selected));

    sg.attach({
        board: document.getElementById('dirty'),
    });
    sg.attach({
        hands: {
            bottom: document.getElementById('hand-bottom'),
        },
    });
    sg.attach({
        hands: {
            top: document.getElementById('hand-top'),
        },
    });

    // Check training deck status
    if (selected._id && selected._id.$oid) {
        checkTrainingDeck(selected._id.$oid);
    } else {
        $('#training-btn').hide();
    }
}

function isMove(engineMove, playerMove, playerPositionMove, returnValue) {
    if (engineMove !== null && engineMove !== undefined) {
        let result = false
        if (engineMove.drop !== null && engineMove.drop !== undefined) {
            if (playerPositionMove === "DROP") {
                const engineRole = sfenRoleToShogiopsRole(engineMove.drop.drop.role);
                result = engineRole === playerMove.piece.role && engineMove.drop.drop.pos === playerMove.key;
            }
        } else {
            if (playerPositionMove === "MOVE") {
                result = engineMove.move.move.orig === playerMove.orig &&
                    engineMove.move.move.dest === playerMove.dest &&
                    engineMove.move.move.promotion === playerMove.prom
            }
        }
        if (result) {
            return returnValue
        } else {
            return -1
        }
    } else {
        return -1;
    }
}

function setHint(move) {
    if (move !== null && move !== undefined) {
        if (move.drop !== null && move.drop !== undefined) {
            return move.drop.hint
        } else {
            return move.move.hint
        }
    } else {
        return null
    }
}

function formatComment(comment) {
    if (!comment) return "";
    return comment.split('\n').map(line => {
        let cls = "";
        let icon = "";
        if (line.startsWith("Blunder")) {
            cls = "text-danger";
            icon = '<i class="bi bi-x-circle-fill me-1"></i>';
        } else if (line.startsWith("Best")) {
            cls = "text-success";
            icon = '<i class="bi bi-check-circle-fill me-1"></i>';
        } else if (line.startsWith("Second")) {
            cls = "text-purple";
            icon = '<i class="bi bi-2-circle me-1"></i>';
        } else if (line.startsWith("Third")) {
            cls = "text-info";
            icon = '<i class="bi bi-3-circle me-1"></i>';
        }
        
        if (cls) {
            return `<p class="${cls} mb-1"><b>${icon}${line}</b></p>`;
        }
        return `<p class="mb-1">${line}</p>`;
    }).join("");
}

function cpToPercent(cp) {
    if (cp >= 30000) return 100;
    if (cp <= -30000) return 0;

    // Koeficient 2450 balansuje body -528 -> ~40% a -1390 -> ~24%
    let result = 50 + 50 * Math.tanh(cp / 2450);

    return Math.round(result * 100) / 100;
}

/**
 * Extract numeric score from a move's score object.
 * Handles both formats:
 *   Regular puzzles: { cp: 250, moves: null } or { cp: null, moves: 3 }
 *   Custom puzzles:  { cp: 250 } or { mate: 3 }
 * Returns { cp, text, isMate } or null.
 */
function extractScore(score) {
    if (!score) return null;
    // Mate: check both 'moves' (regular) and 'mate' (custom) fields
    const mateVal = score.moves !== null && score.moves !== undefined ? score.moves
                  : score.mate !== null && score.mate !== undefined ? score.mate
                  : null;
    if (mateVal !== null) {
        return { cp: mateVal > 0 ? 30000 : -30000, text: `M${mateVal > 0 ? '+' : ''}${mateVal}`, isMate: true };
    }
    if (score.cp !== null && score.cp !== undefined) {
        const cp = score.cp;
        return { cp, text: cp >= 0 ? `+${cp}` : `${cp}`, isMate: false };
    }
    return null;
}

/**
 * Convert CP to a winning probability percentage (0-100).
 * CP 0 = 50%. Uses the same sigmoid as cpToPercent.
 */
function cpToWinPercent(cp) {
    return Math.round(cpToPercent(cp));
}

/**
 * Build an evaluation bar showing where each move sits on the CP scale.
 * highlightMove: 0=blunder, 1=best, 2=second, 3=third, null=none
 * engineScore: optional { kind: 'cp'|'mate', value: N } from engine analysis
 */
function buildEvalBar(pos, highlightMove, engineScore) {
    const items = [];

    // Try move detail score first, then fall back to continuation score (pos.best, pos.second, pos.third)
    function add(moveDetail, continuation, label, color, id) {
        const score = (moveDetail && moveDetail.score) ? moveDetail.score
                    : (continuation && continuation.score) ? continuation.score
                    : null;
        const s = extractScore(score);
        if (!s) return;
        const isYou = highlightMove === id;
        const youLabel = (window.i18n && window.i18n['puzzle.you']) || 'You';
        items.push({ label: isYou ? label + ' (' + youLabel + ')' : label, ...s, color, isYou, pct: cpToPercent(s.cp) });
    }

    add(pos.best_move, pos.best, '1st', '#28a745', 1);
    add(pos.second_move, pos.second, '2nd', '#ab47bc', 2);
    add(pos.third_move, pos.third, '3rd', '#17a2b8', 3);

    // Primary blunder — use first blunder_continuation as score fallback
    // (custom puzzles store scores in continuations, not in move details)
    const firstBlunderCont = (pos.blunder_continuations && pos.blunder_continuations.length > 0)
        ? pos.blunder_continuations[0] : null;
    add(pos.your_move, firstBlunderCont, 'Blunder', '#dc3545', 0);

    // Additional blunder moves (skip index 0 which duplicates your_move)
    if (pos.blunder_moves && Array.isArray(pos.blunder_moves) && pos.blunder_moves.length > 1) {
        for (let idx = 1; idx < pos.blunder_moves.length; idx++) {
            const bm = pos.blunder_moves[idx];
            let label = 'Blunder ' + (idx + 1);
            let continuation = null;
            if (pos.blunder_continuations && pos.blunder_continuations[idx]) {
                const bc = pos.blunder_continuations[idx];
                if (bc.blunder_move) label = bc.blunder_move;
                continuation = bc;
            }
            // Use lighter red and id=-1 so never highlighted as "(You)"
            add(bm, continuation, label, '#e57373', -1);
        }
    }

    // Add engine analysis marker if provided
    if (engineScore) {
        const s = extractScore(engineScore.kind === 'mate'
            ? { moves: engineScore.value }
            : { cp: engineScore.value });
        if (s) {
            items.push({ label: (window.i18n && window.i18n['puzzle.engineLabel']) || 'Engine', ...s, color: '#fff', isYou: false, isEngine: true, pct: cpToPercent(s.cp) });
        }
    }

    if (items.length === 0) return '';

    // Build marker dots on the bar
    const markers = items.map(m => {
        const isEngine = m.isEngine;
        const size = (m.isYou || isEngine) ? 18 : 12;
        const border = m.isYou ? '2px solid #fff'
                     : isEngine ? '2px solid #333'
                     : '1px solid rgba(0,0,0,0.3)';
        const zIdx = isEngine ? 12 : m.isYou ? 10 : 5;
        const winPct = cpToWinPercent(m.cp);
        const losePct = 100 - winPct;
        const youStr = (window.i18n && window.i18n['puzzle.you']) || 'You';
        const oppStr = (window.i18n && window.i18n['puzzle.opponent']) || 'Opp';
        return `<div style="position:absolute;left:${m.pct}%;top:50%;transform:translate(-50%,-50%);z-index:${zIdx};" title="${m.label}: ${m.text} — ${youStr} ${winPct}% / ${oppStr} ${losePct}%">
            <div style="width:${size}px;height:${size}px;border-radius:50%;background:${m.color};border:${border};box-shadow:0 1px 3px rgba(0,0,0,0.5);"></div>
        </div>`;
    }).join('');

    // Build legend items with win percentage tooltips on hover
    const legend = items.map(m => {
        const isEngine = m.isEngine;
        const fw = (m.isYou || isEngine) ? 'bold' : 'normal';
        const ul = m.isYou ? 'underline' : 'none';
        const clr = isEngine ? '#fff' : m.color;
        const winPct = cpToWinPercent(m.cp);
        const losePct = 100 - winPct;
        const winColor = winPct >= 50 ? '#28a745' : '#dc3545';
        const loseColor = losePct >= 50 ? '#dc3545' : '#28a745';
        return `<span style="color:${clr};font-weight:${fw};text-decoration:${ul};white-space:nowrap;cursor:help;position:relative;display:inline-block;" class="eval-legend-item">` +
            `● ${m.label}: ${m.text}` +
            `<span class="eval-win-tooltip" style="position:absolute;bottom:calc(100% + 6px);left:50%;transform:translateX(-50%);background:#1a1a2e;color:#eee;padding:6px 10px;border-radius:6px;font-size:0.85em;white-space:nowrap;z-index:20;box-shadow:0 2px 8px rgba(0,0,0,0.6);display:none;">` +
                `<span style="display:flex;align-items:center;gap:6px;">` +
                    `<span style="color:${winColor};font-weight:bold;">${(window.i18n && window.i18n['puzzle.you']) || 'You'} ${winPct}%</span>` +
                    `<span style="display:inline-block;width:60px;height:8px;background:${loseColor};border-radius:4px;overflow:hidden;">` +
                        `<span style="display:block;width:${winPct}%;height:100%;background:${winColor};"></span>` +
                    `</span>` +
                    `<span style="color:${loseColor};font-weight:bold;">${(window.i18n && window.i18n['puzzle.opponent']) || 'Opp'} ${losePct}%</span>` +
                `</span>` +
                `<span style="position:absolute;top:100%;left:50%;transform:translateX(-50%);border:5px solid transparent;border-top-color:#1a1a2e;"></span>` +
            `</span>` +
        `</span>`;
    }).join('<span style="margin:0 4px;color:#555;">|</span>');

    // CSS for hover tooltip (injected once, idempotent)
    const tooltipStyle = `<style>.eval-legend-item:hover .eval-win-tooltip{display:block!important;}</style>`;

    return `${tooltipStyle}<div id="main-eval-bar" style="margin:12px 0 4px 0;">
        <div style="position:relative;height:28px;border-radius:14px;background:linear-gradient(to right,#5c1a1a 0%,#dc3545 12%,#e8a317 38%,#28a745 62%,#1a5c28 100%);box-shadow:inset 0 1px 3px rgba(0,0,0,0.4);">
            <div style="position:absolute;left:50%;top:0;bottom:0;width:1px;background:rgba(255,255,255,0.35);z-index:1;"></div>
            ${markers}
        </div>
        <div style="display:flex;justify-content:space-between;font-size:0.6em;color:#999;margin-top:2px;padding:0 4px;">
            <span>${(window.i18n && window.i18n['puzzle.evalLosing']) || 'Losing'}</span><span>0</span><span>${(window.i18n && window.i18n['puzzle.evalWinning']) || 'Winning'}</span>
        </div>
        <div style="margin-top:4px;text-align:center;font-size:0.8em;line-height:1.8;overflow:visible;">${legend}</div>
    </div>`;
}

function fireError(pos) {
    if (isPlayingSequence) return;
    dialogPos = pos; dialogHighlight = 0;
    const lishogiSfen = pos.sfen.replace(/ /g, '_');
    Swal.fire({
        icon: 'error',
        title: (window.i18n && window.i18n['common.failure']) || 'Failure',
        width: 600,
        html: '<p>' + ((window.i18n && window.i18n['puzzle.youPlayedBadMove']) || 'You played the bad move!') + '</p>' +
            buildEvalBar(pos, 0) +
            '<div>' + formatComment(pos.comment) + '</div>' +
            getEngineAnalysisButton(pos.sfen, pos.player),
        footer: '<a href="https://lishogi.org/analysis/"' + lishogiSfen + '" target="_blank">' + ((window.i18n && window.i18n['puzzle.lishogiPosition']) || 'Lishogi position') + '</a>'
    })
}

function fireWarning(pos) {
    if (isPlayingSequence) return;
    dialogPos = pos; dialogHighlight = null;
    const lishogiSfen = pos.sfen.replace(/ /g, '_');
    Swal.fire({
        icon: 'warning',
        title: (window.i18n && window.i18n['common.warning']) || 'Warning',
        width: 600,
        html: '<p>' + ((window.i18n && window.i18n['puzzle.notBest3Moves']) || "You didn't play one of the best 3 moves! Use Engine Analysis to check your move.") + '</p>' +
            buildEvalBar(pos, null) +
            '<div>' + formatComment(pos.comment) + '</div>' +
            getEngineAnalysisButton(pos.sfen, pos.player),
        footer: '<a href="https://lishogi.org/analysis/"' + lishogiSfen + '" target="_blank">' + ((window.i18n && window.i18n['puzzle.lishogiPosition']) || 'Lishogi position') + '</a>'
    })
}

function fireSuccess(pos, num) {
    if (isPlayingSequence) return;
    dialogPos = pos; dialogHighlight = num;
    const lishogiSfen = pos.sfen.replace(/ /g, '_');
    let msg;
    switch (num) {
        case 1:
            msg = (window.i18n && window.i18n['puzzle.youFoundBest']) || "You found <b>the best</b> engine move!"
            break
        case 2:
            msg = (window.i18n && window.i18n['puzzle.youFoundSecond']) || "You found <b>the second</b> engine move! Check the best."
            break
        case 3:
            msg = (window.i18n && window.i18n['puzzle.youFoundThird']) || "You found <b>the third</b> engine move! Check the best."
            break
    }
    Swal.fire({
        icon: 'success',
        title: (window.i18n && window.i18n['common.success']) || 'Success',
        width: 600,
        html: '<p>' + msg + '</p>' +
            buildEvalBar(pos, num) +
            '<div>' + formatComment(pos.comment) + '</div>' +
            getEngineAnalysisButton(pos.sfen, pos.player),
        footer: '<a href="https://lishogi.org/analysis/"' + lishogiSfen + '" target="_blank">' + ((window.i18n && window.i18n['puzzle.lishogiPosition']) || 'Lishogi position') + '</a>'
    })
}

/**
 * Generate the engine analysis button HTML for Swal dialogs
 */
function getEngineAnalysisButton(sfen, playerColor) {
    if (!isAuthenticated) return '';
    // Get current board SFEN after player's move
    let currentSfen = sfen;
    if (sg && sg.state) {
        try {
            // Get board from shogiground state
            const board = sg.state.pieces;
            const hands = sg.state.hands;
            const turn = sg.state.turnColor === 'sente' ? 'b' : 'w';
            const moveNumber = 1; // Default
            
            // Generate SFEN components
            const boardSfen = generateBoardSfen(board);
            const handsSfen = generateHandsSfen(hands);
            
            currentSfen = `${boardSfen} ${turn} ${handsSfen} ${moveNumber}`;
        } catch (e) {
            console.warn('[VIEWER] Could not get current SFEN, using original:', e);
        }
    }
    
    return `<div class="mt-3">
        <div class="card">
            <div class="card-body p-2">
                <h6 class="card-title mb-2"><i class="bi bi-gear me-1"></i>Engine Settings</h6>
                <div class="row g-2">
                    <div class="col-6">
                        <label class="form-label small mb-1">Depth</label>
                        <select class="form-select form-select-sm" id="engine-depth">
                            <option value="5">5 plies</option>
                            <option value="10" selected>10 plies</option>
                            <option value="15">15 plies</option>
                            <option value="20">20 plies</option>
                        </select>
                    </div>
                    <div class="col-6">
                        <label class="form-label small mb-1">Time</label>
                        <select class="form-select form-select-sm" id="engine-time">
                            <option value="1">1s</option>
                            <option value="2" selected>2s</option>
                            <option value="5">5s</option>
                            <option value="10">10s</option>
                            <option value="30">30s</option>
                        </select>
                    </div>
                </div>
                <button class="btn btn-sm btn-warning w-100 mt-2 engine-analysis-btn" data-sfen="${currentSfen}" data-player="${playerColor}">
                    <i class="bi bi-cpu me-1"></i>" + ((window.i18n && window.i18n['puzzle.engineAnalysis']) || 'Engine Analysis') + "
                </button>
                            </div>
        </div>
        <div id="engine-result" class="mt-2" style="display:none;"></div>
    </div>`;
}

/**
 * Generate board SFEN from shogiground board state
 */
function generateBoardSfen(board) {
    if (!board || typeof board === 'string') {
        // Board might already be in SFEN format
        return board || '9/9/9/9/9/9/9/9/9';
    }
    
    // Board is a Map with square keys like '55', '76', etc.
    const ranks = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'];
    const files = ['9', '8', '7', '6', '5', '4', '3', '2', '1'];
    
    let sfen = '';
    let emptyCount = 0;
    
    for (let i = 0; i < 9; i++) {
        if (i > 0) sfen += '/';

        for (let j = 0; j < 9; j++) {
            const file = files[j]; // File (column) - 9,8,7,...,1
            const rank = ranks[i]; // Rank (row) - a,b,c,...,i
            const square = file + rank;
            
            const piece = board.get(square);
            
            if (piece) {
                if (emptyCount > 0) {
                    sfen += emptyCount;
                    emptyCount = 0;
                }
                // Convert shogiops role to SFEN format
                sfen += pieceToSfenRole(piece);
            } else {
                emptyCount++;
            }
        }
        
        if (emptyCount > 0) {
            sfen += emptyCount;
            emptyCount = 0;
        }
    }
    
    return sfen || '9/9/9/9/9/9/9/9/9';
}

/**
 * Convert piece to SFEN role
 */
function pieceToSfenRole(piece) {
    const roles = {
        'king': 'K', 'rook': 'R', 'bishop': 'B', 'gold': 'G',
        'silver': 'S', 'knight': 'N', 'lance': 'L', 'pawn': 'P',
        // Shogiops role names
        'promotedRook': '+R', 'promotedBishop': '+B',
        'promotedSilver': '+S', 'promotedKnight': '+N',
        'promotedLance': '+L', 'promotedPawn': '+P',
        // Shogiground native role names
        'dragon': '+R', 'horse': '+B', 'tokin': '+P',
        'promotedsilver': '+S', 'promotedknight': '+N', 'promotedlance': '+L'
    };
    
    let role = roles[piece.role] || piece.role;
    
    // Upper case for Sente (Black), lower case for Gote (White)
    if (piece.color === 'sente' || piece.color === 'gote') {
        if (piece.color === 'gote') {
            role = role.toLowerCase();
        }
    }
    
    return role;
}

/**
 * Generate hands SFEN from shogiground hands state
 */
function generateHandsSfen(hands) {
    if (!hands) return '-';

    const roleOrder = ['rook', 'bishop', 'gold', 'silver', 'knight', 'lance', 'pawn'];
    const roleToSfen = {
        'rook': 'R', 'bishop': 'B', 'gold': 'G',
        'silver': 'S', 'knight': 'N', 'lance': 'L', 'pawn': 'P'
    };

    let senteStr = '';
    let goteStr = '';

    // handMap is a Map<Color, Map<Role, number>>
    const handMap = hands.handMap || hands;

    if (handMap instanceof Map) {
        const senteHand = handMap.get('sente');
        if (senteHand instanceof Map) {
            for (const role of roleOrder) {
                const count = senteHand.get(role) || 0;
                if (count > 0) {
                    if (count > 1) senteStr += count;
                    senteStr += roleToSfen[role];
                }
            }
        }

        const goteHand = handMap.get('gote');
        if (goteHand instanceof Map) {
            for (const role of roleOrder) {
                const count = goteHand.get(role) || 0;
                if (count > 0) {
                    if (count > 1) goteStr += count;
                    goteStr += roleToSfen[role].toLowerCase();
                }
            }
        }
    }

    const result = senteStr + goteStr;
    return result || '-';
}

/**
 * Run engine analysis and display result
 */
function runEngineAnalysis(sfen, playerColor, resultContainer) {
    const depth = document.getElementById('engine-depth')?.value || 10;
    const time = document.getElementById('engine-time')?.value || 2;
    
    $.ajax({
        url: '/viewer/analyze',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({
            sfen: sfen,
            playerColor: playerColor,
            depth: parseInt(depth),
            time: parseInt(time),
            multiPv: 1
        }),
        success: function(response) {
            if (response.success && response.moves && response.moves.length > 0) {
                const bestMove = response.moves[0];
                const score = bestMove.score;
                const usiMove = bestMove.usi;
                const resultDepth = bestMove.depth;
                const timeUsed = time; // Capture the time setting
                
                // Format the score display
                let scoreText = '';
                let scoreClass = '';
                if (score.kind === 'mate') {
                    if (score.value > 0) {
                        scoreText = `Mate in +${score.value}`;
                        scoreClass = 'text-success';
                    } else if (score.value < 0) {
                        scoreText = `Mated in ${score.value}`;
                        scoreClass = 'text-danger';
                    } else {
                        scoreText = 'Draw';
                        scoreClass = 'text-warning';
                    }
                } else {
                    const cpValue = score.value;
                    if (cpValue > 0) {
                        scoreText = `+${cpValue}`;
                        scoreClass = 'text-success';
                    } else if (cpValue < 0) {
                        scoreText = `${cpValue}`;
                        scoreClass = 'text-danger';
                    } else {
                        scoreText = '0';
                        scoreClass = 'text-muted';
                    }
                }
                
                // Update the main eval bar to include engine score marker
                if (dialogPos) {
                    const updatedBar = buildEvalBar(dialogPos, dialogHighlight, score);
                    $('#main-eval-bar').replaceWith(updatedBar);
                }

                // Build PV display and play button
                const pvString = bestMove.pv || '';
                const pvMoves = pvString ? pvString.split(' ') : [];
                let pvHtml = '';
                if (pvMoves.length > 0) {
                    pvHtml = `<div class="mt-1"><small class="text-muted">PV: ${pvMoves.join(' ')}</small></div>`;
                    pvHtml += `<button class="btn btn-sm btn-outline-success w-100 mt-2 play-pv-btn" data-pv="${pvString}" data-sfen="${sfen}">
                        <i class="bi bi-play-fill me-1"></i>` + ((window.i18n && window.i18n['puzzle.playOnBoard']) || 'Play on Board') + ` (${pvMoves.length} ` + ((window.i18n && window.i18n['puzzle.move']) || 'Move') + `)
                    </button>`;
                }

                resultContainer.innerHTML = `
                    <div class="alert alert-light border mb-0">
                        <strong><i class="bi bi-cpu me-1"></i>" + ((window.i18n && window.i18n['puzzle.engine']) || 'Engine:') + "</strong>
                        <span class="${scoreClass}" style="font-size: 1.1em; font-weight: bold;">${scoreText}</span>
                        <small class="text-muted ms-2">(${resultDepth} plies, ${timeUsed}s)</small>
                        ${pvHtml}
                    </div>
                `;
                resultContainer.style.display = 'block';
            } else {
                resultContainer.innerHTML = `
                    <div class="alert alert-warning">
                        Analysis failed: ${response.error || 'No moves found'}
                    </div>
                `;
                resultContainer.style.display = 'block';
            }
        },
        error: function(xhr, status, error) {
            resultContainer.innerHTML = `
                <div class="alert alert-danger">
                    Error: ${error}
                </div>
            `;
            resultContainer.style.display = 'block';
        }
    });
}

// ===== Star Rating =====
function updateStarDisplay(rating) {
    $('#star-rating .star-btn').each(function() {
        const val = parseInt($(this).data('value'));
        const icon = $(this).find('i');
        if (val <= rating) {
            icon.removeClass('bi-star').addClass('bi-star-fill');
            $(this).css('color', '#ffc107');
        } else {
            icon.removeClass('bi-star-fill').addClass('bi-star');
            $(this).css('color', '#666');
        }
    });
    if (rating > 0) {
        $('#rating-label').text(rating + '/5');
    } else {
        $('#rating-label').text(isAuthenticated ? ((window.i18n && window.i18n['viewer.ratePuzzle']) || 'Rate this puzzle') : ((window.i18n && window.i18n['viewer.loginToRate']) || 'Login to rate'));
    }
}

$('#star-rating').on('mouseenter', '.star-btn', function() {
    const hoverVal = parseInt($(this).data('value'));
    $('#star-rating .star-btn').each(function() {
        const v = parseInt($(this).data('value'));
        const icon = $(this).find('i');
        if (v <= hoverVal) {
            icon.removeClass('bi-star').addClass('bi-star-fill');
            $(this).css('color', '#ffc107');
        } else {
            icon.removeClass('bi-star-fill').addClass('bi-star');
            $(this).css('color', '#666');
        }
    });
}).on('mouseleave', '.star-btn', function() {
    updateStarDisplay(selected ? (selected.my_rating || 0) : 0);
});

$('#star-rating').on('click', '.star-btn', function() {
    if (!isAuthenticated) {
        const Toast = Swal.mixin({ toast: true, position: 'top-end', showConfirmButton: false, timer: 2000, timerProgressBar: true });
        Toast.fire({ icon: 'info', title: (window.i18n && window.i18n['puzzle.loginToRate']) || 'Login required to rate puzzles' });
        return;
    }
    if (!selected || !selected._id || !selected._id.$oid) return;

    const stars = parseInt($(this).data('value'));
    $.ajax({
        url: '/viewer/rate',
        type: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ id: selected._id.$oid, stars: stars }),
        success: function() {
            // Update local data
            const wasNew = !selected.my_rating || selected.my_rating === 0;
            selected.my_rating = stars;

            // Recompute avg locally
            const oldCount = selected.rating_count || 0;
            const oldAvg = selected.avg_rating || 0;
            if (wasNew) {
                selected.rating_count = oldCount + 1;
                selected.avg_rating = (oldAvg * oldCount + stars) / selected.rating_count;
            } else {
                // Replace existing rating in average
                if (oldCount > 0) {
                    selected.avg_rating = (oldAvg * oldCount - selected._prev_rating + stars) / oldCount;
                }
            }
            selected._prev_rating = stars;

            updateStarDisplay(stars);
            $('.avg-rating-value').text(selected.avg_rating.toFixed(1));
            $('.rating-count-value').text('(' + selected.rating_count + ')');

            // Update cache
            localStorage.setItem(cacheKey, JSON.stringify(data));

            const Toast = Swal.mixin({ toast: true, position: 'top-end', showConfirmButton: false, timer: 1500, timerProgressBar: true });
            Toast.fire({ icon: 'success', title: 'Rated ' + stars + ' star' + (stars > 1 ? 's' : '') });
        },
        error: function() {
            const Toast = Swal.mixin({ toast: true, position: 'top-end', showConfirmButton: false, timer: 2000, timerProgressBar: true });
            Toast.fire({ icon: 'error', title: (window.i18n && window.i18n['puzzle.rateFailed']) || 'Failed to save rating' });
        }
    });
});

function setHints(pos) {
    if (isPlayingSequence) return;
    const hints = [setHint(pos.best_move), setHint(pos.second_move), setHint(pos.third_move),
        setHint(pos.your_move)];
    // Add hints from blunder_moves array
    if (pos.blunder_moves && Array.isArray(pos.blunder_moves)) {
        pos.blunder_moves.forEach(bm => {
            hints.push(setHint(bm));
        });
    }
    sg.setAutoShapes(hints.filter(elements => {
        return elements !== null;
    }))
    areHintsVisible = true;
}

// Show a circle around the piece that should be moved for the best move
function showPieceHint(pos) {
    if (isPlayingSequence || !pos || !pos.best_move) return;
    
    const hint = setHint(pos.best_move);
    if (!hint) return;
    
    // The hint object directly has orig and dest properties
    let shape = null;
    
    // Check if it's a drop (has role in orig) or regular move
    if (hint.orig && typeof hint.orig === 'object' && hint.orig.role) {
        // Drop move - hint.orig contains {role, color} and hint.dest contains the square
        if (hint.dest) {
            shape = {
                orig: hint.dest,
                dest: hint.dest,
                brush: "primary"
            };
        }
    } else if (hint.orig && typeof hint.orig === 'string') {
        // Regular move - show circle on origin square
        shape = {
            orig: hint.orig,
            dest: hint.orig,
            brush: "primary"
        };
    }
    
    if (shape) {
        sg.setAutoShapes([shape]);
        isPieceHintVisible = true;
        $("#show-hint").html('<i class="bi bi-x-circle-fill me-1"></i>' + ((window.i18n && window.i18n['viewer.hideHint']) || 'Hide Hint'));
    }
}

// Clear the piece hint
function clearPieceHint() {
    if (sg) {
        sg.setAutoShapes([]);
    }
    isPieceHintVisible = false;
    $("#show-hint").html('<i class="bi bi-question-circle-fill me-1"></i>' + ((window.i18n && window.i18n['viewer.hint']) || 'Hint'));
}

function incrementPlayCount() {
    if (playCountIncremented) return;
    if (!selected || !selected._id || !selected._id.$oid) return;
    playCountIncremented = true;
    $.ajax({
        url: '/viewer/play',
        type: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ id: selected._id.$oid }),
        success: function() {
            selected.play_count = (selected.play_count || 0) + 1;
            $('.play-count-value').text(selected.play_count);
        }
    });
}

function resolveMove(pos, r0, r1, r2, r3) {
    incrementPlayCount();
    $('#star-rating').show();
    $(".content").html(formatComment(pos.comment))
    $("#show-hints").show();
    $("#show-hint").hide();

    // Show translate button if user is authenticated and there is a comment
    if (isAuthenticated && pos.comment && pos._id && pos._id.$oid) {
        const hasSk = pos.hasTranslation && pos.hasTranslation.sk;
        const btnLabel = hasSk ? '🌐 Edit Translation' : '🌐 Translate';
        const $existing = $('.translate-puzzle-btn');
        if ($existing.length === 0) {
            const $btn = $('<button class="btn btn-sm btn-outline-secondary translate-puzzle-btn w-100 mt-1">' + btnLabel + '</button>');
            $btn.click(() => openTranslateModal(pos));
            $('.content').after($btn);
        } else {
            $existing.text(btnLabel).off('click').click(() => openTranslateModal(pos));
        }
    } else {
        $('.translate-puzzle-btn').remove();
    }
    
    // Add continuation buttons for top 3 moves
    let continuationHtml = "";
    if (pos.best && pos.best.usi && pos.best.usi.split(' ').length > 0) {
        continuationHtml += `<button class="btn btn-sm btn-outline-success play-continuation-btn me-1 mb-1" data-type="best"><i class="bi bi-1-circle me-1"></i>Top 1</button>`;
    }
    if (pos.second && pos.second.usi && pos.second.usi.split(' ').length > 0) {
        continuationHtml += `<button class="btn btn-sm btn-outline-purple play-continuation-btn me-1 mb-1" data-type="second"><i class="bi bi-2-circle me-1"></i>Top 2</button>`;
    }
    if (pos.third && pos.third.usi && pos.third.usi.split(' ').length > 0) {
        continuationHtml += `<button class="btn btn-sm btn-outline-info play-continuation-btn me-1 mb-1" data-type="third"><i class="bi bi-3-circle me-1"></i>Top 3</button>`;
    }
    // Add blunder continuation buttons
    if (pos.blunder_continuations && Array.isArray(pos.blunder_continuations) && pos.blunder_continuations.length > 0) {
        pos.blunder_continuations.forEach((bc, idx) => {
            if (bc.usi) {
                continuationHtml += `<button class="btn btn-sm btn-outline-danger play-continuation-btn me-1 mb-1" data-type="blunder_continuation" data-blunder-index="${idx}"><i class="bi bi-exclamation-triangle me-1"></i>${bc.blunder_move || 'B' + (idx + 1)}</button>`;
            }
        });
    }

    if (continuationHtml) {
        $("#continuation-options").html(continuationHtml).show();
        $("#play-continuation").hide(); // Hide the old single button
    }

    if (isPlayingSequence) return;

    if (r0 !== -1) {
        fireError(pos)
    } else {
        if (Math.max(r1, r2, r3) !== -1) {
            fireSuccess(pos, Math.max(r1, r2, r3))
        } else {
            fireWarning(pos)
        }
    }
}

function generateConfig(pos) {
    console.log("[PUZZLE] Position data:", pos);
    console.log("[PUZZLE] SFEN:", pos.sfen);
    console.log("[PUZZLE] Hands:", pos.hands);
    console.log("[PUZZLE] Player:", pos.player);
    return {
        sfen: {
            board: pos.sfen,
            hands: pos.hands,
        },
        orientation: pos.player,
        turnColor: pos.player,
        activeColor: pos.player,
        lastDests: pos.opponentLastMovePosition,
        movable: {
            free: false,
            dests: Shogiops.compat.shogigroundMoveDests(Shogiops.sfen.parseSfen("standard", pos.sfen, false).value),
        },
        droppable: {
            free: false,
            dests: (() => {
                // Build full SFEN with hands for proper drop dests calculation
                // SFEN format: <board> <turn> <hands> <moveNumber>
                console.log("[PUZZLE] pos.sfen:", pos.sfen);
                console.log("[PUZZLE] pos.hands:", pos.hands);
                console.log("[PUZZLE] pos.player:", pos.player);
                
                // Check if pos.sfen already contains the full SFEN or just the board
                const sfenParts = pos.sfen.split(' ');
                let fullSfen;
                
                if (sfenParts.length >= 3) {
                    // pos.sfen already contains turn and hands
                    fullSfen = pos.sfen;
                } else {
                    // pos.sfen contains only the board part, we need to add turn and hands
                    const turnChar = pos.player === 'sente' ? 'b' : 'w';
                    const handsStr = pos.hands || '-';
                    fullSfen = `${pos.sfen} ${turnChar} ${handsStr} 1`;
                }
                
                console.log("[PUZZLE] Full SFEN for drop dests:", fullSfen);
                const parsed = Shogiops.sfen.parseSfen("standard", fullSfen, false);
                console.log("[PUZZLE] Parsed SFEN:", parsed);
                if (parsed.isOk) {
                    const dests = Shogiops.compat.shogigroundDropDests(parsed.value);
                    console.log("[PUZZLE] Drop dests:", dests);
                    return dests;
                } else {
                    console.error("[PUZZLE] Failed to parse SFEN for drop dests:", parsed.error);
                    return new Map();
                }
            })(),
        },
        promotion: {
            promotesTo: role => {
                return Shogiops.variantUtil.promote("standard")(role);
            },
            movePromotionDialog: (orig, dest) => {
                const piece = sg.state.pieces.get(orig);
                if (!piece) return false;
                const capture = sg.state.pieces.get(dest) | undefined;
                return Shogiops.variantUtil.pieceCanPromote("standard")(piece, Shogiops.parseSquare(orig), Shogiops.parseSquare(dest), capture)
                    && !Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest))
            },
            forceMovePromotion: (orig, dest) => {
                const piece = sg.state.pieces.get(orig);
                if (!piece) return false;
                return Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest))
            },
        },
        events: {
            move: (a, b, prom) => {
                if (isPlayingSequence) return;
                $(".content").html(formatComment(pos.comment))

                let r0 = isMove(pos.your_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 0)
                // Check blunder_moves array (multiple blunders)
                if (r0 === -1 && pos.blunder_moves && Array.isArray(pos.blunder_moves)) {
                    for (let i = 0; i < pos.blunder_moves.length; i++) {
                        r0 = isMove(pos.blunder_moves[i], {"orig": a, "dest": b, "prom": prom}, "MOVE", 0)
                        if (r0 !== -1) break;
                    }
                }
                let r1 = isMove(pos.best_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 1)
                let r2 = isMove(pos.second_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 2)
                let r3 = isMove(pos.third_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 3)
                setHints(pos)
                resolveMove(pos, r0, r1, r2, r3)
            },
            drop: (piece, key, prom) => {
                if (isPlayingSequence) return;
                $(".content").html(formatComment(pos.comment))

                let r0 = isMove(pos.your_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 0)
                // Check blunder_moves array (multiple blunders)
                if (r0 === -1 && pos.blunder_moves && Array.isArray(pos.blunder_moves)) {
                    for (let i = 0; i < pos.blunder_moves.length; i++) {
                        r0 = isMove(pos.blunder_moves[i], {"piece": piece, "key": key, "prom": prom}, "DROP", 0)
                        if (r0 !== -1) break;
                    }
                }
                let r1 = isMove(pos.best_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 1)
                let r2 = isMove(pos.second_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 2)
                let r3 = isMove(pos.third_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 3)
                setHints(pos)
                resolveMove(pos, r0, r1, r2, r3)
            },
        },
    }
}

function randomNumber(min, max) {
    return Math.floor(min + Math.random()*(max - min + 1))
}

// ===== Training Deck Integration =====
function checkTrainingDeck(puzzleOid) {
    if (!isAuthenticated || !puzzleOid) {
        $('#training-btn').hide();
        return;
    }
    $.ajax({
        url: '/training/check?puzzle_object_id=' + puzzleOid,
        dataType: 'json',
        success: function(resp) {
            const btn = $('#training-btn');
            btn.show();
            if (resp.in_deck) {
                btn.removeClass('btn-outline-warning').addClass('btn-warning');
                btn.find('.training-btn-text').text('Remove from Deck');
            } else {
                btn.removeClass('btn-warning').addClass('btn-outline-warning');
                btn.find('.training-btn-text').text('Add to Deck');
            }
        },
        error: function() {
            $('#training-btn').hide();
        }
    });
}

// ===== Puzzle Translation =====

function openTranslateModal(pos) {
    if (!pos || !pos._id || !pos._id.$oid) return;
    const puzzleId = pos._id.$oid;
    const enComment = pos.comment || '';
    const skComment = (pos.comments_i18n && pos.comments_i18n.sk) ? pos.comments_i18n.sk : '';

    Swal.fire({
        title: '🌐 Translate Puzzle Comment',
        width: 640,
        html: `
            <div class="text-start">
                <label class="form-label fw-bold mb-1">Original (EN):</label>
                <div class="border rounded p-2 mb-3 text-muted" style="white-space:pre-wrap;font-size:0.9em;max-height:120px;overflow-y:auto;">${enComment || '<em>No comment</em>'}</div>
                <label class="form-label fw-bold mb-1">Translation (SK):</label>
                <textarea id="swal-sk-comment" class="form-control mb-2" rows="4" placeholder="Slovak translation...">${skComment}</textarea>
                <button id="swal-auto-translate" class="btn btn-sm btn-outline-info w-100">Auto-translate (MyMemory)</button>
            </div>`,
        showCancelButton: true,
        confirmButtonText: 'Save',
        cancelButtonText: 'Cancel',
        didOpen: () => {
            document.getElementById('swal-auto-translate').addEventListener('click', function() {
                const btn = this;
                btn.disabled = true;
                btn.textContent = 'Translating...';
                $.ajax({
                    url: '/api/translate',
                    method: 'POST',
                    contentType: 'application/json',
                    data: JSON.stringify({ text: enComment, from: 'en', to: 'sk' }),
                    success: function(resp) {
                        if (resp.translated) {
                            document.getElementById('swal-sk-comment').value = resp.translated;
                        }
                    },
                    error: function() {
                        Swal.showValidationMessage('Auto-translate failed. Please try again.');
                    },
                    complete: function() {
                        btn.disabled = false;
                        btn.textContent = 'Auto-translate (MyMemory)';
                    }
                });
            });
        },
        preConfirm: () => {
            return document.getElementById('swal-sk-comment').value;
        }
    }).then(result => {
        if (result.isConfirmed) {
            const skText = result.value || '';
            $.ajax({
                url: '/puzzle-creator/translate',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({ id: puzzleId, lang: 'sk', comment: skText, moveComments: {} }),
                success: function() {
                    // Update local data
                    if (!pos.comments_i18n) pos.comments_i18n = {};
                    pos.comments_i18n.sk = skText;
                    if (!pos.hasTranslation) pos.hasTranslation = {};
                    pos.hasTranslation.sk = skText.length > 0;
                    // Update cache
                    localStorage.setItem(cacheKey, JSON.stringify(data));
                    const Toast = Swal.mixin({ toast: true, position: 'top-end', showConfirmButton: false, timer: 2000, timerProgressBar: true });
                    Toast.fire({ icon: 'success', title: 'Translation saved' });
                },
                error: function() {
                    const Toast = Swal.mixin({ toast: true, position: 'top-end', showConfirmButton: false, timer: 2000, timerProgressBar: true });
                    Toast.fire({ icon: 'error', title: 'Failed to save translation' });
                }
            });
        }
    });
}

$('#training-btn').click(function() {
    if (!selected || !selected._id || !selected._id.$oid) return;
    const puzzleOid = selected._id.$oid;
    const btn = $(this);
    const isInDeck = btn.hasClass('btn-warning');
    const source = selected.is_puzzle ? 'puzzles' : 'puzzles';

    btn.prop('disabled', true);

    if (isInDeck) {
        $.ajax({
            url: '/training/remove',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ puzzle_object_id: puzzleOid }),
            success: function() {
                btn.removeClass('btn-warning').addClass('btn-outline-warning');
                btn.find('.training-btn-text').text((window.i18n && window.i18n['viewer.addToDeck']) || 'Add to Deck');
                const Toast = Swal.mixin({ toast: true, position: 'top-end', showConfirmButton: false, timer: 1500, timerProgressBar: true });
                Toast.fire({ icon: 'success', title: (window.i18n && window.i18n['puzzle.removedFromDeck']) || 'Removed from training deck' });
            },
            complete: function() { btn.prop('disabled', false); }
        });
    } else {
        $.ajax({
            url: '/training/add',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ puzzle_object_id: puzzleOid, puzzle_source: source }),
            success: function() {
                btn.removeClass('btn-outline-warning').addClass('btn-warning');
                btn.find('.training-btn-text').text((window.i18n && window.i18n['viewer.removeFromDeck']) || 'Remove from Deck');
                const Toast = Swal.mixin({ toast: true, position: 'top-end', showConfirmButton: false, timer: 1500, timerProgressBar: true });
                Toast.fire({ icon: 'success', title: (window.i18n && window.i18n['puzzle.addedToDeck']) || 'Added to training deck' });
            },
            error: function(xhr) {
                if (xhr.status === 409) {
                    btn.removeClass('btn-outline-warning').addClass('btn-warning');
                    btn.find('.training-btn-text').text((window.i18n && window.i18n['viewer.removeFromDeck']) || 'Remove from Deck');
                }
            },
            complete: function() { btn.prop('disabled', false); }
        });
    }
});
