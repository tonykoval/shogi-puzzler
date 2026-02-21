
let sg;
let repertoire;
let currentSfen;
let history = [];
let moveHistory = [];
const repertoireId = document.getElementById('repertoireId').value;
let lastMoveComment = null;

function parseAnnotations(comment) {
    if (!comment) return { text: '', shapes: [] };

    const shapes = [];
    const colorMap = { G: 'green', B: 'blue', R: 'red', Y: 'yellow' };

    const calRegex = /\[%cal\s+([^\]]+)\]/g;
    let match;
    while ((match = calRegex.exec(comment)) !== null) {
        match[1].split(',').forEach(token => {
            token = token.trim();
            if (token.length >= 5) {
                const brush = colorMap[token[0]] || 'green';
                const orig = token[1] + token[2];
                const dest = token[3] + token[4];
                shapes.push({ orig, dest, brush });
            }
        });
    }

    const cslRegex = /\[%csl\s+([^\]]+)\]/g;
    while ((match = cslRegex.exec(comment)) !== null) {
        match[1].split(',').forEach(token => {
            token = token.trim();
            if (token.length >= 3) {
                const brush = colorMap[token[0]] || 'green';
                const orig = token[1] + token[2];
                shapes.push({ orig, dest: orig, brush });
            }
        });
    }

    const text = comment.replace(/\[%cal\s+[^\]]+\]/g, '').replace(/\[%csl\s+[^\]]+\]/g, '').trim();

    return { text, shapes };
}

function formatMoveText(usi, pos) {
    const parsed = Shogiops.parseUsi(usi);
    if (!parsed) return usi;

    if (parsed.role) {
        const roleForsyth = Shogiops.sfen.roleToForsyth("standard")(parsed.role);
        return (roleForsyth ? (roleForsyth.startsWith('+') ? roleForsyth[1].toUpperCase() + '+' : roleForsyth.toUpperCase()) : parsed.role[0].toUpperCase()) + '*' + Shogiops.makeSquare(parsed.to);
    } else {
        const piece = pos.board.get(parsed.from);
        if (piece) {
            const roleForsyth = Shogiops.sfen.roleToForsyth("standard")(piece.role);
            return (roleForsyth ? (roleForsyth.startsWith('+') ? roleForsyth[1].toUpperCase() + '+' : roleForsyth.toUpperCase()) : piece.role[0].toUpperCase()) + Shogiops.makeSquare(parsed.from) + (parsed.promotion ? 'x' : '-') + Shogiops.makeSquare(parsed.to);
        }
    }
    return usi;
}

async function loadRepertoire(lastMoveUsi) {
    const response = await fetch(`/repertoire-viewer/${repertoireId}/json`);
    repertoire = await response.json();
    if (!currentSfen) {
        currentSfen = repertoire.rootSfen;
    }
    renderBoard(lastMoveUsi);
    renderVariations();
    updateMenuState();
    displayMoveArrows();
}

function updateMenuState() {
    const revertBtn = document.querySelector('button[onclick="revertMove()"]');
    const toRootBtn = document.querySelector('button[onclick="toRoot()"]');
    const advanceBtn = document.querySelector('button[onclick="advanceMove()"]');

    if (revertBtn) revertBtn.disabled = history.length === 0;
    if (toRootBtn) toRootBtn.disabled = currentSfen === repertoire.rootSfen;

    if (advanceBtn) {
        const nodeKey = sanitizeSfen(currentSfen);
        const node = (repertoire && repertoire.nodes && repertoire.nodes[nodeKey]) || { moves: [] };
        advanceBtn.disabled = node.moves.length !== 1;
    }
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
            viewOnly: false,
            disableContextMenu: true,
            movable: {
                free: false,
                dests: Shogiops.compat.shogigroundMoveDests(pos),
            },
            droppable: {
                free: false,
                dests: Shogiops.compat.shogigroundDropDests(pos),
            },
            promotion: {
                promotesTo: role => Shogiops.variantUtil.promote("standard")(role),
                movePromotionDialog: (orig, dest) => {
                    const piece = sg.state.pieces.get(orig);
                    const capture = sg.state.pieces.get(dest);
                    return Shogiops.variantUtil.pieceCanPromote("standard")(piece, Shogiops.parseSquare(orig), Shogiops.parseSquare(dest), capture)
                        && !Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest));
                },
                forceMovePromotion: (orig, dest) => {
                    const piece = sg.state.pieces.get(orig);
                    return Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest));
                },
            },
            events: {
                move: (orig, dest, prom) => handleBoardMove({ orig, dest, prom }),
                drop: (piece, key) => handleBoardMove({ role: piece.role, key }),
            },
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
        viewOnly: false,
        movable: {
            dests: Shogiops.compat.shogigroundMoveDests(pos),
        },
        droppable: {
            dests: Shogiops.compat.shogigroundDropDests(pos),
        },
    });
}

function handleBoardMove(moveData) {
    const move = moveData.role ? {
        role: moveData.role,
        to: Shogiops.parseSquare(moveData.key)
    } : {
        from: Shogiops.parseSquare(moveData.orig),
        to: Shogiops.parseSquare(moveData.dest),
        promotion: moveData.prom
    };
    const usi = Shogiops.makeUsi(move);

    const nodeKey = sanitizeSfen(currentSfen);
    const node = (repertoire && repertoire.nodes && repertoire.nodes[nodeKey]) || { moves: [] };
    const matchingMove = node.moves.find(m => m.usi === usi);

    if (matchingMove) {
        lastMoveComment = matchingMove.comment || null;
        history.push(currentSfen);
        currentSfen = matchingMove.nextSfen;
        renderBoard(usi);
        renderVariations();
        updateMenuState();
        displayMoveArrows();
    } else {
        renderBoard();
    }
}

