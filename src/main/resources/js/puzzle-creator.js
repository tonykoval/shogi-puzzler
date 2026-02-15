let sg;
let currentPuzzleId = null;
let isPlayingSequence = false;
let currentSequenceMoves = [];
let currentSequenceIndex = -1;
let autoplayInterval = null;
let initialSfen = null;
let allSequences = []; // Store all candidate sequences
let displayedSequenceIndex = 0; // Which sequence is currently displayed
let selectedCandidateIndices = []; // Checked candidates for viewer (max 3)
let isAnalyzing = false; // Track if analysis is running
let arrowsVisible = true; // Track arrow visibility state
let moveComments = {}; // Store comments for moves: { "candidateIndex_moveIndex": "comment text" }
let blunderMoves = []; // Store blunder move USI strings (e.g. ["7g7f", "P*5e"])
let isCapturingBlunder = false; // "Pick from board" mode for blunder moves
let tags = []; // Store tag strings for categorization

// Get game filter from URL query parameter
function getGameFilter() {
    const params = new URLSearchParams(window.location.search);
    return params.get('game') || '';
}

// Build puzzle list URL preserving game filter
function getPuzzleListUrl() {
    const game = getGameFilter();
    return game ? `/puzzle-creator?game=${encodeURIComponent(game)}` : '/puzzle-creator';
}

