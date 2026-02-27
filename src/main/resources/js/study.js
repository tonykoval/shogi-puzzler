export function createStudy() {
    const name = document.getElementById('studyName').value;
    const isAutoReload = document.getElementById('isAutoReload').checked;
    const reloadThreshold = parseInt(document.getElementById('reloadThreshold').value) || 200;
    const reloadColor = document.getElementById('reloadColor').value;
    if (!name) return;

    fetch('/study/create', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({ 
            name: name,
            isAutoReload: isAutoReload,
            reloadThreshold: reloadThreshold,
            reloadColor: isAutoReload ? reloadColor : null
        }),
    })
    .then(response => {
        return response.json().then(data => {
            if (!response.ok) {
                throw new Error(data.error || response.statusText);
            }
            return data;
        });
    })
    .then(data => {
        window.location.reload();
    })
    .catch((error) => {
        console.error('Error:', error);
        alert('Failed to create study: ' + error.message);
    });
}

export function deleteStudy(id) {
    if (!confirm('Are you sure you want to delete this study?')) return;

    fetch('/study/delete', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({ id: id }),
    })
    .then(() => {
        window.location.reload();
    })
    .catch((error) => {
        console.error('Error:', error);
    });
}

export function reloadStudy(id) {
    if (!confirm('This will clear current study and rebuild it from all analyzed games. Continue?')) return;

    const btn = event.target;
    const originalText = btn.innerText;
    btn.disabled = true;
    btn.innerText = 'Reloading...';

    fetch('/study/reload', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({ id: id }),
    })
    .then(response => {
        if (!response.ok) {
            return response.json().then(data => {
                throw new Error(data.error || 'Failed to reload');
            });
        }
        return response.json();
    })
    .then(data => {
        alert('Study reloaded! Processed ' + data.processedGames + ' games.');
        window.location.reload();
    })
    .catch((error) => {
        console.error('Error:', error);
        alert('Error: ' + error.message);
        btn.disabled = false;
        btn.innerText = originalText;
    });
}

export function toggleStudyPublic(id, isPublic) {
    fetch('/study/toggle-public', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({ id: id, isPublic: isPublic }),
    })
    .then(response => {
        if (!response.ok) {
            return response.json().then(data => {
                throw new Error(data.error || 'Failed to toggle public');
            });
        }
        return response.json();
    })
    .then(() => {
        window.location.reload();
    })
    .catch((error) => {
        console.error('Error:', error);
        alert('Failed to toggle public: ' + error.message);
    });
}

export function importKifStudy() {
    const fileInput = document.getElementById('kifFile');
    const nameInput = document.getElementById('kifStudyName');

    if (!fileInput.files || !fileInput.files[0]) {
        alert('Please select a KIF file.');
        return;
    }

    const file = fileInput.files[0];
    const name = nameInput.value || file.name.replace(/\.(kif|kifu)$/i, '');

    if (!name) {
        alert('Please enter a name for the study.');
        return;
    }

    readKifFile(file, function(kif) {
        const btn = document.querySelector('#importKifModal .btn-success');
        const originalText = btn.innerText;
        btn.disabled = true;
        btn.innerText = 'Importing...';

        fetch('/study/create-from-kif', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, kif })
        })
        .then(response => response.json().then(data => {
            if (!response.ok) throw new Error(data.error || response.statusText);
            return data;
        }))
        .then(data => {
            alert(`Study created with ${data.moveCount} moves.`);
            window.location.reload();
        })
        .catch(error => {
            console.error('Error:', error);
            alert('Failed to import KIF: ' + error.message);
            btn.disabled = false;
            btn.innerText = originalText;
        });
    });
}

