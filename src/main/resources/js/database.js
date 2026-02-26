/**
 * Database Page JavaScript
 * Lists KIF games with server-side pagination and filtering.
 */

let myNicknames = [];
let taskPollers = {};
let canAnalyze  = false;
let isLoggedIn  = false;

// Pagination & sort state
let currentPage     = 1;
let currentPageSize = 25;
let currentSort     = 'desc'; // 'asc' | 'desc'

$(document).ready(function () {
  canAnalyze = $('#canAnalyze').val() === 'true';
  isLoggedIn = $('#isLoggedIn').val() === 'true';

  myNicknames = [
    $('#myLishogiNickname').val(),
    $('#myShogiwarsNickname').val(),
    $('#myDojo81Nickname').val()
  ].filter(function (n) {
    return n && n !== 'lishogi_user' && n !== 'swars_user' && n !== 'dojo81_user';
  });

  // Disable "My Games" toggle if no nicknames configured
  if (!myNicknames.length) {
    $('#myGamesToggle').prop('disabled', true).closest('.form-check').addClass('opacity-50');
  }

  loadGames();

  $('#myGamesToggle').on('change', applyFilters);
  $('input[name="sourceFilter"]').on('change', applyFilters);
  $('input[name="statusFilter"]').on('change', applyFilters);
  $('#searchPlayer').on('input', debounce(applyFilters, 300));
  $('#pageSizeSelector').on('change', function () {
    currentPageSize = parseInt($(this).val(), 10) || 25;
    currentPage = 1;
    loadGames();
  });
  $('#refreshBtn').on('click', function () {
    currentPage = 1;
    loadGames();
  });

  // Sort by date toggle on column header
  $('#colDate').on('click', function () {
    currentSort = currentSort === 'desc' ? 'asc' : 'desc';
    currentPage = 1;
    updateSortIcon();
    loadGames();
  });
});

function updateSortIcon() {
  const icon = $('#sortDateIcon');
  icon.removeClass('bi-sort-down bi-sort-up');
  icon.addClass(currentSort === 'desc' ? 'bi-sort-down' : 'bi-sort-up');
  icon.css('opacity', '.85');
}

// ---------------------------------------------------------------------------
// Data loading
// ---------------------------------------------------------------------------

function loadGames() {
  const i18n = window.i18n || {};
  showTableLoading(i18n['database.loading'] || 'Loading games…');

  const params = {
    page:     currentPage,
    pageSize: currentPageSize,
    source:   $('input[name="sourceFilter"]:checked').val() || 'all',
    status:   $('input[name="statusFilter"]:checked').val() || 'all',
    search:   ($('#searchPlayer').val() || '').trim(),
    myGames:  ($('#myGamesToggle').is(':checked') && myNicknames.length > 0) ? 'true' : 'false',
    sort:     currentSort
  };

  $.get('/database-games', params)
    .done(function (data) {
      if (typeof data === 'string') {
        try { data = JSON.parse(data); } catch (e) { showTableError('Parse error'); return; }
      }
      if (!data || !Array.isArray(data.games)) {
        showTableError(i18n['common.error'] || 'Invalid response');
        return;
      }

      // Update global stat cards from server counts
      if (data.statsTotal    !== undefined) $('#statTotal').text(data.statsTotal);
      if (data.statsAnalyzed !== undefined) {
        $('#statAnalyzed').text(data.statsAnalyzed);
        $('#statPending').text((data.statsTotal || 0) - (data.statsAnalyzed || 0));
      }
      if (data.statsPuzzles  !== undefined) $('#statPuzzles').text(data.statsPuzzles);

      renderTable(data.games, data.total, data.page, data.pageSize);
      renderPagination(data.total, data.page, data.pageSize);
    })
    .fail(function () {
      showTableError(i18n['common.error'] || 'Failed to load games');
    });
}

// ---------------------------------------------------------------------------
// Filtering — reset to page 1 and reload
// ---------------------------------------------------------------------------

function applyFilters() {
  currentPage = 1;
  loadGames();
}

// ---------------------------------------------------------------------------
// Source helpers
// ---------------------------------------------------------------------------

