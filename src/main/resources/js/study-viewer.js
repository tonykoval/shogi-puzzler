
let sg;
let study;
let currentSfen;
let history = [];
let moveHistory = [];
const studyId = document.getElementById('studyId').value;
let lastMoveComment = null;

// Map annotation color letters → Shogiground brush names (matches CSS classes)
const _annColorToBrush = { G: 'primary', R: 'alternative0', B: 'alternative1', Y: 'hint' };

function parseAnnotations(comment) {
    if (!comment) return { text: '', shapes: [] };

    const shapes = [];

    const calRegex = /\[%cal\s+([^\]]+)\]/g;
    let match;
    while ((match = calRegex.exec(comment)) !== null) {
        match[1].split(',').forEach(token => {
            token = token.trim();
            if (token.length >= 5) {
                const brush = _annColorToBrush[token[0]] || 'primary';
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
                const brush = _annColorToBrush[token[0]] || 'primary';
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
    const response = await fetch(`/study-viewer/${studyId}/json`);
    study = await response.json();
    if (!currentSfen) {
        currentSfen = study.rootSfen;
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
    if (toRootBtn) toRootBtn.disabled = currentSfen === study.rootSfen;

    if (advanceBtn) {
        const nodeKey = sanitizeSfen(currentSfen);
        const node = (study && study.nodes && study.nodes[nodeKey]) || { moves: [] };
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
    const node = (study && study.nodes && study.nodes[nodeKey]) || { moves: [] };
    const matchingMove = node.moves.find(m => m.usi === usi);

    if (matchingMove) {
        lastMoveComment = matchingMove.comment || null;
        history.push(currentSfen);
        currentSfen = matchingMove.nextSfen;
        renderBoard(usi);
        renderVariations();
        updateMenuState();
        displayMoveArrows();
        _svAnalyzeCurrent();
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

    const comment = lastMoveComment || (currentSfen === study?.rootSfen ? study?.rootComment : null);

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
    if (!study || !study.nodes) {
        sg.setAutoShapes([]);
        return;
    }

    const nodeKey = sanitizeSfen(currentSfen);
    const node = (study.nodes && study.nodes[nodeKey]) || { moves: [] };
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
    const node = (study.nodes && study.nodes[nodeKey]) || { moves: [] };

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
                _svAnalyzeCurrent();
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
        _svAnalyzeCurrent();
    }
}

export function toRoot() {
    if (currentSfen !== study.rootSfen) {
        lastMoveComment = null;
        history = [];
        moveHistory = [];
        currentSfen = study.rootSfen;
        renderBoard();
        renderVariations();
        updateMenuState();
        displayMoveArrows();
        _svAnalyzeCurrent();
    }
}

export function advanceMove() {
    if (!study || !study.nodes) return;
    const nodeKey = sanitizeSfen(currentSfen);
    const node = (study.nodes[nodeKey]) || { moves: [] };
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
        _svAnalyzeCurrent();
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

// ── Local engine (WASM) ──────────────────────────────────────────────────────

let _svCeval   = null;
let _svCevalOn = false;

function svToggleCeval() {
    const btn = document.getElementById('svCevalBtn');
    if (_svCevalOn) {
        _svCevalOn = false;
        _svCeval?.stop();
        if (btn) { btn.classList.remove('active'); btn.innerHTML = '<i class="bi bi-cpu-fill"></i><span class="d-none d-md-inline ms-1">Local</span>'; }
        const statusEl = document.getElementById('engine-status');
        if (statusEl) { statusEl.style.display = 'none'; statusEl.textContent = ''; }
        const panel = document.getElementById('engine-results');
        if (panel) { panel.style.display = 'none'; panel.innerHTML = ''; }
        if (sg) sg.setAutoShapes([]);
        return;
    }

    if (typeof ClientEval === 'undefined' || !ClientEval.isSupported()) {
        alert('Local engine requires a modern browser with SharedArrayBuffer support (Chrome/Edge/Firefox).');
        return;
    }

    _svCevalOn = true;
    if (btn) { btn.classList.add('active'); btn.innerHTML = '<i class="bi bi-cpu-fill"></i><span class="d-none d-md-inline ms-1">Local…</span>'; }
    const statusEl = document.getElementById('engine-status');
    if (statusEl) { statusEl.style.display = 'block'; statusEl.textContent = 'Loading engine…'; }

    if (!_svCeval) {
        _svInitCeval(); // _svAnalyzeCurrent() is called from onReady
    } else {
        _svAnalyzeCurrent(); // engine already loaded, start immediately
    }
}

function _svResetCevalBtn() {
    _svCevalOn = false;
    _svCeval = null;
    const b = document.getElementById('svCevalBtn');
    if (b) { b.classList.remove('active'); b.innerHTML = '<i class="bi bi-cpu-fill"></i><span class="d-none d-md-inline ms-1">Local</span>'; }
    const el = document.getElementById('engine-status');
    if (el) { el.style.display = 'block'; }
}

function _svInitCeval() {
    const s = CevalSettings.load();
    _svCeval = new ClientEval({
        multiPv: s.multiPv,
        threads:  s.threads || undefined,
        onStatus: (msg) => {
            const el = document.getElementById('engine-status');
            if (!el) return;
            if (msg === 'computing') { el.textContent = 'Computing…'; return; }
            if (msg === 'ready')     { el.textContent = 'Ready'; return; }
            // Engine failed to load — reset toggle so user can retry
            if (msg.startsWith('Engine failed to load')) {
                el.textContent = '⚠ ' + msg;
                _svResetCevalBtn();
                return;
            }
            el.textContent = msg;
        },
        onReady: (name) => {
            const b = document.getElementById('svCevalBtn');
            if (b) b.title = name;
            const el = document.getElementById('engine-status');
            if (el) el.textContent = 'Ready';
            // Start analysis now that the engine is ready
            _svAnalyzeCurrent();
        },
        onEval: (ev) => { _svOnEval(ev); },
    });
    _svCeval.init();
}

function svOpenCevalSettings() {
    if (typeof CevalSettings === 'undefined') return;
    CevalSettings.openModal((_newSettings) => {
        if (!_svCevalOn) return;
        // Restart engine so new threads / multiPv take effect
        _svCeval?.destroy();
        _svCeval = null;
        _svInitCeval(); // _svAnalyzeCurrent() called from onReady
    });
}

function _svAnalyzeCurrent() {
    if (!_svCevalOn || !currentSfen || !_svCeval || _svCeval.state === 'idle') return;
    const s = CevalSettings.load();
    const movetime = s.movetime > 0 ? s.movetime * 1000 : 90000;
    _svCeval.analyze(currentSfen, { movetime, multiPv: s.multiPv });
}

function _svOnEval(ev) {
    if (!_svCevalOn || !ev) return;

    // Update button label
    const btn = document.getElementById('svCevalBtn');
    if (btn) {
        const label = ev.mate != null
            ? (ev.mate > 0 ? '▲詰' : '▽詰') + Math.abs(ev.mate)
            : ev.cp != null ? (ev.cp >= 0 ? '▲' : '▽') + Math.abs(ev.cp) : '';
        btn.innerHTML = `<i class="bi bi-cpu-fill"></i><span class="d-none d-md-inline ms-1">${label} d${ev.depth}</span>`;
    }

    // Status line
    const statusEl = document.getElementById('engine-status');
    if (statusEl) { statusEl.style.display = 'block'; statusEl.textContent = `depth ${ev.depth}`; }

    // Engine arrows (overlay on study arrows)
    if (ev.pvs && ev.pvs.length && sg) {
        let pos;
        try { pos = Shogiops.sfen.parseSfen('standard', currentSfen, false).value; } catch (_) {}
        const brushes = ['green', 'blue', 'yellow'];
        const shapes = [];
        ev.pvs.slice(0, 3).forEach((pv, idx) => {
            const move = pv.moves && pv.moves[0];
            if (!move) return;
            const brush = brushes[Math.min(idx, 2)];
            if (move.includes('*')) {
                const roleMap = { p:'pawn', l:'lance', n:'knight', s:'silver', g:'gold', b:'bishop', r:'rook' };
                const role = roleMap[move[0].toLowerCase()];
                if (role && pos) shapes.push({ orig: { role, color: pos.turn }, dest: Shogiops.makeSquare(move.substring(2, 4)), brush });
            } else {
                const parsed = Shogiops.parseUsi(move);
                if (parsed) shapes.push({ orig: Shogiops.makeSquare(parsed.from), dest: Shogiops.makeSquare(parsed.to), brush });
            }
        });
        if (shapes.length) sg.setAutoShapes(shapes);
    }

    // PV panel
    _svDisplayResults(ev);
}

function _svDisplayResults(ev) {
    const panel = document.getElementById('engine-results');
    if (!panel) return;
    panel.style.display = 'block';

    let pos;
    try { pos = Shogiops.sfen.parseSfen('standard', currentSfen, false).value; } catch (_) {}
    const colorIndicators = ['text-success', 'text-primary', 'text-warning'];

    if (!panel.querySelector('.sv-ceval-header')) {
        panel.innerHTML = '';
        const header = document.createElement('div');
        header.className = 'd-flex justify-content-between align-items-center mb-1 sv-ceval-header';
        header.innerHTML = '<span class="text-muted small fw-bold">Local Engine</span>';
        const closeBtn = document.createElement('button');
        closeBtn.className = 'btn btn-sm btn-outline-secondary p-0 px-1';
        closeBtn.innerHTML = '<i class="bi bi-x"></i>';
        closeBtn.onclick = svToggleCeval;
        header.appendChild(closeBtn);
        panel.appendChild(header);
        const pvContainer = document.createElement('div');
        pvContainer.id = 'sv-ceval-pv-rows';
        panel.appendChild(pvContainer);
    }

    const pvContainer = document.getElementById('sv-ceval-pv-rows');
    if (!pvContainer) return;
    pvContainer.innerHTML = '';

    (ev.pvs || []).slice(0, 3).forEach((pv, index) => {
        const move = pv.moves && pv.moves[0];
        if (!move) return;
        const cp = pv.cp, mate = pv.mate;
        let scoreText, scoreCls;
        if (mate != null) {
            scoreText = mate > 0 ? `#${mate}` : `#${mate}`;
            scoreCls = mate > 0 ? 'text-success' : 'text-danger';
        } else {
            scoreText = cp >= 0 ? `+${cp}` : `${cp}`;
            scoreCls = cp >= 100 ? 'text-success' : cp <= -100 ? 'text-danger' : 'text-warning';
        }
        const moveText = pos ? formatMoveText(move, pos) : move;
        const colorCls = colorIndicators[Math.min(index, 2)];
        const depthText = pv.depth ? `d${pv.depth}` : '';
        const row = document.createElement('div');
        row.className = 'analyse__engine-result d-flex align-items-center py-1';
        row.innerHTML = `<i class="bi bi-circle-fill ${colorCls} me-1" style="font-size:0.5em;vertical-align:middle"></i><span class="text-light">${moveText}</span> <span class="fw-bold ${scoreCls} ms-2">${scoreText}</span> <span class="text-muted small ms-1">${depthText}</span>`;
        pvContainer.appendChild(row);
    });
}

window.revertMove = revertMove;
window.toRoot = toRoot;
window.advanceMove = advanceMove;
window.svToggleCeval = svToggleCeval;
window.svOpenCevalSettings = svOpenCevalSettings;

loadRepertoire();
