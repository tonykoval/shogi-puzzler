
const boardEl = document.getElementById('dirty');
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

let sg;
let currentSfen = "lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1";
let currentPuzzleId = null;
let lastAnalysisResults = [];

function initBoard() {
    console.log("Initializing board with SFEN:", currentSfen);
    
    const boardEl = document.getElementById('dirty');
    const handTopEl = document.getElementById('hand-top');
    const handBottomEl = document.getElementById('hand-bottom');

    if (!boardEl) {
        console.error("Board element not found!");
        return;
    }

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

    const wrapElements = {
        board: boardEl,
        hands: {
            top: handTopEl,
            bottom: handBottomEl
        }
    };

    sg = Shogiground(config, wrapElements);
    
    // Check if board was actually attached and has dimensions
    const boardBound = boardEl.getBoundingClientRect();
    console.log("Board dimensions after Shogiground init:", boardBound.width, "x", boardBound.height);
    
    if (boardBound.height === 0) {
        console.warn("Board height is 0, attempting to force dimensions via CSS");
        // We don't force static dimensions anymore, as we want the Viewer layout to work
        // boardEl.style.height = '600px';
        // boardEl.style.width = '550px';
    }

    // Forces Shogiground to re-calculate bounds after a short delay
    setTimeout(() => {
        if (sg) {
            if (sg.state.dom.bounds) {
                if (typeof sg.state.dom.bounds.clear === 'function') {
                    sg.state.dom.bounds.clear();
                } else if (sg.state.dom.bounds.board && sg.state.dom.bounds.board.bounds) {
                    // Try the more specific structure found in shogiground.js
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
            console.log("Shogiground bounds recalculated after delay");
        }
    }, 200);

    console.log("Board initialized and attached:", sg);
}

function updateBoardFromSfen() {
    const sfen = sfenInput.value.trim();
    console.log("Updating board from SFEN input:", sfen);
    
    let targetSfen = sfen;
    if (!targetSfen) {
        console.log("SFEN input is empty, using initial board setting");
        targetSfen = "lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1";
    }

    currentSfen = targetSfen;
    const parts = targetSfen.split(' ');
    
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
            const shape = usiToShape(moveUsi, index);
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
            // because sometimes arrows from hands are not rendered if hand pieces are not found
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

function usiToShape(usi, index) {
    const brushes = ['green', 'yellow', 'blue'];
    const brush = brushes[index] || 'blue';
    const state = sg.state || (sg.dom && sg.dom.state);
    const turnColor = state ? state.turnColor : 'sente';
    
    // Map sente/gote to black/white for Shogiground
    let color = (turnColor === 'sente' || turnColor === 'black' || turnColor === 'b') ? 'black' : 'white';
    
    if (usi.includes('*')) {
        // Drop
        const parts = usi.split('*');
        const role = parts[0];
        const dest = parts[1];
        
        // Map USI piece characters to Shogiground roles
        const roleMap = {
            'P': 'pawn', 'L': 'lance', 'N': 'knight', 'S': 'silver', 
            'G': 'gold', 'B': 'bishop', 'R': 'rook', 'K': 'king'
        };
        const mappedRole = roleMap[role.toUpperCase()] || 'pawn';
        
        // Use exact piece color if provided, or fallback to turn color
        let pieceColor = color;
        if (role === role.toLowerCase()) {
            pieceColor = 'white';
        } else if (role === role.toUpperCase()) {
            pieceColor = 'black';
        }

        console.log(`[DEBUG_LOG] usiToShape creator (drop): ${usi}, turnColor: ${turnColor}, resolved pieceColor: ${pieceColor}`);

        return {
            orig: { role: mappedRole, color: pieceColor },
            dest: dest,
            brush: brush,
            label: (index + 1).toString()
        };
    } else {
        // Normal move
        const orig = usi.substring(0, 2);
        const dest = usi.substring(2, 4);
        
        return {
            orig: orig,
            dest: dest,
            brush: brush,
            label: (index + 1).toString()
        };
    }
}

function generateHint(usi) {
    // Simple hint generation
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
    
    // Find scores for moves if available
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
    
    console.log("Puzzle data to save:", data);

    if (!data.title || !data.solution) {
        console.warn("Missing title or solution");
        Swal.fire('Missing Data', 'Please fill in at least the title and solution.', 'warning');
        return;
    }
    
    try {
        const response = await fetch('/puzzle/save-custom', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        
        console.log("Save response status:", response.status);
        if (!response.ok) throw new Error('Save failed with status ' + response.status);
        
        const result = await response.json();
        console.log("Save result:", result);
        
        currentPuzzleId = result.id;
        
        Swal.fire({
            icon: 'success',
            title: 'Puzzle Saved',
            text: 'Your custom puzzle has been saved.',
            timer: 2000,
            showConfirmButton: false
        });
        
        loadMyPuzzles();
        updateUIForEditing();
        
    } catch (e) {
        console.error("Save error:", e);
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
            
            const titleSpan = document.createElement('span');
            titleSpan.className = 'text-truncate me-2';
            titleSpan.style.maxWidth = '200px';
            titleSpan.innerText = puzzle.title || 'Untitled';
            
            const dateSpan = document.createElement('span');
            dateSpan.className = 'badge bg-dark border border-secondary text-muted';
            dateSpan.style.fontSize = '0.7rem';
            dateSpan.innerText = new Date(puzzle.timestamp).toLocaleDateString();
            
            li.appendChild(titleSpan);
            li.appendChild(dateSpan);
            
            li.addEventListener('click', () => loadPuzzleForEdit(puzzle));
            myPuzzlesList.appendChild(li);
        });
    } catch (e) {
        console.error("Failed to load my puzzles:", e);
    }
}

function loadPuzzleForEdit(puzzle) {
    currentPuzzleId = puzzle.id;
    
    // Reconstruct full SFEN from board, player and hands
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
    
    // Highlight active in list
    document.querySelectorAll('#my-puzzles-list .list-group-item').forEach(li => {
        li.classList.remove('active');
        if (li.innerText.includes(puzzle.title)) li.classList.add('active');
    });
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
    
    document.querySelectorAll('#my-puzzles-list .list-group-item').forEach(li => li.classList.remove('active'));
}

// Event Listeners
loadSfenBtn.addEventListener('click', updateBoardFromSfen);
runEngineBtn.addEventListener('click', runAnalysis);
savePuzzleBtn.addEventListener('click', savePuzzle);
deletePuzzleBtn.addEventListener('click', deletePuzzle);
newPuzzleBtn.addEventListener('click', resetCreator);
refreshMyPuzzlesBtn.addEventListener('click', loadMyPuzzles);

sfenInput.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') updateBoardFromSfen();
});

// Init
document.addEventListener('DOMContentLoaded', () => {
    initBoard();
    if (sfenInput.value) updateBoardFromSfen();
    loadMyPuzzles();
});
