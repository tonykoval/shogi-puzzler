let data, ids, sg, selected, selectedData
let isPlayingSequence = false;
let currentSequenceMoves = [];
let currentSequenceIndex = -1;
let autoplayInterval = null;
let areHintsVisible = false;

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
const isPublicPage = window.location.pathname === "/puzzles";

let apiUrl = hash ? "data?hash=" + hash : "data";
if (isPublicPage) {
    apiUrl = "/public-data";
}

const cacheKey = isPublicPage ? "puzzles_public" : (hash ? "puzzles_" + hash : "puzzles_all");

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
                Swal.fire({
                    icon: 'success',
                    title: 'Data Reloaded',
                    text: 'Puzzles have been updated from the database.',
                    timer: 1500,
                    showConfirmButton: false
                });
            }
        },
        error: function(xhr, status, error) {
            console.error("Error fetching data", error);
            Swal.fire({
                icon: 'error',
                title: 'Error',
                text: 'Failed to fetch data from the server.'
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
                title: 'Access Denied',
                text: json.error
            });
        }
        return;
    }
    data = json
    selectedData = data
    ids = createIds(data)

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

function formatPuzzle (state) {
    if (!state.id) {
        return state.text;
    }
    const data = selectedData[state.id];
    if (data && (data.sente || data.gote)) {
        return $('<span>' + state.text + ' <small style="color: #888">(' + (data.sente || "?") + ' vs ' + (data.gote || "?") + ')</small></span>');
    }
    return state.text;
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
            title: 'Game Link Unavailable',
            text: 'A direct link to this game is not available. You can try searching for it on ' + (selected.site || 'the original platform') + '.',
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
                Toast.fire({
                    icon: 'success',
                    title: isPublic ? 'Puzzle is now public' : 'Puzzle is now private'
                });
            },
            error: function() {
                Swal.fire({
                    icon: 'error',
                    title: 'Error',
                    text: 'Failed to update puzzle visibility.'
                });
            }
        });
    }
});

