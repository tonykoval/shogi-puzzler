let data, ids, sg, selected, selectedData
let isPlayingSequence = false;
let currentSequenceMoves = [];
let currentSequenceIndex = -1;
let autoplayInterval = null;
let areHintsVisible = false;

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

// Creator-specific state
let currentSfen = "lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1";
let currentPuzzleId = null;
let lastAnalysisResults = [];

// Elements (mapped to puzzle-creator-mode)
const sfenInput = document.getElementById('sfen-input');
const loadSfenBtn = document.getElementById('load-sfen');
const runEngineBtn = document.getElementById('run-engine');
const savePuzzleBtn = document.getElementById('save-puzzle');
const deletePuzzleBtn = document.getElementById('delete-puzzle');
const newPuzzleBtn = document.getElementById('new-puzzle');
const refreshMyPuzzlesBtn = document.getElementById('refresh-my-puzzles');
const myPuzzlesList = document.getElementById('my-puzzles-list');

const puzzleTitle = document.getElementById('puzzle-title');
const puzzleQuestion = document.getElementById('puzzle-question');
const puzzleHint = document.getElementById('puzzle-hint');
const puzzleSolution = document.getElementById('puzzle-solution');
const puzzlePublic = document.getElementById('puzzle-public');

function initBoard() {
    console.log("Initializing board with SFEN:", currentSfen);
    
    const boardEl = document.getElementById('dirty');
    const handTopEl = document.getElementById('hand-top');
    const handBottomEl = document.getElementById('hand-bottom');

    const config = {
        sfen: {
            board: currentSfen.split(' ')[0],
            hands: currentSfen.split(' ')[2] !== '-' ? currentSfen.split(' ')[2] : ''
        },
        orientation: 'sente',
        coordinates: true,
        movable: {
            free: true,
            color: 'both'
        },
        drawable: {
            enabled: true,
            visible: true
        },
        highlight: {
            lastMove: true,
            check: true
        }
    };

    if (currentSfen.indexOf(' ') !== -1) {
        const parts = currentSfen.split(' ');
        config.turnColor = parts[1] === 'b' ? 'sente' : 'gote';
    }

    sg = Shogiground();
    sg.set(config);
    
    sg.attach({
        board: boardEl,
        hands: {
            top: handTopEl,
            bottom: handBottomEl
        }
    });
    
    // Forces Shogiground to re-calculate bounds after a short delay
    const forceResize = () => {
        if (sg) {
            console.log("[DEBUG_LOG] Forcing Shogiground bounds recalculation");
            if (sg.state.dom.bounds) {
                if (typeof sg.state.dom.bounds.clear === 'function') {
                    sg.state.dom.bounds.clear();
                } else if (sg.state.dom.bounds.board && sg.state.dom.bounds.board.bounds) {
                    sg.state.dom.bounds.board.bounds.clear();
                    if (sg.state.dom.bounds.hands) {
                        if (sg.state.dom.bounds.hands.bounds) sg.state.dom.bounds.hands.bounds.clear();
                        if (sg.state.dom.bounds.hands.pieceBounds) sg.state.dom.bounds.hands.pieceBounds.clear();
                    }
                }
            }
            if (typeof sg.redrawNow === 'function') {
                sg.redrawNow();
            } else if (sg.dom && typeof sg.dom.redrawNow === 'function') {
                sg.dom.redrawNow();
            }
            
            // Sync autoShapes if hints are visible
            if (areHintsVisible && selected) {
                setHints(selected);
            } else if (lastAnalysisResults && lastAnalysisResults.length > 0) {
                showAnalysis(lastAnalysisResults);
            }

            // Trigger window resize event to satisfy any other listeners
            window.dispatchEvent(new Event('resize'));
        }
    };

    setTimeout(forceResize, 100);
    setTimeout(forceResize, 500);
    setTimeout(forceResize, 1500);

    console.log("Board initialized and attached:", sg);
}

