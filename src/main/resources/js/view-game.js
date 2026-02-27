/**
 * View Game — replays a stored KIF game move by move.
 *
 * Data flow:
 *   page load → fetch /view-game-data/:hash
 *             → init Shogiground (view-only)
 *             → render move list
 *             → render analysis chart (if analyzed)
 *             → navigate with buttons / keyboard arrows
 */

'use strict';

let gameData    = null;       // loaded from /view-game-data/:hash
let sg          = null;       // Shogiground instance
let currentIdx  = 0;          // index into gameData.sfens / .moves
let chart       = null;       // Chart.js instance
let orientation = 'sente';    // board orientation: 'sente' | 'gote'
let canAnalyze  = false;      // true when logged in with my-games permission

// Candidate replay state (null when not replaying)
let candReplayState  = null;  // { sfens, moves, idx, candIdx }
let lastCandidates   = null;  // last analysis results for re-drawing arrows
let moveAnalysis     = {};    // { moveIdx -> candidates[] } loaded from DB
let moveComments     = {};    // { moveIdx -> string } loaded from DB

// ---------------------------------------------------------------------------
// Bootstrap
// ---------------------------------------------------------------------------

$(document).ready(function () {
  canAnalyze = $('#canAnalyze').val() === 'true';

  const hash = $('#gameHash').val();
  if (!hash) { showLoadError('Missing game hash.'); return; }
  loadGameData(hash);

  // Navigation buttons
  $('#btn-first').on('click', () => goTo(0));
  $('#btn-prev' ).on('click', () => navigate(-1));
  $('#btn-next' ).on('click', () => navigate(1));
  $('#btn-last' ).on('click', () => { if (gameData) goTo(gameData.sfens.length - 1); });
  $('#btn-flip' ).on('click', flipBoard);

  // Keyboard navigation
  document.addEventListener('keydown', function (e) {
    const tag = e.target.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
    switch (e.key) {
      case 'ArrowLeft':
        e.preventDefault();
        candReplayState ? candReplayGoTo(candReplayState.idx - 1) : navigate(-1);
        break;
      case 'ArrowRight':
        e.preventDefault();
        candReplayState ? candReplayGoTo(candReplayState.idx + 1) : navigate(1);
        break;
      case 'Home':  e.preventDefault(); candReplayState ? null : goTo(0); break;
      case 'End':   e.preventDefault(); candReplayState ? null : (gameData && goTo(gameData.sfens.length - 1)); break;
      case 'f': case 'F': e.preventDefault(); flipBoard(); break;
      case 'Escape': if (candReplayState) exitCandidateReplay(); break;
    }
  });

  // Reanalysis toggle
  $(document).on('click', '#reanalyzeToggleBtn', function () {
    const panel = $('#reanalyze-panel');
    if (panel.is(':visible')) {
      panel.slideUp(150);
    } else {
      if (!$('#shallowLimitInput').val()) {
        $('#shallowLimitInput').val($('#cfgShallowLimit').val() || 1);
        $('#deepLimitInput').val($('#cfgDeepLimit').val() || 10);
        $('#winChanceDropInput').val($('#cfgWinChanceDrop').val() || 0.1);
      }
      panel.slideDown(150);
    }
  });

  $(document).on('click', '#runReanalyzeBtn',   function () { triggerReanalysis(); });
  $(document).on('click', '#analyzePositionBtn', function () { analyzeCurrentPosition(); });
  $(document).on('click', '#save-analysis-btn',  function () { saveMoveAnalysis(); });
  $(document).on('click', '#create-puzzle-btn',  function () { createReviewPuzzle(); });
  $(document).on('click', '.vg-delete-puzzle',   function () { deletePuzzleFromGame($(this).data('puzzle-id')); });
  $(document).on('click', '#lgaAnalyzeBtn',      function () { startLocalGameAnalysis(); });
  $(document).on('click', '#lga-cancel-btn',     function () { cancelLocalGameAnalysis(); });

  // Position analysis settings toggle
  $(document).on('click', '#analyzePosSettingsBtn', function () {
    const panel = $('#analyze-pos-panel');
    if (panel.is(':visible')) {
      panel.slideUp(150);
    } else {
      $('#reanalyze-panel, #ceval-settings-panel, #lga-settings-panel').slideUp(150);
      panel.slideDown(150);
    }
  });

  // Local analysis (ceval) settings toggle
  $(document).on('click', '#cevalSettingsBtn', function () {
    const panel = $('#ceval-settings-panel');
    if (panel.is(':visible')) {
      panel.slideUp(150);
    } else {
      $('#reanalyze-panel, #analyze-pos-panel, #lga-settings-panel').slideUp(150);
      panel.slideDown(150);
    }
  });

  // Local game analysis settings toggle
  $(document).on('click', '#lgaSettingsBtn', function () {
    const panel = $('#lga-settings-panel');
    if (panel.is(':visible')) {
      panel.slideUp(150);
    } else {
      $('#reanalyze-panel, #analyze-pos-panel, #ceval-settings-panel').slideUp(150);
      panel.slideDown(150);
    }
  });

  // Candidate replay buttons (delegated — panel is re-rendered dynamically)
  $(document).on('click', '.vg-cand-play', function (e) {
    e.stopPropagation();
    enterCandidateReplay(parseInt($(this).data('cand-idx'), 10));
  });
  $(document).on('click', '#cand-rn-prev',   function () { if (candReplayState) candReplayGoTo(candReplayState.idx - 1); });
  $(document).on('click', '#cand-rn-next',   function () { if (candReplayState) candReplayGoTo(candReplayState.idx + 1); });
  $(document).on('click', '#cand-rn-exit',   function () { exitCandidateReplay(); });
  $(document).on('click', '.vg-rm[data-step]', function () {
    if (candReplayState) candReplayGoTo(parseInt($(this).data('step'), 10));
  });

  // Local engine analysis
  initCeval();
});

// ---------------------------------------------------------------------------
// Data loading
// ---------------------------------------------------------------------------

function loadGameData(hash) {
  $.get('/view-game-data/' + hash)
    .done(function (raw) {
      const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
      if (data.error) { showLoadError(data.error); return; }
      gameData = data;
      // Parse per-move saved analyses
      moveAnalysis = {};
      const rawMa = data.moveAnalysis || {};
      Object.keys(rawMa).forEach(function (k) {
        try {
          const parsed = JSON.parse(rawMa[k]);
          // Support both old format (plain array) and new format ({ candidates, orientation })
          moveAnalysis[parseInt(k, 10)] = Array.isArray(parsed)
            ? { candidates: parsed, orientation: 'sente' }
            : parsed;
        } catch (e) {}
      });
      // Load per-move comments
      moveComments = {};
      const rawMc = data.moveComments || {};
      Object.keys(rawMc).forEach(function (k) {
        const v = rawMc[k];
        if (v && typeof v === 'string' && v.trim()) moveComments[parseInt(k, 10)] = v;
      });
      // Index puzzles by ply for fast lookup
      gameData.puzzlesByPly = {};
      (data.puzzles || []).forEach(function (p) {
        const ply = Math.round(p.ply);
        if (!gameData.puzzlesByPly[ply]) gameData.puzzlesByPly[ply] = [];
        gameData.puzzlesByPly[ply].push(p);
      });
      initBoard();
      renderMoveList();
      if (data.isAnalyzed && data.scores && data.scores.length > 0) {
        $('#analysis-section').show();
        renderChart();
      }
      goTo(0);
    })
    .fail(function (xhr) {
      showLoadError('Failed to load game data (' + xhr.status + ').');
    });
}

// ---------------------------------------------------------------------------
// Shogiground board
// ---------------------------------------------------------------------------

function sfenTurnColor(sfenStr) {
  const t = (sfenStr || '').split(' ')[1];
  return t === 'w' ? 'gote' : 'sente';
}