function detectSource(site) {
  if (!site) return 'other';
  const s = site.toLowerCase();
  if (s.includes('lishogi'))                     return 'lishogi';
  if (s.includes('wars'))                        return 'shogiwars';
  if (s.includes('81dojo') || s.includes('dojo')) return 'dojo81';
  return 'other';
}

function sourceBadge(site) {
  const src = detectSource(site);
  switch (src) {
    case 'lishogi':
      return '<span class="badge" style="background:rgba(108,200,127,.25);color:#6cc87f;border:1px solid rgba(108,200,127,.35)">Lishogi</span>';
    case 'shogiwars':
      return '<span class="badge" style="background:rgba(255,193,7,.2);color:#ffc107;border:1px solid rgba(255,193,7,.3)">ShogiWars</span>';
    case 'dojo81':
      return '<span class="badge" style="background:rgba(13,202,240,.2);color:#0dcaf0;border:1px solid rgba(13,202,240,.3)">81Dojo</span>';
    default:
      return site
        ? '<span class="badge bg-secondary bg-opacity-50">' + escHtml(site) + '</span>'
        : '<span class="text-muted">—</span>';
  }
}

// ---------------------------------------------------------------------------
// Table rendering
// ---------------------------------------------------------------------------

function renderTable(games, total, page, pageSize) {
  const i18n  = window.i18n || {};
  const tbody = $('#gamesTableBody');
  tbody.empty();

  const from = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const to   = Math.min(page * pageSize, total);
  const showingText = (i18n['database.showingRange'] || 'Showing {from}–{to} of {total} games')
    .replace('{from}',  from)
    .replace('{to}',    to)
    .replace('{total}', total);
  $('#showingCount').text(showingText);

  const colCount = isLoggedIn ? 6 : 5;
  if (!games.length) {
    tbody.html(
      '<tr><td colspan="' + colCount + '" class="text-center text-muted py-5">' +
      '<i class="bi bi-inbox fs-2 d-block mb-2 opacity-50"></i>' +
      (i18n['database.noGames'] || 'No games found') +
      '</td></tr>'
    );
    return;
  }

  const rows = games.map(function (game) {
    const date = (game.date || '').substring(0, 10).replace(/\//g, '-');

    const statusBadge = game.isAnalyzed
      ? '<span class="badge" style="background:rgba(25,135,84,.2);color:#198754;border:1px solid rgba(25,135,84,.3)">' +
          '<i class="bi bi-check-circle-fill me-1"></i>' + (i18n['database.statusAnalyzed'] || 'Analyzed') + '</span>'
      : '<span class="badge" style="background:rgba(255,193,7,.15);color:#ffc107;border:1px solid rgba(255,193,7,.25)">' +
          '<i class="bi bi-clock me-1"></i>' + (i18n['database.statusPending'] || 'Pending') + '</span>';

    const puzzlesCell = game.puzzleCount > 0
      ? '<a href="/viewer" class="badge bg-primary bg-opacity-75 text-decoration-none" title="' + (i18n['database.btnViewPuzzles'] || 'View Puzzles') + '">' +
          '<i class="bi bi-puzzle me-1"></i>' + game.puzzleCount + '</a>'
      : '<span class="text-muted small">—</span>';

    const viewBtn = '<a href="/view-game/' + escHtml(game.kifHash) + '" ' +
      'class="btn btn-sm btn-outline-info me-1" title="' + (i18n['viewgame.viewBtn'] || 'View Game') + '">' +
      '<i class="bi bi-play-circle"></i></a>';

    const analyzeBtn = !canAnalyze ? '' : game.isAnalyzed
      ? '<button class="btn btn-sm btn-outline-secondary analyze-btn" data-kifhash="' + escHtml(game.kifHash) + '" ' +
          'title="' + (i18n['database.btnReanalyze'] || 'Re-analyze') + '">' +
          '<i class="bi bi-arrow-repeat"></i></button>'
      : '<button class="btn btn-sm btn-outline-primary analyze-btn" data-kifhash="' + escHtml(game.kifHash) + '">' +
          '<i class="bi bi-cpu me-1"></i>' + (i18n['database.btnAnalyze'] || 'Analyze') + '</button>';

    return (
      '<tr data-kifhash="' + escHtml(game.kifHash) + '">' +
      '<td class="text-muted small align-middle text-nowrap">' + escHtml(date) + '</td>' +
      '<td class="align-middle">' +
        '<span class="opacity-50 me-1">☗</span><span class="fw-medium">' + escHtml(game.sente) + '</span>' +
        '<span class="mx-2 text-muted opacity-50">vs</span>' +
        '<span class="opacity-50 me-1">☖</span>' + escHtml(game.gote) +
      '</td>' +
      '<td class="align-middle">' + sourceBadge(game.site) + '</td>' +
      '<td class="align-middle status-cell">' + statusBadge + '</td>' +
      (isLoggedIn ? '<td class="align-middle puzzles-cell">' + puzzlesCell + '</td>' : '') +
      '<td class="align-middle actions-cell text-nowrap">' + viewBtn + analyzeBtn + '</td>' +
      '</tr>'
    );
  });

  tbody.html(rows.join(''));

  tbody.off('click', '.analyze-btn').on('click', '.analyze-btn', function () {
    const kifHash = $(this).data('kifhash');
    triggerAnalysis(kifHash, $(this).closest('tr'));
  });
}

// ---------------------------------------------------------------------------
// Pagination
// ---------------------------------------------------------------------------

// Inline styles to reliably override Bootstrap's CSS custom property defaults
const PG = {
  base:     'display:block;padding:.25rem .5rem;background-color:#1e2124;border:1px solid #495057;color:#adb5bd;line-height:1.5;border-radius:0;text-decoration:none;',
  active:   'display:block;padding:.25rem .5rem;background-color:#0d6efd;border:1px solid #0d6efd;color:#fff;line-height:1.5;border-radius:0;text-decoration:none;font-weight:500;',
  disabled: 'display:block;padding:.25rem .5rem;background-color:#1e2124;border:1px solid #373b3e;color:#4a5057;line-height:1.5;border-radius:0;pointer-events:none;text-decoration:none;',
  ellipsis: 'display:block;padding:.25rem .375rem;background-color:#1e2124;border:1px solid #373b3e;color:#4a5057;line-height:1.5;border-radius:0;'
};

function pgLink(style, dataPage, content, label) {
  const aria = label ? ' aria-label="' + label + '"' : '';
  return '<a href="#" class="page-btn" style="' + style + '" data-page="' + dataPage + '"' + aria + '>' + content + '</a>';
}

function renderPagination(total, page, pageSize) {
  const container  = $('#paginationContainer');
  const totalPages = Math.ceil(total / pageSize);

  if (totalPages <= 1) {
    container.empty();
    return;
  }

  // Window: first, last, and ±2 around current page
  const visible = new Set();
  visible.add(1);
  visible.add(totalPages);
  for (let p = Math.max(1, page - 2); p <= Math.min(totalPages, page + 2); p++) {
    visible.add(p);
  }
  const pages = Array.from(visible).sort(function (a, b) { return a - b; });

  // Round outer corners
  const radius = 'border-radius:.35rem 0 0 .35rem;';
  const radiusR = 'border-radius:0 .35rem .35rem 0;';

  let items = '';

  // Prev arrow
  const prevStyle = PG.base + (page <= 1 ? 'pointer-events:none;color:#4a5057;' : '') + radius;
  items += '<li style="list-style:none;display:inline">' +
    pgLink(prevStyle, page - 1, '<i class="bi bi-chevron-left"></i>', 'Previous') + '</li>';

  // Remove left-radius from first number item since arrow has it
  let prevP = null;
  pages.forEach(function (p, idx) {
    if (prevP !== null && p - prevP > 1) {
      items += '<li style="list-style:none;display:inline"><span style="' + PG.ellipsis + '">…</span></li>';
    }
    const isActive = p === page;
    const isFirst  = idx === 0;
    // First real page item: no extra left-radius (arrow already has it)
    // Last item before Next: no right-radius
    items += '<li style="list-style:none;display:inline">' +
      pgLink(isActive ? PG.active : PG.base, p, String(p)) + '</li>';
    prevP = p;
  });

  // Next arrow
  const nextStyle = PG.base + (page >= totalPages ? 'pointer-events:none;color:#4a5057;' : '') + radiusR;
  items += '<li style="list-style:none;display:inline">' +
    pgLink(nextStyle, page + 1, '<i class="bi bi-chevron-right"></i>', 'Next') + '</li>';

  const html =
    '<nav aria-label="Games pagination">' +
    '<ul style="display:inline-flex;padding:0;margin:0;gap:2px;list-style:none;align-items:center">' +
    items +
    '</ul></nav>';

  container.html(html);

  container.off('click', '.page-btn').on('click', '.page-btn', function (e) {
    e.preventDefault();
    const newPage = parseInt($(this).data('page'), 10);
    if (newPage >= 1 && newPage <= totalPages && newPage !== currentPage) {
      currentPage = newPage;
      loadGames();
      $('html, body').animate({ scrollTop: $('#gamesTable').offset().top - 80 }, 150);
    }
  });
}

// ---------------------------------------------------------------------------
// Analysis triggering
// ---------------------------------------------------------------------------

function triggerAnalysis(kifHash, rowEl) {
  const i18n = window.i18n || {};
  rowEl.find('.actions-cell').html(
    '<span class="spinner-border spinner-border-sm text-primary" role="status"></span>'
  );
  rowEl.find('.status-cell').html(
    '<span class="badge" style="background:rgba(13,202,240,.2);color:#0dcaf0;border:1px solid rgba(13,202,240,.3)">' +
    '<i class="bi bi-hourglass-split me-1"></i>' + (i18n['database.analyzeQueued'] || 'Queued') + '</span>'
  );

  $.ajax({
    url: '/database-analyze',
    method: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({ kifHash: kifHash })
  })
  .done(function (data) {
    if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) {} }
    if (data && data.taskId) {
      pollTaskStatus(data.taskId, rowEl);
    } else {
      showAnalysisError(rowEl, (data && data.error) || (i18n['database.analyzeError'] || 'Error'));
    }
  })
  .fail(function (xhr) {
    let msg = i18n['database.analyzeError'] || 'Failed to start analysis';
    try { const r = JSON.parse(xhr.responseText); if (r.error) msg = r.error; } catch (e) {}
    showAnalysisError(rowEl, msg);
  });
}