function updateBoardFromSfen() {
    const sfen = sfenInput.value.trim();
    console.log("Updating board from SFEN input:", sfen);
    if (sfen) {
        currentSfen = sfen;
        const parts = sfen.split(' ');
        
        const fenBoard = parts[0];
        const turn = (parts.length > 1 && parts[1] === 'b') ? 'sente' : 'gote';
        const hands = (parts.length > 2 && parts[2] !== '-') ? parts[2] : '';
        
        console.log("Parsed SFEN:", { fenBoard, turn, hands });

        sg.set({
            sfen: {
                board: fenBoard,
                hands: hands
            },
            turnColor: turn,
            check: false,
            lastDests: []
        });
        
        // Pre-fill question
        const turnTitle = turn === 'sente' ? 'Sente' : 'Gote';
        puzzleQuestion.value = `Find the best move for ${turnTitle}`;
        console.log("Board updated and question pre-filled");
    } else {
        console.warn("SFEN input is empty");
    }
}

async function runAnalysis() {
    const sfen = sfenInput.value.trim() || currentSfen;
    const enginePath = document.getElementById('engine-select').value;
    console.log("Running analysis for SFEN:", sfen, "Engine:", enginePath);
    
    runEngineBtn.disabled = true;
    runEngineBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Analyzing...';
    
    try {
        const response = await fetch('/engine/analyze-position', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sfen: sfen, engine: enginePath })
        });
        
        console.log("Analysis response status:", response.status);
        if (!response.ok) throw new Error('Analysis failed with status ' + response.status);
        
        const results = await response.json();
        console.log("Analysis results received:", results);
        lastAnalysisResults = results;
        showAnalysis(results);
    } catch (e) {
        console.error("Analysis error:", e);
        Swal.fire('Error', 'Failed to analyze position: ' + e.message, 'error');
    } finally {
        runEngineBtn.disabled = false;
        runEngineBtn.innerHTML = '<i class="bi bi-cpu me-2"></i>Run Engine Analysis';
    }
}

function showAnalysis(results) {
    console.log("[DEBUG_LOG] showAnalysis starting with results:", results);
    const shapes = [];
    
    // Zobrazenie výsledkov analýzy v UI pod tlačidlom
    const analysisInfoEl = document.getElementById('analysis-info');
    if (analysisInfoEl) {
        analysisInfoEl.innerHTML = '';
        analysisInfoEl.classList.remove('d-none');
    }

    results.forEach((res, index) => {
        if (res.pv && res.pv.length > 0) {
            const moveUsi = res.pv[0];
            const brush = ['green', 'yellow', 'blue'][index] || 'blue';
            const shape = usiToShape(moveUsi, index + 1); // Using label 1, 2, 3
            if (shape) {
                shapes.push(shape);
            }
            
            // Predvyplnenie riešenia prvým najlepším ťahom
            if (index === 0) {
                puzzleSolution.value = moveUsi;
                generateHint(moveUsi);
            }

            // Pridanie textovej informácie o analýze
            if (analysisInfoEl) {
                let scoreText = "";
                if (res.score.cp !== undefined) {
                    const cp = res.score.cp / 100;
                    scoreText = (cp > 0 ? "+" : "") + cp.toFixed(2);
                } else if (res.score.mate !== undefined) {
                    const mate = res.score.mate;
                    scoreText = (mate > 0 ? "+" : "-") + "M" + Math.abs(mate);
                }
                
                const badgeClass = index === 0 ? 'bg-success' : (index === 1 ? 'bg-warning text-dark' : 'bg-info');
                const infoLine = `<div class="mb-1"><span class="badge ${badgeClass} me-2">${index + 1}</span> <b>${moveUsi}</b> <span class="ms-2 text-muted">(${scoreText})</span></div>`;
                analysisInfoEl.innerHTML += infoLine;
            }

            // Workaround: if it's a drop, also add a square highlight
            if (moveUsi.includes('*')) {
                const dest = moveUsi.split('*')[1];
                shapes.push({
                    orig: dest,
                    dest: dest,
                    brush: brush,
                    label: (index + 1).toString()
                });
            }
        }
    });
    
    // Debug: log final shapes
    console.log("[DEBUG_LOG] Final autoShapes to set:", JSON.stringify(shapes));
    
    sg.setAutoShapes(shapes);
    if (typeof sg.redrawNow === 'function') {
        sg.redrawNow();
    } else if (sg.dom && typeof sg.dom.redrawNow === 'function') {
        sg.dom.redrawNow();
    }
}

function generateHint(usi) {
    if (usi.includes('*')) {
        const piece = usi.split('*')[0];
        puzzleHint.value = `Drop a ${piece} somewhere`;
    } else {
        const orig = usi.substring(0, 2);
        puzzleHint.value = `Move a piece from ${orig}`;
    }
}