function initBoard() {
  if (!gameData || !gameData.sfens.length) return;

  const sfenStr = gameData.sfens[0];
  const hands   = sfenStr.split(' ')[2] || '-';

  sg = Shogiground();
  sg.set({
    sfen:        { board: sfenStr, hands },
    orientation: 'sente',
    turnColor:   sfenTurnColor(sfenStr),
    viewOnly:    true,
    drawable:    { enabled: false, shapes: [] },
    movable:     { free: false, color: 'none' },
    droppable:   { free: false, color: 'none' },
  });
  sg.attach({ board: document.getElementById('dirty') });
  sg.attach({ hands: { bottom: document.getElementById('hand-bottom') } });
  sg.attach({ hands: { top:    document.getElementById('hand-top') } });
}

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

function goTo(idx) {
  if (!gameData || !sg) return;
  if (idx < 0 || idx >= gameData.sfens.length) return;

  // Exit candidate replay and clear arrows when position changes
  if (idx !== currentIdx) clearArrows();
  currentIdx = idx;

  const sfenStr   = gameData.sfens[idx];
  const sfenParts = sfenStr.split(' ');
  const board     = sfenStr;
  const hands     = sfenParts[2] || '-';
  const turnColor = sfenTurnColor(sfenStr);

  let lastDests = [];
  let lastPiece;
  if (idx > 0 && gameData.moves[idx]) {
    const move = Shogiops.parseUsi(gameData.moves[idx]);
    if (move) {
      if ('role' in move) {
        lastDests = [Shogiops.makeSquare(move.to)];
        lastPiece = { role: move.role, color: turnColor === 'sente' ? 'gote' : 'sente' };
      } else {
        lastDests = [Shogiops.makeSquare(move.from), Shogiops.makeSquare(move.to)];
      }
    }
  }

  sg.set({ sfen: { board, hands }, turnColor, lastDests, lastPiece });

  const lishogiSfen = sfenStr.replace(/ /g, '_');
  $('#openInLishogiBtn').attr('href', 'https://lishogi.org/analysis/' + lishogiSfen);

  updateCounter(idx);
  updateNavButtons();
  highlightMove(idx);
  updateScoreBar(idx);
  updateChartCursor(idx);
  updatePlayerLabels(turnColor);

  // Restore saved analysis for this move (if any and not currently analyzing)
  if (moveAnalysis[idx] && !$('#analyzePositionBtn').prop('disabled')) {
    const saved = moveAnalysis[idx];
    const savedOrientation = saved.orientation || 'sente';
    if (orientation !== savedOrientation) {
      orientation = savedOrientation;
      if (sg) sg.set({ orientation });
      updatePlayerLabels(sfenTurnColor(sfenStr));
    }
    showCandidateArrows(saved.candidates, false);
  } else {
    updatePuzzleWidget(idx);
  }

  renderMoveComment(idx);

  // Trigger local engine analysis for new position
  if (_cevalOn) analyzeCevalPosition();
}

function navigate(delta) {
  if (gameData) goTo(currentIdx + delta);
}

// ---------------------------------------------------------------------------
// Move list
// ---------------------------------------------------------------------------

function renderMoveList() {
  const container = document.getElementById('move-list');
  if (!container || !gameData) return;

  let html = '<div class="vg-movepairs">';
  html += '<div class="vg-movepair"><span class="vg-movenum"></span>' +
          '<span class="vg-move vg-move--init" data-idx="0">▶ Start</span></div>';

  for (let i = 1; i < gameData.sfens.length; i++) {
    const usi     = gameData.moves[i] || '';
    const isSente = (i % 2 === 1);
    const note    = usiToReadable(usi);
    const icon    = isSente ? '☗' : '☖';
    const cls     = isSente ? 'vg-move--sente' : 'vg-move--gote';

    if (isSente) {
      const pairNum = Math.ceil(i / 2);
      html += `<div class="vg-movepair"><span class="vg-movenum">${pairNum}.</span>`;
    }
    const analyzedCls  = moveAnalysis[i]  ? ' vg-move-analyzed'  : '';
    const commentedCls = moveComments[i]  ? ' vg-move-commented' : '';
    html += `<span class="vg-move ${cls}${analyzedCls}${commentedCls}" data-idx="${i}" title="USI: ${usi}">${icon} ${note}</span>`;
    if (!isSente || i === gameData.sfens.length - 1) {
      if (isSente) html += '<span class="vg-move vg-move--empty"></span>';
      html += '</div>';
    }
  }
  html += '</div>';
  container.innerHTML = html;

  $(container).on('click', '.vg-move[data-idx]', function () {
    goTo(parseInt($(this).data('idx'), 10));
  });
}

function highlightMove(idx) {
  $('#move-list .vg-move').removeClass('active');
  const el = document.querySelector(`#move-list .vg-move[data-idx="${idx}"]`);
  if (el) {
    el.classList.add('active');
    // Scroll only within the move list container to avoid page-level scrolling on mobile
    const container = el.closest('.analyse__moves');
    if (container) {
      const elTop    = el.offsetTop - container.offsetTop;
      const elBottom = elTop + el.offsetHeight;
      const ctTop    = container.scrollTop;
      const ctBottom = ctTop + container.clientHeight;
      if (elTop < ctTop) {
        container.scrollTop = elTop;
      } else if (elBottom > ctBottom) {
        container.scrollTop = elBottom - container.clientHeight;
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Move comments
// ---------------------------------------------------------------------------

function renderMoveComment(idx) {
  const i18n    = window.i18n || {};
  const panel   = document.getElementById('move-comment-panel');
  if (!panel) return;

  const comment = moveComments[idx] || '';

  if (!canAnalyze) {
    // Read-only: show comment if present, hide panel otherwise
    panel.innerHTML = comment
      ? '<div class="vg-mc-view">' + escMc(comment) + '</div>'
      : '';
    return;
  }

  // Editable mode
  const placeholder = i18n['viewgame.commentPlaceholder'] || 'Add comment for this move…';
  const saveLabel   = i18n['common.save']   || 'Save';
  const deleteLabel = i18n['common.delete'] || 'Delete';
  const hintLabel   = i18n['viewgame.commentHint'] || 'Ctrl+Enter to save';

  panel.innerHTML =
    '<div class="vg-mc-edit">' +
    '<textarea id="mc-input" placeholder="' + escMcAttr(placeholder) + '" rows="2">' +
    escMc(comment) + '</textarea>' +
    '<div class="vg-mc-edit-row">' +
    '<button class="vg-mc-btn vg-mc-btn-save" id="mc-save">' + saveLabel + '</button>' +
    (comment ? '<button class="vg-mc-btn vg-mc-btn-del" id="mc-delete">' + deleteLabel + '</button>' : '') +
    '<span class="vg-mc-edit-hint">' + hintLabel + '</span>' +
    '</div></div>';

  const textarea = document.getElementById('mc-input');
  if (textarea) {
    textarea.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSaveComment(idx); }
      e.stopPropagation(); // prevent board keyboard shortcuts while typing
    });
    // Focus only if idx > 0 and not on a touch device (mobile keyboard would scroll the page)
    const isTouchDevice = navigator.maxTouchPoints > 0 || window.matchMedia('(pointer: coarse)').matches;
    if (idx > 0 && !isTouchDevice) textarea.focus();
  }

  document.getElementById('mc-save')   && document.getElementById('mc-save').addEventListener('click',   function () { doSaveComment(idx); });
  document.getElementById('mc-delete') && document.getElementById('mc-delete').addEventListener('click', function () { doDeleteComment(idx); });
}

function doSaveComment(idx) {
  const textarea = document.getElementById('mc-input');
  if (!textarea) return;
  const text    = textarea.value;
  const kifHash = $('#gameHash').val();
  if (!kifHash) return;

  $.ajax({ url: '/save-move-comment', method: 'POST', contentType: 'application/json',
    data: JSON.stringify({ kifHash, moveIdx: idx, comment: text }) })
  .done(function (data) {
    if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) {} }
    if (data && data.success) {
      if (text.trim()) {
        moveComments[idx] = text.trim();
      } else {
        delete moveComments[idx];
      }
      // Update indicator on the move span
      const el = document.querySelector('#move-list .vg-move[data-idx="' + idx + '"]');
      if (el) {
        if (moveComments[idx]) el.classList.add('vg-move-commented');
        else                   el.classList.remove('vg-move-commented');
      }
      renderMoveComment(idx); // re-render to reflect saved state
    }
  });
}