// Initialize the board
function initBoard(sfen = null) {
    const config = {
        coordinates: {
            enabled: true,
            files: ['9', '8', '7', '6', '5', '4', '3', '2', '1'],
            ranks: ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i']
        },
        orientation: 'sente',
        movable: {
            free: true,
            color: 'both'
        },
        droppable: {
            free: true,
            color: 'both'
        },
        draggable: {
            enabled: true,
            showGhost: true
        },
        selectable: {
            enabled: true
        },
        events: {
            change: updateBoardInfo,
            select: handleSquareSelect,
            move: handleBoardMove,
            drop: handleBoardDrop
        }
    };

    // If SFEN is provided, parse and set the position
    if (sfen && sfen.trim() !== '') {
        try {
            const parseResult = Shogiops.sfen.parseSfen('standard', sfen, false);
            if (parseResult.isOk) {
                const pos = parseResult.value;
                
                // Extract parts from SFEN
                const sfenParts = sfen.split(' ');
                const boardSfen = sfenParts[0];
                const handsStr = sfenParts.length > 2 ? sfenParts[2] : '';
                
                // Set the SFEN with board and hands string
                config.sfen = {
                    board: boardSfen,
                    hands: handsStr
                };
                config.turnColor = pos.turn === 'black' ? 'sente' : 'gote';
            } else {
                console.error('Invalid SFEN:', sfen, parseResult.error);
                Swal.fire({
                    icon: 'error',
                    title: 'Invalid SFEN',
                    text: 'The provided SFEN notation is invalid. Using initial position.'
                });
            }
        } catch (e) {
            console.error('Error parsing SFEN:', e);
            Swal.fire({
                icon: 'error',
                title: 'Error',
                text: 'Failed to parse SFEN: ' + e.message
            });
        }
    }

    if (!sg) {
        sg = Shogiground();
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
    sg.set(config);
    updateBoardInfo();
}

// Update board information display
function updateBoardInfo() {
    if (!sg || !sg.state) return;
    
    // Get turn from state
    const turn = sg.state.turnColor === 'sente' ? 'Sente' : 'Gote';
    $('#turn-text').text(`${turn} to move`);
    
    // Display info about the board
    const sfenInput = $('#puzzle-sfen').val().trim();
    if (sfenInput) {
        $('#board-info').text(`SFEN: ${sfenInput}`);
    } else {
        $('#board-info').text('Initial position (or enter SFEN above)');
    }
}

// Handle square selection to select candidate sequences
function handleSquareSelect(square) {
    if (!square || !allSequences || allSequences.length === 0) return;
    
    // Find which candidate's first move ends at this square
    for (let i = 0; i < allSequences.length; i++) {
        const sequence = allSequences[i];
        if (!sequence || sequence.length === 0) continue;
        
        const firstMove = sequence[0];
        const usi = firstMove.usi;
        
        if (!usi || usi.length < 3) continue;
        
        let destSquare = null;
        
        // Check if it's a drop move
        if (usi.includes('*')) {
            destSquare = usi.substring(2);
        } else {
            // Regular move: from orig to dest
            destSquare = usi.substring(2, 4);
        }
        
        // If this candidate's first move ends at the selected square
        if (destSquare === square) {
            // Select this candidate
            displayedSequenceIndex = i;
            
            // Update the sequence selector dropdown
            const selector = $('#sequence-selector');
            if (selector.length) {
                selector.val(i);
            }
            
            // Display the selected sequence
            displaySequence(allSequences[i]);
            
            console.log(`[PUZZLE-CREATOR] Selected candidate ${i + 1} by clicking on square ${square}`);
            break;
        }
    }
}

// Handle board move event — capture blunder move if in capture mode
function handleBoardMove(orig, dest, prom, captured) {
    if (!isCapturingBlunder) return;

    const usi = orig + dest + (prom ? '+' : '');
    finishBlunderCapture(usi);
}

// Handle board drop event — capture blunder move if in capture mode
function handleBoardDrop(piece, key, prom) {
    if (!isCapturingBlunder) return;

    const roleMap = {
        'pawn': 'P', 'lance': 'L', 'knight': 'N', 'silver': 'S',
        'gold': 'G', 'bishop': 'B', 'rook': 'R'
    };
    const pieceChar = roleMap[piece.role] || piece.role.charAt(0).toUpperCase();
    const usi = pieceChar + '*' + key;
    finishBlunderCapture(usi);
}

// Finish blunder capture: add USI, reset board, exit capture mode
function finishBlunderCapture(usi) {
    isCapturingBlunder = false;
    blunderMoves.push(usi);

    // Reset board to puzzle SFEN
    const sfen = $('#puzzle-sfen').val().trim();
    initBoard(sfen || null);

    renderBlunderMovesUI();
    if (arrowsVisible && allSequences.length > 0) {
        displayCandidateArrows();
    }

    Swal.fire({
        icon: 'success',
        title: 'Blunder Move Added',
        text: usi,
        timer: 1200,
        showConfirmButton: false
    });
}

// Load a puzzle into the editor
function loadPuzzle(puzzle, showToast) {
    if (showToast === undefined) showToast = true;
    console.log('[PUZZLE-CREATOR] Loading puzzle:', puzzle);
    console.log('[PUZZLE-CREATOR] move_comments field:', puzzle.move_comments);

    currentPuzzleId = puzzle._id.$oid;
    $('#puzzle-name').val(puzzle.name);
    $('#puzzle-sfen').val(puzzle.sfen || '');
    $('#puzzle-comments').val(puzzle.comments || '');
    $('#puzzle-public').prop('checked', puzzle.is_public || false);

    // Load move comments if available
    if (puzzle.move_comments) {
        loadMoveComments(puzzle.move_comments);
    } else {
        moveComments = {}; // Reset comments
        console.log('[PUZZLE-CREATOR] No move_comments found, reset to empty');
    }

    // Load blunder moves if available
    if (puzzle.blunder_moves && Array.isArray(puzzle.blunder_moves)) {
        blunderMoves = puzzle.blunder_moves.slice();
        console.log('[PUZZLE-CREATOR] Loaded blunder moves:', blunderMoves);
    } else {
        blunderMoves = [];
    }
    renderBlunderMovesUI();

    // Load tags if available
    if (puzzle.tags && Array.isArray(puzzle.tags)) {
        tags = puzzle.tags.slice();
        console.log('[PUZZLE-CREATOR] Loaded tags:', tags);
    } else {
        tags = [];
    }
    renderTagsUI();

    // Reinitialize board with the puzzle's SFEN
    initBoard(puzzle.sfen);

    // Restore analysis data if available (no engine call needed)
    if (puzzle.analysis_data && puzzle.analysis_data.length > 0) {
        console.log('[PUZZLE-CREATOR] Restoring saved analysis data...');
        try {
            allSequences = JSON.parse(puzzle.analysis_data);
            displayedSequenceIndex = 0;
            initialSfen = puzzle.sfen || '';
            // Restore selected candidate indices or default to first N
            if (puzzle.selected_candidates && Array.isArray(puzzle.selected_candidates)) {
                selectedCandidateIndices = puzzle.selected_candidates.map(v => typeof v === 'number' ? v : parseInt(v));
            } else {
                selectedCandidateIndices = allSequences.map((_, i) => i).slice(0, Math.min(3, allSequences.length));
            }
            // Display the sequence directly — arrows, playback controls, comments
            setTimeout(() => {
                displaySequence(allSequences[displayedSequenceIndex]);
            }, 100);
        } catch (e) {
            console.error('[PUZZLE-CREATOR] Failed to parse analysis_data:', e);
        }
    } else if (puzzle.selected_sequence && puzzle.selected_sequence.length > 0) {
        // Backward compat: build minimal sequence objects from USI strings (no scores)
        console.log('[PUZZLE-CREATOR] Restoring from selected_sequence (no scores)...');
        initialSfen = puzzle.sfen || '';
        const minimalSequence = puzzle.selected_sequence.map((usi, index) => ({
            moveNum: index + 1,
            usi: usi,
            score: null,
            depth: 0,
            sfenBefore: '',
            pv: ''
        }));
        allSequences = [minimalSequence];
        displayedSequenceIndex = 0;
        setTimeout(() => {
            displaySequence(allSequences[displayedSequenceIndex]);
        }, 100);
    }

    if (showToast) {
        Swal.fire({
            icon: 'success',
            title: 'Puzzle Loaded',
            text: `Loaded puzzle: ${puzzle.name}`,
            timer: 1500,
            showConfirmButton: false
        });
    }
}

// Save puzzle
function savePuzzle() {
    const name = $('#puzzle-name').val().trim();
    const sfen = $('#puzzle-sfen').val().trim();
    const comments = $('#puzzle-comments').val().trim();
    const isPublic = $('#puzzle-public').is(':checked');

    if (!name) {
        Swal.fire({
            icon: 'warning',
            title: 'Missing Name',
            text: 'Please enter a puzzle name.'
        });
        return;
    }

    const data = {
        name: name,
        sfen: sfen || '',
        comments: comments || '',
        isPublic: isPublic,
        moveComments: getAllMoveComments(), // Include move comments
        blunderMoves: blunderMoves, // Include blunder moves
        tags: tags, // Include tags
        blunderAnalyses: window.blunderAnalyses ? JSON.stringify(window.blunderAnalyses) : null // Include blunder analyses
    };

    if (currentPuzzleId) {
        data.id = currentPuzzleId;
    }

    // Include selected sequence and full analysis data if available
    if (allSequences.length > 0 && displayedSequenceIndex >= 0) {
        data.analysisData = JSON.stringify(allSequences);
        // Keep selectedSequence for backward compat
        const selectedSeq = allSequences[displayedSequenceIndex];
        data.selectedSequence = selectedSeq.map(move => move.usi);
        // Include checked candidate indices for viewer
        if (selectedCandidateIndices.length > 0) {
            data.selectedCandidates = selectedCandidateIndices;
        }
    }

    $.ajax({
        url: '/puzzle-creator/save',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(response) {
            Swal.fire({
                icon: 'success',
                title: 'Success',
                text: response.message,
                timer: 1500,
                showConfirmButton: false
            }).then(() => {
                window.location.href = getPuzzleListUrl();
            });
        },
        error: function(xhr, status, error) {
            console.error('Error saving puzzle:', error);
            Swal.fire({
                icon: 'error',
                title: 'Error',
                text: 'Failed to save puzzle.'
            });
        }
    });
}

// Delete puzzle
function deletePuzzle(puzzleId) {
    Swal.fire({
        title: 'Delete Puzzle?',
        text: 'This action cannot be undone.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#d33',
        cancelButtonColor: '#3085d6',
        confirmButtonText: 'Yes, delete it!'
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/puzzle-creator/delete',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({ id: puzzleId }),
                success: function(response) {
                    Swal.fire({
                        icon: 'success',
                        title: 'Deleted',
                        text: response.message,
                        timer: 1500,
                        showConfirmButton: false
                    }).then(() => {
                        window.location.href = getPuzzleListUrl();
                    });
                },
                error: function(xhr, status, error) {
                    console.error('Error deleting puzzle:', error);
                    Swal.fire({
                        icon: 'error',
                        title: 'Error',
                        text: 'Failed to delete puzzle.'
                    });
                }
            });
        }
    });
}

