
let sg;
let study;
let currentSfen;
let history = [];
let moveHistory = [];
let _userDrawnShapes = []; // USI moves parallel to history (SFENs)
const studyId = document.getElementById('studyId').value;

let editingMove = null; // { parentSfen, usi, comment, isPuzzle }
let engineArrowsActive = false;
let editingCommentUsi = null;
let lastMoveComment = null;

// Map annotation color letters ↔ Shogiground brush names
// Shogiground CSS defines: primary(green), alternative0(red), alternative1(blue/purple), alternative2(cyan), hint(yellow)
const _annColorToBrush = { G: 'primary', R: 'alternative0', B: 'alternative1', Y: 'hint' };
const _brushToAnnColor = { primary: 'G', alternative0: 'R', alternative1: 'B', alternative2: 'Y', hint: 'Y' };

function parseAnnotations(comment) {
    if (!comment) return { text: '', shapes: [] };

    const shapes = [];

    // Parse [%cal ...] arrows
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

    // Parse [%csl ...] circles
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

    // Strip annotations from text
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
    const response = await fetch(`/study/${studyId}/json`);
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
            },
            drawable: {
                enabled: true,
                visible: true,
                eraseOnClick: false,
                onChange: (shapes) => { _userDrawnShapes = shapes; },
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

        const response = await fetch(`/study/${studyId}/move`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ parentSfen, usi, nextSfen })
        });

        if (!response.ok) {
            throw new Error('Failed to save move');
        }

        clearEngineAnalysis();
        editingCommentUsi = null;
        lastMoveComment = null; // new move has no comment yet
        history.push(currentSfen);
        moveHistory.push(usi);
        currentSfen = nextSfen;
        await loadRepertoire(usi);
        _studyAnalyzeCurrent();
    } catch (e) {
        console.error('Error handling move:', e);
        alert('Error: ' + e.message);
        renderBoard(); // Reset board
    }
}

