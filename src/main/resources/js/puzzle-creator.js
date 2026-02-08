let sg;
let currentPuzzleId = null;
let isPlayingSequence = false;
let currentSequenceMoves = [];
let currentSequenceIndex = -1;
let autoplayInterval = null;
let initialSfen = null;
let allSequences = []; // Store all candidate sequences
let displayedSequenceIndex = 0; // Which sequence is currently displayed
let isAnalyzing = false; // Track if analysis is running
let arrowsVisible = true; // Track arrow visibility state
let moveComments = {}; // Store comments for moves: { "candidateIndex_moveIndex": "comment text" }

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
        draggable: {
            enabled: true,
            showGhost: true
        },
        selectable: {
            enabled: true
        },
        events: {
            change: updateBoardInfo,
            select: handleSquareSelect
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

    // Reinitialize board with the puzzle's SFEN
    initBoard(puzzle.sfen);

    // If there's a saved sequence, automatically run analysis to display it with comments
    if (puzzle.selected_sequence && puzzle.selected_sequence.length > 0) {
        console.log('[PUZZLE-CREATOR] Loaded puzzle with saved sequence, running analysis...');
        // Store initial SFEN for sequence playback
        initialSfen = puzzle.sfen || '';
        // Auto-run analysis to display the sequence with comments
        setTimeout(() => {
            analyzeSequence();
        }, 500);
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
        moveComments: getAllMoveComments() // Include move comments
    };

    if (currentPuzzleId) {
        data.id = currentPuzzleId;
    }

    // Include selected sequence if available
    if (allSequences.length > 0 && displayedSequenceIndex >= 0) {
        const selectedSeq = allSequences[displayedSequenceIndex];
        data.selectedSequence = selectedSeq.map(move => move.usi);
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
                window.location.href = '/puzzle-creator';
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
                        window.location.href = '/puzzle-creator';
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
    
    $.ajax({
        url: '/puzzle-creator/analyze',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({
            sfen: sfen,
            multiPv: multiPv,
            depth: 15
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
    const depth = parseInt($('#analysis-depth').val()) || 12;
    const numMoves = parseInt($('#analysis-moves').val()) || 3;
    const numCandidates = parseInt($('#analysis-candidates').val()) || 1;
    
    // Store initial SFEN for sequence playback
    initialSfen = sfen;
    
    // Set analyzing state
    isAnalyzing = true;
    $('#analyze-sequence').prop('disabled', true);
    $('#stop-analysis').show();
    
    // Show loading indicator
    Swal.fire({
        title: 'Analyzing...',
        text: `Engine is calculating ${numCandidates} candidate(s) with ${numMoves} moves each`,
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
            multiPv: numCandidates
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
        sequenceHtml += '<label class="form-label text-light">Candidate Sequence:</label>';
        sequenceHtml += '<select id="sequence-selector" class="form-select form-select-sm">';
        
        allSequences.forEach((seq, index) => {
            const selected = index === displayedSequenceIndex ? 'selected' : '';
            const score = formatSequenceScore(seq[0].score);
            sequenceHtml += `<option value="${index}" ${selected}>Candidate ${index + 1}: ${seq[0].usi} ${score}</option>`;
        });
        
        sequenceHtml += '</select>';
        sequenceHtml += '</div>';
    }
    
    sequenceHtml += '<div class="sequence-moves mb-2">';
    
    sequence.forEach((move, index) => {
        const score = formatSequenceScore(move.score);
        // Only show comment for the first move (candidate move) - index 0
        const isCandidateMove = index === 0;
        const commentKey = `${displayedSequenceIndex}_${index}`;
        const defaultComment = score; // Pre-fill with CP score
        const savedComment = moveComments[commentKey];
        const displayComment = (savedComment !== undefined && savedComment !== '') ? savedComment : defaultComment;
        
        console.log(`[PUZZLE-CREATOR] Move ${index}, commentKey: ${commentKey}, savedComment:`, savedComment, 'displayComment:', displayComment);
        
        sequenceHtml += `<div class="sequence-move mb-2 p-2 bg-dark rounded" data-index="${index}" data-comment-key="${commentKey}">`;
        sequenceHtml += `<div class="d-flex justify-content-between align-items-center">`;
        sequenceHtml += `<div><strong>${move.moveNum}.</strong> ${move.usi}</div>`;
        if (isCandidateMove) {
            sequenceHtml += `<div class="btn-group btn-group-sm">`;
            sequenceHtml += `<button class="btn btn-outline-info btn-sm edit-comment-btn" data-comment-key="${commentKey}" title="Edit comment"><i class="bi bi-chat-text"></i></button>`;
            sequenceHtml += `<button class="btn btn-outline-danger btn-sm delete-comment-btn" data-comment-key="${commentKey}" title="Delete comment"><i class="bi bi-trash"></i></button>`;
            sequenceHtml += `</div>`;
        }
        sequenceHtml += `</div>`;
        if (isCandidateMove) {
            sequenceHtml += `<div class="move-comment-display mt-1 small text-info" id="comment-display-${commentKey}">${displayComment}</div>`;
        }
        sequenceHtml += `</div>`;
    });
    
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
    
    // Update the feedback area
    const feedbackDiv = $('.puzzle__feedback .content');
    if (feedbackDiv.length) {
        feedbackDiv.html(`Engine sequence analysis complete! ${sequence.length} moves found.${sequenceHtml}`);
    }
    
    // Initialize sequence controls
    initSequenceControls();
    
    // Add sequence selector handler if there are multiple sequences
    if (allSequences.length > 1) {
        $('#sequence-selector').off('change').on('change', function() {
            const selectedIndex = parseInt($(this).val());
            displayedSequenceIndex = selectedIndex;
            currentSequenceIndex = selectedIndex;
            displaySequence(allSequences[selectedIndex]);
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
        inputValue: currentComment,
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
    if (score === null || score === undefined) return '';
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

// Initialize on page load
$(document).ready(function() {
    // Check if we have pre-loaded puzzle data (edit mode)
    if (window.__puzzleData) {
        loadPuzzle(window.__puzzleData, false);
    } else {
        // Initialize board with initial position
        initBoard();
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