// Analyze position with engine and show best moves
function analyzePosition(multiPv = 3) {
    const sfenInput = $('#puzzle-sfen').val().trim();
    const sfen = sfenInput || getCurrentSfen();
    
    if (!sfen) {
        Swal.fire({
            icon: 'warning',
            title: 'No Position',
            text: 'Please enter a SFEN or load a puzzle first.'
        });
        return;
    }
    
    // Show loading indicator
    Swal.fire({
        title: 'Analyzing...',
        text: 'Engine is calculating best moves',
        allowOutsideClick: false,
        didOpen: () => {
            Swal.showLoading();
        }
    });
    
    const depth = parseInt($('#analysis-depth').val()) || 15;
    const time = parseInt($('#analysis-time').val()) || 0;

    $.ajax({
        url: '/puzzle-creator/analyze',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({
            sfen: sfen,
            multiPv: multiPv,
            depth: depth,
            time: time > 0 ? time * 1000 : undefined
        }),
        success: function(response) {
            Swal.close();
            
            console.log('[PUZZLE-CREATOR] Analysis response:', response);
            console.log('[PUZZLE-CREATOR] Moves:', response.moves);
            
            if (response.success && response.moves && response.moves.length > 0) {
                console.log('[PUZZLE-CREATOR] Displaying', response.moves.length, 'moves');
                displayEngineMoves(response.moves);
            } else {
                console.error('[PUZZLE-CREATOR] Analysis failed:', response.error);
                Swal.fire({
                    icon: 'error',
                    title: 'Analysis Failed',
                    text: response.error || 'No moves found'
                });
            }
        },
        error: function(xhr, status, error) {
            Swal.close();
            console.error('Error analyzing position:', error);
            Swal.fire({
                icon: 'error',
                title: 'Error',
                text: 'Failed to analyze position. Make sure the engine is configured.'
            });
        }
    });
}

// Analyze sequence of moves with engine
function analyzeSequence() {
    const sfenInput = $('#puzzle-sfen').val().trim();
    const sfen = sfenInput || getCurrentSfen();
    
    if (!sfen) {
        Swal.fire({
            icon: 'warning',
            title: 'No Position',
            text: 'Please enter a SFEN or load a puzzle first.'
        });
        return;
    }
    
    // Get analysis parameters
    const depth = parseInt($('#analysis-depth').val()) || 15;
    const time = parseInt($('#analysis-time').val()) || 0;
    const numMoves = parseInt($('#analysis-moves').val()) || 3;
    const numCandidates = parseInt($('#analysis-candidates').val()) || 1;

    // Store initial SFEN for sequence playback
    initialSfen = sfen;

    // Set analyzing state
    isAnalyzing = true;
    $('#analyze-sequence').prop('disabled', true);
    $('#stop-analysis').show();

    // Build description
    let desc = `depth ${depth}`;
    if (time > 0) desc += `, ${time}s/move`;

    // Show loading indicator
    Swal.fire({
        title: 'Analyzing...',
        text: `Engine: ${numCandidates} candidate(s), ${numMoves} moves each (${desc})`,
        allowOutsideClick: false,
        didOpen: () => {
            Swal.showLoading();
        }
    });

    $.ajax({
        url: '/puzzle-creator/analyze-sequence',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({
            sfen: sfen,
            numMoves: numMoves,
            multiPv: numCandidates,
            depth: depth,
            time: time
        }),
        success: function(response) {
            Swal.close();
            
            // Reset analyzing state
            isAnalyzing = false;
            $('#analyze-sequence').prop('disabled', false);
            $('#stop-analysis').hide();
            
            console.log('[PUZZLE-CREATOR] Sequence analysis response:', response);
            
            if (response.success && response.sequences && response.sequences.length > 0) {
                // Store all sequences
                allSequences = response.sequences;
                displayedSequenceIndex = 0;
                // Auto-select first N candidates (max 3) for the viewer
                selectedCandidateIndices = allSequences.map((_, i) => i).slice(0, Math.min(3, allSequences.length));

                console.log('[PUZZLE-CREATOR] Found', allSequences.length, 'candidate sequences');
                displaySequence(allSequences[displayedSequenceIndex]);
            } else {
                console.error('[PUZZLE-CREATOR] Sequence analysis failed:', response.error);
                Swal.fire({
                    icon: 'error',
                    title: 'Analysis Failed',
                    text: response.error || 'No sequence found'
                });
            }
        },
        error: function(xhr, status, error) {
            Swal.close();
            
            // Reset analyzing state
            isAnalyzing = false;
            $('#analyze-sequence').prop('disabled', false);
            $('#stop-analysis').hide();
            
            console.error('Error analyzing sequence:', error);
            Swal.fire({
                icon: 'error',
                title: 'Error',
                text: 'Failed to analyze sequence. Make sure the engine is configured.'
            });
        }
    });
}

// Stop analysis
function stopAnalysis() {
    // This is a placeholder - in a real implementation, you would need to
    // communicate with the backend to stop the ongoing analysis
    // For now, we'll just show a message
    Swal.fire({
        icon: 'info',
        title: 'Stop Analysis',
        text: 'Analysis stop functionality requires backend support. The current analysis will complete.'
    });
}

