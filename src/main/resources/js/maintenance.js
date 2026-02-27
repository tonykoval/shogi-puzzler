let analysisChart = null;

$(document).on('click', '.graph-btn', function() {
  const hash = $(this).data('hash');
  const senteName = $(this).data('sente');
  const goteName = $(this).data('gote');
  const i18n = window.i18n || {};
  $('#graphTitle').text((i18n['maintenance.graphTitle'] || 'Analysis') + ': ' + senteName + ' ' + (i18n['common.vs'] || 'vs') + ' ' + goteName);
  
  $.get('/maintenance-analysis-data?hash=' + hash, function(data) {
    if (typeof data === 'string') {
        try {
            data = JSON.parse(data);
        } catch (e) {
            console.error("Failed to parse analysis data", e);
        }
    }
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
          label: i18n['chart.scoreLabel'] || 'Score',
          data: scorePoints,
          borderColor: 'rgb(75, 192, 192)',
          backgroundColor: 'rgba(75, 192, 192, 0.2)',
          fill: true,
          tension: 0,
          pointRadius: 3,
          pointHitRadius: 10
        }, {
          label: i18n['chart.puzzlesLabel'] || 'Puzzles',
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
              text: i18n['chart.move'] || 'Move'
            },
            ticks: {
              stepSize: 1,
              precision: 0
            }
          },
          y: {
            title: {
              display: true,
              text: '▲ ' + senteName + ' ' + (i18n['common.vs'] || 'vs') + ' ▽ ' + goteName,
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
                const i18n = window.i18n || {};
                if (Math.abs(value) < 0.01) return '0';
                if (value > 0.99) return '▲ ' + (i18n['chart.mateIn'] || 'Mate');
                if (value < -0.99) return '▽ ' + (i18n['chart.mateIn'] || 'Mate');
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
                const i18n = window.i18n || {};
                const x = tooltipItems[0].raw.x;
                return x === 0 ? (i18n['chart.start'] || 'Start') : (i18n['chart.move'] || 'Move') + ' ' + x;
              },
              label: function(context) {
                const i18n = window.i18n || {};
                const dataIndex = context.raw.x;
                const s = data.scores[dataIndex];
                if (s === undefined) return '';
                
                let scoreStr = '';
                const mateIn = i18n['chart.mateIn'] || 'Mate in';
                if (Math.abs(s) > 15000) {
                  const moves = 30000 - Math.abs(s);
                  scoreStr = (s > 0 ? '▲ ' + mateIn + ' ' : '▽ ' + mateIn + ' ') + moves;
                } else {
                  scoreStr = (s > 0 ? '▲' : '▽') + Math.abs(s);
                }

                if (context.datasetIndex === 0) {
                   const hasPuzzle = data.puzzles.some(p => p.ply === dataIndex);
                   return (i18n['chart.score'] || 'Score') + ': ' + scoreStr + (hasPuzzle ? ' ' + (i18n['chart.puzzleIndicator'] || '(Puzzle!)') : '');
                } else {
                   const p = data.puzzles.find(pz => pz.ply === dataIndex);
                   let res = [(i18n['chart.puzzleHere'] || 'Puzzle here!'), (i18n['chart.score'] || 'Score') + ': ' + scoreStr];
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
  const hash = $(this).data('hash');
  const player = $(this).data('player');
  const $btn = $(this);
  const i18n = window.i18n || {};

  function doDelete() {
    $btn.prop('disabled', true).text(i18n['status.deleting'] || 'Deleting...');
    $.ajax({
      url: '/maintenance-delete-analysis',
      type: 'POST',
      contentType: 'application/json',
      data: JSON.stringify({ hash: hash }),
      success: function() {
        const keysToRemove = [];
        for (let i = 0; i < localStorage.length; i++) {
          const key = localStorage.key(i);
          if (key && (key.startsWith('maintenance_games_') || key.startsWith('puzzles_'))) {
            keysToRemove.push(key);
          }
        }
        keysToRemove.forEach(key => localStorage.removeItem(key));

        const lishogiName = $('#lishogiNickname').val();
        const shogiwarsName = $('#shogiwarsNickname').val();
        const dojo81Name = $('#dojo81Nickname').val();
        if (lishogiName && lishogiName !== 'lishogi_user') window.maintenance.doFetch('lishogi', lishogiName, false);
        if (shogiwarsName && shogiwarsName !== 'swars_user') window.maintenance.doFetch('shogiwars', shogiwarsName, false);
        if (dojo81Name && dojo81Name !== 'dojo81_user') window.maintenance.doFetch('dojo81', dojo81Name, false);
      },
      error: function(xhr) {
        alert((i18n['status.errorDeleting'] || 'Error deleting analysis:') + ' ' + xhr.statusText);
        $btn.prop('disabled', false).text(i18n['status.deleteAnalysis'] || 'Delete Analysis');
      }
    });
  }

  // Fetch puzzle stats before confirming
  $.get('/maintenance-puzzle-stats?hash=' + hash)
    .done(function(stats) {
      if (typeof stats === 'string') {
        try { stats = JSON.parse(stats); } catch (e) { stats = null; }
      }
      if (stats && stats.total > 0) {
        const parts = [];
        if (stats.accepted > 0) parts.push(stats.accepted + ' ' + (i18n['maintenance.accepted'] || 'accepted'));
        if (stats.review > 0) parts.push(stats.review + ' ' + (i18n['maintenance.inReview'] || 'in review'));
        if (stats.regular > 0) parts.push(stats.regular + ' ' + (i18n['maintenance.regular'] || 'regular'));
        const msg = (i18n['status.confirmDeletePuzzles'] || 'This will delete ALL {total} puzzle(s) ({accepted} accepted, {review} in review, {regular} regular) and analysis data. Are you sure?')
          .replace('{total}', stats.total)
          .replace('{accepted}', stats.accepted)
          .replace('{review}', stats.review)
          .replace('{regular}', stats.regular);
        if (!confirm(msg)) return;
      } else {
        if (!confirm(i18n['status.confirmDeleteAnalysis'] || 'Are you sure you want to delete analysis results?')) return;
      }
      doDelete();
    })
    .fail(function() {
      if (!confirm(i18n['status.confirmDeleteAnalysis'] || 'Are you sure you want to delete analysis results?')) return;
      doDelete();
    });
});
window.maintenance = {
  isFetchingLishogi: false,
  isFetchingShogiwars: false,
  isFetchingDojo81: false,
  autoFetched: false,
  activePolls: {},
  
  pollTask: function(taskId, $results, onComplete, onError, onProgress, taskType = 'fetch') {
    if (typeof taskId === 'object' && taskId !== null && taskId.taskId) {
        taskId = taskId.taskId;
    }
    if (!taskId) {
        console.warn('pollTask called without taskId');
        return;
    }
    if (this.activePolls[taskId]) {
        return;
    }
    this.activePolls[taskId] = true;

    const self = this;
    const activeTasks = JSON.parse(localStorage.getItem('activeTasks') || '{}');
    if (taskId && !activeTasks[taskId]) {
      activeTasks[taskId] = { type: taskType, startedAt: Date.now() };
      localStorage.setItem('activeTasks', JSON.stringify(activeTasks));
    }


    const poll = () => {
      $.get('/maintenance-task-status?id=' + taskId)
        .done(function(task) {
          if (typeof task === 'string') {
            try {
              task = JSON.parse(task);
            } catch (e) {
              console.error('Failed to parse task status:', e, task);
              if (onError) onError('Invalid response from server');
              return;
            }
          }
          if (task.error) {
            console.error('Task error from backend:', taskId, task.error);
            if (onError) onError(task.error);
            return;
          }
          if (task.status === 'running' || task.status === 'waiting') {
            if (onProgress) onProgress(task.message);
            else if ($results && $results.length > 0) {
               $results.find('.status-message').text(task.message);
            }
            
            // Periodically re-check if button exists to disable it if it was just rendered
            const currentActive = JSON.parse(localStorage.getItem('activeTasks') || '{}');
            if (currentActive[taskId] && currentActive[taskId].kifHash) {
               const kifHash = currentActive[taskId].kifHash;
               const $btns = $('.btn-task-' + kifHash);
               if ($btns.length > 0 && (!$btns.prop('disabled') || $btns.find('.spinner-border').length === 0)) {
                  $btns.prop('disabled', true);
                  if ($btns.find('.btn-text').length > 0) {
                    $btns.find('.btn-text').text(task.message);
                  } else {
                    $btns.html('<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span> <span class="btn-text">' + task.message + '</span>');
                  }
               }
            }

            setTimeout(poll, 1000);
          } else {
            delete self.activePolls[taskId];
            // Remove from active tasks
            const currentActive = JSON.parse(localStorage.getItem('activeTasks') || '{}');
            delete currentActive[taskId];
            localStorage.setItem('activeTasks', JSON.stringify(currentActive));

            if (task.status === 'completed') {
              if (onComplete) {
                onComplete(task.resultHtml);
              } else {
                console.warn('Task completed but no onComplete callback for taskId:', taskId);
              }
            } else if (task.status === 'failed') {
              if (onError) onError(task.error || 'Unknown error');
            }
          }
        })
        .fail(function(xhr) {
          console.error('Polling failed for task:', taskId, xhr.status, xhr.statusText);
          delete self.activePolls[taskId];
          if (onError) onError('Failed to poll task status: ' + xhr.statusText);
        });
    };
    poll();
  },

  doFetch: function(source, name, force = false) {
    const i18n = window.i18n || {};
    if (!name || name.trim() === '') {
      alert((i18n['status.pleaseEnterNickname'] || 'Please enter a nickname for {source}').replace('{source}', source));
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
    if ($results.length === 0) {
      console.error('Results container not found:', resultsId);
    }
    const self = this;

    const cacheKey = 'maintenance_games_' + source + '_' + name + '_' + maxGames;

    if (!force) {
      const cachedHtml = localStorage.getItem(cacheKey);
      if (cachedHtml) {
        $results.html(cachedHtml);
        return;
      }
    }
    
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
    
    // Check if there is already an active task for this source/name
    const activeTasks = JSON.parse(localStorage.getItem('activeTasks') || '{}');
    const existingTaskId = Object.keys(activeTasks).find(tid => {
      const t = activeTasks[tid];
      return t.type === 'fetch' && t.source === source && t.name === name;
    });

    if (existingTaskId) {
      // It will be resumed by the resume logic in ready() or it's already polling
      return;
    }
    
    $results.html('<div class="d-flex align-items-center"><div class="spinner-border spinner-border-sm text-primary me-2" role="status"></div> <span class="status-message">' + (i18n['status.fetchingGames'] || 'Fetching games for {name}...').replace('{name}', name) + '</span></div>');
    
    $.get('/maintenance-fetch?player=' + encodeURIComponent(name) + '&source=' + source + '&force=' + force + '&limit=' + maxGames)
      .done(function(data) {
        let taskId;
        try {
          if (typeof data === 'string') {
            const parsed = JSON.parse(data);
            taskId = parsed.taskId;
          } else if (typeof data === 'object' && data !== null) {
            taskId = data.taskId;
          }
          
          if (!taskId) {
            // If we don't have a taskId, maybe it's the result HTML directly
            if ($results.length > 0) {
                $results.html(data);
                localStorage.setItem(cacheKey, typeof data === 'string' ? data : JSON.stringify(data));
            }
            if (isLishogi) self.isFetchingLishogi = false;
            else if (isShogiwars) self.isFetchingShogiwars = false;
            else if (isDojo81) self.isFetchingDojo81 = false;
            return;
          }
        } catch(e) {
          console.error('Error parsing fetch response:', e, data);
          if ($results.length > 0) {
              $results.html(data);
              localStorage.setItem(cacheKey, data);
          }
          if (isLishogi) self.isFetchingLishogi = false;
          else if (isShogiwars) self.isFetchingShogiwars = false;
          else if (isDojo81) self.isFetchingDojo81 = false;
          return;
        }
        
        const activeTasks = JSON.parse(localStorage.getItem('activeTasks') || '{}');
        activeTasks[taskId] = { 
          type: 'fetch', 
          startedAt: Date.now(), 
          source: source,
          name: name,
          maxGames: maxGames
        };
        localStorage.setItem('activeTasks', JSON.stringify(activeTasks));
        
        self.pollTask(taskId, $results, 
          function(html) { // complete
            $(resultsId).html(html);
            try {
              localStorage.setItem(cacheKey, html);
            } catch (e) {
              console.warn('Failed to save to localStorage:', e);
            }
            if (isLishogi) self.isFetchingLishogi = false;
            else if (isShogiwars) self.isFetchingShogiwars = false;
            else if (isDojo81) self.isFetchingDojo81 = false;
          },
          function(error) { // error
            $results.html('<div class="alert alert-danger">Error: ' + error + '</div>');
            if (isLishogi) self.isFetchingLishogi = false;
            else if (isShogiwars) self.isFetchingShogiwars = false;
            else if (isDojo81) self.isFetchingDojo81 = false;
          },
          null,
          'fetch'
        );
      })
      .fail(function(xhr) {
        $results.html('<div class="alert alert-danger">Error: ' + xhr.statusText + '</div>');
        if (isLishogi) self.isFetchingLishogi = false;
        else if (isShogiwars) self.isFetchingShogiwars = false;
        else if (isDojo81) self.isFetchingDojo81 = false;
      });
  },
  
  filterGames: function(filterType, containerId) {
    const $container = $('#' + containerId);
    const $rows = $container.find('tbody tr');
    
    $rows.each(function() {
      const $row = $(this);
      const status = $row.data('status');
      
      if (filterType === 'all') {
        $row.show();
      } else if (filterType === 'analyzed') {
        if (status === 'analyzed') $row.show();
        else $row.hide();
      } else if (filterType === 'indb') {
        if (status === 'indb' || status === 'analyzed') $row.show();
        else $row.hide();
      }
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
  $btn.prop('disabled', true).text((window.i18n && window.i18n['status.storing']) || 'Storing...');
  
  $.ajax({
    url: '/maintenance-store',
    type: 'POST',
    contentType: 'application/json',
    data: JSON.stringify(games),
    success: function(res) {
      alert('Stored ' + res.stored + ' games, ' + res.duplicates + ' duplicates skipped.');
      
      // Invalidate maintenance games cache after successful storage to show 'Stored' status correctly
      const keysToRemove = [];
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key && (key.startsWith('maintenance_games_') || key.startsWith('puzzles_'))) {
          keysToRemove.push(key);
        }
      }
      keysToRemove.forEach(key => localStorage.removeItem(key));

      const lishogiName = $('#lishogiNickname').val();
      const shogiwarsName = $('#shogiwarsNickname').val();
      const dojo81Name = $('#dojo81Nickname').val();
      if (lishogiName && lishogiName !== 'lishogi_user') window.maintenance.doFetch('lishogi', lishogiName, false);
      if (shogiwarsName && shogiwarsName !== 'swars_user') window.maintenance.doFetch('shogiwars', shogiwarsName, false);
      if (dojo81Name && dojo81Name !== 'dojo81_user') window.maintenance.doFetch('dojo81', dojo81Name, false);
    },
    error: function(xhr) {
      alert('Error storing games: ' + xhr.statusText);
      $btn.prop('disabled', false).text((window.i18n && window.i18n['maintenance.downloadSelected']) || 'Download Selected to DB');
    }
  });
});

$(document).on('click', '.analyze-btn', function() {
  const kif = $(this).data('kif');
  const player = $(this).data('player');
  const source = $(this).data('site') || 'unknown';
  
  // Use same hash logic as backend
  let hash = 0;
  for (let i = 0; i < kif.length; i++) {
    hash = ((31 * hash) + kif.charCodeAt(i)) | 0;
  }
  const kifHash = Math.abs(hash).toString(16);
  
  const $btn = $(this);
  $btn.addClass('btn-task-' + kifHash);
  $btn.prop('disabled', true).html('<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span> <span class="btn-text">' + ((window.i18n && window.i18n['common.analyze']) || 'Analyzing...') + '</span>');
  
  
  $.ajax({
    url: '/maintenance-analyze',
    type: 'POST',
    contentType: 'application/json',
    data: JSON.stringify({ 
      kif: kif, 
      player: player,
      source: source
    }),
    success: function(data) {
      // Invalidate maintenance games cache to show 'Analyzed' status soon
      const keysToRemove = [];
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key && (key.startsWith('maintenance_games_') || key.startsWith('puzzles_'))) {
          keysToRemove.push(key);
        }
      }
      keysToRemove.forEach(key => localStorage.removeItem(key));

      let taskId;
      try {
        if (typeof data === 'string') {
          taskId = JSON.parse(data).taskId;
        } else if (typeof data === 'object' && data !== null) {
          taskId = data.taskId;
        }
        
        if (!taskId) throw new Error('No taskId in response');
      } catch(e) {
        alert('Analysis complete!');
        location.reload();
        return;
      }
      
      const activeTasks = JSON.parse(localStorage.getItem('activeTasks') || '{}');
      activeTasks[taskId] = { type: 'analyze', startedAt: Date.now(), kifHash: kifHash };
      localStorage.setItem('activeTasks', JSON.stringify(activeTasks));

      window.maintenance.pollTask(taskId, null,
        function(result) { // complete
          alert(result || 'Analysis complete!');
          
          // Invalidate cache again on completion
          const keysToRemove = [];
          for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (key && (key.startsWith('maintenance_games_') || key.startsWith('puzzles_'))) {
              keysToRemove.push(key);
            }
          }
          keysToRemove.forEach(key => localStorage.removeItem(key));

          const lishogiName = $('#lishogiNickname').val();
          const shogiwarsName = $('#shogiwarsNickname').val();
          const dojo81Name = $('#dojo81Nickname').val();
          
          if (lishogiName && lishogiName !== 'lishogi_user') window.maintenance.doFetch('lishogi', lishogiName, false);
          if (shogiwarsName && shogiwarsName !== 'swars_user') window.maintenance.doFetch('shogiwars', shogiwarsName, false);
          if (dojo81Name && dojo81Name !== 'dojo81_user') window.maintenance.doFetch('dojo81', dojo81Name, false);
        },
        function(error) { // error
          alert('Error analyzing game: ' + error);
          $btn.prop('disabled', false).text((window.i18n && window.i18n['common.analyze']) || 'Analyze');
        },
        function(message) { // progress
          const $btns = $('.btn-task-' + kifHash);
          $btns.prop('disabled', true);
          if ($btns.find('.btn-text').length > 0) {
            $btns.find('.btn-text').text(message);
          } else {
            $btns.html('<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span> <span class="btn-text">' + message + '</span>');
          }
        },
        'analyze'
      );
    },
    error: function(xhr) {
      console.error('Analysis error:', xhr.status, xhr.responseText);
      alert('Error analyzing game: ' + (xhr.responseText || xhr.statusText));
      $btn.prop('disabled', false).text('Analyze');
    }
  });
});
$(document).on('click', '.reload-data', function() {
  const $btn = $(this);
  const originalHtml = $btn.html();
  $btn.prop('disabled', true).html('<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span> Reloading...');

  // Clear local storage puzzles cache and maintenance games cache
  const keysToRemove = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (key && (key.startsWith('puzzles_') || key.startsWith('maintenance_games_'))) {
      keysToRemove.push(key);
    }
  }
  keysToRemove.forEach(key => localStorage.removeItem(key));

  // Refresh current data by re-fetching games
  const lishogiName = $('#lishogiNickname').val();
  const shogiwarsName = $('#shogiwarsNickname').val();
  const dojo81Name = $('#dojo81Nickname').val();
  
  const fetchPromises = [];
  if (lishogiName && lishogiName !== 'lishogi_user') {
    fetchPromises.push(new Promise(resolve => {
       window.maintenance.doFetch('lishogi', lishogiName, false);
       resolve();
    }));
  }
  if (shogiwarsName && shogiwarsName !== 'swars_user') {
    fetchPromises.push(new Promise(resolve => {
       window.maintenance.doFetch('shogiwars', shogiwarsName, false);
       resolve();
    }));
  }
  if (dojo81Name && dojo81Name !== 'dojo81_user') {
    fetchPromises.push(new Promise(resolve => {
       window.maintenance.doFetch('dojo81', dojo81Name, false);
       resolve();
    }));
  }

  Promise.all(fetchPromises).then(() => {
    $btn.prop('disabled', false).html(originalHtml);
  });
});