async function savePuzzle() {
    console.log("Saving custom puzzle...");
    
    let bestScore = { cp: 0 };
    let secondScore = null;
    let thirdScore = null;
    
    if (lastAnalysisResults && lastAnalysisResults.length > 0) {
        if (lastAnalysisResults[0] && lastAnalysisResults[0].score) bestScore = lastAnalysisResults[0].score;
        if (lastAnalysisResults[1] && lastAnalysisResults[1].score) secondScore = lastAnalysisResults[1].score;
        if (lastAnalysisResults[2] && lastAnalysisResults[2].score) thirdScore = lastAnalysisResults[2].score;
    }

    const data = {
        id: currentPuzzleId,
        sfen: sfenInput.value.trim() || currentSfen,
        title: puzzleTitle.value.trim(),
        question: puzzleQuestion.value.trim(),
        hint: puzzleHint.value.trim(),
        solution: puzzleSolution.value.trim(),
        isPublic: puzzlePublic.checked,
        bestScore: bestScore,
        secondScore: secondScore,
        thirdScore: thirdScore,
        secondUsi: (lastAnalysisResults[1] && lastAnalysisResults[1].pv) ? lastAnalysisResults[1].pv[0] : null,
        thirdUsi: (lastAnalysisResults[2] && lastAnalysisResults[2].pv) ? lastAnalysisResults[2].pv[0] : null
    };
    
    if (!data.title || !data.solution) {
        Swal.fire('Missing Data', 'Please fill in at least the title and solution.', 'warning');
        return;
    }
    
    try {
        const response = await fetch('/puzzle/save-custom', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        
        if (!response.ok) throw new Error('Save failed');
        const result = await response.json();
        currentPuzzleId = result.id;
        
        Swal.fire({ icon: 'success', title: 'Puzzle Saved', timer: 2000, showConfirmButton: false });
        loadMyPuzzles();
        updateUIForEditing();
    } catch (e) {
        Swal.fire('Error', 'Failed to save puzzle: ' + e.message, 'error');
    }
}

async function deletePuzzle() {
    if (!currentPuzzleId) return;
    const result = await Swal.fire({
        title: 'Are you sure?',
        text: "You won't be able to revert this!",
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#d33',
        cancelButtonColor: '#3085d6',
        confirmButtonText: 'Yes, delete it!'
    });
    
    if (result.isConfirmed) {
        try {
            const response = await fetch('/puzzle/delete-custom', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: currentPuzzleId })
            });
            if (!response.ok) throw new Error('Delete failed');
            Swal.fire('Deleted!', 'Your puzzle has been deleted.', 'success');
            resetCreator();
            loadMyPuzzles();
        } catch (e) {
            Swal.fire('Error', 'Failed to delete puzzle.', 'error');
        }
    }
}

async function loadMyPuzzles() {
    try {
        const response = await fetch('/puzzle/my-custom');
        const puzzles = await response.json();
        myPuzzlesList.innerHTML = '';
        if (puzzles.length === 0) {
            myPuzzlesList.innerHTML = '<li class="list-group-item text-muted text-center py-3">No puzzles created yet</li>';
            return;
        }
        puzzles.sort((a, b) => b.timestamp - a.timestamp).forEach(puzzle => {
            const li = document.createElement('li');
            li.className = 'list-group-item';
            if (currentPuzzleId === puzzle.id) li.classList.add('active');
            li.innerHTML = `<span class="text-truncate me-2" style="max-width: 200px;">${puzzle.title || 'Untitled'}</span>
                            <span class="badge bg-dark border border-secondary text-muted" style="font-size: 0.7rem;">${new Date(puzzle.timestamp).toLocaleDateString()}</span>`;
            li.addEventListener('click', () => loadPuzzleForEdit(puzzle));
            myPuzzlesList.appendChild(li);
        });
    } catch (e) {
        console.error("Failed to load my puzzles:", e);
    }
}