// Display arrows for first moves of all candidate sequences
function displayCandidateArrows() {
    if (!sg || !allSequences || allSequences.length === 0) {
        return;
    }
    
    const autoShapes = [];
    const colors = ['primary', 'alternative1', 'alternative2', 'alternative0'];
    
    allSequences.forEach((sequence, index) => {
        if (!sequence || sequence.length === 0) return;
        
        const firstMove = sequence[0];
        const usi = firstMove.usi;
        
        if (!usi || usi.length < 3) return;
        
        const color = colors[index % colors.length];
        const isSelected = index === displayedSequenceIndex;
        
        // Validate square format (should be like "5e", "7b", etc.)
        const isValidSquare = (sq) => sq && sq.length === 2 && /^[1-9][a-i]$/.test(sq);
        
        // Check if it's a drop move (format: P*5e or P*7b)
        if (usi.includes('*')) {
            const pieceChar = usi.charAt(0);
            const dest = usi.substring(2);  // Get everything after P*
            
            if (!isValidSquare(dest)) return;
            
            // Map piece character to role name
            const pieceRoleMap = {
                'P': 'pawn', 'L': 'lance', 'N': 'knight', 'S': 'silver',
                'G': 'gold', 'B': 'bishop', 'R': 'rook'
            };
            const role = pieceRoleMap[pieceChar.toUpperCase()] || 'pawn';
            
            // For drops, use orig as piece descriptor and dest as target square
            autoShapes.push({
                orig: {
                    role: role,
                    color: 'sente'  // Assuming sente for now
                },
                dest: dest,
                brush: color,
                label: {
                    text: (index + 1).toString()
                }
            });
        } else {
            // Regular move: from orig to dest
            const orig = usi.substring(0, 2);
            const dest = usi.substring(2, 4);
            
            if (!isValidSquare(orig) || !isValidSquare(dest)) return;
            
            // Add arrow from origin to destination
            autoShapes.push({
                orig: orig,
                dest: dest,
                brush: color,
                label: {
                    text: (index + 1).toString()
                }
            });
        }
    });
    
    // Add blunder move arrows (red)
    addBlunderArrows(autoShapes);

    console.log('[PUZZLE-CREATOR] Setting candidate arrows:', autoShapes);
    sg.setAutoShapes(autoShapes);
}

// Display sequence with move numbers and controls
function displaySequence(sequence) {
    if (!sg) {
        console.error('[PUZZLE-CREATOR] sg is not initialized!');
        return;
    }
    
    // Clear any existing arrows
    sg.setAutoShapes([]);
    
    // Extract moves from sequence
    currentSequenceMoves = sequence.map(move => move.usi);
    currentSequenceIndex = -1;
    
    // Build sequence display HTML
    let sequenceHtml = '<div class="mt-3"><h6>Engine Sequence Analysis:</h6>';
    
    // Display arrows for first moves of all candidates and set state
    displayCandidateArrows();
    arrowsVisible = true;
    
    // Add candidate selector if there are multiple sequences
    if (allSequences.length > 1) {
        sequenceHtml += '<div class="mb-2">';
        sequenceHtml += '<label class="form-label text-light">Candidates <small class="text-muted">(check up to 3 for viewer)</small>:</label>';
        sequenceHtml += '<div id="candidate-list" class="list-group list-group-flush">';

        allSequences.forEach((seq, index) => {
            const isActive = index === displayedSequenceIndex;
            const isChecked = selectedCandidateIndices.includes(index);
            const atLimit = selectedCandidateIndices.length >= 3 && !isChecked;
            const score = formatSequenceScore(seq[0].score);
            sequenceHtml += `<div class="list-group-item list-group-item-action d-flex align-items-center p-1 ${isActive ? 'active' : ''}" data-index="${index}" style="background:${isActive ? '#3a3530' : '#2e2a24'};border-color:#444;cursor:pointer;">`;
            sequenceHtml += `<input type="checkbox" class="form-check-input me-2 candidate-checkbox" data-index="${index}" ${isChecked ? 'checked' : ''} ${atLimit ? 'disabled' : ''} style="cursor:pointer;">`;
            sequenceHtml += `<span class="candidate-label text-light small flex-grow-1">${index + 1}. ${seq[0].usi} ${score}</span>`;
            sequenceHtml += `</div>`;
        });

        sequenceHtml += '</div>';
        sequenceHtml += '</div>';
    }
    
    sequenceHtml += '<div class="sequence-moves mb-2">';
    
    // Only show the candidate move (first move) with its comment
    const commentKey = `${displayedSequenceIndex}_0`;
    const defaultComment = sequence.map(m => {
        const s = formatSequenceScore(m.score);
        return s ? `${m.usi} (${s})` : m.usi;
    }).join(' -> ');
    const savedComment = moveComments[commentKey];
    const displayComment = (savedComment !== undefined && savedComment !== '') ? savedComment : defaultComment;

    sequenceHtml += `<div class="sequence-move mb-2 p-2 bg-dark rounded" data-index="0" data-comment-key="${commentKey}">`;
    sequenceHtml += `<div class="d-flex justify-content-between align-items-center">`;
    sequenceHtml += `<div class="move-comment-display small text-info flex-grow-1 me-2" id="comment-display-${commentKey}">${displayComment}</div>`;
    sequenceHtml += `<div class="btn-group btn-group-sm flex-shrink-0">`;
    sequenceHtml += `<button class="btn btn-outline-info btn-sm edit-comment-btn" data-comment-key="${commentKey}" title="Edit comment"><i class="bi bi-chat-text"></i></button>`;
    sequenceHtml += `<button class="btn btn-outline-danger btn-sm delete-comment-btn" data-comment-key="${commentKey}" title="Delete comment"><i class="bi bi-trash"></i></button>`;
    sequenceHtml += `</div>`;
    sequenceHtml += `</div>`;
    sequenceHtml += `</div>`;
    
    sequenceHtml += '</div>';
    
    // Add playback controls
    sequenceHtml += `
        <div class="sequence-controls mt-3">
            <div class="btn-group btn-group-sm w-100" role="group">
                <button id="sequence-back" class="btn btn-outline-secondary" title="Previous Move">
                    <i class="bi bi-skip-start-fill"></i>
                </button>
                <button id="sequence-autoplay" class="btn btn-outline-success" title="Autoplay">
                    <i class="bi bi-play-fill"></i>
                </button>
                <button id="sequence-next" class="btn btn-outline-secondary" title="Next Move">
                    <i class="bi bi-skip-end-fill"></i>
                </button>
                <button id="sequence-reset" class="btn btn-outline-warning" title="Reset">
                    <i class="bi bi-arrow-counterclockwise"></i>
                </button>
            </div>
            <div class="mt-2 text-center">
                <small class="text-muted" id="sequence-status">Move 0 of ${sequence.length}</small>
            </div>
        </div>
    `;
    
    sequenceHtml += '</div>';
    
    // Update the feedback area - use ID container to allow both analyses to show
    const feedbackDiv = $('.puzzle__feedback .content');
    if (feedbackDiv.length) {
        feedbackDiv.html(`Engine sequence analysis complete! ${sequence.length} moves found.${sequenceHtml}`);
    }
    
    // Initialize sequence controls
    initSequenceControls();
    
    // Add candidate list handlers if there are multiple sequences
    if (allSequences.length > 1) {
        // Row click → switch preview
        $('#candidate-list .list-group-item').off('click').on('click', function(e) {
            if ($(e.target).is('.candidate-checkbox')) return; // let checkbox handler deal with it
            const idx = parseInt($(this).data('index'));
            displayedSequenceIndex = idx;
            displaySequence(allSequences[idx]);
        });

        // Checkbox change → toggle candidate selection
        $('#candidate-list .candidate-checkbox').off('change').on('change', function(e) {
            e.stopPropagation();
            const idx = parseInt($(this).data('index'));
            if ($(this).is(':checked')) {
                if (!selectedCandidateIndices.includes(idx)) {
                    selectedCandidateIndices.push(idx);
                }
            } else {
                selectedCandidateIndices = selectedCandidateIndices.filter(i => i !== idx);
            }
            // Enforce max 3: disable unchecked boxes when limit reached
            const atLimit = selectedCandidateIndices.length >= 3;
            $('#candidate-list .candidate-checkbox').each(function() {
                if (!$(this).is(':checked')) {
                    $(this).prop('disabled', atLimit);
                }
            });
        });
    }
}

