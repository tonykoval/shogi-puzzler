
let sg;
let repertoire;
let currentSfen;
let history = [];
const repertoireId = document.getElementById('repertoireId').value;

let editingMove = null; // { parentSfen, usi, comment, isPuzzle }

async function loadRepertoire(lastMoveUsi) {
    const response = await fetch(`/repertoire/${repertoireId}/json`);
    repertoire = await response.json();
    if (!currentSfen) {
        currentSfen = repertoire.rootSfen;
    }
    renderBoard(lastMoveUsi);
    renderVariations();
    updateMenuState();
}

function updateMenuState() {
    const revertBtn = document.querySelector('button[onclick="revertMove()"]');
    const toRootBtn = document.querySelector('button[onclick="toRoot()"]');
    
    if (revertBtn) revertBtn.disabled = history.length === 0;
    if (toRootBtn) toRootBtn.disabled = currentSfen === repertoire.rootSfen;
}

function renderBoard(lastMoveUsi) {
    const pos = Shogiops.sfen.parseSfen("standard", currentSfen, false).value;
    const hands = currentSfen.split(' ')[2];
    
    let lastDests = [];
    let lastPiece = undefined;
    if (lastMoveUsi) {
        const move = Shogiops.parseUsi(lastMoveUsi);
        if (move) {
            if (move.role) {
                lastDests = [Shogiops.makeSquare(move.to)];
                lastPiece = { role: move.role, color: pos.turn === 'sente' ? 'gote' : 'sente' };
            } else {
                lastDests = [Shogiops.makeSquare(move.from), Shogiops.makeSquare(move.to)];
            }
        }
    }

    if (!sg) {
        sg = Shogiground();
        sg.set({
            sfen: {
                board: currentSfen,
                hands: hands
            },
            orientation: 'sente',
            turnColor: pos.turn,
            activeColor: pos.turn,
            lastDests: lastDests,
            lastPiece: lastPiece,
            movable: {
                free: false,
                dests: Shogiops.compat.shogigroundMoveDests(pos),
            },
            droppable: {
                free: false,
                dests: Shogiops.compat.shogigroundDropDests(pos),
            },
            viewOnly: false,
            disableContextMenu: true,
            promotion: {
                promotesTo: role => Shogiops.variantUtil.promote("standard")(role),
                movePromotionDialog: (orig, dest) => {
                    const piece = sg.state.pieces.get(orig);
                    const capture = sg.state.pieces.get(dest);
                    return Shogiops.variantUtil.pieceCanPromote("standard")(piece, Shogiops.parseSquare(orig), Shogiops.parseSquare(dest), capture)
                        && !Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest))
                },
                forceMovePromotion: (orig, dest) => {
                    const piece = sg.state.pieces.get(orig);
                    return Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest))
                },
            },
            events: {
                move: (orig, dest, prom) => handleMove({ orig, dest, prom }),
                drop: (piece, key) => handleMove({ role: piece.role, key }),
            }
        });
        sg.attach({
            board: document.getElementById('dirty'),
        });
        sg.attach({
            hands: {
                bottom: document.getElementById('hand-bottom'),
            }
        });
        sg.attach({
            hands: {
                top: document.getElementById('hand-top'),
            }
        });
    }

    sg.set({
        sfen: {
            board: currentSfen,
            hands: hands
        },
        turnColor: pos.turn,
        activeColor: pos.turn,
        lastDests: lastDests,
        lastPiece: lastPiece,
        movable: {
            dests: Shogiops.compat.shogigroundMoveDests(pos),
        },
        droppable: {
            dests: Shogiops.compat.shogigroundDropDests(pos),
        },
        viewOnly: false
    });
}

function getNextSfen(usi) {
    const pos = Shogiops.sfen.parseSfen("standard", currentSfen, false).value;
    const move = Shogiops.parseUsi(usi);
    pos.play(move);
    return Shogiops.sfen.makeSfen(pos);
}