function readKifFile(file, callback) {
    const reader = new FileReader();
    reader.onload = function(e) {
        const buffer = e.target.result;
        // Try UTF-8 first
        const utf8Text = new TextDecoder('utf-8').decode(buffer);
        if (!utf8Text.includes('\uFFFD')) {
            callback(utf8Text);
            return;
        }
        // Fallback to Shift-JIS (common for KIF files)
        const sjisText = new TextDecoder('shift-jis').decode(buffer);
        callback(sjisText);
    };
    reader.readAsArrayBuffer(file);
}

export async function importLishogiStudy() {
    const url = document.getElementById('lishogiStudyUrl').value.trim();
    if (!url) {
        alert('Please enter a Lishogi study URL.');
        return;
    }

    const btn = document.getElementById('importLishogiStudyBtn');
    const originalText = btn.innerText;
    const progressContainer = document.getElementById('importStudyProgress');
    const progressStatus = document.getElementById('importStudyStatus');
    const progressBar = document.getElementById('importStudyBar');

    btn.disabled = true;
    btn.innerText = 'Preparing...';

    try {
        // Phase 1: Prepare - fetch and split chapters server-side
        const prepResponse = await fetch('/study/prepare-lishogi-study', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url })
        });
        const prepData = await prepResponse.json();
        if (!prepResponse.ok) throw new Error(prepData.error || prepResponse.statusText);

        const { key, chapters } = prepData;

        // For single-chapter studies, use the original single-request endpoint
        if (chapters.length <= 1) {
            btn.innerText = 'Importing...';
            const response = await fetch('/study/create-from-lishogi-study', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url })
            });
            const data = await response.json();
            if (!response.ok) throw new Error(data.error || response.statusText);

            const count = data.chapters.length;
            const summary = data.chapters.map(c => `${c.name} (${c.moveCount} moves)`).join(', ');
            alert(`Imported ${count} study(s): ${summary}`);
            window.location.reload();
            return;
        }

        // Phase 2: Multi-chapter - import one by one with progress
        progressContainer.style.display = 'block';
        btn.innerText = 'Importing...';

        const total = chapters.length;
        const results = [];
        let failed = null;

        for (let i = 0; i < total; i++) {
            const chapter = chapters[i];
            progressStatus.textContent = `Importing chapter ${i + 1} / ${total}: ${chapter.name}`;
            progressBar.style.width = `${((i) / total) * 100}%`;

            try {
                const chResponse = await fetch('/study/import-lishogi-chapter', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ key, index: chapter.index })
                });
                const chData = await chResponse.json();
                if (!chResponse.ok) throw new Error(chData.error || chResponse.statusText);

                results.push(chData);
            } catch (chError) {
                failed = { chapter: chapter.name, index: i + 1, error: chError.message };
                break;
            }

            progressBar.style.width = `${((i + 1) / total) * 100}%`;
        }

        if (failed) {
            progressStatus.textContent = `Failed at chapter ${failed.index} / ${total}: ${failed.chapter}`;
            progressBar.classList.add('bg-danger');
            const imported = results.length;
            alert(`Error importing chapter "${failed.chapter}": ${failed.error}\n\n${imported} chapter(s) were imported successfully before the error.`);
            if (imported > 0) {
                window.location.reload();
            } else {
                btn.disabled = false;
                btn.innerText = originalText;
            }
        } else {
            progressStatus.textContent = `Done! Imported ${results.length} chapters.`;
            progressBar.style.width = '100%';
            const summary = results.map(c => `${c.name} (${c.moveCount} moves)`).join(', ');
            alert(`Imported ${results.length} study(s): ${summary}`);
            window.location.reload();
        }
    } catch (error) {
        console.error('Error:', error);
        alert('Failed to import Lishogi study: ' + error.message);
        btn.disabled = false;
        btn.innerText = originalText;
        if (progressContainer) progressContainer.style.display = 'none';
    }
}