// Format sequence score
function formatSequenceScore(score) {
    if (!score) return '';
    const kind = score.kind || 'cp';
    const value = score.value || 0;
    
    if (kind === 'mate') {
        return `M${value}`;
    } else {
        return value >= 0 ? `+${value}` : `${value}`;
    }
}

// Initialize sequence controls
function initSequenceControls() {
    $('#sequence-back').off('click').on('click', function() {
        stopAutoplay();
        playBackMove();
    });
    
    $('#sequence-next').off('click').on('click', function() {
        stopAutoplay();
        playNextMove();
    });
    
    $('#sequence-autoplay').off('click').on('click', function() {
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
    
    $('#sequence-reset').off('click').on('click', function() {
        stopAutoplay();
        initSequence(currentSequenceMoves.join(' '));
        clearArrows();
    });
    
    // Edit comment button handlers
    $('.edit-comment-btn').off('click').on('click', function() {
        const commentKey = $(this).data('comment-key');
        editMoveComment(commentKey);
    });
    
    // Delete comment button handlers
    $('.delete-comment-btn').off('click').on('click', function() {
        const commentKey = $(this).data('comment-key');
        deleteMoveComment(commentKey);
    });
    
    updateSequenceControls();
}

// Edit move comment
function editMoveComment(commentKey) {
    const currentComment = moveComments[commentKey] || '';
    const displayEl = $(`#comment-display-${commentKey}`);
    const currentText = displayEl.text();

    Swal.fire({
        title: 'Edit Move Comment',
        input: 'textarea',
        inputValue: currentComment || currentText,
        inputPlaceholder: 'Enter comment for this move...',
        showCancelButton: true,
        confirmButtonText: 'Save',
        cancelButtonText: 'Cancel',
        inputValidator: (value) => {
            return null; // Allow any value including empty
        }
    }).then((result) => {
        if (result.isConfirmed) {
            const newComment = result.value || '';
            moveComments[commentKey] = newComment;
            
            // Update display - show comment or default score
            if (newComment.trim() !== '') {
                displayEl.text(newComment);
            } else {
                // If comment is empty, show default score
                const parts = commentKey.split('_');
                const moveIndex = parseInt(parts[1]);
                if (allSequences[displayedSequenceIndex] && allSequences[displayedSequenceIndex][moveIndex]) {
                    const score = formatSequenceScore(allSequences[displayedSequenceIndex][moveIndex].score);
                    displayEl.text(score);
                }
            }
            
            displayEl.addClass('text-info');
            
            Swal.fire({
                icon: 'success',
                title: 'Comment Saved',
                timer: 1000,
                showConfirmButton: false
            });
        }
    });
}

// Delete move comment
function deleteMoveComment(commentKey) {
    const currentComment = moveComments[commentKey];
    
    if (currentComment === undefined || currentComment === '') {
        Swal.fire({
            icon: 'info',
            title: 'No Comment',
            text: 'There is no comment to delete for this move.',
            timer: 1500,
            showConfirmButton: false
        });
        return;
    }
    
    Swal.fire({
        title: 'Delete Comment?',
        text: 'This will remove the comment and show the default CP score.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#d33',
        cancelButtonColor: '#3085d6',
        confirmButtonText: 'Yes, delete it!'
    }).then((result) => {
        if (result.isConfirmed) {
            delete moveComments[commentKey];
            
            // Reset display to default score
            const displayEl = $(`#comment-display-${commentKey}`);
            const parts = commentKey.split('_');
            const moveIndex = parseInt(parts[1]);
            if (allSequences[displayedSequenceIndex] && allSequences[displayedSequenceIndex][moveIndex]) {
                const score = formatSequenceScore(allSequences[displayedSequenceIndex][moveIndex].score);
                displayEl.text(score);
            }
            
            Swal.fire({
                icon: 'success',
                title: 'Comment Deleted',
                timer: 1000,
                showConfirmButton: false
            });
        }
    });
}

// Get all move comments for saving
function getAllMoveComments() {
    return moveComments;
}

// Load move comments from saved data
function loadMoveComments(comments) {
    if (comments && typeof comments === 'object') {
        // Handle MongoDB Document format - convert to plain object
        moveComments = {};
        Object.keys(comments).forEach(key => {
            moveComments[key] = comments[key];
        });
        console.log('[PUZZLE-CREATOR] Loaded move comments:', moveComments);
    } else {
        moveComments = {};
    }
}

// Update sequence controls state
function updateSequenceControls() {
    $('#sequence-back').prop('disabled', currentSequenceIndex < 0);
    $('#sequence-next').prop('disabled', currentSequenceIndex >= currentSequenceMoves.length - 1);
    $('#sequence-status').text(`Move ${currentSequenceIndex + 1} of ${currentSequenceMoves.length}`);
}

// Stop autoplay
function stopAutoplay() {
    if (autoplayInterval) {
        clearInterval(autoplayInterval);
        autoplayInterval = null;
    }
    $('#sequence-autoplay').html('<i class="bi bi-play-fill"></i>').removeClass('btn-success').addClass('btn-outline-success');
}

// Start autoplay
function startAutoplay() {
    if (autoplayInterval) return;
    
    $('#sequence-autoplay').html('<i class="bi bi-pause-fill"></i>').removeClass('btn-outline-success').addClass('btn-success');
    
    autoplayInterval = setInterval(() => {
        // Only stop if we've reached the end of the sequence
        if (currentSequenceIndex >= currentSequenceMoves.length - 1) {
            stopAutoplay();
        } else {
            playNextMove();
        }
    }, 1000);
}

// Play next move in sequence
function playNextMove() {
    if (currentSequenceIndex < currentSequenceMoves.length - 1) {
        currentSequenceIndex++;
        const moveUsi = currentSequenceMoves[currentSequenceIndex];
        
        // Enable animation for single step forward
        sg.set({ animation: { enabled: true } });
        
        applyMove(moveUsi, currentSequenceIndex, true);
        
        // Clear arrows after first move is played
        if (currentSequenceIndex === 0) {
            sg.setAutoShapes([]);
        }
        
        updateSequenceControls();
        return true;
    }
    stopAutoplay();
    return false;
}

// Play back to previous move in sequence
function playBackMove() {
    if (currentSequenceIndex >= 0) {
        currentSequenceIndex--;
        
        // Temporarily disable animation for the entire playback process
        const wasAnimationEnabled = sg.state.animation.enabled;
        sg.set({ animation: { enabled: false } });

        // Re-render board to initial state
        sg.set({
            sfen: {
                board: initialSfen.split(' ')[0],
                hands: initialSfen.split(' ').length > 2 ? initialSfen.split(' ')[2] : '',
            },
            turnColor: initialSfen.split(' ')[1] === 'b' ? 'sente' : 'gote',
            animation: { enabled: false }
        });
        
        // Play moves up to current index without animation
        for (let i = 0; i <= currentSequenceIndex; i++) {
            applyMove(currentSequenceMoves[i], i, false);
        }
        
        // Restore animation setting
        sg.set({ animation: { enabled: wasAnimationEnabled } });
        
        updateSequenceControls();
    }
}

// Initialize sequence playback
function initSequence(usiString, showArrows = true) {
    if (!usiString) return;
    currentSequenceMoves = usiString.split(' ');
    currentSequenceIndex = -1;
    
    isPlayingSequence = true;
    stopAutoplay();
    
    // Clear any existing arrows first
    sg.setAutoShapes([]);
    
    // Reset board to initial state
    sg.set({
        sfen: {
            board: initialSfen.split(' ')[0],
            hands: initialSfen.split(' ').length > 2 ? initialSfen.split(' ')[2] : '',
        },
        turnColor: initialSfen.split(' ')[1] === 'b' ? 'sente' : 'gote',
        animation: { enabled: false },
        lastDest: undefined,
        lastMove: undefined,
        lastDests: []
    });
    
    // Reset turn color and movable state to ensure clean start
    sg.set({ animation: { enabled: true } });
    
    updateSequenceControls();
    
    // Only display arrows if requested
    if (showArrows && allSequences.length > 0) {
        displayCandidateArrows();
    }
}

// Apply move to board
function applyMove(moveUsi, index, animate = true) {
    if (!sg) return;

    // Determine whose turn it is based on the move index
    const isInitialPlayerTurn = (index % 2 === 0);
    const initialTurn = initialSfen.split(' ')[1] === 'b' ? 'sente' : 'gote';
    const opponentColor = initialTurn === 'sente' ? 'gote' : 'sente';
    const currentColor = isInitialPlayerTurn ? initialTurn : opponentColor;

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
    const oppositeColor = currentColor === 'sente' ? 'gote' : 'sente';
    sg.set({ turnColor: oppositeColor });
}

// Display engine moves as arrows on the board
function displayEngineMoves(moves) {
    console.log('[PUZZLE-CREATOR] displayEngineMoves called with:', moves);
    if (!sg) {
        console.error('[PUZZLE-CREATOR] sg is not initialized!');
        return;
    }
    
    const autoShapes = [];
    const colors = ['primary', 'alternative1', 'alternative2', 'alternative0'];
    
    moves.forEach((move, index) => {
        const usi = move.usi;
        console.log(`[PUZZLE-CREATOR] Processing move ${index}:`, move, 'USI:', usi);
        if (!usi || usi.length < 3) {
            console.warn(`[PUZZLE-CREATOR] Skipping move ${index} - invalid USI:`, usi);
            return;
        }
        
        const color = colors[index % colors.length];
        
        // Validate square format (should be like "5e", "7b", etc.)
        const isValidSquare = (sq) => sq && sq.length === 2 && /^[1-9][a-i]$/.test(sq);
        
        // Check if it's a drop move (format: P*5e or P*7b)
        if (usi.includes('*')) {
            const pieceChar = usi.charAt(0);
            const dest = usi.substring(2);  // Get everything after P*
            console.log(`[PUZZLE-CREATOR] Drop move - piece: '${pieceChar}', dest: '${dest}', valid: ${isValidSquare(dest)}`);
            
            if (!isValidSquare(dest)) {
                console.warn(`[PUZZLE-CREATOR] Skipping drop move - invalid destination: '${dest}'`);
                return;
            }
            
            // Map piece character to role name
            const pieceRoleMap = {
                'P': 'pawn', 'L': 'lance', 'N': 'knight', 'S': 'silver',
                'G': 'gold', 'B': 'bishop', 'R': 'rook'
            };
            const role = pieceRoleMap[pieceChar.toUpperCase()] || 'pawn';
            
            // For drops, use orig as piece descriptor and dest as target square
            autoShapes.push({
                orig: {
                    role: role,
                    color: 'sente'  // Assuming sente for now
                },
                dest: dest,
                brush: color,
                label: {
                    text: (index + 1).toString()
                }
            });
        } else {
            // Regular move: from orig to dest
            const orig = usi.substring(0, 2);
            const dest = usi.substring(2, 4);
            
            console.log(`[PUZZLE-CREATOR] Regular move - orig: '${orig}', dest: '${dest}', valid: ${isValidSquare(orig) && isValidSquare(dest)}`);
            
            if (!isValidSquare(orig) || !isValidSquare(dest)) {
                console.warn(`[PUZZLE-CREATOR] Skipping regular move - invalid squares: orig='${orig}', dest='${dest}'`);
                return;
            }
            
            // Add arrow from origin to destination
            autoShapes.push({
                orig: orig,
                dest: dest,
                brush: color,
                label: {
                    text: (index + 1).toString()
                }
            });
        }
    });
    
    console.log('[PUZZLE-CREATOR] Setting autoShapes:', autoShapes);
    console.log('[PUZZLE-CREATOR] AutoShapes JSON:', JSON.stringify(autoShapes, null, 2));
    // Set auto shapes on the board
    try {
        sg.setAutoShapes(autoShapes);
        console.log('[PUZZLE-CREATOR] AutoShapes set successfully');
    } catch (e) {
        console.error('[PUZZLE-CREATOR] Error setting autoShapes:', e);
        console.error('[PUZZLE-CREATOR] Problematic shapes:', autoShapes);
    }
    
    // Show move info
    let moveInfoHtml = '<div class="mt-3"><h6>Engine Analysis:</h6>';
    moves.forEach((move, index) => {
        const score = move.score !== undefined ? formatScore(move.score) : '';
        const depth = move.depth || '';
        moveInfoHtml += `<div class="mb-1"><strong>${index + 1}.</strong> ${move.usi} ${score} (depth: ${depth})</div>`;
    });
    moveInfoHtml += '</div>';
    
    // Update the feedback area
    const feedbackDiv = $('.puzzle__feedback .content');
    if (feedbackDiv.length) {
        feedbackDiv.html(`Engine analysis complete! ${moves.length} best moves shown.<button class="btn btn-sm btn-outline-secondary ms-2" onclick="clearArrows()">Clear</button>${moveInfoHtml}`);
    }
}

// Clear arrows from board
function clearArrows() {
    if (sg) {
        sg.setAutoShapes([]);
        sg.set({
            selected: undefined,
            selectedPiece: undefined,
            lastDest: undefined,
            lastMove: undefined,
            lastDests: []
        });
    }
    updateBoardInfo();
}

// Format engine score
function formatScore(score) {
    if (!score) return '';
    
    // Handle object format {kind: 'cp', value: 100}
    if (typeof score === 'object' && score !== null) {
        const kind = score.kind;
        const value = score.value;
        
        if (kind === 'mate') {
            return `M${value}`;
        } else if (kind === 'cp' || typeof value === 'number') {
            return value >= 0 ? `+${value}` : `${value}`;
        }
        return '';
    }
    
    // Handle legacy formats
    if (score === 'mate') return '#';
    if (typeof score === 'number') {
        return score >= 0 ? `+${score}` : `${score}`;
    }
    return score.toString();
}

// Get current SFEN from board state
function getCurrentSfen() {
    if (!sg || !sg.state) return null;
    
    try {
        // Build SFEN from current board state
        const board = sg.state.pieces;
        const turnColor = sg.state.turnColor === 'sente' ? 'b' : 'w';
        
        // Build board string (rank 9 to 1)
        let boardSfen = '';
        for (let rank = 9; rank >= 1; rank--) {
            let emptyCount = 0;
            for (let file = 9; file >= 1; file--) {
                const square = `${file}${rank}`;
                const piece = board.get(square);
                if (piece) {
                    if (emptyCount > 0) {
                        boardSfen += emptyCount.toString();
                        emptyCount = 0;
                    }
                    boardSfen += pieceToSfen(piece);
                } else {
                    emptyCount++;
                }
            }
            if (emptyCount > 0) {
                boardSfen += emptyCount.toString();
            }
            if (rank > 1) {
                boardSfen += '/';
            }
        }
        
        return `${boardSfen} ${turnColor} - 1`;
    } catch (e) {
        console.error('Error getting SFEN:', e);
        return null;
    }
}

// Convert piece to SFEN notation
function pieceToSfen(piece) {
    const roleMap = {
        'king': 'K',
        'rook': 'R',
        'bishop': 'B',
        'gold': 'G',
        'silver': 'S',
        'knight': 'N',
        'lance': 'L',
        'pawn': 'P',
        'promotedRook': 'R',
        'promotedBishop': 'B',
        'promotedSilver': 'S',
        'promotedKnight': 'N',
        'promotedLance': '+L',
        'promotedPawn': '+P'
    };
    
    let role = roleMap[piece.role] || piece.role.charAt(0).toUpperCase();
    return piece.color === 'sente' ? role.toLowerCase() : role.toUpperCase();
}

// Add blunder move arrows (red) to an autoShapes array
function addBlunderArrows(autoShapes) {
    if (!blunderMoves || blunderMoves.length === 0) return;

    const isValidSquare = (sq) => sq && sq.length === 2 && /^[1-9][a-i]$/.test(sq);
    const pieceRoleMap = {
        'P': 'pawn', 'L': 'lance', 'N': 'knight', 'S': 'silver',
        'G': 'gold', 'B': 'bishop', 'R': 'rook'
    };

    blunderMoves.forEach((usi) => {
        if (!usi || usi.length < 3) return;

        if (usi.includes('*')) {
            const pieceChar = usi.charAt(0);
            const dest = usi.substring(2);
            if (!isValidSquare(dest)) return;
            const role = pieceRoleMap[pieceChar.toUpperCase()] || 'pawn';
            autoShapes.push({
                orig: { role: role, color: 'sente' },
                dest: dest,
                brush: 'alternative0',
                label: { text: 'X' }
            });
        } else {
            const orig = usi.substring(0, 2);
            const dest = usi.substring(2, 4);
            if (!isValidSquare(orig) || !isValidSquare(dest)) return;
            autoShapes.push({
                orig: orig,
                dest: dest,
                brush: 'alternative0',
                label: { text: 'X' }
            });
        }
    });
}

// Render the blunder moves UI section in the controls panel
function renderBlunderMovesUI() {
    // Remove existing blunder UI if any
    $('#blunder-moves-section').remove();

    const section = $('<div id="blunder-moves-section" class="row g-2 mb-3"></div>');
    const col = $('<div class="col-12"></div>');
    col.append('<label class="form-label text-light">Blunder Moves</label>');

    // Input row
    const inputRow = $('<div class="d-flex gap-2 mb-2"></div>');
    inputRow.append('<input type="text" class="form-control form-control-sm" id="blunder-move-input" placeholder="USI (e.g. 7g7f or P*5e)">');
    inputRow.append('<button class="btn btn-danger btn-sm" id="add-blunder-move" title="Add typed USI"><i class="bi bi-plus-lg"></i></button>');
    inputRow.append('<button class="btn btn-outline-warning btn-sm" id="pick-blunder-move" title="Pick move from board"><i class="bi bi-hand-index"></i> Board</button>');
    col.append(inputRow);

    // Capture mode indicator
    if (isCapturingBlunder) {
        col.append('<div class="alert alert-warning py-1 px-2 mb-2 small" id="capture-indicator"><i class="bi bi-hand-index me-1"></i>Make a move on the board to capture it as blunder move. <a href="#" id="cancel-capture">Cancel</a></div>');
    }

    // List of current blunder moves
    const list = $('<div id="blunder-moves-list"></div>');
    blunderMoves.forEach((usi, index) => {
        const item = $(`<div class="d-flex align-items-center gap-2 mb-1"></div>`);
        item.append(`<span class="badge bg-danger">${usi}</span>`);
        item.append(`<button class="btn btn-outline-danger btn-sm remove-blunder-move" data-index="${index}"><i class="bi bi-x"></i></button>`);
        list.append(item);
    });
    col.append(list);

    section.append(col);

    // Insert after the comments textarea row
    $('#puzzle-comments').closest('.row').after(section);

    // Bind events
    $('#add-blunder-move').off('click').on('click', function() {
        const usi = $('#blunder-move-input').val().trim();
        if (!usi) return;
        blunderMoves.push(usi);
        $('#blunder-move-input').val('');
        renderBlunderMovesUI();
        if (arrowsVisible && allSequences.length > 0) {
            displayCandidateArrows();
        }
    });

    // Pick from board button
    $('#pick-blunder-move').off('click').on('click', function() {
        isCapturingBlunder = true;
        renderBlunderMovesUI();
    });

    // Cancel capture mode
    $('#cancel-capture').off('click').on('click', function(e) {
        e.preventDefault();
        isCapturingBlunder = false;
        renderBlunderMovesUI();
    });

    // Allow Enter key in the input
    $('#blunder-move-input').off('keydown').on('keydown', function(e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            $('#add-blunder-move').click();
        }
    });

    $('.remove-blunder-move').off('click').on('click', function() {
        const idx = parseInt($(this).data('index'));
        blunderMoves.splice(idx, 1);
        renderBlunderMovesUI();
        if (arrowsVisible && allSequences.length > 0) {
            displayCandidateArrows();
        }
    });
}

// Render the tags UI section in the controls panel
function renderTagsUI() {
    // Remove existing tags UI if any
    $('#tags-section').remove();

    const section = $('<div id="tags-section" class="row g-2 mb-3"></div>');
    const col = $('<div class="col-12"></div>');
    col.append('<label class="form-label text-light">Tags</label>');

    // Input row
    const inputRow = $('<div class="d-flex gap-2 mb-2"></div>');
    inputRow.append('<input type="text" class="form-control form-control-sm" id="tag-input" placeholder="e.g. strategy, endgame, sacrifice">');
    inputRow.append('<button class="btn btn-info btn-sm" id="add-tag" title="Add tag"><i class="bi bi-plus-lg"></i></button>');
    col.append(inputRow);

    // List of current tags as badges
    const list = $('<div id="tags-list" class="d-flex flex-wrap gap-1"></div>');
    tags.forEach((tag, index) => {
        const badge = $(`<span class="badge bg-info text-dark d-flex align-items-center gap-1" style="font-size:0.85em;">${tag}<button class="btn-close btn-close-sm remove-tag" data-index="${index}" style="font-size:0.5em;"></button></span>`);
        list.append(badge);
    });
    col.append(list);

    section.append(col);

    // Insert after the blunder moves section, or after comments if blunder section doesn't exist
    const blunderSection = $('#blunder-moves-section');
    if (blunderSection.length) {
        blunderSection.after(section);
    } else {
        $('#puzzle-comments').closest('.row').after(section);
    }

    // Bind events
    $('#add-tag').off('click').on('click', function() {
        const val = $('#tag-input').val().trim();
        if (!val) return;
        // Support comma-separated input
        val.split(',').forEach(t => {
            const trimmed = t.trim();
            if (trimmed && !tags.includes(trimmed)) {
                tags.push(trimmed);
            }
        });
        $('#tag-input').val('');
        renderTagsUI();
    });

    // Allow Enter key in the input
    $('#tag-input').off('keydown').on('keydown', function(e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            $('#add-tag').click();
        }
    });

    $('.remove-tag').off('click').on('click', function() {
        const idx = parseInt($(this).data('index'));
        tags.splice(idx, 1);
        renderTagsUI();
    });
}