async function handleMove(moveData) {
    try {
        const move = moveData.role ? {
            role: moveData.role,
            to: Shogiops.parseSquare(moveData.key)
        } : {
            from: Shogiops.parseSquare(moveData.orig),
            to: Shogiops.parseSquare(moveData.dest),
            promotion: moveData.prom
        };
        const usi = Shogiops.makeUsi(move);
        const nextSfen = getNextSfen(usi);
        const parentSfen = currentSfen;

        const response = await fetch(`/repertoire/${repertoireId}/move`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ parentSfen, usi, nextSfen })
        });

        if (!response.ok) {
            throw new Error('Failed to save move');
        }

        history.push(currentSfen);
        currentSfen = nextSfen;
        await loadRepertoire(usi);
    } catch (e) {
        console.error('Error handling move:', e);
        alert('Error: ' + e.message);
        renderBoard(); // Reset board
    }
}

async function deleteMove(usi) {
    try {
        const parentSfen = currentSfen;
        const response = await fetch(`/repertoire/${repertoireId}/move/delete`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ parentSfen, usi })
        });

        if (!response.ok) {
            throw new Error('Failed to delete move');
        }

        await loadRepertoire();
    } catch (e) {
        console.error('Error deleting move:', e);
        alert('Error: ' + e.message);
    }
}

function sanitizeSfen(sfen) {
    return sfen.replace(/\./g, "_").replace(/\//g, "-").replace(/ /g, "_");
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

function renderVariations() {
    const container = document.getElementById('variation-list');
    container.innerHTML = '';
    
    const nodeKey = sanitizeSfen(currentSfen);
    const node = (repertoire.nodes && repertoire.nodes[nodeKey]) || { moves: [] };
    
    if (node.moves.length === 0) {
        container.innerHTML = '<div class="text-muted p-2">No moves here yet. Play a move on the board to add one.</div>';
    } else {
        const moveList = document.createElement('div');
        moveList.className = 'moves';
        
        const pos = Shogiops.sfen.parseSfen("standard", currentSfen, false).value;

        node.moves.forEach(move => {
            const moveRow = document.createElement('div');
            moveRow.className = 'mb-3 border-bottom border-secondary pb-2';

            const moveElem = document.createElement('move');
            const parsedMove = Shogiops.parseUsi(move.usi);
            let moveText = move.usi;
            
            if (parsedMove) {
                if (parsedMove.role) {
                    const roleForsyth = Shogiops.sfen.roleToForsyth("standard")(parsedMove.role);
                    moveText = (roleForsyth ? (roleForsyth.startsWith('+') ? roleForsyth[1].toUpperCase() + '+' : roleForsyth.toUpperCase()) : parsedMove.role[0].toUpperCase()) + '*' + Shogiops.makeSquare(parsedMove.to);
                } else {
                    const piece = pos.board.get(parsedMove.from);
                    if (piece) {
                        const roleForsyth = Shogiops.sfen.roleToForsyth("standard")(piece.role);
                        moveText = (roleForsyth ? (roleForsyth.startsWith('+') ? roleForsyth[1].toUpperCase() + '+' : roleForsyth.toUpperCase()) : piece.role[0].toUpperCase()) + Shogiops.makeSquare(parsedMove.from) + (parsedMove.promotion ? 'x' : '-') + Shogiops.makeSquare(parsedMove.to);
                    }
                }
            }

            moveElem.innerText = moveText;
            moveElem.className = 'btn btn-sm btn-outline-light me-1 mb-1';
            moveElem.onclick = () => {
                history.push(currentSfen);
                currentSfen = move.nextSfen;
                renderBoard(move.usi);
                renderVariations();
                updateMenuState();
            };
            
            const moveActionWrapper = document.createElement('div');
            moveActionWrapper.className = 'd-inline-flex align-items-center me-2';
            moveActionWrapper.appendChild(moveElem);

            const deleteBtn = document.createElement('button');
            deleteBtn.className = 'btn btn-sm btn-outline-danger p-1';
            deleteBtn.style.lineHeight = '1';
            deleteBtn.innerHTML = '<i class="bi bi-x"></i>';
            deleteBtn.title = 'Delete move';
            deleteBtn.onclick = (e) => {
                e.stopPropagation();
                if (confirm(`Are you sure you want to delete move ${moveText}?`)) {
                    deleteMove(move.usi);
                }
            };
            moveActionWrapper.appendChild(deleteBtn);

            const editBtn = document.createElement('button');
            editBtn.className = 'btn btn-sm btn-outline-warning p-1 ms-1';
            editBtn.style.lineHeight = '1';
            editBtn.innerHTML = '<i class="bi bi-pencil"></i>';
            editBtn.title = 'Edit move details';
            editBtn.onclick = (e) => {
                e.stopPropagation();
                openMoveEditModal(currentSfen, move);
            };
            moveActionWrapper.appendChild(editBtn);

            if (move.isPuzzle) {
                const puzzleIcon = document.createElement('i');
                puzzleIcon.className = 'bi bi-puzzle ms-1 text-warning';
                puzzleIcon.title = 'Suitable for puzzle';
                moveActionWrapper.appendChild(puzzleIcon);
            }

            moveRow.appendChild(moveActionWrapper);

            if (move.comment) {
                const commentDiv = document.createElement('div');
                commentDiv.className = 'move-comment mt-1 small text-info';
                commentDiv.innerHTML = formatComment(move.comment);
                moveRow.appendChild(commentDiv);
            }

            moveList.appendChild(moveRow);
        });
        
        container.appendChild(moveList);
    }
}

function openMoveEditModal(parentSfen, move) {
    editingMove = {
        parentSfen: parentSfen,
        usi: move.usi,
        comment: move.comment || '',
        isPuzzle: !!move.isPuzzle
    };
    
    document.getElementById('moveComment').value = editingMove.comment;
    document.getElementById('moveIsPuzzle').checked = editingMove.isPuzzle;
    
    const modal = new bootstrap.Modal(document.getElementById('moveEditModal'));
    modal.show();
}

async function saveMoveDetails() {
    if (!editingMove) return;
    
    const comment = document.getElementById('moveComment').value;
    const isPuzzle = document.getElementById('moveIsPuzzle').checked;
    
    try {
        const response = await fetch(`/repertoire/${repertoireId}/move/update`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                parentSfen: editingMove.parentSfen,
                usi: editingMove.usi,
                comment: comment,
                isPuzzle: isPuzzle
            })
        });

        if (!response.ok) {
            throw new Error('Failed to update move');
        }

        const modalElement = document.getElementById('moveEditModal');
        const modal = bootstrap.Modal.getInstance(modalElement);
        modal.hide();
        
        await loadRepertoire();
    } catch (e) {
        console.error('Error updating move:', e);
        alert('Error: ' + e.message);
    }
}

