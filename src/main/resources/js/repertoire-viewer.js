
let sg;
let repertoire;
let currentSfen;
let history = [];
const repertoireId = document.getElementById('repertoireId').value;

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
            lastDests: lastDests,
            lastPiece: lastPiece,
            viewOnly: true,
            disableContextMenu: true,
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
        lastDests: lastDests,
        lastPiece: lastPiece,
        viewOnly: true
    });
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

function displayMoveArrows() {
    if (!sg) return;
    if (!repertoire || !repertoire.nodes) {
        sg.setAutoShapes([]);
        return;
    }

    const nodeKey = sanitizeSfen(currentSfen);
    const node = (repertoire.nodes && repertoire.nodes[nodeKey]) || { moves: [] };
    const pos = Shogiops.sfen.parseSfen("standard", currentSfen, false).value;
    const brushes = ['green', 'blue', 'yellow', 'red'];
    const shapes = [];

    node.moves.forEach((move, index) => {
        const brush = brushes[Math.min(index, brushes.length - 1)];
        const parsed = Shogiops.parseUsi(move.usi);
        if (parsed) {
            if (parsed.role) {
                shapes.push({ orig: { role: parsed.role, color: pos.turn }, dest: Shogiops.makeSquare(parsed.to), brush });
            } else {
                shapes.push({ orig: Shogiops.makeSquare(parsed.from), dest: Shogiops.makeSquare(parsed.to), brush });
            }
        }
    });

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
        currentSfen = history.pop();
        renderBoard();
        renderVariations();
        updateMenuState();
        displayMoveArrows();
    }
}

export function toRoot() {
    if (currentSfen !== repertoire.rootSfen) {
        history = [];
        currentSfen = repertoire.rootSfen;
        renderBoard();
        renderVariations();
        updateMenuState();
        displayMoveArrows();
    }
}

window.revertMove = revertMove;
window.toRoot = toRoot;

loadRepertoire();