function doDeleteComment(idx) {
  const kifHash = $('#gameHash').val();
  if (!kifHash) return;
  $.ajax({ url: '/save-move-comment', method: 'POST', contentType: 'application/json',
    data: JSON.stringify({ kifHash, moveIdx: idx, comment: '' }) })
  .done(function () {
    delete moveComments[idx];
    const el = document.querySelector('#move-list .vg-move[data-idx="' + idx + '"]');
    if (el) el.classList.remove('vg-move-commented');
    renderMoveComment(idx);
  });
}

function escMc(s) {
  if (s == null) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
function escMcAttr(s) {
  return escMc(s).replace(/"/g, '&quot;');
}

function usiToReadable(usi) {
  if (!usi) return '—';
  const rows   = { a:'一', b:'二', c:'三', d:'四', e:'五', f:'六', g:'七', h:'八', i:'九' };
  const pieces = { p:'歩', l:'香', n:'桂', s:'銀', g:'金', b:'角', r:'飛', k:'玉' };

  if (usi.includes('*')) {
    const piece = pieces[usi[0].toLowerCase()] || usi[0].toUpperCase();
    return `${piece}打 ${usi[2]}${rows[usi[3]] || usi[3]}`;
  }
  const fRow = rows[usi[1]] || usi[1];
  const tRow = rows[usi[3]] || usi[3];
  const prom = usi.endsWith('+') ? '成' : (usi.endsWith('=') ? '不成' : '');
  return `${usi[0]}${fRow}→${usi[2]}${tRow}${prom}`;
}

// ---------------------------------------------------------------------------
// Nav UI
// ---------------------------------------------------------------------------

function updateCounter(idx) {
  const total = gameData ? gameData.sfens.length - 1 : 0;
  $('#move-counter').text(idx + ' / ' + total);
}

function updateNavButtons() {
  const total = gameData ? gameData.sfens.length - 1 : 0;
  $('#btn-first, #btn-prev').prop('disabled', currentIdx <= 0);
  $('#btn-next,  #btn-last').prop('disabled', currentIdx >= total);
}

// ---------------------------------------------------------------------------
// Board flip
// ---------------------------------------------------------------------------

function flipBoard() {
  orientation = orientation === 'sente' ? 'gote' : 'sente';
  if (sg) sg.set({ orientation });
  if (gameData && gameData.sfens[currentIdx]) {
    updatePlayerLabels(sfenTurnColor(gameData.sfens[currentIdx]));
  }
}

// ---------------------------------------------------------------------------
// Player labels
// ---------------------------------------------------------------------------

function updatePlayerLabels(turnColor) {
  const sente = $('#gameSente').val() || 'Sente';
  const gote  = $('#gameGote').val()  || 'Gote';

  const bottomIs = orientation === 'sente' ? 'sente' : 'gote';
  const topIs    = orientation === 'sente' ? 'gote'  : 'sente';

  function labelHtml(player) {
    const icon = player === 'sente'
      ? '<span class="vg-pl-icon text-warning">☗</span>'
      : '<span class="vg-pl-icon text-secondary opacity-75">☖</span>';
    const name = player === 'sente' ? sente : gote;
    return icon
      + `<span class="vg-pl-name">${name}</span>`
      + '<span class="vg-pl-tomove">▶</span>';
  }

  $('#vg-player-top')
    .html(labelHtml(topIs))
    .toggleClass('is-tomove', turnColor === topIs);
  $('#vg-player-bottom')
    .html(labelHtml(bottomIs))
    .toggleClass('is-tomove', turnColor === bottomIs);
}

// ---------------------------------------------------------------------------
// Score bar
// ---------------------------------------------------------------------------

function updateScoreBar(idx) {
  if (!gameData || !gameData.isAnalyzed) return;
  const scores = gameData.scores || [];
  const s = scores[idx];
  if (s === undefined) return;

  const t   = s / (Math.abs(s) + 1000);
  const pct = Math.round(((t + 1) / 2) * 100);

  $('#score-bar').css('background',
    `linear-gradient(to right, #1a1a1a ${pct}%, #cccccc ${pct}%)`
  );

  let label;
  if (Math.abs(s) > 15000) {
    const inN = 30000 - Math.abs(s);
    label = (s > 0 ? '▲ 詰' : '▽ 詰') + (inN > 0 ? ' ' + inN : '');
  } else {
    label = (s >= 0 ? '▲ ' : '▽ ') + Math.abs(s);
  }
  $('#score-label').text(label);
}

// ---------------------------------------------------------------------------
// Analysis chart
// ---------------------------------------------------------------------------

function renderChart() {
  if (!gameData || !gameData.scores || !gameData.scores.length) return;
  const i18n   = window.i18n || {};
  const sente  = $('#gameSente').val();
  const gote   = $('#gameGote').val();
  const rawScores = gameData.scores;
  const scores = (rawScores.length > 0 && rawScores[rawScores.length - 1] === 0)
    ? rawScores.slice(0, -1)
    : rawScores;
  const sig    = s => s / (Math.abs(s) + 1000);

  const scorePts  = scores.map((s, i) => ({ x: i, y: sig(s) }));
  const puzzlePts = (gameData.puzzles || []).map(p => ({
    x: p.ply,
    y: sig(scores[p.ply] || 0),
    id: p.id,
  }));

  const ctx = document.getElementById('analysisChart').getContext('2d');
  if (chart) chart.destroy();

  chart = new Chart(ctx, {
    type: 'line',
    data: {
      datasets: [
        {
          data: scorePts,
          borderColor: 'rgb(75,192,192)',
          backgroundColor: 'rgba(75,192,192,0.12)',
          fill: true, tension: 0,
          pointRadius: 0, pointHitRadius: 8, borderWidth: 1.5,
        },
        {
          data: puzzlePts,
          borderColor: 'rgb(255,99,132)', backgroundColor: 'rgb(255,99,132)',
          pointRadius: 6, pointHitRadius: 10, showLine: false,
        },
        {
          data: [{ x: 0, y: -1.05 }, { x: 0, y: 1.05 }],
          borderColor: 'rgba(255,220,50,0.85)', borderWidth: 2,
          pointRadius: 0, showLine: true, fill: false, parsing: false,
        },
      ],
    },
    options: {
      animation: { duration: 0 },
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            title(items) {
              const x = items[0].raw.x;
              return x === 0 ? (i18n['chart.start'] || 'Start') : (i18n['chart.move'] || 'Move') + ' ' + x;
            },
            label(ctx2) {
              if (ctx2.datasetIndex === 2) return null;
              const x = ctx2.raw.x;
              const s = scores[x];
              if (s === undefined) return '';
              let str;
              if (Math.abs(s) > 15000) {
                const inN = 30000 - Math.abs(s);
                str = (s > 0 ? '▲ Mate' : '▽ Mate') + (inN > 0 ? ' ' + inN : '');
              } else {
                str = (s >= 0 ? '▲ ' : '▽ ') + Math.abs(s);
              }
              if (ctx2.datasetIndex === 1) return ['🔴 Puzzle here', 'Score: ' + str];
              return 'Score: ' + str;
            },
          },
        },
      },
      onClick(evt) {
        const pts = chart.getElementsAtEventForMode(evt, 'nearest', { axis: 'x', intersect: false }, true);
        if (pts.length > 0) goTo(scorePts[pts[0].index].x);
      },
      scales: {
        x: {
          type: 'linear',
          title: {
            display: true,
            text: '▲ ' + sente + '  vs  ▽ ' + gote,
            color: '#aaa', font: { size: 11 },
          },
          ticks: { stepSize: 5, precision: 0, color: '#888', font: { size: 10 } },
          grid:  { color: 'rgba(255,255,255,0.05)' },
        },
        y: {
          min: -1.05, max: 1.05,
          ticks: {
            callback(v) {
              if (Math.abs(v) < 0.01) return '0';
              if (v >  0.99) return '▲';
              if (v < -0.99) return '▽';
              const s    = 1000 * v / (1 - Math.abs(v));
              const a    = Math.abs(s);
              const pre  = s > 0 ? '▲' : '▽';
              if (a < 1000) return pre + Math.round(a / 100) * 100;
              if (a < 5000) return pre + Math.round(a / 500) * 500;
              return pre + Math.round(a / 1000) * 1000;
            },
            color: '#888', font: { size: 10 },
          },
          grid: {
            color: ctx2 => ctx2.tick.value === 0
              ? 'rgba(255,255,255,0.25)'
              : 'rgba(255,255,255,0.05)',
          },
        },
      },
    },
  });

  document.getElementById('analysisChart').style.cursor = 'pointer';
}