function sanitizeSfen(sfen) {
    return sfen.replace(/\./g, "_").replace(/\//g, "-").replace(/ /g, "_");
}

function formatComment(comment) {
    if (!comment) return "";
    const { text } = parseAnnotations(comment);
    if (!text) return "";
    return text.split('\n').map(line => {
        if (!line.trim()) return "";
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

function updateCommentDisplay() {
    const panel = document.getElementById('comment-display');
    if (!panel) return;

    const comment = lastMoveComment || (currentSfen === repertoire?.rootSfen ? repertoire?.rootComment : null);

    if (comment) {
        const { text } = parseAnnotations(comment);
        if (text) {
            panel.innerHTML = formatComment(comment);
            panel.style.display = 'block';
            return;
        }
    }
    panel.style.display = 'none';
    panel.innerHTML = '';
}

function displayMoveArrows() {
    updateCommentDisplay();
    if (!sg) return;
    if (!repertoire || !repertoire.nodes) {
        sg.setAutoShapes([]);
        return;
    }

    const nodeKey = sanitizeSfen(currentSfen);
    const node = (repertoire.nodes && repertoire.nodes[nodeKey]) || { moves: [] };
    const pos = Shogiops.sfen.parseSfen("standard", currentSfen, false).value;
    const shapes = [];

    node.moves.forEach((move, index) => {
        const brush = 'dark';
        const parsed = Shogiops.parseUsi(move.usi);
        if (parsed) {
            if (parsed.role) {
                shapes.push({ orig: { role: parsed.role, color: pos.turn }, dest: Shogiops.makeSquare(parsed.to), brush });
            } else {
                shapes.push({ orig: Shogiops.makeSquare(parsed.from), dest: Shogiops.makeSquare(parsed.to), brush });
            }
        }
    });

    // Add annotation shapes from the last played move's comment
    if (lastMoveComment) {
        const { shapes: annotationShapes } = parseAnnotations(lastMoveComment);
        shapes.push(...annotationShapes);
    }

    sg.setAutoShapes(shapes);
}

function renderVariations() {
    const container = document.getElementById('variation-list');
    container.innerHTML = '';

    const nodeKey = sanitizeSfen(currentSfen);
    const node = (repertoire.nodes && repertoire.nodes[nodeKey]) || { moves: [] };

    if (node.moves.length === 0) {
        container.innerHTML = '<div class="text-muted p-2">No moves from this position.</div>';
    } else {
        const moveList = document.createElement('div');
        moveList.className = 'moves';

        const pos = Shogiops.sfen.parseSfen("standard", currentSfen, false).value;

        node.moves.forEach(move => {
            const moveRow = document.createElement('div');
            moveRow.className = 'mb-3 border-bottom border-secondary pb-2';

            const moveElem = document.createElement('move');
            const moveText = formatMoveText(move.usi, pos);

            moveElem.innerText = moveText;
            moveElem.className = 'btn btn-sm btn-outline-light me-1 mb-1';
            moveElem.onclick = () => {
                lastMoveComment = move.comment || null;
                history.push(currentSfen);
                currentSfen = move.nextSfen;
                renderBoard(move.usi);
                renderVariations();
                updateMenuState();
                displayMoveArrows();
            };

            moveRow.appendChild(moveElem);

            if (move.comment) {
                const commentDiv = document.createElement('div');
                commentDiv.className = 'move-comment-inline small text-info';
                commentDiv.innerHTML = formatComment(move.comment);
                moveRow.appendChild(commentDiv);
            }

            moveList.appendChild(moveRow);
        });

        container.appendChild(moveList);
    }
}

export function revertMove() {
    if (history.length > 0) {
        lastMoveComment = null;
        currentSfen = history.pop();
        moveHistory.pop();
        renderBoard();
        renderVariations();
        updateMenuState();
        displayMoveArrows();
    }
}

export function toRoot() {
    if (currentSfen !== repertoire.rootSfen) {
        lastMoveComment = null;
        history = [];
        moveHistory = [];
        currentSfen = repertoire.rootSfen;
        renderBoard();
        renderVariations();
        updateMenuState();
        displayMoveArrows();
    }
}

export function advanceMove() {
    if (!repertoire || !repertoire.nodes) return;
    const nodeKey = sanitizeSfen(currentSfen);
    const node = (repertoire.nodes[nodeKey]) || { moves: [] };
    if (node.moves.length === 1) {
        const move = node.moves[0];
        lastMoveComment = move.comment || null;
        history.push(currentSfen);
        moveHistory.push(move.usi);
        currentSfen = move.nextSfen;
        renderBoard(move.usi);
        renderVariations();
        updateMenuState();
        displayMoveArrows();
    }
}

document.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA' || e.target.tagName === 'SELECT') return;
    if (e.key === 'ArrowLeft') {
        e.preventDefault();
        revertMove();
    } else if (e.key === 'ArrowRight') {
        e.preventDefault();
        advanceMove();
    }
});

window.revertMove = revertMove;
window.toRoot = toRoot;
window.advanceMove = advanceMove;

loadRepertoire();
