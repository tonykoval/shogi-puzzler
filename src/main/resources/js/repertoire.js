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

// Expose to global scope for onclick handlers
window.createRepertoire = createRepertoire;
window.deleteRepertoire = deleteRepertoire;
window.reloadRepertoire = reloadRepertoire;

document.addEventListener('DOMContentLoaded', () => {
    const isAutoReloadCheckbox = document.getElementById('isAutoReload');
    const autoReloadSettings = document.getElementById('autoReloadSettings');
    
    if (isAutoReloadCheckbox && autoReloadSettings) {
        isAutoReloadCheckbox.addEventListener('change', () => {
            autoReloadSettings.style.display = isAutoReloadCheckbox.checked ? 'block' : 'none';
        });
    }
});