function updateChartCursor(idx) {
  if (!chart) return;
  const scorePts = chart.data.datasets[0].data;
  const maxX = scorePts.length > 0 ? scorePts[scorePts.length - 1].x : idx;
  const x = Math.min(idx, maxX);
  chart.data.datasets[2].data = [{ x, y: -1.05 }, { x, y: 1.05 }];
  chart.update('none');
}

// ---------------------------------------------------------------------------
// Reanalysis with custom settings
// ---------------------------------------------------------------------------

function triggerReanalysis() {
  const hash         = $('#gameHash').val();
  const shallowLimit = parseFloat($('#shallowLimitInput').val()) || 1;
  const deepLimit    = parseFloat($('#deepLimitInput').val())    || 10;
  const winChanceDrop = parseFloat($('#winChanceDropInput').val()) || 0.1;

  $('#runReanalyzeBtn').prop('disabled', true)
    .html('<span class="spinner-border spinner-border-sm me-1" role="status"></span>Starting…');
  $('#reanalyze-panel').slideUp(150);
  $('#analysis-progress').show();
  $('#analysis-progress-msg').text('Starting analysis…');
  $('#analysis-result').empty();

  $.ajax({
    url: '/reanalyze-game',
    method: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({ kifHash: hash, shallowLimit, deepLimit, winChanceDropThreshold: winChanceDrop })
  })
  .done(function (raw) {
    const d = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (d && d.taskId) pollReanalysis(d.taskId);
    else { resetReanalyzeBtn(); $('#analysis-progress-msg').text('Failed to start.'); }
  })
  .fail(function () {
    resetReanalyzeBtn();
    $('#analysis-progress-msg').text('Failed to start analysis.');
  });
}

function pollReanalysis(taskId) {
  let lastMsg = '';
  const timer = setInterval(function () {
    $.get('/maintenance-task-status', { id: taskId })
      .done(function (raw) {
        const d = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (!d) return;
        if (d.message && d.message !== lastMsg) {
          lastMsg = d.message;
          $('#analysis-progress-msg').text(d.message);
        }
        if (d.status === 'completed') {
          clearInterval(timer);
          resetReanalyzeBtn();
          handleReanalysisComplete(d.resultJson || d.resultHtml || '');
          loadGameData($('#gameHash').val());
        } else if (d.status === 'failed') {
          clearInterval(timer);
          resetReanalyzeBtn();
          $('#analysis-progress-msg').text('Analysis failed: ' + (d.error || 'unknown error'));
        }
      });
  }, 2000);
}

function handleReanalysisComplete(resultStr) {
  const i18n = window.i18n || {};
  try {
    const result  = JSON.parse(resultStr);
    const count   = typeof result.puzzleCount === 'number' ? result.puzzleCount : 0;
    const kifHash = result.kifHash || $('#gameHash').val();
    const reviewUrl = '/puzzle-creator?game=' + encodeURIComponent(kifHash) + '&status=review';
    $('#analysis-progress-msg').html(
      '<i class="bi bi-check-circle-fill text-success me-1"></i>' +
      (i18n['viewgame.analysisComplete'] || 'Analysis complete!')
    );
    if (count > 0) {
      $('#analysis-result').html(
        `<span class="text-success fw-semibold">${count}</span> ` +
        (i18n['viewgame.puzzlesFound'] || 'puzzle(s) found.') +
        ` <a href="${reviewUrl}" class="text-warning">${i18n['viewgame.reviewPuzzles'] || 'Review →'}</a>`
      );
    } else {
      $('#analysis-result').html(
        '<span class="text-muted">' + (i18n['viewgame.noPuzzlesFound'] || 'No puzzles found.') + '</span>'
      );
    }
  } catch (e) {
    $('#analysis-progress-msg').html(
      '<i class="bi bi-check-circle-fill text-success me-1"></i>' +
      (resultStr || (i18n['viewgame.analysisComplete'] || 'Analysis complete!'))
    );
  }
}

function resetReanalyzeBtn() {
  const i18n       = window.i18n || {};
  const isAnalyzed = $('#gameIsAnalyzed').val() === 'true';
  const label      = isAnalyzed
    ? (i18n['database.btnReanalyze'] || 'Re-analyze')
    : (i18n['database.btnAnalyze']   || 'Analyze');
  $('#runReanalyzeBtn').prop('disabled', false)
    .html('<i class="bi bi-cpu me-1"></i>' + label);
}

// ---------------------------------------------------------------------------
// Position analysis — best moves as arrows on the board
// ---------------------------------------------------------------------------

let positionAnalysisTimer = null;

function analyzeCurrentPosition() {
  if (!gameData || !sg) return;
  const sfen = gameData.sfens[currentIdx];
  if (!sfen) return;

  const $btn = $('#analyzePositionBtn');
  $btn.prop('disabled', true)
    .html('<span class="spinner-border spinner-border-sm" role="status"></span>');

  // Read settings (fall back to defaults if panel not shown)
  const multiPv   = parseInt($('#posCandidatesInput').val()) || parseInt($('#cfgPosCandidates').val()) || 3;
  const depth     = parseInt($('#posDepthInput').val())      || parseInt($('#cfgPosDepth').val())      || 20;
  const seconds   = parseInt($('#posSecondsInput').val())    || parseInt($('#cfgPosSeconds').val())    || 5;
  const sequences = parseInt($('#posSequencesInput').val())  || parseInt($('#cfgPosSequences').val())  || 5;

  // Persist sequences for use after result arrives
  window._posAnalysisSequences = sequences;

  $('#analyze-pos-panel').slideUp(150);
  clearArrows();

  $.ajax({
    url: '/analyze-position',
    method: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({ sfen, seconds, multiPv, depth })
  })
  .done(function (raw) {
    const d = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (d && d.taskId) pollPositionAnalysis(d.taskId, $btn);
    else restorePositionBtn($btn);
  })
  .fail(function () { restorePositionBtn($btn); });
}

function pollPositionAnalysis(taskId, $btn) {
  if (positionAnalysisTimer) clearInterval(positionAnalysisTimer);

  positionAnalysisTimer = setInterval(function () {
    $.get('/maintenance-task-status', { id: taskId })
      .done(function (raw) {
        const d = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (!d) return;
        if (d.status === 'completed') {
          clearInterval(positionAnalysisTimer);
          positionAnalysisTimer = null;
          restorePositionBtn($btn);
          try {
            const raw        = JSON.parse(d.resultJson || '[]');
            const numSeq     = window._posAnalysisSequences || 5;
            const candidates = raw.map(c => ({
              usi:   c.usi,
              score: c.score,
              depth: c.depth,
              pv:    (c.pv || '').split(' ').slice(0, numSeq).join(' ')
            }));
            showCandidateArrows(candidates, true);
          } catch (e) {
            console.error('[view-game] Failed to parse position analysis result', e);
          }
        } else if (d.status === 'failed') {
          clearInterval(positionAnalysisTimer);
          positionAnalysisTimer = null;
          restorePositionBtn($btn);
        }
      });
  }, 1000);
}

function restorePositionBtn($btn) {
  const i18n = window.i18n || {};
  $btn.prop('disabled', false)
    .html('<i class="bi bi-eye me-1"></i>' + (i18n['viewgame.analyzePositionBtn'] || 'Analyze move'));
}

function saveMoveAnalysis() {
  const i18n    = window.i18n || {};
  const kifHash = $('#gameHash').val();
  const moveIdx = currentIdx;
  if (!kifHash || !lastCandidates || !lastCandidates.length) return;

  const $btn = $('#save-analysis-btn');
  $btn.prop('disabled', true);

  $.ajax({
    url: '/save-move-analysis',
    method: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({
      kifHash,
      moveIdx,
      candidates: lastCandidates,
      orientation,
      source: _analysisIsLocal ? 'local' : 'server',
    })
  })
  .done(function () {
    moveAnalysis[moveIdx] = { candidates: lastCandidates, orientation };
    markMoveAnalyzed(moveIdx);
    $btn.replaceWith(
      `<span class="text-success" style="font-size:.72rem">` +
      `<i class="bi bi-bookmark-check-fill me-1"></i>${i18n['viewgame.analysisSaved'] || 'Saved'}</span>`
    );
  })
  .fail(function () {
    $btn.prop('disabled', false);
  });
}