function loadPuzzleForEdit(puzzle) {
    currentPuzzleId = puzzle.id;
    const board = puzzle.sfen;
    const turn = puzzle.player === 'gote' ? 'w' : 'b';
    const hands = (puzzle.hands && puzzle.hands !== '-') ? puzzle.hands : '-';
    const fullSfen = `${board} ${turn} ${hands} 1`;
    currentSfen = fullSfen;
    sfenInput.value = fullSfen;
    puzzleTitle.value = puzzle.title || '';
    puzzleQuestion.value = puzzle.comment || '';
    puzzleHint.value = puzzle.hint_text || '';
    puzzleSolution.value = puzzle.best ? puzzle.best.usi : '';
    puzzlePublic.checked = !!puzzle.is_public;
    updateBoardFromSfen();
    updateUIForEditing();
}

function updateUIForEditing() {
    if (currentPuzzleId) {
        deletePuzzleBtn.style.display = 'block';
        newPuzzleBtn.style.display = 'block';
        savePuzzleBtn.innerText = 'Update Puzzle';
    } else {
        deletePuzzleBtn.style.display = 'none';
        newPuzzleBtn.style.display = 'none';
        savePuzzleBtn.innerHTML = '<i class="bi bi-save me-1"></i>Save Puzzle';
    }
}

function resetCreator() {
    currentPuzzleId = null;
    currentSfen = "lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1";
    sfenInput.value = "";
    puzzleTitle.value = "";
    puzzleQuestion.value = "";
    puzzleHint.value = "";
    puzzleSolution.value = "";
    puzzlePublic.checked = false;
    updateBoardFromSfen();
    updateUIForEditing();
}

// Attach Creator listeners
loadSfenBtn.addEventListener('click', updateBoardFromSfen);
runEngineBtn.addEventListener('click', runAnalysis);
savePuzzleBtn.addEventListener('click', savePuzzle);
deletePuzzleBtn.addEventListener('click', deletePuzzle);
newPuzzleBtn.addEventListener('click', resetCreator);
refreshMyPuzzlesBtn.addEventListener('click', loadMyPuzzles);

sfenInput.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') updateBoardFromSfen();
});

document.addEventListener('DOMContentLoaded', () => {
    initBoard();
    if (sfenInput && sfenInput.value) updateBoardFromSfen();
    loadMyPuzzles();
});

function usiToShape(usi, label) {
    if (!usi) return null;
    
    const state = sg.state || (sg.dom && sg.dom.state);
    const turnColor = state ? state.turnColor : 'sente';
    
    // Map sente/gote to black/white for Shogiground
    const color = (turnColor === 'sente' || turnColor === 'black' || turnColor === 'b') ? 'black' : 'white';
    
    const brushes = ['green', 'yellow', 'blue'];
    const brush = (typeof label === 'number') ? (brushes[label - 1] || 'green') : 'green';

    if (usi.includes('*')) {
        // Drop
        const parts = usi.split('*');
        const role = parts[0];
        const dest = parts[1];
        
        const roleMap = {
            'P': 'pawn', 'L': 'lance', 'N': 'knight', 'S': 'silver', 
            'G': 'gold', 'B': 'bishop', 'R': 'rook', 'K': 'king'
        };
        const mappedRole = roleMap[role.toUpperCase()] || 'pawn';
        
        // Use exact piece color if provided by case, or fallback to turn color
        let pieceColor = color;
        if (role === role.toLowerCase()) {
            pieceColor = 'white';
        } else if (role === role.toUpperCase()) {
            pieceColor = 'black';
        }

        console.log(`[DEBUG_LOG] usiToShape (drop): ${usi}, turnColor: ${turnColor}, resolved pieceColor: ${pieceColor}`);

        return {
            orig: { role: mappedRole, color: pieceColor },
            dest: dest,
            brush: brush,
            label: label ? label.toString() : undefined
        };
    } else {
        // Normal move
        const orig = usi.substring(0, 2);
        const dest = usi.substring(2, 4);
        return {
            orig: orig,
            dest: dest,
            brush: brush,
            label: label ? label.toString() : undefined
        };
    }
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

if ($(".games").length > 0) {
    loadData();
}

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
            let label = value.title || value.id
            if (!value.title && value.move_number) {
                label = "Move " + value.move_number
            } else if (!value.title && value.ply) {
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
        const handsText = (selected.hands === "-" || !selected.hands) ? "" : selected.hands;
        if (handsText) {
             $('#material-text').text(handsText);
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
            top: document.getElementById('hand-top'),
        },
    });

    // Ensure bounds are calculated after attachment
    setTimeout(() => {
        if (sg && sg.state && sg.state.dom && sg.state.dom.bounds) {
            if (typeof sg.state.dom.bounds.clear === 'function') {
                sg.state.dom.bounds.clear();
            } else if (sg.state.dom.bounds.board && sg.state.dom.bounds.board.bounds) {
                sg.state.dom.bounds.board.bounds.clear();
                if (sg.state.dom.bounds.hands) {
                    if (sg.state.dom.bounds.hands.bounds) sg.state.dom.bounds.hands.bounds.clear();
                    if (sg.state.dom.bounds.hands.pieceBounds) sg.state.dom.bounds.hands.pieceBounds.clear();
                }
            }
            if (typeof sg.redrawNow === 'function') {
                sg.redrawNow();
            } else if (sg.dom && typeof sg.dom.redrawNow === 'function') {
                sg.dom.redrawNow();
            }
        }
    }, 100);
}

