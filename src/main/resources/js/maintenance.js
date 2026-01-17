let analysisChart = null;

$(document).on('click', '.graph-btn', function() {
  const hash = $(this).data('hash');
  const senteName = $(this).data('sente');
  const goteName = $(this).data('gote');
  $('#graphTitle').text('Analysis: ' + senteName + ' vs ' + goteName);
  
  $.get('/maintenance-analysis-data?hash=' + hash, function(data) {
    const ctx = document.getElementById('analysisChart').getContext('2d');
    
    if (analysisChart) {
      analysisChart.destroy();
    }
    
    const transform = (s) => s / (Math.abs(s) + 1000);
    const scorePoints = data.scores.map((s, i) => ({
      x: i,
      y: transform(s)
    }));
    const puzzlePoints = data.puzzles.map(p => ({ 
      x: p.ply, 
      y: transform(data.scores[p.ply]),
      puzzleId: p.id
    }));

    analysisChart = new Chart(ctx, {
      type: 'line',
      data: {
        datasets: [{
          label: 'Score',
          data: scorePoints,
          borderColor: 'rgb(75, 192, 192)',
          backgroundColor: 'rgba(75, 192, 192, 0.2)',
          fill: true,
          tension: 0,
          pointRadius: 3,
          pointHitRadius: 10
        }, {
          label: 'Puzzles',
          data: puzzlePoints,
          borderColor: 'rgb(255, 99, 132)',
          backgroundColor: 'rgb(255, 99, 132)',
          pointRadius: 8,
          pointHitRadius: 15,
          showLine: false
        }]
      },
      options: {
        maintainAspectRatio: false,
        interaction: {
          mode: 'nearest',
          axis: 'x',
          intersect: false,
        },
        scales: {
          x: {
            type: 'linear',
            title: {
              display: true,
              text: 'Move'
            },
            ticks: {
              stepSize: 1,
              precision: 0
            }
          },
          y: {
            title: {
              display: true,
              text: '▲ ' + senteName + ' vs ▽ ' + goteName,
              font: {
                size: 16,
                weight: 'bold'
              }
            },
            min: -1.05,
            max: 1.05,
            ticks: {
              stepSize: 0.1,
              callback: function(value) {
                if (Math.abs(value) < 0.01) return '0';
                if (value > 0.99) return '▲ Mate';
                if (value < -0.99) return '▽ Mate';
                const s = 1000 * value / (1 - Math.abs(value));
                const absS = Math.abs(s);
                const prefix = s > 0 ? '▲' : '▽';
                if (absS < 1000) return prefix + Math.round(absS / 100) * 100;
                if (absS < 5000) return prefix + Math.round(absS / 500) * 500;
                return prefix + Math.round(absS / 1000) * 1000;
              }
            },
            grid: {
              color: function(context) {
                if (context.tick.value === 0) return '#000';
                return 'rgba(0, 0, 0, 0.1)';
              }
            }
          }
        },
        plugins: {
          tooltip: {
            callbacks: {
              title: function(tooltipItems) {
                const x = tooltipItems[0].raw.x;
                return x === 0 ? 'Start' : 'Move ' + x;
              },
              label: function(context) {
                const dataIndex = context.raw.x;
                const s = data.scores[dataIndex];
                if (s === undefined) return '';
                
                let scoreStr = '';
                if (Math.abs(s) > 15000) {
                  const moves = 30000 - Math.abs(s);
                  scoreStr = (s > 0 ? '▲ Mate in ' : '▽ Mate in ') + moves;
                } else {
                  scoreStr = (s > 0 ? '▲' : '▽') + Math.abs(s);
                }

                if (context.datasetIndex === 0) {
                   const hasPuzzle = data.puzzles.some(p => p.ply === dataIndex);
                   return 'Score: ' + scoreStr + (hasPuzzle ? ' (Puzzle!)' : '');
                } else {
                   const p = data.puzzles.find(pz => pz.ply === dataIndex);
                   let res = ['Puzzle here!', 'Score: ' + scoreStr];
                   if (p && p.comment) {
                      res.push('---');
                      res = res.concat(p.comment.split('\n'));
                   }
                   return res;
                }
              }
            }
          }
        }
      }
    });
    
    const modal = new bootstrap.Modal(document.getElementById('graphModal'));
    modal.show();
  });
});