function markMoveAnalyzed(idx) {
  const el = document.querySelector(`#move-list .vg-move[data-idx="${idx}"]`);
  if (el) el.classList.add('vg-move-analyzed');
}

// ---------------------------------------------------------------------------
// Candidate arrows (first move only — no continuation arrows)
// ---------------------------------------------------------------------------

// USI letter → Shogiground role name (for drop moves)
const USI_ROLE_MAP = { p: 'pawn', l: 'lance', n: 'knight', s: 'silver', g: 'gold', b: 'bishop', r: 'rook' };

/**
 * Convert a USI move + brush into a Shogiground DrawShape.
 * For drops (P*5e), orig is {color, role} so Shogiground draws the arrow from the hand.
 */
function usiToShape(usi, brush, turnColor) {
  if (!usi) return null;
  if (usi.includes('*')) {
    const role = USI_ROLE_MAP[usi[0].toLowerCase()];
    if (!role) return null;
    return { orig: { color: turnColor, role }, dest: usi.substring(2, 4), brush };
  }
  const clean = usi.replace('+', '').replace('=', '');
  if (clean.length < 4) return null;
  return { orig: clean.substring(0, 2), dest: clean.substring(2, 4), brush };
}

function showCandidateArrows(candidates, showSaveBtn) {
  if (!sg || !candidates || !candidates.length) return;

  lastCandidates       = candidates;
  _serverAnalysisShown = true;

  const brushes   = ['green', 'blue', 'yellow'];
  const turnColor = gameData ? sfenTurnColor(gameData.sfens[currentIdx]) : 'sente';
  const shapes    = candidates.slice(0, 3).map(function (cand, idx) {
    return usiToShape(cand.usi, brushes[idx] || 'paleGrey', turnColor);
  }).filter(Boolean);

  sg.set({ drawable: { shapes, enabled: false } });
  showCandidatesPanel(candidates, showSaveBtn === true);
  updatePuzzleWidget(currentIdx);
}

function clearArrows() {
  candReplayState      = null;
  lastCandidates       = null;
  _serverAnalysisShown = false;
  $('#candidates-panel').hide();
  updatePuzzleWidget(currentIdx);
  // Restore ceval arrows/panel if local analysis is running, otherwise clear
  if (_cevalOn && _lastCevalEv) {
    showCevalArrows(_lastCevalEv.pvs);
  } else if (sg) {
    sg.set({ drawable: { shapes: [], enabled: false } });
  }
}

// ---------------------------------------------------------------------------
// Candidates panel
// ---------------------------------------------------------------------------

function showCandidatesPanel(candidates, showSaveBtn, isLocal) {
  const i18n      = window.i18n || {};
  const dotColors = ['#4caf50', '#2196f3', '#ffc107'];

  // Update panel title with save/saved button
  const alreadySaved = !!moveAnalysis[currentIdx];
  let titleExtra;
  if (showSaveBtn && canAnalyze) {
    titleExtra = `<button id="save-analysis-btn" class="btn btn-sm btn-outline-success py-0 px-2" style="font-size:.72rem">` +
      `<i class="bi bi-bookmark-fill me-1"></i>${i18n['viewgame.saveAnalysis'] || 'Save'}</button>`;
  } else if (alreadySaved) {
    titleExtra = `<span class="text-success" style="font-size:.72rem">` +
      `<i class="bi bi-bookmark-check-fill me-1"></i>${i18n['viewgame.analysisSaved'] || 'Saved'}</span>`;
  } else {
    titleExtra = '';
  }
  const savedSource  = moveAnalysis[currentIdx]?.source;
  const showLocal    = isLocal || savedSource === 'local';
  const localBadge   = showLocal
    ? `<span class="badge bg-secondary bg-opacity-50 ms-1" style="font-size:.65rem;vertical-align:middle" title="Analysis performed by local engine">` +
      `<i class="bi bi-cpu me-1"></i>Local</span>`
    : '';
  $('#candidates-panel .vg-cp-title').html(
    `<span><i class="bi bi-graph-up-arrow me-1"></i>${i18n['viewgame.candidatesTitle'] || 'Best Moves'}${localBadge}</span>` +
    titleExtra
  );

  let listHtml = '';
  candidates.slice(0, 3).forEach(function (c, idx) {
    const score = formatCandidateScore(c.score);
    listHtml +=
      `<div class="vg-candidate" data-cand-idx="${idx}">` +
        `<span class="vg-cand-dot" style="background:${dotColors[idx] || '#888'}"></span>` +
        `<span class="vg-cand-move">${usiToReadable(c.usi)}</span>` +
        `<span class="vg-cand-score">${score}</span>` +
        `<button class="vg-cand-play" data-cand-idx="${idx}" title="Replay">▶</button>` +
      `</div>`;
  });

  const replayHtml =
    `<div class="vg-rn-moves" id="cand-replay-moves"></div>` +
    `<div class="vg-rn-controls">` +
      `<button id="cand-rn-prev" class="vg-rn-btn" disabled>◄</button>` +
      `<span id="cand-rn-counter" class="vg-rn-counter"></span>` +
      `<button id="cand-rn-next" class="vg-rn-btn">►</button>` +
      `<button id="cand-rn-exit" class="vg-rn-btn ms-auto" title="Exit replay (Esc)">✕</button>` +
    `</div>`;

  $('#candidates-list').html(listHtml || '<span class="text-muted small">No candidates.</span>');
  $('#cand-replay-wrap').html(replayHtml).hide();
  $('#candidates-panel').show();
}

// ---------------------------------------------------------------------------
// Candidate replay
// ---------------------------------------------------------------------------

/**
 * Build an array of SFENs by applying the given PV move list to baseSfen.
 * Returns { sfens: string[], moves: string[] } where moves[0] = '' (start).
 */
function buildPvSfens(baseSfen, pvMoves) {
  const sfens      = [baseSfen];
  const validMoves = [''];           // index 0 = no move played yet

  let pos;
  try {
    const result = Shogiops.sfen.parseSfen('standard', baseSfen, false);
    if (!result || !result.value) return { sfens, validMoves };
    pos = result.value;
  } catch (e) {
    console.warn('[view-game] parseSfen failed:', e);
    return { sfens, validMoves };
  }

  for (const usi of pvMoves) {
    if (!usi) continue;
    try {
      const move = Shogiops.parseUsi(usi);
      if (!move) break;
      pos.play(move);
      sfens.push(Shogiops.sfen.makeSfen(pos));
      validMoves.push(usi);
    } catch (e) {
      console.warn('[view-game] PV move failed:', usi, e);
      break;
    }
  }

  return { sfens, validMoves };
}

function enterCandidateReplay(candIdx) {
  if (!gameData || !sg || !lastCandidates) return;
  const cand = lastCandidates[candIdx];
  if (!cand) return;

  const baseSfen = gameData.sfens[currentIdx];
  const pvMoves  = (cand.pv || cand.usi || '').split(' ').filter(Boolean);
  const { sfens, validMoves } = buildPvSfens(baseSfen, pvMoves);

  candReplayState = { sfens, moves: validMoves, idx: 0, candIdx };

  // Render the move list in the replay wrap
  const baseColor = sfenTurnColor(baseSfen);
  let movesHtml = `<span class="vg-rm active" data-step="0">▶ Start</span>`;
  validMoves.slice(1).forEach(function (usi, i) {
    const step     = i + 1;
    // Determine who played this move: alternate from baseColor
    const isSente  = (step % 2 === 1) === (baseColor === 'sente');
    const icon     = isSente ? '☗' : '☖';
    movesHtml += `<span class="vg-rm" data-step="${step}">${icon} ${usiToReadable(usi)}</span>`;
  });
  $('#cand-replay-moves').html(movesHtml);
  $('#cand-replay-wrap').show();

  // Highlight selected candidate row
  $('.vg-candidate').removeClass('vg-cand-selected');
  $(`.vg-candidate[data-cand-idx="${candIdx}"]`).addClass('vg-cand-selected');

  candReplayGoTo(0);
}