async function deleteMove(usi) {
    try {
        const parentSfen = currentSfen;
        const response = await fetch(`/study/${studyId}/move/delete`, {
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

    // Show root comment when at root position, otherwise show last move comment
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
    if (engineArrowsActive || !sg) return;
    if (!study || !study.nodes) {
        sg.setAutoShapes([]);
        _userDrawnShapes = [];
        return;
    }

    const nodeKey = sanitizeSfen(currentSfen);
    const node = (study.nodes && study.nodes[nodeKey]) || { moves: [] };

    const pos = Shogiops.sfen.parseSfen("standard", currentSfen, false).value;
    const autoShapes = [];

    node.moves.forEach((move) => {
        const parsed = Shogiops.parseUsi(move.usi);
        if (parsed) {
            if (parsed.role) {
                autoShapes.push({ orig: { role: parsed.role, color: pos.turn }, dest: Shogiops.makeSquare(parsed.to), brush: 'dark' });
            } else {
                autoShapes.push({ orig: Shogiops.makeSquare(parsed.from), dest: Shogiops.makeSquare(parsed.to), brush: 'dark' });
            }
        }
    });

    sg.setAutoShapes(autoShapes);

    // Load saved annotation shapes (circles/arrows from comment) into drawable so user can edit them
    const comment = lastMoveComment || (currentSfen === study?.rootSfen ? study?.rootComment : null);
    const annotationShapes = comment ? parseAnnotations(comment).shapes : [];
    sg.set({ drawable: { shapes: annotationShapes } });
    _userDrawnShapes = annotationShapes;
}

function renderVariations() {
    const container = document.getElementById('variation-list');
    container.innerHTML = '';

    const nodeKey = sanitizeSfen(currentSfen);
    const node = (study.nodes && study.nodes[nodeKey]) || { moves: [] };

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
            const moveText = formatMoveText(move.usi, pos);

            moveElem.innerText = moveText;
            moveElem.className = 'btn btn-sm btn-outline-light me-1 mb-1';
            moveElem.onclick = () => {
                clearEngineAnalysis();
                editingCommentUsi = null;
                lastMoveComment = move.comment || null;
                history.push(currentSfen);
                currentSfen = move.nextSfen;
                renderBoard(move.usi);
                renderVariations();
                updateMenuState();
                displayMoveArrows();
                _studyAnalyzeCurrent();
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
            editBtn.title = 'Toggle puzzle flag';
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

                const sendBtn = document.createElement('button');
                sendBtn.className = 'btn btn-sm btn-outline-info p-1 ms-1';
                sendBtn.style.lineHeight = '1';
                sendBtn.innerHTML = '<i class="bi bi-box-arrow-up-right"></i>';
                sendBtn.title = 'Send to Puzzle Creator';
                sendBtn.onclick = (e) => {
                    e.stopPropagation();
                    sendToPuzzleCreator(currentSfen, move);
                };
                moveActionWrapper.appendChild(sendBtn);
            }

            moveRow.appendChild(moveActionWrapper);

            // Inline comment section
            const commentContainer = document.createElement('div');
            commentContainer.className = 'mt-1';

            if (editingCommentUsi === move.usi) {
                const tabUid = move.usi.replace(/[^a-zA-Z0-9]/g, '_');

                // Outer card wrapper
                const editorWrap = document.createElement('div');
                editorWrap.style.cssText = 'background:#191714; border:1px solid #3a3632; border-radius:8px; padding:10px 12px; margin-top:6px;';

                // Tab nav
                const tabNav = document.createElement('ul');
                tabNav.className = 'nav mb-2';
                tabNav.style.cssText = 'gap:2px; border-bottom:1px solid #3a3632; padding-bottom:6px;';
                tabNav.innerHTML = `
                    <li class="nav-item">
                        <button class="nav-link active py-1 px-3" data-tab="en" data-uid="${tabUid}"
                            style="font-size:0.8em; background:#2e2b28; color:#e0dbd5; border-radius:5px; border:1px solid #4a4744;">
                            🇬🇧 EN
                        </button>
                    </li>
                    <li class="nav-item">
                        <button class="nav-link py-1 px-3" data-tab="sk" data-uid="${tabUid}"
                            style="font-size:0.8em; background:transparent; color:#888; border-radius:5px; border:1px solid transparent;">
                            🇸🇰 SK
                        </button>
                    </li>`;
                editorWrap.appendChild(tabNav);

                // Helper: build a styled auto-resizing textarea
                function makeCommentTextarea(id, value, placeholder) {
                    const ta = document.createElement('textarea');
                    ta.id = id;
                    ta.className = 'form-control bg-dark text-light';
                    ta.style.cssText = [
                        'border:1px solid #3a3632',
                        'border-radius:6px',
                        'font-size:0.875em',
                        'line-height:1.7',
                        'resize:none',
                        'overflow:hidden',
                        'min-height:88px',
                        'padding:8px 10px',
                        'transition:border-color 0.15s, box-shadow 0.15s'
                    ].join(';');
                    ta.rows = 4;
                    ta.value = value;
                    ta.placeholder = placeholder;
                    ta.addEventListener('input', () => {
                        ta.style.height = 'auto';
                        ta.style.height = ta.scrollHeight + 'px';
                    });
                    ta.addEventListener('focus', () => {
                        ta.style.borderColor = '#5a9fd4';
                        ta.style.boxShadow = '0 0 0 0.15rem rgba(90,159,212,0.18)';
                    });
                    ta.addEventListener('blur', () => {
                        ta.style.borderColor = '#3a3632';
                        ta.style.boxShadow = 'none';
                    });
                    return ta;
                }

                // EN pane
                const enPane = document.createElement('div');
                enPane.id = `comment-pane-en-${tabUid}`;

                const textarea = makeCommentTextarea('inline-comment-textarea', move.comment || '', 'Comment for this move… (Ctrl+Enter to save)');
                textarea.addEventListener('keydown', (e) => {
                    if (e.ctrlKey && e.key === 'Enter') { e.preventDefault(); saveMoveComment(currentSfen, move.usi, move.isPuzzle); }
                });
                enPane.appendChild(textarea);

                const enFooter = document.createElement('div');
                enFooter.className = 'd-flex justify-content-between align-items-center mt-2';
                enFooter.innerHTML = `<small style="color:#555; font-size:0.7em;"><i class="bi bi-keyboard me-1"></i>Ctrl+Enter to save</small>`;

                const enBtns = document.createElement('div');
                enBtns.className = 'd-flex gap-1';

                const saveBtn = document.createElement('button');
                saveBtn.className = 'btn btn-sm btn-success';
                saveBtn.innerHTML = '<i class="bi bi-check-lg me-1"></i>Save';
                saveBtn.onclick = (e) => { e.stopPropagation(); saveMoveComment(currentSfen, move.usi, move.isPuzzle); };

                const cancelBtn = document.createElement('button');
                cancelBtn.className = 'btn btn-sm btn-outline-secondary';
                cancelBtn.innerHTML = '<i class="bi bi-x me-1"></i>Cancel';
                cancelBtn.onclick = (e) => {
                    e.stopPropagation();
                    editingCommentUsi = null;
                    renderVariations();
                    displayMoveArrows();
                };

                enBtns.appendChild(saveBtn);
                enBtns.appendChild(cancelBtn);
                enFooter.appendChild(enBtns);
                enPane.appendChild(enFooter);
                editorWrap.appendChild(enPane);

                // SK pane
                const skPane = document.createElement('div');
                skPane.id = `comment-pane-sk-${tabUid}`;
                skPane.style.display = 'none';

                const skTextarea = makeCommentTextarea('inline-comment-textarea-sk',
                    (move.comment_i18n && move.comment_i18n.sk) ? move.comment_i18n.sk : '',
                    'Slovak translation… (Ctrl+Enter to save)');
                skTextarea.addEventListener('keydown', (e) => {
                    if (e.ctrlKey && e.key === 'Enter') { e.preventDefault(); saveMoveTranslation(currentSfen, move.usi, 'sk', skTextarea.value); }
                });
                skPane.appendChild(skTextarea);

                const skFooter = document.createElement('div');
                skFooter.className = 'd-flex justify-content-between align-items-center mt-2';
                skFooter.innerHTML = `<small style="color:#555; font-size:0.7em;"><i class="bi bi-keyboard me-1"></i>Ctrl+Enter to save</small>`;

                const skBtns = document.createElement('div');
                skBtns.className = 'd-flex gap-1';

                const autoTranslateBtn = document.createElement('button');
                autoTranslateBtn.className = 'btn btn-sm btn-outline-info';
                autoTranslateBtn.innerHTML = '<i class="bi bi-translate me-1"></i>Auto';
                autoTranslateBtn.onclick = async (e) => {
                    e.stopPropagation();
                    const enText = textarea.value || move.comment || '';
                    if (!enText.trim()) return;
                    autoTranslateBtn.disabled = true;
                    autoTranslateBtn.innerHTML = '<i class="bi bi-arrow-repeat me-1"></i>…';
                    try {
                        const resp = await fetch('/api/translate', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ text: enText, from: 'en', to: 'sk' })
                        });
                        const data = await resp.json();
                        if (data.translated) {
                            skTextarea.value = data.translated;
                            skTextarea.dispatchEvent(new Event('input'));
                        }
                    } catch (err) {
                        alert('Auto-translate failed: ' + err.message);
                    } finally {
                        autoTranslateBtn.disabled = false;
                        autoTranslateBtn.innerHTML = '<i class="bi bi-translate me-1"></i>Auto';
                    }
                };

                const saveSkBtn = document.createElement('button');
                saveSkBtn.className = 'btn btn-sm btn-success';
                saveSkBtn.innerHTML = '<i class="bi bi-check-lg me-1"></i>Save SK';
                saveSkBtn.onclick = (e) => { e.stopPropagation(); saveMoveTranslation(currentSfen, move.usi, 'sk', skTextarea.value); };

                skBtns.appendChild(autoTranslateBtn);
                skBtns.appendChild(saveSkBtn);
                skFooter.appendChild(skBtns);
                skPane.appendChild(skFooter);
                editorWrap.appendChild(skPane);

                // Tab switching
                tabNav.querySelectorAll('[data-tab]').forEach(tabBtn => {
                    tabBtn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        tabNav.querySelectorAll('[data-tab]').forEach(b => {
                            b.classList.remove('active');
                            b.style.background = 'transparent';
                            b.style.color = '#888';
                            b.style.borderColor = 'transparent';
                        });
                        tabBtn.classList.add('active');
                        tabBtn.style.background = '#2e2b28';
                        tabBtn.style.color = '#e0dbd5';
                        tabBtn.style.borderColor = '#4a4744';
                        const tab = tabBtn.dataset.tab;
                        enPane.style.display = tab === 'en' ? '' : 'none';
                        skPane.style.display = tab === 'sk' ? '' : 'none';
                        setTimeout(() => {
                            const activeTA = tab === 'en' ? textarea : skTextarea;
                            activeTA.style.height = 'auto';
                            activeTA.style.height = activeTA.scrollHeight + 'px';
                            activeTA.focus();
                        }, 0);
                    });
                });

                commentContainer.appendChild(editorWrap);

                // Init heights + focus after render
                setTimeout(() => {
                    [textarea, skTextarea].forEach(ta => {
                        ta.style.height = 'auto';
                        ta.style.height = ta.scrollHeight + 'px';
                    });
                    textarea.focus();
                }, 0);
            } else {
                if (move.comment) {
                    const commentDiv = document.createElement('div');
                    commentDiv.className = 'move-comment-inline small text-info';
                    commentDiv.innerHTML = formatComment(move.comment);

                    const editIcon = document.createElement('i');
                    editIcon.className = 'bi bi-pencil ms-2 text-muted';
                    editIcon.style.cursor = 'pointer';
                    editIcon.style.fontSize = '0.85em';
                    editIcon.title = 'Edit comment';
                    editIcon.onclick = (e) => {
                        e.stopPropagation();
                        editingCommentUsi = move.usi;
                        renderVariations();
                        displayMoveArrows();
                        setTimeout(() => {
                            const ta = document.getElementById('inline-comment-textarea');
                            if (ta) ta.focus();
                        }, 0);
                    };
                    commentDiv.appendChild(editIcon);
                    commentContainer.appendChild(commentDiv);
                } else {
                    const addLink = document.createElement('a');
                    addLink.className = 'small text-muted';
                    addLink.href = '#';
                    addLink.textContent = '+ Add comment';
                    addLink.onclick = (e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        editingCommentUsi = move.usi;
                        renderVariations();
                        displayMoveArrows();
                        setTimeout(() => {
                            const ta = document.getElementById('inline-comment-textarea');
                            if (ta) ta.focus();
                        }, 0);
                    };
                    commentContainer.appendChild(addLink);
                }
            }

            moveRow.appendChild(commentContainer);
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

    document.getElementById('moveIsPuzzle').checked = editingMove.isPuzzle;

    const modal = new bootstrap.Modal(document.getElementById('moveEditModal'));
    modal.show();
}

