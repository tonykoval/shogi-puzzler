/**
 * Games Page JavaScript
 * Handles fetching shogi game records from external sources
 */

$(document).ready(function() {
  // Handle fetch button click
  $('#fetchGamesBtn').on('click', function() {
    fetchGames();
  });

  // Allow pressing Enter in the nickname input
  $('#gamesNickname').on('keypress', function(e) {
    if (e.which === 13) {
      fetchGames();
    }
  });

  // KIF upload
  $('#uploadKifBtn').on('click', uploadKif);
  $('#kifFileInput').on('change', function() {
    $('#uploadKifBtn').prop('disabled', !this.files || !this.files.length);
    $('#upload-results').empty();
  });

  // Update nickname field and clear results when switching source
  $('input[name="sourceGroup"]').on('change', function() {
    const source = $(this).val();
    const placeholders = { lishogi: 'lishogi_user', shogiwars: 'swars_user', dojo81: 'dojo81_user' };
    const nicknameMap = {
      lishogi:   $('#lishogiNickname').val(),
      shogiwars: $('#shogiwarsNickname').val(),
      dojo81:    $('#dojo81Nickname').val()
    };
    const nick = nicknameMap[source] || '';
    const placeholder = placeholders[source] || '';
    $('#gamesNickname').val(nick === placeholder ? '' : nick);
    $('#games-results').empty();
  });
});

function fetchGames() {
  const i18n = window.i18n || {};
  
  // Get input values
  const nickname = $('#gamesNickname').val().trim();
  const source = $('input[name="sourceGroup"]:checked').val() || 'lishogi';
  const limit = parseInt($('#gamesLimit').val()) || 10;

  // Validate input
  if (!nickname) {
    showError(i18n['games.nicknamePlaceholder'] || 'Please enter a player nickname');
    return;
  }

  // Show loading state
  showLoading();

  // Make the fetch request
  $.get('/games-fetch', {
    player: nickname,
    source: source,
    limit: limit
  })
  .done(function(data) {
    if (typeof data === 'string') {
      try {
        data = JSON.parse(data);
      } catch (e) {
        showError(i18n['common.error'] || 'Error parsing response');
        return;
      }
    }

    if (data.error) {
      showError(data.error);
    } else {
      showResults(data);
    }
  })
  .fail(function(xhr) {
    let errorMsg = i18n['games.networkError'] || 'Network error while fetching games';
    try {
      const response = JSON.parse(xhr.responseText);
      if (response.error) {
        errorMsg = response.error;
      }
    } catch (e) {
      // Use default error message
    }
    showError(errorMsg);
  });
}

function showLoading() {
  const i18n = window.i18n || {};
  const loadingHtml = 
    '<div class="d-flex align-items-center">' +
      '<div class="spinner-border spinner-border-sm text-primary me-2" role="status"></div>' +
      ' <span>' + (i18n['common.loading'] || 'Loading...') + '</span>' +
    '</div>';
  
  $('#games-results').html(loadingHtml);
  $('#fetchGamesBtn').prop('disabled', true);
}

function showResults(data) {
  const i18n = window.i18n || {};
  
  // Re-enable the button
  $('#fetchGamesBtn').prop('disabled', false);

  // Build the results HTML
  let resultsHtml = '<div class="alert ';
  
  if (data.newGames > 0) {
    resultsHtml += 'alert-success">';
    resultsHtml += '<h5 class="alert-heading">' + (i18n['common.success'] || 'Success') + '</h5>';
    resultsHtml += '<p class="mb-0">' + 
      (i18n['games.success'] || 'Successfully stored {count} new games')
        .replace('{count}', data.newGames) + 
      '</p>';
  } else if (data.fetched === 0) {
    resultsHtml += 'alert-warning">';
    resultsHtml += '<p class="mb-0">' + (i18n['games.noNewGames'] || 'No new games available for this player') + '</p>';
  } else {
    resultsHtml += 'alert-info">';
    resultsHtml += '<p class="mb-0">' + (i18n['games.allExisting'] || 'All fetched games already exist in the database') + '</p>';
  }
  
  resultsHtml += '</div>';
  
  // Add summary statistics
  resultsHtml += '<div class="mt-3">';
  resultsHtml += '<h6>' + (i18n['games.storedCount'] || 'Games in database') + '</h6>';
  resultsHtml += '<div class="d-flex gap-4">';
  resultsHtml += '<div>';
  resultsHtml += '<span class="fs-4 fw-bold">' + data.stored + '</span>';
  resultsHtml += '<span class="text-muted ms-1">' + (i18n['games.totalStored'] || 'Total in DB').replace('{count}', data.stored) + '</span>';
  resultsHtml += '</div>';
  resultsHtml += '<div>';
  resultsHtml += '<span class="fs-4 fw-bold">' + data.newGames + '</span>';
  resultsHtml += '<span class="text-muted ms-1">' + (i18n['games.fetchedCount'] || 'Fetched this session') + '</span>';
  resultsHtml += '</div>';
  resultsHtml += '</div>';
  resultsHtml += '</div>';

  $('#games-results').html(resultsHtml);
}