function candReplayGoTo(idx) {
  if (!candReplayState) return;
  const { sfens, moves, candIdx } = candReplayState;
  if (idx < 0 || idx >= sfens.length) return;

  candReplayState.idx = idx;

  const sfenStr   = sfens[idx];
  const parts     = sfenStr.split(' ');
  const hands     = parts[2] || '-';
  const turnColor = parts[1] === 'w' ? 'gote' : 'sente';

  // Highlight the move that was last applied
  let lastDests = [];
  let lastPiece;
  if (idx > 0 && moves[idx]) {
    const move = Shogiops.parseUsi(moves[idx]);
    if (move) {
      if ('role' in move) {
        lastDests = [Shogiops.makeSquare(move.to)];
        lastPiece = { role: move.role, color: turnColor === 'sente' ? 'gote' : 'sente' };
      } else {
        lastDests = [Shogiops.makeSquare(move.from), Shogiops.makeSquare(move.to)];
      }
    }
  }

  sg.set({ sfen: { board: sfenStr, hands }, turnColor, lastDests, lastPiece, drawable: { shapes: [], enabled: false } });

  // Update move list highlight
  $('.vg-rm').removeClass('active');
  $(`.vg-rm[data-step="${idx}"]`).addClass('active');

  // Update counter and nav buttons
  $('#cand-rn-counter').text((idx) + ' / ' + (sfens.length - 1));
  $('#cand-rn-prev').prop('disabled', idx <= 0);
  $('#cand-rn-next').prop('disabled', idx >= sfens.length - 1);
}

function exitCandidateReplay() {
  if (!candReplayState) return;
  candReplayState = null;
  $('#cand-replay-wrap').hide();
  $('.vg-candidate').removeClass('vg-cand-selected');

  // Restore game board position with candidate arrows
  const sfenStr   = gameData.sfens[currentIdx];
  const parts     = sfenStr.split(' ');
  const turnColor = parts[1] === 'w' ? 'gote' : 'sente';

  let lastDests = [];
  let lastPiece;
  if (currentIdx > 0 && gameData.moves[currentIdx]) {
    const move = Shogiops.parseUsi(gameData.moves[currentIdx]);
    if (move) {
      if ('role' in move) {
        lastDests = [Shogiops.makeSquare(move.to)];
        lastPiece = { role: move.role, color: turnColor === 'sente' ? 'gote' : 'sente' };
      } else {
        lastDests = [Shogiops.makeSquare(move.from), Shogiops.makeSquare(move.to)];
      }
    }
  }

  // Re-draw the candidate first-move arrows
  const brushes   = ['green', 'blue', 'yellow'];
  const exitColor = sfenTurnColor(sfenStr);
  const shapes    = lastCandidates
    ? lastCandidates.slice(0, 3).map(function (c, i) {
        return usiToShape(c.usi, brushes[i] || 'paleGrey', exitColor);
      }).filter(Boolean)
    : [];

  sg.set({ sfen: { board: sfenStr, hands: parts[2] || '-' }, turnColor, lastDests, lastPiece, drawable: { shapes, enabled: false } });
}

// ---------------------------------------------------------------------------
// Score/helper formatting
// ---------------------------------------------------------------------------

function formatCandidateScore(score) {
  if (!score) return '';
  if (score.kind === 'mate') return (score.value > 0 ? '+' : '') + 'M' + Math.abs(score.value);
  const v = score.value || 0;
  return (v >= 0 ? '+' : '') + v;
}

function escHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// ---------------------------------------------------------------------------
// Puzzle-at-ply widget
// ---------------------------------------------------------------------------

function updatePuzzleWidget(idx) {
  const i18n          = window.i18n || {};
  const customPuzzles = gameData && gameData.puzzlesByPly && gameData.puzzlesByPly[idx]
    ? gameData.puzzlesByPly[idx].filter(function (p) { return p.source === 'custom'; })
    : [];
  // Show widget when there are existing puzzles or the user can create one
  if (!customPuzzles.length && !canAnalyze) {
    $('#puzzle-at-ply').hide();
    return;
  }

  let html = '';

  if (customPuzzles.length) {
    html += `<div class="vg-pw-title"><i class="bi bi-puzzle me-1"></i>${i18n['viewgame.puzzleHere'] || 'Puzzle at this position'}</div>`;
    customPuzzles.forEach(function (p) {
      const badge = p.status === 'review'
        ? `<span class="badge bg-warning text-dark" style="font-size:.65rem">Review</span>`
        : `<span class="badge bg-success" style="font-size:.65rem">Accepted</span>`;
      html += `<div class="vg-pw-item mb-1">` + badge;
      if (canAnalyze) {
        html +=
          `<a href="/puzzle-creator/edit/${p.id}" class="btn btn-sm btn-outline-primary py-0 px-2" style="font-size:.72rem" target="_blank"><i class="bi bi-pencil me-1"></i>${i18n['viewgame.editPuzzle'] || 'Edit'}</a>` +
          `<button class="btn btn-sm btn-outline-danger py-0 px-2 vg-delete-puzzle" data-puzzle-id="${p.id}" style="font-size:.72rem"><i class="bi bi-trash me-1"></i>${i18n['viewgame.deletePuzzle'] || 'Delete'}</button>`;
      }
      html += `</div>`;
    });
  }

  if (canAnalyze) {
    if (!customPuzzles.length) {
      html += `<div class="vg-pw-title"><i class="bi bi-puzzle me-1 text-warning"></i>${i18n['viewgame.markForReview'] || 'Mark position'}</div>`;
    }
    html += `<div class="vg-pw-item">` +
      `<button id="create-puzzle-btn" class="btn btn-sm btn-outline-warning py-0 px-2" style="font-size:.72rem">` +
      `<i class="bi bi-plus-square me-1"></i>${i18n['viewgame.createPuzzle'] || 'Create puzzle'}</button>` +
      `</div>`;
  }

  $('#puzzle-at-ply').html(html).show();
}

function createReviewPuzzle() {
  if (!gameData) return;
  const i18n    = window.i18n || {};
  const kifHash = $('#gameHash').val();
  const sfen    = gameData.sfens[currentIdx];
  const sente   = $('#gameSente').val() || 'Sente';
  const gote    = $('#gameGote').val()  || 'Gote';
  const name    = 'Move ' + currentIdx + ' \u2014 ' + sente + ' vs ' + gote;

  const $btn = $('#create-puzzle-btn');
  $btn.prop('disabled', true).html('<span class="spinner-border spinner-border-sm"></span>');

  $.ajax({
    url: '/puzzle-creator/save',
    method: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({ name, sfen, status: 'review', source: 'game', gameKifHash: kifHash, moveNumber: currentIdx, comments: '' })
  })
  .done(function (raw) {
    const d = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (d.success && d.id) {
      // Add to local data so widget refreshes
      const newPuzzle = { ply: currentIdx, id: d.id, source: 'custom', status: 'review', comment: '' };
      gameData.puzzles.push(newPuzzle);
      if (!gameData.puzzlesByPly[currentIdx]) gameData.puzzlesByPly[currentIdx] = [];
      gameData.puzzlesByPly[currentIdx].push(newPuzzle);
      if (gameData.isAnalyzed && gameData.scores) renderChart();
      updatePuzzleWidget(currentIdx);
      window.open('/puzzle-creator/edit/' + d.id, '_blank');
    } else {
      $btn.prop('disabled', false).html('<i class="bi bi-plus-square me-1"></i>' + (i18n['viewgame.createPuzzle'] || 'Create puzzle'));
    }
  })
  .fail(function () {
    $btn.prop('disabled', false).html('<i class="bi bi-plus-square me-1"></i>' + (i18n['viewgame.createPuzzle'] || 'Create puzzle'));
  });
}