function isMove(engineMove, playerMove, playerPositionMove, returnValue) {
    if (engineMove !== null && engineMove !== undefined) {
        let result = false;
        
        // Convert player move to USI for easier comparison if needed
        let playerUsi = "";
        if (playerPositionMove === "DROP") {
            const roleMap = {
                'pawn': 'P', 'lance': 'L', 'knight': 'N', 'silver': 'S',
                'gold': 'G', 'bishop': 'B', 'rook': 'R', 'king': 'K'
            };
            playerUsi = (roleMap[playerMove.piece.role] || 'P') + "*" + playerMove.key;
        } else {
            playerUsi = playerMove.orig + playerMove.dest + (playerMove.prom ? "+" : "");
        }

        // Support for simple USI string or object with usi property (from puzzle-creator)
        if (typeof engineMove === 'string' && engineMove === playerUsi) {
            result = true;
        } else if (engineMove.usi && engineMove.usi === playerUsi) {
            result = true;
        } else if (engineMove.drop !== null && engineMove.drop !== undefined) {
            if (playerPositionMove === "DROP") {
                result = engineMove.drop.drop.role === playerMove.piece.role && engineMove.drop.drop.pos === playerMove.key;
            }
        } else if (engineMove.move !== null && engineMove.move !== undefined) {
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
        return null;
    }
}

function setHint(move, label) {
    if (move !== null && move !== undefined) {
        if (move.drop !== null && move.drop !== undefined) {
            return move.drop.visualHint || move.drop.hint;
        } else if (move.move !== null && move.move !== undefined) {
            return move.move.visualHint || move.move.hint;
        } else if (typeof move === 'string') {
            return usiToShape(move, label);
        } else if (move.usi) {
            return usiToShape(move.usi, label);
        }
    }
    return null
}


function formatComment(comment, pos) {
    let html = "";
    if (comment) {
        html = comment.split('\n').map(line => {
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

    if (pos) {
        const addScore = (move, label, cls) => {
            if (move && move.score) {
                let scoreText = "";
                if (move.score.cp !== undefined) {
                    const cp = move.score.cp / 100;
                    scoreText = (cp > 0 ? "+" : "") + cp.toFixed(2);
                } else if (move.score.mate !== undefined) {
                    const mate = move.score.mate;
                    scoreText = (mate > 0 ? "+" : "-") + "M" + Math.abs(mate);
                }
                if (scoreText) {
                    const usi = move.usi || "";
                    const title = label + (usi ? " (" + usi + ")" : "");
                    html += `<div class="badge bg-dark border border-secondary ${cls} me-1" title="${title}">CP: ${scoreText}</div>`;
                }
            }
        };

        html += '<div class="mt-2 d-flex flex-wrap gap-1">';
        addScore(pos.best_move || pos.best, "Best move", "text-success");
        addScore(pos.second_move || pos.second, "Second move", "text-warning");
        addScore(pos.third_move || pos.third, "Third move", "text-info");
        html += '</div>';
    }

    return html;
}

function fireError(pos) {
    if (isPlayingSequence) return;
    const lishogiSfen = pos.sfen.replace(/ /g, '_');
    Swal.fire({
        icon: 'error',
        title: 'Failure',
        html: '<p>You played the bad move!</p> ' +
            '<div>' + formatComment(pos.comment, pos) + '</div>',
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
            '<div>' + formatComment(pos.comment, pos) + '</div>',
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
            '<div>' + formatComment(pos.comment, pos) + '</div>',
        footer: '<a href="https://lishogi.org/analysis/' + lishogiSfen + '" target="_blank">Lishogi position</a>'
    })
}

function fireSave(text) {
    Swal.fire({
        icon: 'question',
        title: 'Update',
        html: '<p>Do you want update the comment?</p>' +
            '<div>' + formatComment(text, selected) + '</div>' +
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
    const hints = [
        { move: pos.best_move || pos.best, label: "1" },
        { move: pos.second_move || pos.second, label: "2" },
        { move: pos.third_move || pos.third, label: "3" },
        { move: pos.your_move, label: "" }
    ];

    const shapes = [];
    hints.forEach(h => {
        const shape = setHint(h.move, h.label);
        if (shape) {
            shapes.push(shape);
            // Workaround for drops: add square highlight
            const usi = (typeof h.move === 'string') ? h.move : (h.move && h.move.usi);
            if (usi && usi.includes('*')) {
                const dest = usi.split('*')[1];
                shapes.push({
                    orig: dest,
                    dest: dest,
                    brush: shape.brush || 'green',
                    label: h.label
                });
            }
        }
    });

    sg.setAutoShapes(shapes);
    
    // Debug: log final shapes
    console.log("[DEBUG_LOG] Final autoShapes to set (Viewer):", JSON.stringify(shapes));
    
    areHintsVisible = true;
}

function resolveMove(pos, r0, r1, r2, r3) {
    $(".content").html(formatComment(pos.comment, pos))
    $("#show-hints").show();
    
    // Add continuation buttons for top 3 moves
    let continuationHtml = "";
    const best = pos.best_move || pos.best;
    const second = pos.second_move || pos.second;
    const third = pos.third_move || pos.third;

    if (best && best.usi && best.usi.split(' ').length > 0) {
        continuationHtml += `<button class="btn btn-sm btn-outline-success play-continuation-btn me-1 mb-1" data-type="${pos.best_move ? 'best_move' : 'best'}"><i class="bi bi-1-circle me-1"></i>Top 1</button>`;
    }
    if (second && second.usi && second.usi.split(' ').length > 0) {
        continuationHtml += `<button class="btn btn-sm btn-outline-warning play-continuation-btn me-1 mb-1" data-type="${pos.second_move ? 'second_move' : 'second'}"><i class="bi bi-2-circle me-1"></i>Top 2</button>`;
    }
    if (third && third.usi && third.usi.split(' ').length > 0) {
        continuationHtml += `<button class="btn btn-sm btn-outline-info play-continuation-btn me-1 mb-1" data-type="${pos.third_move ? 'third_move' : 'third'}"><i class="bi bi-3-circle me-1"></i>Top 3</button>`;
    }
    
    if (continuationHtml) {
        $("#continuation-options").html(continuationHtml).show();
        $("#play-continuation").hide(); 
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
    const hands = (pos.hands === "-" || !pos.hands) ? "" : pos.hands;
    return {
        sfen: {
            board: pos.sfen,
            hands: hands,
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
                $(".content").html(formatComment(pos.comment, pos))

                let r0 = isMove(pos.your_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 0)
                let r1 = isMove(pos.best_move || pos.best, {"orig": a, "dest": b, "prom": prom}, "MOVE", 1)
                let r2 = isMove(pos.second_move || pos.second, {"orig": a, "dest": b, "prom": prom}, "MOVE", 2)
                let r3 = isMove(pos.third_move || pos.third, {"orig": a, "dest": b, "prom": prom}, "MOVE", 3)
                setHints(pos)
                resolveMove(pos, r0, r1, r2, r3)

                $(".save-comment").show()
            },
            drop: (piece, key, prom) => {
                if (isPlayingSequence) return;
                $(".content").html(formatComment(pos.comment, pos))

                let r0 = isMove(pos.your_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 0)
                let r1 = isMove(pos.best_move || pos.best, {"piece": piece, "key": key, "prom": prom}, "DROP", 1)
                let r2 = isMove(pos.second_move || pos.second, {"piece": piece, "key": key, "prom": prom}, "DROP", 2)
                let r3 = isMove(pos.third_move || pos.third, {"piece": piece, "key": key, "prom": prom}, "DROP", 3)
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