export async function reloadStudyGroup(ids) {
    const idList = ids.split(',').filter(id => id);
    if (!confirm(`Reload ${idList.length} chapter(s) from Lishogi?`)) return;

    const btn = event.currentTarget;
    const originalHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = '<i class="bi bi-hourglass-split"></i>';

    const results = [];
    for (const id of idList) {
        try {
            const response = await fetch(`/study/${id}/reload-from-study`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: '{}'
            });
            const data = await response.json();
            if (!response.ok) throw new Error(data.error || 'Failed');
            results.push({ id, moveCount: data.moveCount, success: true });
        } catch (e) {
            results.push({ id, error: e.message, success: false });
        }
    }

    const succeeded = results.filter(r => r.success).length;
    const lines = results.map(r =>
        r.success ? `✓ ${r.id} (${r.moveCount} moves)` : `✗ ${r.id}: ${r.error}`
    );
    alert(`Reloaded ${succeeded} / ${idList.length} chapters.\n\n${lines.join('\n')}`);
    window.location.reload();
}

let activeSourceFilter = 'all';

export function filterStudies() {
    const searchText = (document.getElementById('studySearch')?.value || '').toLowerCase();
    const filter = activeSourceFilter;

    // Filter study groups
    document.querySelectorAll('.study-group').forEach(group => {
        const source = group.dataset.source || '';
        const sourceMatch = filter === 'all' || source === filter;
        const items = group.querySelectorAll('.study-item');
        let anyVisible = false;

        items.forEach(item => {
            const name = item.dataset.name || '';
            const nameMatch = !searchText || name.includes(searchText);
            const visible = sourceMatch && nameMatch;
            item.style.display = visible ? '' : 'none';
            if (visible) anyVisible = true;
        });

        group.style.display = (sourceMatch && anyVisible) ? '' : 'none';
    });

    // Filter standalone items
    document.querySelectorAll('#study-list > .row .study-item').forEach(item => {
        const source = item.dataset.source || 'manual';
        const name = item.dataset.name || '';
        const sourceMatch = filter === 'all' || source === filter;
        const nameMatch = !searchText || name.includes(searchText);
        item.style.display = (sourceMatch && nameMatch) ? '' : 'none';
    });
}

export function setSourceFilter(btn) {
    document.querySelectorAll('[data-filter]').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    activeSourceFilter = btn.dataset.filter;
    filterStudies();
}

export function deleteStudyGroup(ids) {
    const idList = ids.split(',').filter(id => id);
    if (!confirm(`Delete all ${idList.length} chapters in this study?`)) return;

    Promise.all(idList.map(id =>
        fetch('/study/delete', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id })
        })
    )).then(() => {
        window.location.reload();
    }).catch(error => {
        console.error('Error:', error);
        alert('Failed to delete some chapters: ' + error.message);
    });
}

// Expose to global scope for onclick handlers
window.createStudy = createStudy;
window.deleteStudy = deleteStudy;
window.reloadStudy = reloadStudy;
window.toggleStudyPublic = toggleStudyPublic;
window.importKifStudy = importKifStudy;
window.importLishogiStudy = importLishogiStudy;
window.filterStudies = filterStudies;
window.setSourceFilter = setSourceFilter;
window.deleteStudyGroup = deleteStudyGroup;
window.reloadStudyGroup = reloadStudyGroup;

document.addEventListener('DOMContentLoaded', () => {
    const isAutoReloadCheckbox = document.getElementById('isAutoReload');
    const autoReloadSettings = document.getElementById('autoReloadSettings');

    if (isAutoReloadCheckbox && autoReloadSettings) {
        isAutoReloadCheckbox.addEventListener('change', () => {
            autoReloadSettings.style.display = isAutoReloadCheckbox.checked ? 'block' : 'none';
        });
    }

    const kifFile = document.getElementById('kifFile');
    if (kifFile) {
        kifFile.addEventListener('change', () => {
            const nameInput = document.getElementById('kifStudyName');
            if (nameInput && !nameInput.value && kifFile.files[0]) {
                nameInput.value = kifFile.files[0].name.replace(/\.(kif|kifu)$/i, '');
            }
        });
    }
});