export function revertMove() {
    if (history.length > 0) {
        currentSfen = history.pop();
        renderBoard();
        renderVariations();
        updateMenuState();
    }
}

export function toRoot() {
    if (currentSfen !== repertoire.rootSfen) {
        history = [];
        currentSfen = repertoire.rootSfen;
        renderBoard();
        renderVariations();
        updateMenuState();
    }
}

window.revertMove = revertMove;
window.toRoot = toRoot;
window.saveMoveDetails = saveMoveDetails;

async function importMoves() {
    const usis = document.getElementById('importUsis').value;
    if (!usis.trim()) return;

    const comment = document.getElementById('importComment').value;
    const isPuzzle = document.getElementById('importIsPuzzle').checked;

    try {
        const response = await fetch(`/repertoire/${repertoireId}/import`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                usis: usis,
                startSfen: currentSfen,
                comment: comment,
                isPuzzle: isPuzzle
            })
        });

        if (!response.ok) {
            throw new Error('Failed to import moves');
        }

        const result = await response.json();
        alert(`Successfully imported ${result.importedCount} moves.`);
        
        // Reset form fields
        document.getElementById('importUsis').value = '';
        document.getElementById('importComment').value = '';
        document.getElementById('importIsPuzzle').checked = false;

        const modalElement = document.getElementById('importMovesModal');
        const modal = bootstrap.Modal.getInstance(modalElement);
        if (modal) modal.hide();
        
        await loadRepertoire();
    } catch (e) {
        console.error('Error importing moves:', e);
        alert('Error: ' + e.message);
    }
}

window.importMoves = importMoves;

// Expose currentSfen for Lishogi analysis button
Object.defineProperty(window, 'currentSfen', {
    get: function() { return currentSfen; }
});

loadRepertoire();