async function saveMoveDetails() {
    if (!editingMove) return;

    const isPuzzle = document.getElementById('moveIsPuzzle').checked;

    try {
        const response = await fetch(`/study/${studyId}/move/update`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                parentSfen: editingMove.parentSfen,
                usi: editingMove.usi,
                comment: editingMove.comment,
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

async function saveMoveComment(parentSfen, usi, isPuzzle) {
    const textarea = document.getElementById('inline-comment-textarea');
    if (!textarea) return;

    const comment = textarea.value;

    try {
        const response = await fetch(`/study/${studyId}/move/update`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                parentSfen: parentSfen,
                usi: usi,
                comment: comment,
                isPuzzle: isPuzzle
            })
        });

        if (!response.ok) throw new Error('Failed to update comment');

        editingCommentUsi = null;
        await loadRepertoire();
    } catch (e) {
        console.error('Error saving comment:', e);
        alert('Error: ' + e.message);
    }
}

async function saveMoveTranslation(parentSfen, usi, lang, comment) {
    try {
        const response = await fetch(`/study/${studyId}/move/translate`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ parentSfen, usi, lang, comment })
        });

        if (!response.ok) throw new Error('Failed to save translation');

        editingCommentUsi = null;
        await loadRepertoire();
    } catch (e) {
        console.error('Error saving translation:', e);
        alert('Error: ' + e.message);
    }
}

// Engine analysis — server-side

let _studyAbortCtrl = null;

async function analyzePosition() {
    const depth = parseInt(document.getElementById('analyzeDepth')?.value) || 15;
    const timeSec = parseInt(document.getElementById('analyzeTime')?.value) || 0;
    const multiPv = parseInt(document.getElementById('analyzeMultiPv')?.value) || 3;
    const time = timeSec > 0 ? timeSec * 1000 : undefined;

    // Close modal
    const modalEl = document.getElementById('analyzeModal');
    if (modalEl) {
        const modal = bootstrap.Modal.getInstance(modalEl);
        if (modal) modal.hide();
    }

    const serverBtn = document.getElementById('analyzeServerBtn');
    const cancelBtn = document.getElementById('analyzeCancelBtn');
    const analyzeBtn = document.getElementById('analyzeBtn');
    if (serverBtn) { serverBtn.disabled = true; serverBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1" role="status"></span>Analyzing…'; }
    if (cancelBtn) cancelBtn.style.display = 'inline-block';
    if (analyzeBtn) analyzeBtn.disabled = true;

    _studyAbortCtrl = new AbortController();

    try {
        const body = { sfen: currentSfen, depth, multiPv };
        if (time) body.time = time;

        const response = await fetch('/puzzle-creator/analyze', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
            signal: _studyAbortCtrl.signal
        });

        if (!response.ok) throw new Error('Analysis failed');

        const data = await response.json();
        if (data.success) {
            displayEngineResults(data.moves);
        } else {
            throw new Error(data.error || 'Analysis failed');
        }
    } catch (e) {
        if (e.name !== 'AbortError') {
            console.error('Engine analysis error:', e);
            alert('Analysis failed: ' + e.message);
        }
    } finally {
        _studyAbortCtrl = null;
        if (serverBtn) { serverBtn.disabled = false; serverBtn.innerHTML = '<i class="bi bi-cpu"></i><span class="d-none d-md-inline ms-1">Analyze</span>'; }
        if (cancelBtn) cancelBtn.style.display = 'none';
        if (analyzeBtn) analyzeBtn.disabled = false;
    }
}