function clearShapes() {
    if (sg) {
        sg.setAutoShapes([]);
        areHintsVisible = false;
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

        // Re-render board to initial state
        sg.set({
            sfen: {
                board: selected.sfen,
                hands: selected.hands,
            },
            lastDests: selected.opponentLastMovePosition,
            turnColor: selected.player
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
    // index 0, 2, 4... are for the player who starts the puzzle (selected.player)
    // index 1, 3, 5... are for the opponent
    const isInitialPlayerTurn = (index % 2 === 0);
    const playerColor = selected.player;
    const opponentColor = playerColor === 'sente' ? 'gote' : 'sente';
    const currentColor = isInitialPlayerTurn ? playerColor : opponentColor;

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

function initSequence(usiString) {
    if (!usiString) return;
    currentSequenceMoves = usiString.split(' ');
    currentSequenceIndex = -1;
    
    isPlayingSequence = true;
    clearShapes();
    stopAutoplay();
    
    // Reset board to initial puzzle state
    sg.set({
        sfen: {
            board: selected.sfen,
            hands: selected.hands,
        },
        lastDests: selected.opponentLastMovePosition,
        turnColor: selected.player,
        animation: { enabled: false }
    });
    
    // Reset turn color and movable state to ensure clean start
    sg.set({ turnColor: selected.player, animation: { enabled: true } });

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
    if (selected && selected[type] && selected[type].usi) {
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

$(".lishogi-position").click( function () {
    const lishogiSfen = selected.sfen.replace(/ /g, '_');
    window.open("https://lishogi.org/analysis/" + lishogiSfen, "_blank");
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

$(".save-comment").click( function () {
    const text = $(".content").val()
    fireSave(text)
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
            if (value.move_number) {
                label = "Move " + value.move_number
            } else if (value.ply) {
                label = "Move " + (value.ply + 1)
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
    $('.content').html('<b>Play the correct move!</b>');
    $('.save-comment').hide();
    $('#play-continuation').hide();
    $('#continuation-options').hide().empty();
    $('#continuation-controls').hide();
    $('#show-hints').hide();
    stopAutoplay();
    isPlayingSequence = false;
    selected = data[id]

    // Update puzzle info panel
    if (selected) {
        $('#players-text').html('<i class="bi bi-people-fill me-1"></i>' + (selected.sente || "?") + " vs " + (selected.gote || "?"));
        $('#turn-text').text(selected.player === "sente" ? "Sente to play" : "Gote to play");
        
        // Disable "Game" button if no valid URL and no valid hash
        const gameHash = selected.id ? selected.id.split('#')[0] : null;
        const hasValidHash = gameHash && gameHash !== "unknown";
        const hasValidUrl = selected.site && selected.site.startsWith("http");
        
        if (!hasValidHash && !hasValidUrl) {
            $(".lishogi-game").addClass('disabled').attr('title', 'Game link unavailable');
        } else {
            $(".lishogi-game").removeClass('disabled').attr('title', 'View on Lishogi');
        }

        // Hide comment until move is played
        $('.content').html('<b>Play the correct move!</b>');

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
}

function isMove(engineMove, playerMove, playerPositionMove, returnValue) {
    if (engineMove !== null && engineMove !== undefined) {
        let result = false
        if (engineMove.drop !== null && engineMove.drop !== undefined) {
            if (playerPositionMove === "DROP") {
                const engineRole = sfenRoleToShogiopsRole(engineMove.drop.dropMove.pieceRole);
                result = engineRole === playerMove.piece.role && engineMove.drop.dropMove.destinationSquare === playerMove.key;
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
            cls = "text-warning";
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

function fireError(pos) {
    if (isPlayingSequence) return;
    const lishogiSfen = pos.sfen.replace(/ /g, '_');
    Swal.fire({
        icon: 'error',
        title: 'Failure',
        html: '<p>You played the bad move!</p> ' +
            '<div>' + formatComment(pos.comment) + '</div>',
        footer: '<a href="https://lishogi.org/analysis/' + lishogiSfen + '" target="_blank">Lishogi position</a>'
    })
}

function fireWarning(pos) {
    if (isPlayingSequence) return;
    const lishogiSfen = pos.sfen.replace(/ /g, '_');
    Swal.fire({
        icon: 'warning',
        title: 'Warning',
        html: '<p>You didn\'t played one of the best 3 moves! Please analyze your move.</p>' +
            '<div>' + formatComment(pos.comment) + '</div>',
        footer: '<a href="https://lishogi.org/analysis/' + lishogiSfen + '" target="_blank">Lishogi position</a>'
    })
}

function fireSuccess(pos, num) {
    if (isPlayingSequence) return;
    const lishogiSfen = pos.sfen.replace(/ /g, '_');
    let msg;
    switch (num) {
        case 1:
            msg = "You found <b>the best</b> engine move!"
            break
        case 2:
            msg = "You found <b>the second</b> engine move! Check the best."
            break
        case 3:
            msg = "You found <b>the third</b> engine move! Check the best."
            break
    }
    Swal.fire({
        icon: 'success',
        title: 'Success',
        html: '<p> ' + msg + '</p> ' +
            '<div>' + formatComment(pos.comment) + '</div>',
        footer: '<a href="https://lishogi.org/analysis/' + lishogiSfen + '" target="_blank">Lishogi position</a>'
    })
}

function fireSave(text) {
    Swal.fire({
        icon: 'question',
        title: 'Update',
        html: '<p>Do you want update the comment?</p>' +
            '<div>' + formatComment(text) + '</div>' +
            '<p><b>Feedback</b></p>',
        showCancelButton: true,
        input: 'radio',
        // TODO
        inputOptions: {'yes': 'Yes',
            'no': 'No'},
        inputValidator: (value) => {
            if (!value) {
                // TODO
                return 'You need to choose something!'
            }
        },
        confirmButtonColor: '#3085d6',
        cancelButtonColor: '#d33',
        confirmButtonText: 'Send!'
    }).then((result) => {
        if (result.isConfirmed) {
            const jsondata = {"id": selected.id, "comment": text, "timestamp": Date.now(),
                feedback: Swal.getInput().value};
            
            fetch('/api/feedback', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify(jsondata),
            })
            .then(response => response.json())
            .then(data => {
                console.log('Success:', data);
            })
            .catch((error) => {
                console.error('Error:', error);
            });
        }
    })
}

function setHints(pos) {
    if (isPlayingSequence) return;
    sg.setAutoShapes([setHint(pos.best_move), setHint(pos.second_move), setHint(pos.third_move),
        setHint(pos.your_move)].filter(elements => {
        return elements !== null;
    }))
    areHintsVisible = true;
}

function resolveMove(pos, r0, r1, r2, r3) {
    $(".content").html(formatComment(pos.comment))
    $("#show-hints").show();
    
    // Add continuation buttons for top 3 moves
    let continuationHtml = "";
    if (pos.best && pos.best.usi && pos.best.usi.split(' ').length > 0) {
        continuationHtml += `<button class="btn btn-sm btn-outline-success play-continuation-btn me-1 mb-1" data-type="best"><i class="bi bi-1-circle me-1"></i>Top 1</button>`;
    }
    if (pos.second && pos.second.usi && pos.second.usi.split(' ').length > 0) {
        continuationHtml += `<button class="btn btn-sm btn-outline-warning play-continuation-btn me-1 mb-1" data-type="second"><i class="bi bi-2-circle me-1"></i>Top 2</button>`;
    }
    if (pos.third && pos.third.usi && pos.third.usi.split(' ').length > 0) {
        continuationHtml += `<button class="btn btn-sm btn-outline-info play-continuation-btn me-1 mb-1" data-type="third"><i class="bi bi-3-circle me-1"></i>Top 3</button>`;
    }
    
    if (continuationHtml) {
        $("#continuation-options").html(continuationHtml).show();
        $("#play-continuation").hide(); // Hide the old single button
        // Initialize sequence data even before user clicks "Play", so manual stepping works
        // But maybe better wait for click to avoid clearing arrows prematurely if they just finished the puzzle
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
    console.log(pos)
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
            dests: Shogiops.compat.shogigroundDropDests(Shogiops.sfen.parseSfen("standard", pos.sfen, false).value),
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
                let r1 = isMove(pos.best_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 1)
                let r2 = isMove(pos.second_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 2)
                let r3 = isMove(pos.third_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 3)
                setHints(pos)
                resolveMove(pos, r0, r1, r2, r3)

                $(".save-comment").show()
            },
            drop: (piece, key, prom) => {
                if (isPlayingSequence) return;
                $(".content").html(formatComment(pos.comment))

                let r0 = isMove(pos.your_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 0)
                let r1 = isMove(pos.best_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 1)
                let r2 = isMove(pos.second_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 2)
                let r3 = isMove(pos.third_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 3)
                setHints(pos)
                resolveMove(pos, r0, r1, r2, r3)

                $(".save-comment").show()
            },
        },
    }
}

function randomNumber(min, max) {
    return Math.floor(min + Math.random()*(max - min + 1))
}