// Initialize on page load
$(document).ready(function() {
    // Check if we have pre-loaded puzzle data (edit mode)
    if (window.__puzzleData) {
        loadPuzzle(window.__puzzleData, false);
    } else {
        // Initialize board with initial position
        initBoard();
        renderBlunderMovesUI();
        renderTagsUI();
    }

    // Save button handler
    $('#save-puzzle').on('click', savePuzzle);
    
    // Analyze buttons
    $('#analyze-sequence').on('click', function() {
        analyzeSequence();
    });

    $('#stop-analysis').on('click', function() {
        stopAnalysis();
    });
    
    // Toggle arrows and reset button
    $('#toggle-arrows').on('click', function() {
        // Toggle the arrow visibility state
        arrowsVisible = !arrowsVisible;
        
        // Reset to beginning position
        if (initialSfen && allSequences.length > 0) {
            initSequence(currentSequenceMoves.join(' '), arrowsVisible);
        }
    });
    
    // Update board when SFEN is changed
    $('#puzzle-sfen').on('change', function() {
        const sfen = $(this).val().trim();
        if (sfen) {
            initBoard(sfen);
        } else {
            initBoard();
        }
    });
    
    // New puzzle button (clear form)
    $(document).on('keydown', function(e) {
        // Ctrl+N or Cmd+N for new puzzle
        if ((e.ctrlKey || e.metaKey) && e.key === 'n') {
            e.preventDefault();
            currentPuzzleId = null;
            $('#puzzle-name').val('');
            $('#puzzle-sfen').val('');
            initBoard();
        }
    });
});