$(document).on('click', '.delete-analysis-btn', function() {
  if (!confirm('Are you sure you want to delete analysis results?')) return;
  const hash = $(this).data('hash');
  const player = $(this).data('player');
  const $btn = $(this);
  $btn.prop('disabled', true).text('Deleting...');
  
  $.ajax({
    url: '/maintenance-delete-analysis',
    type: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({ hash: hash }),
    success: function() {
      const lishogiName = $('#lishogiNickname').val();
      const shogiwarsName = $('#shogiwarsNickname').val();
      const dojo81Name = $('#dojo81Nickname').val();
      if (lishogiName) window.maintenance.doFetch('lishogi', lishogiName, false);
      if (shogiwarsName) window.maintenance.doFetch('shogiwars', shogiwarsName, false);
      if (dojo81Name) window.maintenance.doFetch('dojo81', dojo81Name, false);
    },
    error: function(xhr) {
      alert('Error deleting analysis: ' + xhr.statusText);
      $btn.prop('disabled', false).text('Delete Analysis');
    }
  });
});
window.maintenance = {
  isFetchingLishogi: false,
  isFetchingShogiwars: false,
  isFetchingDojo81: false,
  autoFetched: false,
  doFetch: function(source, name, force = false) {
    if (!name || name.trim() === '') {
      alert('Please enter a nickname for ' + source);
      return;
    }
    const isLishogi = source === 'lishogi';
    const isShogiwars = source === 'shogiwars';
    const isDojo81 = source === 'dojo81';
    
    let maxGamesId = '#lishogiMaxGames';
    if (isShogiwars) maxGamesId = '#shogiwarsMaxGames';
    else if (isDojo81) maxGamesId = '#dojo81MaxGames';
    
    const maxGames = $(maxGamesId).val() || 10;
    
    let resultsId = '#lishogi-results';
    if (isShogiwars) resultsId = '#shogiwars-results';
    else if (isDojo81) resultsId = '#dojo81-results';
    
    const $results = $(resultsId);
    const self = this;
    
    if (isLishogi) {
      if (this.isFetchingLishogi) return;
      this.isFetchingLishogi = true;
    } else if (isShogiwars) {
      if (this.isFetchingShogiwars) return;
      this.isFetchingShogiwars = true;
    } else if (isDojo81) {
      if (this.isFetchingDojo81) return;
      this.isFetchingDojo81 = true;
    }
    
    $results.html('<div class="d-flex align-items-center"><div class="spinner-border spinner-border-sm text-primary me-2" role="status"></div> <span>Fetching games for ' + name + '...</span></div>');
    
    $.get('/maintenance-fetch?player=' + encodeURIComponent(name) + '&source=' + source + '&force=' + force + '&limit=' + maxGames)
      .done(function(html) {
        $results.html(html);
      })
      .fail(function(xhr) {
        $results.html('<div class="alert alert-danger">Error: ' + xhr.statusText + '</div>');
      })
      .always(function() {
        if (isLishogi) self.isFetchingLishogi = false;
        else if (isShogiwars) self.isFetchingShogiwars = false;
        else if (isDojo81) self.isFetchingDojo81 = false;
      });
  }
};

$(document).on('click', '#storeBtn', function() {
  const games = [];
  $('.game-check:checked').each(function() {
    games.push({
      sente: $(this).data('sente'),
      gote: $(this).data('gote'),
      date: $(this).data('date'),
      kif: $(this).data('kif'),
      site: $(this).data('site')
    });
  });
  
  if (games.length === 0) return;
  
  const $btn = $(this);
  $btn.prop('disabled', true).text('Storing...');
  
  $.ajax({
    url: '/maintenance-store',
    type: 'POST',
    contentType: 'application/json',
    data: JSON.stringify(games),
    success: function(res) {
      alert('Stored ' + res.stored + ' games, ' + res.duplicates + ' duplicates skipped.');
      const lishogiName = $('#lishogiNickname').val();
      const shogiwarsName = $('#shogiwarsNickname').val();
      const dojo81Name = $('#dojo81Nickname').val();
      if (lishogiName) window.maintenance.doFetch('lishogi', lishogiName, false);
      if (shogiwarsName) window.maintenance.doFetch('shogiwars', shogiwarsName, false);
      if (dojo81Name) window.maintenance.doFetch('dojo81', dojo81Name, false);
    },
    error: function(xhr) {
      alert('Error storing games: ' + xhr.statusText);
      $btn.prop('disabled', false).text('Download Selected to DB');
    }
  });
});

$(document).on('click', '.analyze-btn', function() {
  const kif = $(this).data('kif');
  const player = $(this).data('player');
  const source = $(this).data('site') || 'unknown';
  const $btn = $(this);
  $btn.prop('disabled', true).text('Analyzing...');
  
  console.log('Sending analysis request for', player, 'from', source);
  
  $.ajax({
    url: '/maintenance-analyze',
    type: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({ 
      kif: kif, 
      player: player,
      source: source
    }),
    success: function(res) {
      console.log('Analysis response:', res);
      // Backend returns a string for corsResponse sometimes, or JSON
      // If it's a success string from corsResponse(s"Analysis complete...")
      alert('Analysis complete!');
      
      // Find the row and update it instead of full refresh if possible, 
      // but full refresh is safer for now to ensure state consistency.
      const lishogiName = $('#lishogiNickname').val();
      const shogiwarsName = $('#shogiwarsNickname').val();
      const dojo81Name = $('#dojo81Nickname').val();
      
      if (lishogiName) window.maintenance.doFetch('lishogi', lishogiName, false);
      if (shogiwarsName) window.maintenance.doFetch('shogiwars', shogiwarsName, false);
      if (dojo81Name) window.maintenance.doFetch('dojo81', dojo81Name, false);
    },
    error: function(xhr) {
      console.error('Analysis error:', xhr.status, xhr.responseText);
      alert('Error analyzing game: ' + (xhr.responseText || xhr.statusText));
      $btn.prop('disabled', false).text('Analyze');
    }
  });
});
$(document).ready(function() {
  if (!window.maintenance.autoFetched) {
    window.maintenance.autoFetched = true;
    const lishogiName = $('#lishogiNicknameInput').val();
    const shogiwarsName = $('#shogiwarsNicknameInput').val();
    const dojo81Name = $('#dojo81NicknameInput').val();
    
    if (lishogiName && lishogiName !== 'lishogi_user') window.maintenance.doFetch('lishogi', lishogiName, false);
    if (shogiwarsName && shogiwarsName !== 'swars_user') window.maintenance.doFetch('shogiwars', shogiwarsName, false);
    if (dojo81Name && dojo81Name !== 'dojo81_user') window.maintenance.doFetch('dojo81', dojo81Name, false);
  }
});
