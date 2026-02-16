export function createRepertoire() {
    const name = document.getElementById('repertoireName').value;
    const isAutoReload = document.getElementById('isAutoReload').checked;
    const reloadThreshold = parseInt(document.getElementById('reloadThreshold').value) || 200;
    const reloadColor = document.getElementById('reloadColor').value;
    if (!name) return;

    fetch('/repertoire/create', {
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
        alert('Failed to create repertoire: ' + error.message);
    });
}

export function deleteRepertoire(id) {
    if (!confirm('Are you sure you want to delete this repertoire?')) return;

    fetch('/repertoire/delete', {
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

export function reloadRepertoire(id) {
    if (!confirm('This will clear current repertoire and rebuild it from all analyzed games. Continue?')) return;

    const btn = event.target;
    const originalText = btn.innerText;
    btn.disabled = true;
    btn.innerText = 'Reloading...';

    fetch('/repertoire/reload', {
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
        alert('Repertoire reloaded! Processed ' + data.processedGames + ' games.');
        window.location.reload();
    })
    .catch((error) => {
        console.error('Error:', error);
        alert('Error: ' + error.message);
        btn.disabled = false;
        btn.innerText = originalText;
    });
}

export function toggleRepertoirePublic(id, isPublic) {
    fetch('/repertoire/toggle-public', {
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

export function importKifRepertoire() {
    const fileInput = document.getElementById('kifFile');
    const nameInput = document.getElementById('kifRepertoireName');

    if (!fileInput.files || !fileInput.files[0]) {
        alert('Please select a KIF file.');
        return;
    }

    const file = fileInput.files[0];
    const name = nameInput.value || file.name.replace(/\.(kif|kifu)$/i, '');

    if (!name) {
        alert('Please enter a name for the repertoire.');
        return;
    }

    readKifFile(file, function(kif) {
        const btn = document.querySelector('#importKifModal .btn-success');
        const originalText = btn.innerText;
        btn.disabled = true;
        btn.innerText = 'Importing...';

        fetch('/repertoire/create-from-kif', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, kif })
        })
        .then(response => response.json().then(data => {
            if (!response.ok) throw new Error(data.error || response.statusText);
            return data;
        }))
        .then(data => {
            alert(`Repertoire created with ${data.moveCount} moves.`);
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

// Expose to global scope for onclick handlers
window.createRepertoire = createRepertoire;
window.deleteRepertoire = deleteRepertoire;
window.reloadRepertoire = reloadRepertoire;
window.toggleRepertoirePublic = toggleRepertoirePublic;
window.importKifRepertoire = importKifRepertoire;

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
            const nameInput = document.getElementById('kifRepertoireName');
            if (nameInput && !nameInput.value && kifFile.files[0]) {
                nameInput.value = kifFile.files[0].name.replace(/\.(kif|kifu)$/i, '');
            }
        });
    }
});