function cancelAnalysis() {
    _studyAbortCtrl?.abort();
}

// Engine analysis — local (WASM)

let _studyCeval = null;
let _studyCevalOn = false;

function studyToggleCeval() {
    const btn = document.getElementById('studyCevalBtn');
    if (_studyCevalOn) {
        _studyCevalOn = false;
        _studyCeval?.stop();
        if (btn) { btn.classList.remove('active'); btn.innerHTML = '<i class="bi bi-cpu-fill"></i><span class="d-none d-md-inline ms-1">Local</span>'; }
        const statusEl = document.getElementById('engine-status');
        if (statusEl) { statusEl.style.display = 'none'; statusEl.textContent = ''; }
        const panel = document.getElementById('engine-results');
        if (panel) { panel.style.display = 'none'; panel.innerHTML = ''; }
        engineArrowsActive = false;
        displayMoveArrows();
        return;
    }

    if (typeof ClientEval === 'undefined' || !ClientEval.isSupported()) {
        alert('Local engine requires a modern browser with SharedArrayBuffer support (Chrome/Edge/Firefox).');
        return;
    }

    _studyCevalOn = true;
    if (btn) { btn.classList.add('active'); btn.innerHTML = '<i class="bi bi-cpu-fill"></i><span class="d-none d-md-inline ms-1">Local…</span>'; }

    const statusEl = document.getElementById('engine-status');
    if (statusEl) { statusEl.style.display = 'block'; statusEl.textContent = 'Loading engine…'; }

    if (!_studyCeval) {
        _studyInitCeval(); // _studyAnalyzeCurrent() is called from onReady
    } else {
        _studyAnalyzeCurrent(); // engine already loaded, start immediately
    }
}

function _studyResetCevalBtn() {
    _studyCevalOn = false;
    _studyCeval = null;
    const b = document.getElementById('studyCevalBtn');
    if (b) { b.classList.remove('active'); b.innerHTML = '<i class="bi bi-cpu-fill"></i><span class="d-none d-md-inline ms-1">Local</span>'; }
    const el = document.getElementById('engine-status');
    if (el) { el.style.display = 'block'; }
}

function _studyInitCeval() {
    const s = CevalSettings.load();
    _studyCeval = new ClientEval({
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
                _studyResetCevalBtn();
                return;
            }
            el.textContent = msg;
        },
        onReady: (name) => {
            const b = document.getElementById('studyCevalBtn');
            if (b) b.title = name;
            const el = document.getElementById('engine-status');
            if (el) el.textContent = 'Ready';
            // Start analysis now that the engine is ready
            _studyAnalyzeCurrent();
        },
        onEval: (ev) => { _studyOnEval(ev); },
    });
    _studyCeval.init();
}

function studyOpenCevalSettings() {
    if (typeof CevalSettings === 'undefined') return;
    CevalSettings.openModal((_newSettings) => {
        if (!_studyCevalOn) return;
        // Restart engine so new threads / multiPv take effect immediately
        _studyCeval?.destroy();
        _studyCeval = null;
        _studyInitCeval(); // _studyAnalyzeCurrent() called from onReady
    });
}

function _studyAnalyzeCurrent() {
    if (!_studyCevalOn || !currentSfen || !_studyCeval || _studyCeval.state === 'idle') return;
    const s = CevalSettings.load();
    const movetime = s.movetime > 0 ? s.movetime * 1000 : 90000;
    _studyCeval.analyze(currentSfen, { movetime, multiPv: s.multiPv });
}

function _studyOnEval(ev) {
    if (!_studyCevalOn || !ev) return;

    // Update button label
    const btn = document.getElementById('studyCevalBtn');
    if (btn) {
        const label = ev.mate != null
            ? (ev.mate > 0 ? '▲詰' : '▽詰') + Math.abs(ev.mate)
            : ev.cp != null ? (ev.cp >= 0 ? '▲' : '▽') + Math.abs(ev.cp) : '';
        btn.innerHTML = `<i class="bi bi-cpu-fill"></i><span class="d-none d-md-inline ms-1">${label} d${ev.depth}</span>`;
    }

    // Update status
    const statusEl = document.getElementById('engine-status');
    if (statusEl) {
        statusEl.style.display = 'block';
        statusEl.textContent = `depth ${ev.depth}`;
    }

    // Draw arrows for top moves
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
        if (shapes.length) {
            engineArrowsActive = true;
            sg.setAutoShapes(shapes);
        }
    }

    // Display PV lines in panel
    _displayLocalEngineResults(ev);
}

function _displayLocalEngineResults(ev) {
    const panel = document.getElementById('engine-results');
    if (!panel) return;
    panel.style.display = 'block';

    let pos;
    try { pos = Shogiops.sfen.parseSfen('standard', currentSfen, false).value; } catch (_) {}

    const colorIndicators = ['text-success', 'text-primary', 'text-warning'];
    const brushColors = ['text-success', 'text-primary', 'text-warning'];

    // Header (only build once, reuse if already present)
    if (!panel.querySelector('.study-ceval-header')) {
        panel.innerHTML = '';
        const header = document.createElement('div');
        header.className = 'd-flex justify-content-between align-items-center mb-1 study-ceval-header';
        header.innerHTML = '<span class="text-muted small fw-bold">Local Engine</span>';
        const closeBtn = document.createElement('button');
        closeBtn.className = 'btn btn-sm btn-outline-secondary p-0 px-1';
        closeBtn.innerHTML = '<i class="bi bi-x"></i>';
        closeBtn.onclick = () => { studyToggleCeval(); clearEngineAnalysis(); };
        header.appendChild(closeBtn);
        panel.appendChild(header);
        // Placeholder for PV rows
        const pvContainer = document.createElement('div');
        pvContainer.id = 'ceval-pv-rows';
        panel.appendChild(pvContainer);
    }

    const pvContainer = document.getElementById('ceval-pv-rows');
    if (!pvContainer) return;
    pvContainer.innerHTML = '';

    const captureSfen = currentSfen; // capture for button closures
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
        row.className = 'analyse__engine-result d-flex align-items-center justify-content-between py-1';

        const moveInfo = document.createElement('span');
        moveInfo.innerHTML = `<i class="bi bi-circle-fill ${colorCls} me-1" style="font-size:0.5em;vertical-align:middle"></i><span class="text-light">${moveText}</span> <span class="fw-bold ${scoreCls} ms-2">${scoreText}</span> <span class="text-muted small ms-1">${depthText}</span>`;

        const btnGroup = document.createElement('div');
        btnGroup.className = 'd-flex gap-1';

        const addMoveBtn = document.createElement('button');
        addMoveBtn.className = 'btn btn-sm btn-outline-success p-0 px-1';
        addMoveBtn.style.lineHeight = '1';
        addMoveBtn.innerHTML = '<i class="bi bi-plus-lg"></i>';
        addMoveBtn.title = 'Add move to study';
        addMoveBtn.onclick = () => addLocalMoveToStudy(pv, captureSfen);
        btnGroup.appendChild(addMoveBtn);

        if (pv.moves && pv.moves.length > 1) {
            const pvBtn = document.createElement('button');
            pvBtn.className = 'btn btn-sm btn-outline-warning p-0 px-1';
            pvBtn.style.lineHeight = '1';
            pvBtn.innerHTML = '<i class="bi bi-diagram-3"></i>';
            pvBtn.title = `Save PV line (${pv.moves.length} moves)`;
            pvBtn.onclick = () => addLocalPvToStudy(pv, captureSfen);
            btnGroup.appendChild(pvBtn);
        }

        row.appendChild(moveInfo);
        row.appendChild(btnGroup);
        pvContainer.appendChild(row);
    });
}