function pollTaskStatus(taskId, rowEl) {
  if (taskPollers[taskId]) return;

  taskPollers[taskId] = setInterval(function () {
    $.get('/maintenance-task-status', { id: taskId })
      .done(function (data) {
        if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) {} }
        if (data && (data.status === 'done' || data.status === 'error')) {
          clearInterval(taskPollers[taskId]);
          delete taskPollers[taskId];
          if (data.status === 'done') {
            loadGames();
          } else {
            showAnalysisError(rowEl, 'Analysis failed');
          }
        }
      });
  }, 3000);
}

function showAnalysisError(rowEl, msg) {
  rowEl.find('.actions-cell').html('<span class="text-danger small">' + escHtml(msg) + '</span>');
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function showTableLoading(msg) {
  const colCount = isLoggedIn ? 6 : 5;
  $('#gamesTableBody').html(
    '<tr><td colspan="' + colCount + '" class="text-center py-5">' +
    '<div class="d-flex align-items-center justify-content-center gap-2">' +
    '<div class="spinner-border spinner-border-sm text-primary" role="status"></div>' +
    '<span class="text-muted">' + escHtml(msg) + '</span>' +
    '</div></td></tr>'
  );
  $('#paginationContainer').empty();
}

function showTableError(msg) {
  const colCount = isLoggedIn ? 6 : 5;
  $('#gamesTableBody').html(
    '<tr><td colspan="' + colCount + '" class="text-center py-4 text-danger">' + escHtml(msg) + '</td></tr>'
  );
  $('#paginationContainer').empty();
}

function escHtml(str) {
  if (str == null) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function debounce(fn, delay) {
  let t;
  return function () { clearTimeout(t); t = setTimeout(fn, delay); };
}
