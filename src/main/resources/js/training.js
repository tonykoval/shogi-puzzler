let sg, currentCard, currentPuzzle;
let sessionSolved = 0;
let sessionCorrect = 0;
let puzzleStartTime = 0;
let puzzleResolved = false;

// Mapping between backend SFEN role notation and shogiops role notation
const sfenToShogiopsRole = {
    'K': 'king', 'R': 'rook', 'B': 'bishop', 'G': 'gold', 'S': 'silver',
    'N': 'knight', 'L': 'lance', 'P': 'pawn',
    '+R': 'promotedRook', '+B': 'promotedBishop', '+S': 'promotedSilver',
    '+N': 'promotedKnight', '+L': 'promotedLance', '+P': 'promotedPawn'
};

function sfenRoleToShogiopsRole(sfenRole) {
    const normalizedRole = sfenRole.replace(/^[+-]?/, '');
    const upperRole = normalizedRole.toUpperCase();
    return sfenToShogiopsRole[upperRole] || normalizedRole.toLowerCase();
}

function loadStats() {
    $.ajax({
        url: '/training/stats',
        dataType: 'json',
        success: function(stats) {
            $('#stat-due').text(stats.due);
            $('#stat-total').text(stats.total);
            $('#stat-success').text(stats.success_rate + '%');
            $('#stat-streak').text(stats.streak);
        }
    });
}

function updateSessionProgress() {
    $('#session-progress').text('Session: ' + sessionSolved + ' solved, ' + sessionCorrect + ' correct');
}

function loadNextPuzzle() {
    puzzleResolved = false;
    $('#next-training-btn').hide();
    $('#training-message').html('<span class="spinner-border spinner-border-sm me-2"></span>Loading...');
    $('#card-info').hide();

    $.ajax({
        url: '/training/next',
        dataType: 'json',
        success: function(data) {
            if (data.empty) {
                showEmptyState();
                return;
            }
            if (data.error) {
                $('#training-message').html('<span class="text-danger">' + data.error + '</span>');
                return;
            }

            currentCard = data.card;
            currentPuzzle = data.puzzle;

            // Show board areas, hide empty state
            $('#empty-state').hide();
            $('.puzzle__board, .puzzle__controls, .puzzle__side').show();
            $('#training-message').html('<b>Play the correct move!</b>');

            // Show card info
            $('#card-ef').text(currentCard.ease_factor.toFixed(2));
            $('#card-interval').text(currentCard.interval);
            $('#card-reps').text(currentCard.repetitions);
            $('#card-info').show();

            // Update turn and players
            if (currentPuzzle.is_custom_puzzle && currentPuzzle.custom_puzzle_name) {
                $('#players-text').html('<i class="bi bi-puzzle-fill me-1" style="color: #ffc107;"></i>' + currentPuzzle.custom_puzzle_name);
            } else {
                $('#players-text').html('<i class="bi bi-people-fill me-1"></i>' + (currentPuzzle.sente || "?") + " vs " + (currentPuzzle.gote || "?"));
            }
            if (currentPuzzle.tags && Array.isArray(currentPuzzle.tags) && currentPuzzle.tags.length > 0) {
                const tagBadges = currentPuzzle.tags.map(t => '<span class="badge bg-info text-dark me-1" style="font-size:0.7em;">' + t + '</span>').join('');
                $('#players-text').append('<div class="mt-1">' + tagBadges + '</div>');
            }
            $('#turn-text').text(currentPuzzle.player === "sente" ? "Sente to play" : "Gote to play");

            // Setup board
            sg = Shogiground();
            sg.set(generateConfig(currentPuzzle));
            sg.attach({ board: document.getElementById('dirty') });
            sg.attach({ hands: { bottom: document.getElementById('hand-bottom') } });
            sg.attach({ hands: { top: document.getElementById('hand-top') } });

            puzzleStartTime = Date.now();
        },
        error: function() {
            $('#training-message').html('<span class="text-danger">Failed to load puzzle</span>');
        }
    });
}