function displayEngineResults(moves) {
    engineArrowsActive = true;

    // Draw engine arrows
    const pos = Shogiops.sfen.parseSfen("standard", currentSfen, false).value;
    const brushes = ['green', 'blue', 'yellow'];
    const shapes = [];
    moves.forEach((move, index) => {
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

    // Show results panel
    const panel = document.getElementById('engine-results');
    if (!panel) return;
    panel.style.display = 'block';
    panel.innerHTML = '';

    const header = document.createElement('div');
    header.className = 'd-flex justify-content-between align-items-center mb-2';
    header.innerHTML = '<span class="text-muted small fw-bold">Engine Analysis</span>';
    const closeBtn = document.createElement('button');
    closeBtn.className = 'btn btn-sm btn-outline-secondary p-0 px-1';
    closeBtn.innerHTML = '<i class="bi bi-x"></i>';
    closeBtn.onclick = clearEngineAnalysis;
    header.appendChild(closeBtn);
    panel.appendChild(header);

    const colorIndicators = ['text-success', 'text-primary', 'text-warning'];

    moves.forEach((move, index) => {
        const score = move.score;
        let scoreText;
        let scoreCls;
        if (score.kind === 'mate') {
            scoreText = score.value > 0 ? `#${score.value}` : `#${score.value}`;
            scoreCls = score.value > 0 ? 'text-success' : 'text-danger';
        } else {
            scoreText = score.value >= 0 ? `+${score.value}` : `${score.value}`;
            scoreCls = score.value >= 100 ? 'text-success' : score.value <= -100 ? 'text-danger' : 'text-warning';
        }

        const moveText = formatMoveText(move.usi, pos);
        const colorCls = colorIndicators[Math.min(index, colorIndicators.length - 1)];
        const depthText = move.depth ? `d${move.depth}` : '';

        const row = document.createElement('div');
        row.className = 'analyse__engine-result d-flex align-items-center justify-content-between py-1';

        const moveInfo = document.createElement('span');
        moveInfo.innerHTML = `<i class="bi bi-circle-fill ${colorCls} me-1" style="font-size:0.5em;vertical-align:middle"></i><span class="text-light">${moveText}</span> <span class="fw-bold ${scoreCls} ms-2">${scoreText}</span> <span class="text-muted small ms-1">${depthText}</span>`;

        const btnGroup = document.createElement('div');
        btnGroup.className = 'd-flex gap-1';

        const addMoveBtn = document.createElement('button');
        addMoveBtn.className = 'btn btn-sm btn-outline-success p-0 px-1';
        addMoveBtn.style.lineHeight = '1';
        addMoveBtn.innerHTML = '<i class="bi bi-plus-lg"></i>';
        addMoveBtn.title = 'Add move to study';
        addMoveBtn.onclick = () => addEngineMove(move);
        btnGroup.appendChild(addMoveBtn);

        const addBtn = document.createElement('button');
        addBtn.className = 'btn btn-sm btn-outline-info p-0 px-1';
        addBtn.style.lineHeight = '1';
        addBtn.innerHTML = '<i class="bi bi-chat-dots"></i>';
        addBtn.title = 'Add eval as comment';
        addBtn.onclick = () => addEngineEvalAsComment(move);
        btnGroup.appendChild(addBtn);

        // Save full PV sequence if pv string is present
        if (move.pv) {
            const pvMoves = move.pv.trim().split(/\s+/).filter(Boolean);
            if (pvMoves.length > 1) {
                const pvBtn = document.createElement('button');
                pvBtn.className = 'btn btn-sm btn-outline-warning p-0 px-1';
                pvBtn.style.lineHeight = '1';
                pvBtn.innerHTML = '<i class="bi bi-diagram-3"></i>';
                pvBtn.title = `Save PV line (${pvMoves.length} moves)`;
                const captureSfen = currentSfen;
                const score = move.score;
                const evalText = score.kind === 'mate'
                    ? `Mate in ${score.value}`
                    : score.value >= 0 ? `+${score.value}` : `${score.value}`;
                pvBtn.onclick = () => addEnginePvSequence(captureSfen, pvMoves, `Engine: ${evalText} (d${move.depth})`);
                btnGroup.appendChild(pvBtn);
            }
        }

        row.appendChild(moveInfo);
        row.appendChild(btnGroup);
        panel.appendChild(row);
    });
}

function clearEngineAnalysis() {
    engineArrowsActive = false;
    // When local engine is on, just clear the panel content — it will be repopulated
    // by the next onEval callback. Only hide if local engine is off.
    const panel = document.getElementById('engine-results');
    if (panel && !_studyCevalOn) {
        panel.style.display = 'none';
        panel.innerHTML = '';
    } else if (panel && _studyCevalOn) {
        panel.innerHTML = ''; // cleared, will rebuild on next eval
    }
    displayMoveArrows();
}

async function addEngineEvalAsComment(engineMove) {
    const nodeKey = sanitizeSfen(currentSfen);
    const node = (study.nodes && study.nodes[nodeKey]) || { moves: [] };
    const matchingMove = node.moves.find(m => m.usi === engineMove.usi);

    if (!matchingMove) {
        alert('This move is not in the study at this position.');
        return;
    }

    const score = engineMove.score;
    let evalText;
    if (score.kind === 'mate') {
        evalText = `Mate in ${score.value}`;
    } else {
        evalText = score.value >= 0 ? `+${score.value}` : `${score.value}`;
    }

    const evalLine = `Engine: ${evalText} (d${engineMove.depth})`;
    const newComment = matchingMove.comment
        ? `${matchingMove.comment}\n${evalLine}`
        : evalLine;

    try {
        const response = await fetch(`/study/${studyId}/move/update`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                parentSfen: currentSfen,
                usi: engineMove.usi,
                comment: newComment,
                isPuzzle: matchingMove.isPuzzle || false
            })
        });

        if (!response.ok) throw new Error('Failed to update');
        await loadRepertoire();
    } catch (e) {
        console.error('Error adding eval comment:', e);
        alert('Error: ' + e.message);
    }
}