function showError(message) {
  const i18n = window.i18n || {};
  
  // Re-enable the button
  $('#fetchGamesBtn').prop('disabled', false);

  const errorHtml = 
    '<div class="alert alert-danger">' +
      '<h5 class="alert-heading">' + (i18n['common.error'] || 'Error') + '</h5>' +
      '<p class="mb-0">' + message + '</p>' +
    '</div>';
  
  $('#games-results').html(errorHtml);
}

// ---------------------------------------------------------------------------
// KIF file upload
// ---------------------------------------------------------------------------

function uploadKif() {
  const i18n = window.i18n || {};
  const fileInput = document.getElementById('kifFileInput');
  if (!fileInput || !fileInput.files || !fileInput.files.length) {
    showUploadError(i18n['games.uploadNoFile'] || 'No file selected');
    return;
  }

  const file = fileInput.files[0];
  const reader = new FileReader();

  reader.onload = function (e) {
    const content = e.target.result;

    // Show loading
    $('#upload-results').html(
      '<div class="d-flex align-items-center gap-2 text-muted small">' +
      '<div class="spinner-border spinner-border-sm text-primary" role="status"></div>' +
      (i18n['common.loading'] || 'Loading…') + '</div>'
    );
    $('#uploadKifBtn').prop('disabled', true);

    $.ajax({
      url: '/games-upload-kif',
      method: 'POST',
      contentType: 'text/plain; charset=utf-8',
      data: content
    })
    .done(function (data) {
      if (typeof data === 'string') { try { data = JSON.parse(data); } catch (e) {} }
      $('#uploadKifBtn').prop('disabled', false);
      if (data && data.success) {
        showUploadSuccess(data);
        // Reset file input after successful upload
        fileInput.value = '';
        $('#uploadKifBtn').prop('disabled', true);
      } else {
        showUploadError(
          (data && data.error) || (i18n['games.uploadInvalid'] || 'Upload failed'),
          data && data.duplicate
        );
      }
    })
    .fail(function (xhr) {
      $('#uploadKifBtn').prop('disabled', false);
      let msg = i18n['games.uploadInvalid'] || 'Upload failed';
      try { const r = JSON.parse(xhr.responseText); if (r.error) msg = r.error; } catch (ex) {}
      showUploadError(msg, xhr.status === 409);
    });
  };

  reader.onerror = function () {
    showUploadError(i18n['games.uploadReadError'] || 'Could not read file');
  };

  reader.readAsText(file, 'UTF-8');
}

function showUploadSuccess(data) {
  const i18n = window.i18n || {};
  const sente = data.sente || '?';
  const gote  = data.gote  || '?';
  const date  = data.date  || '';
  const site  = data.site  || '';
  const moves = data.moves || 0;

  let html =
    '<div class="alert alert-success">' +
    '<div class="fw-semibold mb-1"><i class="bi bi-check-circle-fill me-1"></i>' +
    (i18n['games.uploadSuccess'] || 'Game added to database') + '</div>' +
    '<div class="small text-success-emphasis">' +
    '<span class="opacity-75 me-1">☗</span>' + escUpload(sente) +
    '<span class="mx-2 opacity-50">vs</span>' +
    '<span class="opacity-75 me-1">☖</span>' + escUpload(gote);
  if (date) html += '<span class="ms-3 opacity-75">' + escUpload(date) + '</span>';
  if (site && site !== 'upload') html += '<span class="ms-2 opacity-75">· ' + escUpload(site) + '</span>';
  html += '<span class="ms-3 opacity-75">' + moves + ' ' + (i18n['games.uploadMoves'] || 'moves') + '</span>';
  html += '</div></div>';

  $('#upload-results').html(html);
}

function showUploadError(msg, isDuplicate) {
  const i18n = window.i18n || {};
  const icon = isDuplicate ? 'bi-copy' : 'bi-exclamation-triangle-fill';
  const alertClass = isDuplicate ? 'alert-warning' : 'alert-danger';
  $('#upload-results').html(
    '<div class="alert ' + alertClass + '">' +
    '<i class="bi ' + icon + ' me-1"></i>' + escUpload(msg) +
    '</div>'
  );
}

function escUpload(s) {
  if (s == null) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