function deletePuzzleFromGame(puzzleId) {
  const i18n = window.i18n || {};
  if (!confirm(i18n['viewgame.deletePuzzleConfirm'] || 'Delete this puzzle?')) return;

  $.ajax({
    url: '/puzzle-creator/delete',
    method: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({ id: puzzleId })
  })
  .done(function (raw) {
    const d = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (d.success) {
      // Remove from local data
      gameData.puzzles = gameData.puzzles.filter(function (p) { return p.id !== puzzleId; });
      const plyArr = gameData.puzzlesByPly[currentIdx];
      if (plyArr) {
        gameData.puzzlesByPly[currentIdx] = plyArr.filter(function (p) { return p.id !== puzzleId; });
        if (!gameData.puzzlesByPly[currentIdx].length) delete gameData.puzzlesByPly[currentIdx];
      }
      if (gameData.isAnalyzed && gameData.scores) renderChart();
      updatePuzzleWidget(currentIdx);
    }
  });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function showLoadError(msg) {
  $('#move-list').html('<p class="text-danger p-3"><i class="bi bi-exclamation-triangle me-2"></i>' + msg + '</p>');
  $('#btn-first, #btn-prev, #btn-next, #btn-last').prop('disabled', true);
}

// ---------------------------------------------------------------------------
// Local engine analysis (ceval.js)
// ---------------------------------------------------------------------------

let _ceval              = null;
let _cevalOn            = false;
let _cevalBar           = null;
let _lastCevalEv        = null;
let _analysisIsLocal    = false;  // true when candidates panel shows local (ceval) result
let _serverAnalysisShown = false; // true when server analysis arrows/panel are active

// Local game analysis state
let _lgaRunning  = false;
let _lgaCancel   = false;
let _lgaScores   = [];
let _lgaBlunders = [];

function initCeval() {
  // Register toggle button
  $(document).on('click', '#cevalToggleBtn', function () {
    toggleCeval();
  });

  // Check browser support
  if (typeof ClientEval === 'undefined' || !ClientEval.isSupported()) {
    $('#cevalToggleBtn')
      .prop('disabled', true)
      .attr('title', 'Local analysis requires a modern browser with SharedArrayBuffer support (Chrome/Edge/Firefox)');
    return;
  }
}

function toggleCeval() {
  if (_cevalOn) {
    stopCeval();
  } else {
    startCeval();
  }
}

function startCeval() {
  if (_cevalOn) return;
  _cevalOn = true;

  $('#cevalToggleBtn').addClass('active').html('<i class="bi bi-cpu-fill me-1"></i>Local ✓');
  $('#ceval-gauge').show();

  if (!_cevalBar) {
    _cevalBar = new CevalBar('#ceval-bar-fill', '#ceval-bar-info');
  }
  _cevalBar.reset();

  if (!_ceval) {
    _ceval = new ClientEval({
      multiPv:    parseInt($('#cevalCandidatesInput').val()) || parseInt($('#cfgPosCandidates').val()) || 3,
      onReady:    function (name) { updateCevalStatus('ready', name); },
      onStatus:   function (msg)  { updateCevalStatus(msg); },
      onEval:     function (ev)   { onCevalResult(ev); },
      onComplete: function (ev)   { onCevalComplete(ev); },
    });
    _ceval.init();
  }

  analyzeCevalPosition();
}

function stopCeval() {
  if (!_cevalOn) return;
  _cevalOn = false;

  $('#cevalToggleBtn').removeClass('active').html('<i class="bi bi-cpu-fill me-1"></i>Local');
  $('#ceval-gauge').hide();

  _ceval?.stop();
  _lastCevalEv = null;

  clearCevalArrows();
  updateCevalStatus('off');
}

function analyzeCevalPosition() {
  if (!_cevalOn || !gameData || !sg) return;
  const sfen = gameData.sfens[currentIdx];
  if (!sfen) return;

  _lastCevalEv    = null;
  _analysisIsLocal = false;
  _cevalBar?.reset();
  updateCevalStatus('computing');

  const multiPv   = parseInt($('#cevalCandidatesInput').val()) || parseInt($('#cfgPosCandidates').val()) || 3;
  const depth     = parseInt($('#cevalDepthInput').val())     || parseInt($('#cfgPosDepth').val())      || null;
  const movetime  = (parseInt($('#cevalMovetimeInput').val()) || parseInt($('#cfgPosSeconds').val()) || 30) * 1000;

  _ceval.analyze(sfen, { movetime, multiPv, depth });
}

function onCevalResult(ev) {
  if (!_cevalOn || _lgaRunning) return;
  _lastCevalEv = ev;

  // Update eval bar
  _cevalBar?.update(ev, _ceval?.engineName || '', true);

  // Update arrows (top-3 PVs)
  showCevalArrows(ev.pvs);

  // Show eval in candidates panel if server analysis not active (intermediate update, no save btn)
  if (!_serverAnalysisShown) {
    showCevalPanel(ev, false);
  }
}

function onCevalComplete(ev) {
  if (!_cevalOn || _lgaRunning || !ev) return;
  // Analysis finished — show final result with save button if no server analysis present
  if (!_serverAnalysisShown) {
    _analysisIsLocal = true;
    showCevalPanel(ev, canAnalyze);
  }
}

function showCevalArrows(pvs) {
  if (!sg || !pvs || !pvs.length) return;
  if (_serverAnalysisShown) return; // don't overwrite server analysis arrows

  const brushes   = ['green', 'blue', 'yellow'];
  const turnColor = gameData ? sfenTurnColor(gameData.sfens[currentIdx]) : 'sente';
  const shapes    = pvs.slice(0, 3).map(function (pv, idx) {
    const firstMove = pv.moves && pv.moves[0];
    return firstMove ? usiToShape(firstMove, brushes[idx] || 'paleGrey', turnColor) : null;
  }).filter(Boolean);

  sg.set({ drawable: { shapes, enabled: false } });
}

function clearCevalArrows() {
  if (!sg || lastCandidates) return;
  sg.set({ drawable: { shapes: [], enabled: false } });
}

function showCevalPanel(ev, showSaveBtn) {
  if (!ev || !ev.pvs || !ev.pvs.length) return;

  const numSeq = parseInt($('#cevalSequencesInput').val()) || parseInt($('#cfgPosSequences').val()) || 5;

  // Build candidate objects matching server-analysis format
  const candidates = ev.pvs.map(function (pv) {
    const moves = pv.moves || [];
    return {
      usi:   moves[0],
      score: pv.mate != null
        ? { kind: 'mate', value: pv.mate }
        : { kind: 'cp', value: pv.cp || 0 },
      depth: pv.depth || ev.depth,
      pv:    moves.slice(0, numSeq).join(' '),
    };
  });

  // Set lastCandidates so candidate replay works
  lastCandidates = candidates;

  showCandidatesPanel(candidates, !!showSaveBtn, true);
}

// ---------------------------------------------------------------------------
// Local Game Analysis (LGA) — full sequential analysis via WASM engine
// EXPERIMENTAL — disabled from UI (button + settings panel removed from backend).
// Code preserved here for future use. Do not remove.
// ---------------------------------------------------------------------------

function startLocalGameAnalysis() {
  const i18n = window.i18n || {};
  if (_lgaRunning || !gameData || !gameData.sfens || !gameData.sfens.length) return;
  if (typeof ClientEval === 'undefined' || !ClientEval.isSupported()) {
    alert(i18n['ceval.notSupported'] || 'Local engine not supported in this browser.');
    return;
  }

  const total = gameData.sfens.length;
  if (!confirm(
    (i18n['lga.confirmMsg'] || 'Analyze all {n} positions with the local engine?\nShallow scan + deep analysis of blunders.\nThis may take several minutes.')
      .replace('{n}', total)
  )) return;

  if (_cevalOn) stopCeval();

  _lgaRunning  = true;
  _lgaCancel   = false;
  _lgaScores   = new Array(total).fill(0);
  _lgaBlunders = [];

  if (!_ceval) {
    const multiPv = parseInt($('#lgaDeepCandidatesInput').val()) || parseInt($('#cfgPosCandidates').val()) || 3;
    _ceval = new ClientEval({
      multiPv,
      onReady:    function (name) { updateCevalStatus('ready', name); },
      onStatus:   function (msg)  { if (!_lgaRunning) updateCevalStatus(msg); },
      onEval:     function (ev)   { onCevalResult(ev); },
      onComplete: function (ev)   { onCevalComplete(ev); },
    });
    _ceval.init();
  }

  showLgaPanel();
  lgaUpdateProgress('shallow', 0, total, 0);

  const waitAndStart = function () {
    if (_ceval.isReady || _lgaCancel) {
      if (!_lgaCancel) runLgaShallowPass(0);
    } else {
      setTimeout(waitAndStart, 200);
    }
  };
  waitAndStart();
}

function cancelLocalGameAnalysis() {
  _lgaCancel = true;
  _ceval?.stop();
}

// ── Shallow pass ────────────────────────────────────────────────────────────

function runLgaShallowPass(idx) {
  const total = gameData.sfens.length;
  if (_lgaCancel) { finishLga(true); return; }
  if (idx >= total) {
    const threshold  = parseFloat($('#lgaThresholdInput').val()) || parseFloat($('#cfgWinChanceDrop').val()) || 0.15;
    const blunderIdxs = lgaDetectBlunders(_lgaScores, threshold);
    lgaUpdateProgress('deep', 0, blunderIdxs.length, 0);
    runLgaDeepPass(blunderIdxs, 0);
    return;
  }

  lgaUpdateProgress('shallow', idx, total, 0);

  const shallowMovetime = (parseInt($('#lgaShallowMovetimeInput').val()) || 1) * 1000;
  _ceval.analyze(gameData.sfens[idx], {
    movetime:   shallowMovetime,
    multiPv:    1,
    onComplete: function (ev) {
      _lgaScores[idx] = lgaEvalToScore(ev);
      runLgaShallowPass(idx + 1);
    },
  });
}

// ── Deep pass ───────────────────────────────────────────────────────────────

function runLgaDeepPass(blunderIdxs, i) {
  if (_lgaCancel) { finishLga(true); return; }
  if (i >= blunderIdxs.length) { finishLga(false); return; }

  lgaUpdateProgress('deep', i, blunderIdxs.length, _lgaBlunders.length);

  const idx      = blunderIdxs[i];
  const sfen     = gameData.sfens[idx];
  const multiPv  = parseInt($('#lgaDeepCandidatesInput').val()) || parseInt($('#cfgPosCandidates').val()) || 3;
  const depth    = parseInt($('#lgaDeepDepthInput').val())      || parseInt($('#cfgPosDepth').val())      || 20;
  const movetime = (parseInt($('#lgaDeepMovetimeInput').val())  || parseInt($('#cfgPosSeconds').val())    || 10) * 1000;
  const numSeq   = parseInt($('#lgaDeepSequencesInput').val())  || parseInt($('#cfgPosSequences').val())  || 5;

  _ceval.analyze(sfen, {
    depth,
    movetime,
    multiPv,
    onComplete: function (ev) {
      if (ev) {
        const candidates = (ev.pvs || []).map(function (pv) {
          return {
            usi:   (pv.moves || [])[0],
            score: pv.mate != null ? { kind: 'mate', value: pv.mate } : { kind: 'cp', value: pv.cp || 0 },
            depth: pv.depth || ev.depth,
            pv:    (pv.moves || []).slice(0, numSeq).join(' '),
          };
        }).filter(function (c) { return !!c.usi; });

        if (candidates.length) {
          _lgaBlunders.push({ idx, sfen, candidates, orientation });
        }
      }
      runLgaDeepPass(blunderIdxs, i + 1);
    },
  });
}

// ── Helpers ─────────────────────────────────────────────────────────────────

function lgaDetectBlunders(scores, threshold) {
  const result = [];
  for (let i = 1; i < scores.length; i++) {
    const wcPrev = CevalBar.cpToWinChance(scores[i - 1]);
    const wcCurr = CevalBar.cpToWinChance(scores[i]);
    if (Math.abs(wcCurr - wcPrev) >= threshold) result.push(i);
  }
  return result;
}

function lgaEvalToScore(ev) {
  if (!ev) return 0;
  const pv = ev.pvs && ev.pvs[0];
  if (pv) {
    if (pv.mate != null) return pv.mate > 0 ? 20000 : -20000;
    if (pv.cp   != null) return Math.max(-20000, Math.min(20000, pv.cp));
  }
  if (ev.mate != null) return ev.mate > 0 ? 20000 : -20000;
  return Math.max(-20000, Math.min(20000, ev.cp || 0));
}

// ── Finish ───────────────────────────────────────────────────────────────────

function finishLga(cancelled) {
  _lgaRunning = false;
  if (cancelled) { hideLgaPanel(); return; }

  lgaUpdateProgress('saving', 0, 0, 0);
  saveLgaResults();
}

function saveLgaResults() {
  const kifHash = $('#gameHash').val();
  $.ajax({
    url: '/save-local-game-analysis',
    method: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({ kifHash, scores: _lgaScores, blunders: _lgaBlunders }),
  })
  .done(function (raw) {
    const d = typeof raw === 'string' ? JSON.parse(raw) : raw;
    gameData.isAnalyzed = true;
    gameData.scores = _lgaScores;
    (d.puzzles || []).forEach(function (p) {
      gameData.puzzles.push(p);
      const ply = Math.round(p.ply);
      if (!gameData.puzzlesByPly[ply]) gameData.puzzlesByPly[ply] = [];
      gameData.puzzlesByPly[ply].push(p);
    });
    // Show score bar + chart
    $('.vg-score-wrap').show();
    $('#analysis-section').show();
    renderChart();
    renderMoveList();
    updateScoreBar(currentIdx);
    lgaShowDone(_lgaBlunders.length);
  })
  .fail(function () {
    hideLgaPanel();
    $('#lgaAnalyzeBtn').prop('disabled', false);
  });
}

// ── Progress UI ──────────────────────────────────────────────────────────────

function showLgaPanel() {
  $('#lgaAnalyzeBtn').prop('disabled', true);
  $('#lga-progress').slideDown(150);
}

function hideLgaPanel() {
  $('#lga-progress').slideUp(150);
  $('#lgaAnalyzeBtn').prop('disabled', false);
}

function lgaUpdateProgress(phase, current, total, blundersSoFar) {
  const i18n = window.i18n || {};
  const phaseLabels = {
    shallow: i18n['lga.phaseShallow'] || 'Shallow scan',
    deep:    i18n['lga.phaseDeep']    || 'Deep analysis',
    saving:  i18n['lga.phaseSaving']  || 'Saving…',
  };
  const label = phaseLabels[phase] || phase;
  const pct   = total > 0 ? Math.round((current / total) * 100) : 0;

  $('#lga-phase').text(total > 0 ? `${label}: ${current} / ${total}` : label);
  $('#lga-bar').css('width', pct + '%');

  if (phase === 'deep' && blundersSoFar > 0) {
    const bl = i18n['lga.blundersFound'] || '{n} blunder(s) found';
    $('#lga-stats').text(bl.replace('{n}', blundersSoFar));
  } else if (phase === 'shallow') {
    $('#lga-stats').text('');
  }
}

function lgaShowDone(blunderCount) {
  const i18n = window.i18n || {};
  $('#lga-phase').text(i18n['lga.done'] || 'Done');
  $('#lga-bar').css('width', '100%').removeClass('progress-bar-animated progress-bar-striped');
  const bl = i18n['lga.blundersFound'] || '{n} blunder(s) found';
  $('#lga-stats').text(bl.replace('{n}', blunderCount));
  $('#lga-cancel-btn').text(i18n['common.close'] || 'Close').off('click').on('click', hideLgaPanel);
  $('#lgaAnalyzeBtn').prop('disabled', false);
}

function updateCevalStatus(status, engineName) {
  const $btn = $('#cevalToggleBtn');
  if (status === 'off') {
    $btn.removeClass('active').html('<i class="bi bi-cpu-fill me-1"></i>Local');
  } else if (status === 'computing') {
    // spinner in info bar handled by CevalBar
  } else if (status === 'ready') {
    const name = engineName ? engineName.replace(/TOURNAMENT.*/, '').trim() : 'Engine';
    $btn.attr('title', 'Local analysis: ' + name);
  }
}