$(document).ready(function() {
  if (!window.maintenance.autoFetched) {
    window.maintenance.autoFetched = true;
    const lishogiName = $('#lishogiNickname').val();
    const shogiwarsName = $('#shogiwarsNickname').val();
    const dojo81Name = $('#dojo81Nickname').val();
    
    if (lishogiName && lishogiName !== 'lishogi_user') window.maintenance.doFetch('lishogi', lishogiName, false);
    if (shogiwarsName && shogiwarsName !== 'swars_user') window.maintenance.doFetch('shogiwars', shogiwarsName, false);
    if (dojo81Name && dojo81Name !== 'dojo81_user') window.maintenance.doFetch('dojo81', dojo81Name, false);

    // Discover tasks from other tabs/backend
    $.get('/maintenance-tasks', function(tasks) {
      if (typeof tasks === 'string') {
        try {
          tasks = JSON.parse(tasks);
        } catch (e) {
          console.error('Failed to parse maintenance tasks:', e);
          return;
        }
      }
      if (!Array.isArray(tasks)) {
        console.error('Maintenance tasks is not an array:', tasks);
        return;
      }
      const activeTasks = JSON.parse(localStorage.getItem('activeTasks') || '{}');
      tasks.forEach(task => {
        if (task.status === 'running' && !activeTasks[task.id]) {
          activeTasks[task.id] = { type: 'analyze', startedAt: Date.now(), kifHash: task.kifHash };
        }
      });
      localStorage.setItem('activeTasks', JSON.stringify(activeTasks));
      
      // Resume active tasks (now includes discovered ones)
      Object.keys(activeTasks).forEach(taskId => {
        const task = activeTasks[taskId];
        // If task is already being polled by doFetch, don't start another polling
        // But doFetch might have been skipped if taskId was found in activeTasks.
        // The safest is to ensure only one pollTask runs per taskId.
        if (window.maintenance.activePolls && window.maintenance.activePolls[taskId]) {
           return;
        }
        if (task.type === 'analyze') {
          window.maintenance.pollTask(taskId, null, 
            function(result) { 
              alert(result || 'Analysis complete!');
              location.reload(); 
            },
            function(err) { console.error('Resumed analysis task failed:', err); },
            function(msg) {
               if (task.kifHash) {
                  const $btns = $('.btn-task-' + task.kifHash);
                  $btns.prop('disabled', true);
                  if ($btns.find('.btn-text').length > 0) {
                    $btns.find('.btn-text').text(msg);
                  } else {
                    $btns.html('<span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span> <span class="btn-text">' + msg + '</span>');
                  }
               }
            },
            'analyze'
          );
        } else if (task.type === 'fetch') {
          // autoFetch already started new fetch tasks, so we might have duplicates.
          // For simplicity, we just clear old fetch tasks from localStorage if they're old,
          // or just let them poll.
          if (Date.now() - task.startedAt > 10 * 60 * 1000) { // 10 minutes
             delete activeTasks[taskId];
             localStorage.setItem('activeTasks', JSON.stringify(activeTasks));
          } else {
             // Try to find the results container for this fetch task
             let resultsId = null;
             if (task.source === 'lishogi') resultsId = '#lishogi-results';
             else if (task.source === 'shogiwars') resultsId = '#shogiwars-results';
             else if (task.source === 'dojo81') resultsId = '#dojo81-results';
             
             const $results = resultsId ? $(resultsId) : null;
             const cacheKey = (task.source && task.name && task.maxGames) ? 
                ('maintenance_games_' + task.source + '_' + task.name + '_' + task.maxGames) : null;

             window.maintenance.pollTask(taskId, $results, 
                function(html) { // complete
                  if ($results && $results.length > 0) {
                    $results.html(html);
                  }
                  if (cacheKey) {
                    try {
                      localStorage.setItem(cacheKey, html);
                    } catch (e) { console.warn('Failed to save to localStorage:', e); }
                  }
                  if (task.source === 'lishogi') window.maintenance.isFetchingLishogi = false;
                  else if (task.source === 'shogiwars') window.maintenance.isFetchingShogiwars = false;
                  else if (task.source === 'dojo81') window.maintenance.isFetchingDojo81 = false;
                }, 
                function(err) { console.error('Resumed fetch task failed:', err); }, 
                null, 
                'fetch'
             );
          }
        }
      });
    });
  }
});