function showEmptyState() {
    // Hide board, controls and side panel; show empty state inside the grid
    $('.puzzle__board, .puzzle__controls, .puzzle__side').hide();
    $('#empty-state').show();
    
    // Get next review time and update the message
    $.ajax({
        url: '/training/stats',
        dataType: 'json',
        success: function(stats) {
            if (stats.next_review_time) {
                const nextTime = new Date(stats.next_review_time);
                const now = new Date();
                const diffMs = nextTime - now;
                const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24));
                
                let nextText;
                if (diffDays <= 0) {
                    nextText = 'soon';
                } else if (diffDays === 1) {
                    nextText = 'tomorrow';
                } else if (diffDays < 7) {
                    nextText = 'in ' + diffDays + ' days';
                } else if (diffDays < 30) {
                    const weeks = Math.ceil(diffDays / 7);
                    nextText = 'in ' + weeks + ' week' + (weeks > 1 ? 's' : '');
                } else {
                    const months = Math.ceil(diffDays / 30);
                    nextText = 'in ' + months + ' month' + (months > 1 ? 's' : '');
                }
                
                $('#empty-state p').html('No puzzles are due for review right now.<br>Next puzzle will be available ' + nextText + '.<br>Add puzzles from the <a href="/viewer" style="color: #6ea8fe;">Puzzle Viewer</a> using the deck button.');
            }
        }
    });
}

function isMove(engineMove, playerMove, playerPositionMove, returnValue) {
    if (engineMove !== null && engineMove !== undefined) {
        let result = false;
        if (engineMove.drop !== null && engineMove.drop !== undefined) {
            if (playerPositionMove === "DROP") {
                const engineRole = sfenRoleToShogiopsRole(engineMove.drop.drop.role);
                result = engineRole === playerMove.piece.role && engineMove.drop.drop.pos === playerMove.key;
            }
        } else {
            if (playerPositionMove === "MOVE") {
                result = engineMove.move.move.orig === playerMove.orig &&
                    engineMove.move.move.dest === playerMove.dest &&
                    engineMove.move.move.promotion === playerMove.prom;
            }
        }
        return result ? returnValue : -1;
    }
    return -1;
}

function resolveTrainingMove(pos, r0, r1, r2, r3) {
    if (puzzleResolved) return;
    puzzleResolved = true;

    const timeMs = Date.now() - puzzleStartTime;
    sessionSolved++;

    let result, quality;
    if (r1 !== -1) {
        result = "best_move"; quality = 5;
        sessionCorrect++;
        showFeedback(true, "Best move!", "text-success");
    } else if (r2 !== -1) {
        result = "second"; quality = 3;
        sessionCorrect++;
        showFeedback(true, "Second best move", "text-info");
    } else if (r3 !== -1) {
        result = "third"; quality = 2;
        showFeedback(false, "Third best - not quite!", "text-warning");
    } else if (r0 !== -1) {
        result = "blunder"; quality = 0;
        showFeedback(false, "Blunder!", "text-danger");
    } else {
        result = "wrong"; quality = 1;
        showFeedback(false, "Wrong move", "text-danger");
    }

    updateSessionProgress();

    // Send result to server
    $.ajax({
        url: '/training/result',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({
            card_id: currentCard.card_id,
            result: result,
            quality: quality,
            time_spent_ms: timeMs
        }),
        success: function() {
            loadStats();
        }
    });

    // Show comment and hints
    if (pos.comment) {
        $('#training-message').append('<div class="mt-2" style="font-size:0.85rem;">' + formatComment(pos.comment) + '</div>');
    }

    // Show next button
    $('#next-training-btn').show();
}

function showFeedback(correct, message, cls) {
    const icon = correct ? '<i class="bi bi-check-circle-fill me-1"></i>' : '<i class="bi bi-x-circle-fill me-1"></i>';
    $('#training-message').html('<div class="' + cls + ' fw-bold">' + icon + message + '</div>');
}