async function addEngineMove(engineMove) {
    const nextSfen = getNextSfen(engineMove.usi);
    const score = engineMove.score;
    let evalText;
    if (score.kind === 'mate') {
        evalText = `Mate in ${score.value}`;
    } else {
        evalText = score.value >= 0 ? `+${score.value}` : `${score.value}`;
    }
    const comment = `Engine: ${evalText} (d${engineMove.depth})`;

    try {
        const response = await fetch(`/study/${studyId}/move`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                parentSfen: currentSfen,
                usi: engineMove.usi,
                nextSfen: nextSfen,
                comment: comment
            })
        });

        if (!response.ok) throw new Error('Failed to add move');
        await loadRepertoire();
    } catch (e) {
        console.error('Error adding engine move:', e);
        alert('Error: ' + e.message);
    }
}

// Save a full PV line (sequence of USI moves) as a study branch.
// firstComment is set on the first move; subsequent moves get no comment.
async function addEnginePvSequence(startSfen, usiMoves, firstComment) {
    if (!usiMoves || !usiMoves.length) return;

    // Compute (parentSfen, usi, nextSfen) for each step
    const steps = [];
    let sfen = startSfen;
    for (const usi of usiMoves) {
        try {
            const pos = Shogiops.sfen.parseSfen('standard', sfen, false).value;
            const move = Shogiops.parseUsi(usi);
            pos.play(move);
            const nextSfen = Shogiops.sfen.makeSfen(pos);
            steps.push({ parentSfen: sfen, usi, nextSfen });
            sfen = nextSfen;
        } catch (_) { break; }
    }
    if (!steps.length) return;

    for (let i = 0; i < steps.length; i++) {
        const { parentSfen, usi, nextSfen } = steps[i];
        try {
            const resp = await fetch(`/study/${studyId}/move`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ parentSfen, usi, nextSfen, comment: i === 0 ? firstComment : '' })
            });
            if (!resp.ok) { console.error('addEnginePvSequence step', i, 'failed'); break; }
        } catch (e) { console.error('addEnginePvSequence error:', e); break; }
    }
    await loadRepertoire();
}

// Helpers for local engine PV objects: { moves: [...], cp, mate, depth }
async function addLocalMoveToStudy(pv, captureSfen) {
    const usi = pv.moves && pv.moves[0];
    if (!usi) return;
    const evalText = pv.mate != null
        ? `Mate in ${Math.abs(pv.mate)}`
        : pv.cp != null ? (pv.cp >= 0 ? `+${pv.cp}` : `${pv.cp}`) : '?';
    const comment = `Engine: ${evalText} (d${pv.depth})`;
    try {
        const pos = Shogiops.sfen.parseSfen('standard', captureSfen, false).value;
        const move = Shogiops.parseUsi(usi);
        pos.play(move);
        const nextSfen = Shogiops.sfen.makeSfen(pos);
        const resp = await fetch(`/study/${studyId}/move`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ parentSfen: captureSfen, usi, nextSfen, comment })
        });
        if (!resp.ok) throw new Error('Failed to add move');
        await loadRepertoire();
    } catch (e) { alert('Error: ' + e.message); }
}

async function addLocalPvToStudy(pv, captureSfen) {
    const evalText = pv.mate != null
        ? `Mate in ${Math.abs(pv.mate)}`
        : pv.cp != null ? (pv.cp >= 0 ? `+${pv.cp}` : `${pv.cp}`) : '?';
    await addEnginePvSequence(captureSfen, pv.moves || [], `Engine: ${evalText} (d${pv.depth})`);
}

// ── Board annotations (save circles & arrows to comment) ────────────────────

function shapesToAnnotationString(shapes) {
    const arrows = [], circles = [];
    shapes.forEach(s => {
        if (!s.orig || typeof s.orig !== 'string') return;
        const letter = _brushToAnnColor[s.brush] || 'G';
        const isCircle = !s.dest || s.dest === s.orig;
        if (isCircle) {
            circles.push(letter + s.orig);
        } else if (typeof s.dest === 'string') {
            arrows.push(letter + s.orig + s.dest);
        }
    });
    let result = '';
    if (arrows.length)  result += `[%cal ${arrows.join(',')}]`;
    if (circles.length) result += `[%csl ${circles.join(',')}]`;
    return result;
}

