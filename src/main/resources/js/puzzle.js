let data, ids, sg, selected, selectedData
const games = $(".games")
const urlParams = new URLSearchParams(window.location.search);
const hash = urlParams.get('hash');
const apiUrl = hash ? "data?hash=" + hash : "data";
const cacheKey = hash ? "puzzles_" + hash : "puzzles_all";

function loadData(forceReload = false) {
    if (!forceReload) {
        const cachedData = localStorage.getItem(cacheKey);
        if (cachedData) {
            try {
                const json = JSON.parse(cachedData);
                initPuzzles(json);
                return;
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
            localStorage.setItem(cacheKey, JSON.stringify(json));
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

function initPuzzles(json) {
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

    selectSituation(randomNumber(0, selectedData.length - 1), selectedData)
}

loadData();

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

$(".lishogi-position").click( function () {
    window.open("https://lishogi.org/analysis/standard/" + selected.sfen, "_blank");
});

$(".prev-puzzle").click( function () {
    const currentId = parseInt(games.val());
    if (currentId > 0) {
        selectSituation(currentId - 1, selectedData);
    }
});

$(".next-puzzle").click( function () {
    const currentId = parseInt(games.val());
    if (currentId < selectedData.length - 1) {
        selectSituation(currentId + 1, selectedData);
    }
});

$(".save-comment").click( function () {
    const text = $(".content").val()
    fireSave(text)
});

function createIds(data) {
    return data.map( (value, index) => {
            let obj = {}
            obj["id"] = index
            let label = value.id
            if (value.move_number) {
                label = "Move " + value.move_number
            } else if (value.ply) {
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
        if (selected.hands) {
             $('#material-text').text(selected.hands);
        } else {
             $('#material-text').text("-");
        }

        // Update nav buttons state
        $(".prev-puzzle").prop('disabled', id === 0);
        $(".next-puzzle").prop('disabled', id === data.length - 1);
    }

    sg = Shogiground();
    sg.set(generateConfig(selected));

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

function isMove(engineMove, playerMove, playerPositionMove, returnValue) {
    if (engineMove !== null) {
        let result = false
        if (engineMove.drop !== null) {
            if (playerPositionMove === "DROP") {
                result = engineMove.drop.drop.role === playerMove.piece.role && engineMove.drop.drop.pos === playerMove.key;
            }
        } else {
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

function setHint(move) {
    if (move !== null) {
        if (move.drop !== null) {
            return move.drop.hint
        } else {
            return move.move.hint
        }
    } else {
        return null
    }
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

function fireError(pos) {
    Swal.fire({
        icon: 'error',
        title: 'Failure',
        html: '<p>You played the bad move!</p> ' +
            '<div>' + formatComment(pos.comment) + '</div>',
        footer: '<a href="https://lishogi.org/analysis/standard/' + pos.sfen + '" target="_blank">Lishogi position</a>'
    })
}

function fireWarning(pos) {
    Swal.fire({
        icon: 'warning',
        title: 'Warning',
        html: '<p>You didn\'t played one of the best 3 moves! Please analyze your move.</p>' +
            '<div>' + formatComment(pos.comment) + '</div>',
        footer: '<a href="https://lishogi.org/analysis/standard/' + pos.sfen + '" target="_blank">Lishogi position</a>'
    })
}

function fireSuccess(pos, num) {
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
            '<div>' + formatComment(pos.comment) + '</div>',
        footer: '<a href="https://lishogi.org/analysis/standard/' + pos.sfen + '" target="_blank">Lishogi position</a>'
    })
}

function fireSave(text) {
    Swal.fire({
        icon: 'question',
        title: 'Update',
        html: '<p>Do you want update the comment?</p>' +
            '<div>' + formatComment(text) + '</div>' +
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
    sg.setAutoShapes([setHint(pos.best_move), setHint(pos.second_move), setHint(pos.third_move),
        setHint(pos.your_move)].filter(elements => {
        return elements !== null;
    }))
}

function resolveMove(pos, r0, r1, r2, r3) {
    $(".content").html(formatComment(pos.comment))
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
            dests: Shogiops.compat.shogigroundDropDests(Shogiops.sfen.parseSfen("standard", pos.sfen, false).value),
        },
        promotion: {
            promotesTo: role => {
                return Shogiops.variantUtil.promote("standard")(role);
            },
            movePromotionDialog: (orig, dest) => {
                const piece = sg.state.pieces.get(orig);
                const capture = sg.state.pieces.get(dest) | undefined;
                return Shogiops.variantUtil.pieceCanPromote("standard")(piece, Shogiops.parseSquare(orig), Shogiops.parseSquare(dest), capture)
                    && !Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest))
            },
            forceMovePromotion: (orig, dest) => {
                const piece = sg.state.pieces.get(orig);
                return Shogiops.variantUtil.pieceForcePromote("standard")(piece, Shogiops.parseSquare(dest))
            },
        },
        events: {
            move: (a, b, prom) => {
                setHints(pos)
                $(".content").html(formatComment(pos.comment))

                let r0 = isMove(pos.your_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 0)
                let r1 = isMove(pos.best_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 1)
                let r2 = isMove(pos.second_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 2)
                let r3 = isMove(pos.third_move, {"orig": a, "dest": b, "prom": prom}, "MOVE", 3)
                resolveMove(pos, r0, r1, r2, r3)

                $(".save-comment").show()
            },
            drop: (piece, key, prom) => {
                setHints(pos)
                $(".content").html(formatComment(pos.comment))

                let r0 = isMove(pos.your_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 0)
                let r1 = isMove(pos.best_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 1)
                let r2 = isMove(pos.second_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 2)
                let r3 = isMove(pos.third_move, {"piece": piece, "key": key, "prom": prom}, "DROP", 3)
                resolveMove(pos, r0, r1, r2, r3)

                $(".save-comment").show()
            },
        },
    }
}

function randomNumber(min, max) {
    return Math.floor(min + Math.random()*(max - min + 1))
}