function formatComment(comment) {
    if (!comment) return "";
    return comment.split('\n').map(function(line) {
        let cls = "", icon = "";
        if (line.startsWith("Blunder")) { cls = "text-danger"; icon = '<i class="bi bi-x-circle-fill me-1"></i>'; }
        else if (line.startsWith("Best")) { cls = "text-success"; icon = '<i class="bi bi-check-circle-fill me-1"></i>'; }
        else if (line.startsWith("Second")) { cls = "text-purple"; icon = '<i class="bi bi-2-circle me-1"></i>'; }
        else if (line.startsWith("Third")) { cls = "text-info"; icon = '<i class="bi bi-3-circle me-1"></i>'; }
        if (cls) return '<p class="' + cls + ' mb-1"><b>' + icon + line + '</b></p>';
        return '<p class="mb-1">' + line + '</p>';
    }).join("");
}

function generateConfig(pos) {
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
            dests: (function() {
                const sfenParts = pos.sfen.split(' ');
                let fullSfen;
                if (sfenParts.length >= 3) {
                    fullSfen = pos.sfen;
                } else {
                    const turnChar = pos.player === 'sente' ? 'b' : 'w';
                    const handsStr = pos.hands || '-';
                    fullSfen = pos.sfen + ' ' + turnChar + ' ' + handsStr + ' 1';
                }
                const parsed = Shogiops.sfen.parseSfen("standard", fullSfen, false);
                if (parsed.isOk) {
                    return Shogiops.compat.shogigroundDropDests(parsed.value);
                }
                return new Map();
            })(),
        },
        promotion: {
            promotesTo: function(role) {
                return Shogiops.variantUtil.promote("standard")(role);
            },
            movePromotionDialog: function(orig, dest) {
                const piece = sg.state.pieces.get(orig);
                if (!piece) return false;
                const capture = sg.state.pieces.get(dest) | undefined;
                return Shogiops.variantUtil.pieceCanPromote("standard")(piece, Shogiops.parseSquare(orig), Shogiops.parseSquare(dest), capture)
                    && !Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest));
            },
            forceMovePromotion: function(orig, dest) {
                const piece = sg.state.pieces.get(orig);
                if (!piece) return false;
                return Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest));
            },
        },
        events: {
            move: function(a, b, prom) {
                if (puzzleResolved) return;
                let r0 = isMove(pos.your_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 0);
                if (r0 === -1 && pos.blunder_moves && Array.isArray(pos.blunder_moves)) {
                    for (let i = 0; i < pos.blunder_moves.length; i++) {
                        r0 = isMove(pos.blunder_moves[i], {"orig": a, "dest": b, "prom": prom}, "MOVE", 0);
                        if (r0 !== -1) break;
                    }
                }
                let r1 = isMove(pos.best_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 1);
                let r2 = isMove(pos.second_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 2);
                let r3 = isMove(pos.third_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 3);
                resolveTrainingMove(pos, r0, r1, r2, r3);
            },
            drop: function(piece, key, prom) {
                if (puzzleResolved) return;
                let r0 = isMove(pos.your_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 0);
                if (r0 === -1 && pos.blunder_moves && Array.isArray(pos.blunder_moves)) {
                    for (let i = 0; i < pos.blunder_moves.length; i++) {
                        r0 = isMove(pos.blunder_moves[i], {"piece": piece, "key": key, "prom": prom}, "DROP", 0);
                        if (r0 !== -1) break;
                    }
                }
                let r1 = isMove(pos.best_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 1);
                let r2 = isMove(pos.second_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 2);
                let r3 = isMove(pos.third_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 3);
                resolveTrainingMove(pos, r0, r1, r2, r3);
            },
        },
    };
}

// Next button
$('#next-training-btn').click(function() {
    loadNextPuzzle();
});

// Initialize
loadStats();
loadNextPuzzle();
updateSessionProgress();