async function saveAnnotations() {
    if (!sg) return;

    const savedShapes = _userDrawnShapes.filter(s =>
        s.brush && s.brush in _brushToAnnColor && typeof s.orig === 'string'
    );
    const annotStr = shapesToAnnotationString(savedShapes);

    // Merge: new annotations replace old ones, preserve existing text
    const isRoot = currentSfen === study?.rootSfen;
    const existing = isRoot
        ? (study?.rootComment || '')
        : (lastMoveComment || '');
    const existingText = parseAnnotations(existing).text;
    const newComment = annotStr
        ? (existingText ? annotStr + '\n' + existingText : annotStr)
        : existingText;

    try {
        if (isRoot) {
            const resp = await fetch(`/study/${studyId}/root-comment`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ comment: newComment })
            });
            if (!resp.ok) throw new Error('Failed to save');
        } else {
            const parentSfen = history[history.length - 1];
            const usi = moveHistory[moveHistory.length - 1];
            if (!parentSfen || !usi) { alert('Navigate to a move first.'); return; }
            const nodeKey = sanitizeSfen(parentSfen);
            const node = (study.nodes && study.nodes[nodeKey]) || { moves: [] };
            const matchingMove = node.moves.find(m => m.usi === usi);
            const resp = await fetch(`/study/${studyId}/move/update`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ parentSfen, usi, comment: newComment, isPuzzle: matchingMove?.isPuzzle || false })
            });
            if (!resp.ok) throw new Error('Failed to save');
        }
        await loadRepertoire();
    } catch (e) {
        alert('Error saving annotations: ' + e.message);
    }
}

// ── Study title inline edit ──────────────────────────────────────────────────

let _editingTitle = null;

function startEditTitle() {
    const titleEl = document.getElementById('studyTitle');
    if (!titleEl) return;
    _editingTitle = titleEl.textContent.trim();
    const wrap = document.getElementById('study-title-wrap');
    if (!wrap) return;
    wrap.innerHTML = `
        <input type="text" id="titleInput" class="form-control form-control-sm bg-dark text-light border-secondary"
               value="${_editingTitle.replace(/"/g, '&quot;')}" style="max-width:320px;">
        <button class="btn btn-sm btn-success ms-1" onclick="saveTitle()"><i class="bi bi-check-lg"></i></button>
        <button class="btn btn-sm btn-secondary ms-1" onclick="cancelEditTitle()"><i class="bi bi-x-lg"></i></button>`;
    const inp = document.getElementById('titleInput');
    inp.focus();
    inp.select();
    inp.addEventListener('keydown', e => {
        if (e.key === 'Enter')  saveTitle();
        if (e.key === 'Escape') cancelEditTitle();
    });
}

function _restoreTitleWrap(name) {
    const wrap = document.getElementById('study-title-wrap');
    if (!wrap) return;
    wrap.innerHTML = `
        <h2 class="puzzle__board-header__title mb-0" id="studyTitle">${name}</h2>
        <button class="btn btn-sm btn-outline-secondary p-0 px-1" onclick="startEditTitle()" title="Rename study"><i class="bi bi-pencil"></i></button>`;
}

async function saveTitle() {
    const inp = document.getElementById('titleInput');
    if (!inp) return;
    const newName = inp.value.trim();
    if (!newName) return;
    try {
        const resp = await fetch(`/study/${studyId}/rename`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: newName })
        });
        if (!resp.ok) throw new Error('Failed to rename');
        _restoreTitleWrap(newName);
        document.title = `Editing ${newName}`;
        _editingTitle = null;
    } catch (e) {
        alert('Error: ' + e.message);
    }
}

function cancelEditTitle() {
    if (_editingTitle !== null) {
        _restoreTitleWrap(_editingTitle);
        _editingTitle = null;
    }
}

async function reloadFromStudy() {
    const btn = document.getElementById('reloadStudyBtn');
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<i class="bi bi-hourglass-split me-1"></i><span class="d-none d-lg-inline">Reloading...</span>';
    }

    try {
        const response = await fetch(`/study/${studyId}/reload-from-study`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: '{}'
        });

        const data = await response.json();
        if (!response.ok) throw new Error(data.error || 'Failed to reload');

        alert(`Reloaded ${data.moveCount} moves from study.`);
        await loadRepertoire();
    } catch (e) {
        console.error('Error reloading from study:', e);
        alert('Error: ' + e.message);
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.innerHTML = '<i class="bi bi-arrow-repeat me-1"></i><span class="d-none d-lg-inline">Reload Study</span>';
        }
    }
}

window.reloadFromStudy = reloadFromStudy;

export function revertMove() {
    if (history.length > 0) {
        clearEngineAnalysis();
        editingCommentUsi = null;
        lastMoveComment = null;
        currentSfen = history.pop();
        moveHistory.pop();
        renderBoard();
        renderVariations();
        updateMenuState();
        displayMoveArrows();
        _studyAnalyzeCurrent();
    }
}

export function toRoot() {
    if (currentSfen !== study.rootSfen) {
        clearEngineAnalysis();
        editingCommentUsi = null;
        lastMoveComment = null;
        history = [];
        moveHistory = [];
        currentSfen = study.rootSfen;
        renderBoard();
        renderVariations();
        updateMenuState();
        displayMoveArrows();
        _studyAnalyzeCurrent();
    }
}

export function advanceMove() {
    if (!study || !study.nodes) return;
    const nodeKey = sanitizeSfen(currentSfen);
    const node = (study.nodes[nodeKey]) || { moves: [] };
    if (node.moves.length === 1) {
        const move = node.moves[0];
        clearEngineAnalysis();
        editingCommentUsi = null;
        lastMoveComment = move.comment || null;
        history.push(currentSfen);
        moveHistory.push(move.usi);
        currentSfen = move.nextSfen;
        renderBoard(move.usi);
        renderVariations();
        updateMenuState();
        displayMoveArrows();
        _studyAnalyzeCurrent();
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
window.saveMoveDetails = saveMoveDetails;
window.analyzePosition = analyzePosition;
window.cancelAnalysis = cancelAnalysis;
window.studyToggleCeval = studyToggleCeval;
window.studyOpenCevalSettings = studyOpenCevalSettings;
window.clearEngineAnalysis = clearEngineAnalysis;
window.saveAnnotations = saveAnnotations;
window.startEditTitle = startEditTitle;
window.saveTitle = saveTitle;
window.cancelEditTitle = cancelEditTitle;

async function importMoves() {
    const usis = document.getElementById('importUsis').value;
    if (!usis.trim()) return;

    const comment = document.getElementById('importComment').value;
    const isPuzzle = document.getElementById('importIsPuzzle').checked;

    try {
        const response = await fetch(`/study/${studyId}/import`, {
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

async function importKifFile() {
    const fileInput = document.getElementById('kifFileInput');

    if (!fileInput.files || !fileInput.files[0]) {
        alert('Please select a KIF file.');
        return;
    }

    const file = fileInput.files[0];

    readKifFile(file, async function(kif) {
        const btn = document.querySelector('#importKifModal .btn-success');
        const originalText = btn.innerText;
        btn.disabled = true;
        btn.innerText = 'Importing...';

        try {
            const response = await fetch(`/study/${studyId}/import-kif`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ kif })
            });

            if (!response.ok) {
                const data = await response.json();
                throw new Error(data.error || 'Failed to import');
            }

            const result = await response.json();
            alert(`Successfully imported ${result.importedCount} moves.`);

            const modalElement = document.getElementById('importKifModal');
            const modal = bootstrap.Modal.getInstance(modalElement);
            if (modal) modal.hide();

            await loadRepertoire();
        } catch (e) {
            console.error('Error importing KIF:', e);
            alert('Error: ' + e.message);
            btn.disabled = false;
            btn.innerText = originalText;
        }
    });
}

function readKifFile(file, callback) {
    const reader = new FileReader();
    reader.onload = function(e) {
        const buffer = e.target.result;
        const utf8Text = new TextDecoder('utf-8').decode(buffer);
        if (!utf8Text.includes('\uFFFD')) {
            callback(utf8Text);
            return;
        }
        const sjisText = new TextDecoder('shift-jis').decode(buffer);
        callback(sjisText);
    };
    reader.readAsArrayBuffer(file);
}

window.importKifFile = importKifFile;

function reviewInPuzzleCreator() {
    const nodeKey = sanitizeSfen(currentSfen);
    const node = (study.nodes && study.nodes[nodeKey]) || { moves: [] };
    const puzzleMove = node.moves.find(m => m.isPuzzle);

    const params = new URLSearchParams();
    params.set('sfen', currentSfen);
    if (moveHistory.length) params.set('prelude', moveHistory.join(' '));
    if (study.rootSfen) params.set('rootSfen', study.rootSfen);
    if (puzzleMove) {
        params.set('blunder', puzzleMove.usi);
        if (puzzleMove.comment) {
            params.set('comment', puzzleMove.comment);
        }
    }
    window.open(`/puzzle-creator/new?${params.toString()}`, '_blank');

    if (puzzleMove) {
        clearAndAdvancePuzzle(currentSfen, puzzleMove);
    }
}

window.reviewInPuzzleCreator = reviewInPuzzleCreator;

async function saveDraftPuzzle() {
    const nodeKey = sanitizeSfen(currentSfen);
    const node = (study.nodes && study.nodes[nodeKey]) || { moves: [] };
    const puzzleMove = node.moves.find(m => m.isPuzzle);

    const data = {
        name: puzzleMove && puzzleMove.comment ? puzzleMove.comment : `Draft from ${study.name}`,
        sfen: currentSfen,
        status: 'review',
        source: 'study',
        blunderMoves: puzzleMove ? [puzzleMove.usi] : [],
        comments: '',
        isPublic: false,
        tags: [],
        prelude: moveHistory.length ? moveHistory.join(' ') : '',
        rootSfen: study.rootSfen || '',
    };

    try {
        const response = await fetch('/puzzle-creator/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });

        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.error || 'Failed to save draft');
        }

        alert('Draft puzzle saved! Find it in Puzzle Editor → Needs Review.');

        if (puzzleMove) {
            clearAndAdvancePuzzle(currentSfen, puzzleMove);
        }
    } catch (e) {
        console.error('Error saving draft puzzle:', e);
        alert('Error: ' + e.message);
    }
}

window.saveDraftPuzzle = saveDraftPuzzle;

function sendToPuzzleCreator(parentSfen, move) {
    const params = new URLSearchParams();
    params.set('sfen', parentSfen);
    params.set('blunder', move.usi);
    if (move.comment) params.set('comment', move.comment);
    if (moveHistory.length) params.set('prelude', moveHistory.join(' '));
    if (study.rootSfen) params.set('rootSfen', study.rootSfen);
    window.open(`/puzzle-creator/new?${params.toString()}`, '_blank');
    clearAndAdvancePuzzle(parentSfen, move);
}

async function clearAndAdvancePuzzle(parentSfen, move) {
    try {
        // Clear isPuzzle flag on this move
        await fetch(`/study/${studyId}/move/update`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                parentSfen: parentSfen,
                usi: move.usi,
                comment: move.comment || '',
                isPuzzle: false
            })
        });

        // Reload study data
        const response = await fetch(`/study/${studyId}/json`);
        study = await response.json();

        clearEngineAnalysis();
        editingCommentUsi = null;

        // Find next puzzle move
        const nextPuzzle = findNextPuzzleMove();
        if (nextPuzzle) {
            // Navigate to that position
            history = nextPuzzle.path.slice();
            currentSfen = nextPuzzle.parentSfen;
            renderBoard();
            renderVariations();
            updateMenuState();
            displayMoveArrows();
        } else {
            // No more puzzles, just refresh current view
            renderBoard();
            renderVariations();
            updateMenuState();
            displayMoveArrows();
        }
    } catch (e) {
        console.error('Error clearing puzzle flag:', e);
        await loadRepertoire();
    }
}

function findNextPuzzleMove() {
    if (!study || !study.nodes) return null;

    // DFS through study nodes starting from root
    const rootSfen = study.rootSfen;
    const visited = new Set();

    function dfs(sfen, path) {
        const nodeKey = sanitizeSfen(sfen);
        if (visited.has(nodeKey)) return null;
        visited.add(nodeKey);

        const node = study.nodes[nodeKey];
        if (!node || !node.moves) return null;

        for (const move of node.moves) {
            if (move.isPuzzle) {
                return { parentSfen: sfen, move: move, path: path };
            }
            if (move.nextSfen) {
                const result = dfs(move.nextSfen, [...path, sfen]);
                if (result) return result;
            }
        }
        return null;
    }

    return dfs(rootSfen, []);
}

// Expose currentSfen for Lishogi analysis button
Object.defineProperty(window, 'currentSfen', {
    get: function() { return currentSfen; }
});

loadRepertoire();
