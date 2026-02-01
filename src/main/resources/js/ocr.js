
document.addEventListener('DOMContentLoaded', () => {
    const imageUpload = document.getElementById('imageUpload');
    const regionControls = document.getElementById('regionControls');
    const ocrRegionButtons = document.getElementById('ocr-region-buttons');
    const ocrCanvasContainer = document.getElementById('ocr-canvas-container');
    const ocrCanvas = document.getElementById('ocr-canvas');
    const ocrStatus = document.getElementById('ocrStatus');
    const pieceCountStatus = document.getElementById('pieceCountStatus');
    const updateSfenFromVerified = document.getElementById('updateSfenFromVerified');
    window.updateSfenFromVerified = () => {
        const items = squaresGrid.querySelectorAll('.square-item');
        if (items.length !== 81) {
            alert('Identify squares first.');
            return;
        }

        let sfenRows = [];
        for (let rank = 0; rank < 9; rank++) {
            let row = '';
            let emptyCount = 0;
            for (let fileIdx = 0; fileIdx < 9; fileIdx++) {
                // identifyAllSquares in ShogiOCR.scala:
                // for (rank <- 0 until 9; fileIdx <- 0 until 9) yield { ... "coords" -> s"${9-fileIdx}${('a' + rank).toChar}" ... }
                // So index 0 is 9a, index 1 is 8a, ..., index 8 is 1a.
                // SFEN for a row also goes from 9th file to 1st file.
                
                const itemIndex = rank * 9 + fileIdx;
                const piece = items[itemIndex].dataset.currentPiece;
                
                // Shogiground (and SFEN) uses + prefix for promoted pieces.
                
                if (piece) {
                    if (emptyCount > 0) {
                        row += emptyCount;
                        emptyCount = 0;
                    }
                    row += piece;
                } else {
                    emptyCount++;
                }
            }
            if (emptyCount > 0) row += emptyCount;
            sfenRows.push(row);
        }

        const boardSfen = sfenRows.join('/');
        
        // Hand pieces
        const sentePieces = [];
        const gotePieces = [];
        const handItems = document.querySelectorAll('.hand-grid .square-item');
        handItems.forEach(item => {
            const piece = item.dataset.currentPiece;
            const count = parseInt(item.dataset.currentCount);
            if (piece && count > 0) {
                for (let i = 0; i < count; i++) {
                    if (item.dataset.type === 'senteHand') {
                        sentePieces.push(piece);
                    } else {
                        gotePieces.push(piece);
                    }
                }
            }
        });

        // SFEN hand pieces must be in specific order: B, R, G, S, N, L, P
        const pieceOrder = ['B', 'R', 'G', 'S', 'N', 'L', 'P'];
        let handPart = '';

        pieceOrder.forEach(p => {
            const count = sentePieces.filter(x => x === p).length;
            if (count > 1) handPart += count + p;
            else if (count === 1) handPart += p;
        });

        pieceOrder.forEach(p => {
            const lp = p.toLowerCase();
            const count = gotePieces.filter(x => x === lp).length;
            if (count > 1) handPart += count + lp;
            else if (count === 1) handPart += lp;
        });

        if (!handPart) handPart = '-';
        
        let currentSfen = sfenResult.value;
        let turnPart = 'b';
        let movePart = '1';
        
        if (currentSfen && currentSfen.includes(' ')) {
            const parts = currentSfen.split(' ');
            if (parts.length >= 2) {
                turnPart = parts[1];
                movePart = parts[3] || '1';
            }
        }
        
        const fullSfen = `${boardSfen} ${turnPart} ${handPart} ${movePart}`;
        sfenResult.value = fullSfen;
        updateLishogiLink(fullSfen);
        initBoard(fullSfen);
    };

    const saveAllVerified = document.getElementById('saveAllVerified');
    window.saveAllVerified = async () => {
        const items = document.querySelectorAll('.square-item');
        let savedCount = 0;
        let totalToSave = 0;

        // Count non-empty pieces
        items.forEach(item => {
            if (item.dataset.currentPiece) totalToSave++;
        });

        if (totalToSave === 0) {
            alert('No pieces to save.');
            return;
        }

        if (ocrStatus) ocrStatus.innerText = `Saving pieces... 0/${totalToSave}`;

        for (const item of items) {
            let label = item.dataset.currentPiece;
            const image = item.dataset.image;
            const type = item.dataset.type;
            const count = item.dataset.currentCount;
            const isHand = type.includes('Hand');
            const isGote = String(item.dataset.isGote) === 'true';

            if (!label) continue;

            try {
                const resp = await fetch('/ocr/verify-piece', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ 
                        label, 
                        image,
                        isHand: isHand,
                        count: count,
                        isGote: isGote
                    })
                });

                if (resp.ok) {
                    const data = await resp.json();
                    if (data.success && data.id) {
                        item.dataset.pieceId = data.id;
                    }
                    savedCount++;
                    item.classList.remove('changed');
                    item.classList.add('verified');
                    if (ocrStatus) ocrStatus.innerText = `Saving pieces... ${savedCount}/${totalToSave}`;
                }
            } catch (e) {
                console.error('Error saving piece:', e);
            }
        }

        if (ocrStatus) ocrStatus.innerText = `Successfully saved ${savedCount} pieces to training database.`;
        
        // Refresh piece counts in UI
        validatePieceCounts();
    };

    const sfenResult = document.getElementById('sfenResult');
    const copySfen = document.getElementById('copySfen');
    
    const lishogiLink = document.getElementById('lishogi-link');
    const squaresGrid = document.getElementById('squaresGrid');
    const senteHandGrid = document.getElementById('senteHandGrid');
    const goteHandGrid = document.getElementById('goteHandGrid');

    function updateLishogiLink(sfen) {
        if (lishogiLink && sfen && sfen.indexOf(' ') !== -1) {
            const lishogiSfen = sfen.replace(/ /g, '_');
            lishogiLink.href = `https://lishogi.org/analysis/${lishogiSfen}`;
            lishogiLink.style.display = 'inline-block';
        } else if (lishogiLink) {
            lishogiLink.style.display = 'none';
        }
    }
    
    const ocrEntryId = document.getElementById('ocr-entry-id');
    const ocrComment = document.getElementById('ocr-comment');
    const ocrInitialData = document.getElementById('ocr-initial-data');
    
    const ocrProfileSelect = document.getElementById('ocrProfileSelect');
    const btnNewProfile = document.getElementById('btnNewProfile');
    const btnDeleteProfile = document.getElementById('btnDeleteProfile');
    
    let ground;
    let originalImageData = null;
    let regions = {
        board: { x: 0, y: 0, w: 0, h: 0 },
        sente: { x: 0, y: 0, w: 0, h: 0 },
        gote: { x: 0, y: 0, w: 0, h: 0 }
    };
    let activeRegion = 'board';
    let isDrawing = false;
    let startX, startY;
    let imgWidth, imgHeight;

    ocrProfileSelect.addEventListener('change', async () => {
        const profile = ocrProfileSelect.value;
        console.log(`[OCR] Changing profile to: ${profile}`);
        const response = await fetch('/ocr/select-profile', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ profile: profile })
        });
        
        if (response.ok) {
            const data = await response.json();
            console.log(`[OCR] Profile data received:`, data);
            if (data.regions) {
                regions = data.regions;
                if (originalImageData && imgWidth > 0) {
                    console.log(`[OCR] Updating overlays for image. imgWidth=${imgWidth}`);
                    updateRectOverlay('board');
                    updateRectOverlay('sente');
                    updateRectOverlay('gote');
                } else {
                    console.log(`[OCR] No image loaded yet or imgWidth is 0, skipping overlay update.`);
                    // Even if image is not loaded, we want to ensure overlays are updated when it DOES load
                }
                if (ocrStatus) ocrStatus.innerText = `Profile "${profile}" loaded.`;
            } else if (imageUpload.files.length > 0) {
                uploadImage(imageUpload.files[0]);
            }
        }
    });

    btnNewProfile.addEventListener('click', () => {
        const name = prompt('Enter profile name:');
        if (name) {
            const option = document.createElement('option');
            option.value = name;
            option.text = name;
            option.selected = true;
            ocrProfileSelect.add(option);
            saveRegions();
        }
    });

    btnDeleteProfile.addEventListener('click', async () => {
        const profile = ocrProfileSelect.value;
        if (profile === 'Default' && ocrProfileSelect.options.length === 1) {
            alert('Cannot delete the last profile.');
            return;
        }
        if (confirm(`Delete profile "${profile}"?`)) {
            await fetch('/ocr/delete-profile', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ profile: profile })
            });
            window.location.reload();
        }
    });

    window.setActiveRegion = (region) => {
        activeRegion = region;
        ['board', 'sente', 'gote'].forEach(r => {
            const btn = document.getElementById(`btn-detect-${r}`);
            const rect = document.getElementById(`rect-${r}`);
            if (r === region) {
                btn.classList.add('active');
                rect.classList.add('active');
            } else {
                btn.classList.remove('active');
                rect.classList.remove('active');
            }
        });
    };

    function updateRectOverlay(region) {
        const rect = document.getElementById(`rect-${region}`);
        if (!rect) return;
        const r = regions[region];
        if (r && r.w > 0 && r.h > 0 && imgWidth > 0 && ocrCanvas.clientWidth > 0) {
            const displayScale = ocrCanvas.clientWidth / imgWidth;
            console.log(`[OCR] updateRectOverlay(${region}): scale=${displayScale}, r=`, r);
            rect.style.left = (r.x * displayScale) + 'px';
            rect.style.top = (r.y * displayScale) + 'px';
            rect.style.width = (r.w * displayScale) + 'px';
            rect.style.height = (r.h * displayScale) + 'px';
            rect.style.display = 'block';
        } else {
            console.log(`[OCR] updateRectOverlay(${region}): hiding rect. r=`, r, `imgWidth=${imgWidth}, canvasWidth=${ocrCanvas.clientWidth}`);
            rect.style.display = 'none';
        }
    }

    ocrCanvas.addEventListener('mousedown', (e) => {
        if (!originalImageData) return;
        isDrawing = true;
        const rect = ocrCanvas.getBoundingClientRect();
        const displayScale = imgWidth / ocrCanvas.clientWidth;
        startX = (e.clientX - rect.left) * displayScale;
        startY = (e.clientY - rect.top) * displayScale;
        
        regions[activeRegion] = { x: startX, y: startY, w: 0, h: 0 };
    });

    window.addEventListener('mousemove', (e) => {
        if (!isDrawing) return;
        const rect = ocrCanvas.getBoundingClientRect();
        const displayScale = imgWidth / ocrCanvas.clientWidth;
        let currentX = (e.clientX - rect.left) * displayScale;
        let currentY = (e.clientY - rect.top) * displayScale;
        
        // Constrain to image bounds
        currentX = Math.max(0, Math.min(imgWidth, currentX));
        currentY = Math.max(0, Math.min(imgHeight, currentY));

        const x = Math.min(startX, currentX);
        const y = Math.min(startY, currentY);
        const w = Math.abs(currentX - startX);
        const h = Math.abs(currentY - startY);

        regions[activeRegion] = { x, y, w, h };
        updateRectOverlay(activeRegion);
    });

    window.addEventListener('mouseup', async () => {
        if (isDrawing) {
            isDrawing = false;
            // Save regions to backend when adjustment is finished
            await saveRegions();
        }
    });

    async function saveRegions() {
        try {
            await fetch('/ocr/save-regions', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    profile: ocrProfileSelect.value,
                    regions: regions
                })
            });
        } catch (error) {
            console.error('Error saving regions:', error);
        }
    }

    function initBoard(sfen) {
        const boardEl = document.getElementById('ocr-board');
        if (!boardEl) return;

        let config = {
            orientation: 'sente',
            coordinates: true,
            viewOnly: true,
            aspectRatio: 11 / 12,
            selectable: {
                enabled: false,
            },
            movable: {
                free: false,
                color: 'both',
            },
            premovable: {
                enabled: false,
            },
            drawable: {
                enabled: true,
            },
            highlight: {
                lastMove: true,
                check: true,
            },
        };

        const targetSfen = sfen || 'lnsgkgsln/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1';
        updateLishogiLink(targetSfen);
        
        // Handle shogiground sfen format vs fen string
        if (targetSfen.indexOf(' ') !== -1) {
            const parts = targetSfen.split(' ');
            config.sfen = {
                board: parts[0],
                hands: parts[2] !== '-' ? parts[2] : ''
            };
            config.turnColor = parts[1] === 'b' ? 'sente' : 'gote';
        } else {
            config.fen = targetSfen;
        }

        if (ground) {
            ground.set(config);
        } else {
            const wrapElements = {
                board: boardEl,
                hands: {
                    top: document.getElementById('hand-top'),
                    bottom: document.getElementById('hand-bottom')
                }
            };
            ground = Shogiground(config, wrapElements);
        }
    }

    initBoard();

    // Load initial data if editing
    if (window.initialData) {
        try {
            const data = window.initialData;
            originalImageData = data.image;
            regions = {
                board: data.boardRect,
                sente: data.senteRect,
                gote: data.goteRect
            };
            
            // Load image into canvas
            const img = new Image();
            img.onload = () => {
                imgWidth = img.width;
                imgHeight = img.height;
                ocrCanvas.width = imgWidth;
                ocrCanvas.height = imgHeight;
                const ctx = ocrCanvas.getContext('2d');
                ctx.drawImage(img, 0, 0);
                
                ocrCanvasContainer.style.display = 'inline-block';
                if (ocrRegionButtons) ocrRegionButtons.style.display = 'block';
                regionControls.style.display = 'block';
                
                updateRectOverlay('board');
                updateRectOverlay('sente');
                updateRectOverlay('gote');
                
                if (data.sfen) {
                    sfenResult.value = data.sfen;
                    initBoard(data.sfen);
                }
                
                if (data.comment) {
                    ocrComment.value = data.comment;
                }
                
                // If we have manual adjustments, we might want to re-identify squares or just show SFEN
                // For now, let's just trigger identify if we are in edit mode to show the squares
                window.identifySquaresWithOCR(true);
            };
            img.src = data.image;
        } catch (e) {
            console.error('Error loading initial OCR data:', e);
        }
    }

    window.saveOcrEntry = async () => {
        if (!originalImageData) {
            alert('No image to save.');
            return;
        }

        const btn = document.getElementById('save-ocr-btn');
        const originalText = btn.innerText;
        btn.disabled = true;
        btn.innerText = 'Saving...';

        try {
            const manualAdjustments = {};
            const pieceReferences = {};
            document.querySelectorAll('.square-item.changed, .square-item.verified').forEach(item => {
                const piece = item.dataset.currentPiece;
                const count = item.dataset.currentCount;
                if (item.dataset.type.includes('Hand')) {
                    manualAdjustments[item.dataset.coords] = `${piece}:${count}`;
                } else {
                    manualAdjustments[item.dataset.coords] = piece;
                }
                if (item.dataset.pieceId) {
                    pieceReferences[item.dataset.coords] = item.dataset.pieceId;
                }
            });

            const response = await fetch('/ocr/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    id: ocrEntryId.value,
                    image: originalImageData,
                    boardRect: regions.board,
                    senteRect: regions.sente,
                    goteRect: regions.gote,
                    sfen: sfenResult.value,
                    manualAdjustments: manualAdjustments,
                    pieceReferences: pieceReferences,
                    comment: ocrComment.value
                })
            });

            if (response.ok) {
                const result = await response.json();
                if (result.success) {
                    alert('Saved successfully to library!');
                    window.location.href = '/ocr';
                } else {
                    alert('Save failed: ' + result.error);
                }
            } else {
                alert('Server error during save.');
            }
        } catch (error) {
            console.error('Save Error:', error);
            alert('Error: ' + error.message);
        } finally {
            btn.disabled = false;
            btn.innerText = originalText;
        }
    };

    imageUpload.addEventListener('change', async (e) => {
        const file = e.target.files[0];
        if (!file) return;

        // Upload to get regions and image preview
        const formData = new FormData();
        formData.append('image', file);

        try {
            if (ocrStatus) ocrStatus.innerText = 'Uploading and detecting regions...';
            const response = await fetch('/ocr/upload', {
                method: 'POST',
                body: formData
            });

            if (response.ok) {
                const data = await response.json();
                originalImageData = data.image;
                imgWidth = data.width;
                imgHeight = data.height;
                regions = data.regions;
                
                // Load image into canvas
                const img = new Image();
                img.onload = () => {
                    ocrCanvas.width = imgWidth;
                    ocrCanvas.height = imgHeight;
                    const ctx = ocrCanvas.getContext('2d');
                    ctx.drawImage(img, 0, 0);
                    
                    ocrCanvasContainer.style.display = 'inline-block';
                    if (ocrRegionButtons) ocrRegionButtons.style.display = 'block';
                    regionControls.style.display = 'block';
                    
                    // Show detected regions
                    updateRectOverlay('board');
                    updateRectOverlay('sente');
                    updateRectOverlay('gote');
                    
                    if (ocrStatus) ocrStatus.innerText = 'Regions detected. Adjust if needed and click "Identify with Model".';
                };
                img.src = data.image;
            } else {
                if (ocrStatus) ocrStatus.innerText = 'Error uploading image';
            }
        } catch (error) {
            console.error('Upload Error:', error);
            if (ocrStatus) ocrStatus.innerText = 'Error: ' + error.message;
        }
    });


    copySfen.addEventListener('click', () => {
        if (sfenResult.value && sfenResult.value !== 'Processing...' && !sfenResult.value.startsWith('Error')) {
            navigator.clipboard.writeText(sfenResult.value);
            const originalIcon = copySfen.innerHTML;
            copySfen.innerHTML = '<i class="bi bi-check"></i>';
            setTimeout(() => {
                copySfen.innerHTML = originalIcon;
            }, 2000);
        }
    });

    const piecePickerModal = document.getElementById('piece-picker-modal');
    const piecePickerGrid = document.getElementById('piece-picker-grid');
    const piecePickerCountGroup = document.getElementById('piece-picker-count-group');
    const piecePickerCountInput = document.getElementById('piece-picker-count');
    const btnConfirmPiece = document.getElementById('btnConfirmPiece');
    let currentEditingItem = null;
    let selectedPieceInPicker = null;

    window.adjustPickerCount = (delta) => {
        let val = parseInt(piecePickerCountInput.value) || 0;
        val = Math.max(0, Math.min(18, val + delta));
        piecePickerCountInput.value = val;
    };

    const piecePickerTypes = [
        { section: 'Empty' },
        { label: 'Empty', value: '', symbol: '∅' },
        { section: 'Sente' },
        { label: 'Pawn', value: 'P', symbol: 'P', cssClass: 'pawn sente' },
        { label: 'Lance', value: 'L', symbol: 'L', cssClass: 'lance sente' },
        { label: 'Knight', value: 'N', symbol: 'N', cssClass: 'knight sente' },
        { label: 'Silver', value: 'S', symbol: 'S', cssClass: 'silver sente' },
        { label: 'Gold', value: 'G', symbol: 'G', cssClass: 'gold sente' },
        { label: 'Bishop', value: 'B', symbol: 'B', cssClass: 'bishop sente' },
        { label: 'Rook', value: 'R', symbol: 'R', cssClass: 'rook sente' },
        { label: 'King', value: 'K', symbol: 'K', cssClass: 'king sente' },
        { section: 'Sente Promoted' },
        { label: '+Pawn', value: '+P', symbol: '+P', cssClass: 'tokin sente' },
        { label: '+Lance', value: '+L', symbol: '+L', cssClass: 'promotedlance sente' },
        { label: '+Knight', value: '+N', symbol: '+N', cssClass: 'promotedknight sente' },
        { label: '+Silver', value: '+S', symbol: '+S', cssClass: 'promotedsilver sente' },
        { label: '+Bishop', value: '+B', symbol: '+B', cssClass: 'horse sente' },
        { label: '+Rook', value: '+R', symbol: '+R', cssClass: 'dragon sente' },
        { section: 'Gote' },
        { label: 'gPawn', value: 'p', symbol: 'p', cssClass: 'pawn gote' },
        { label: 'gLance', value: 'l', symbol: 'l', cssClass: 'lance gote' },
        { label: 'gKnight', value: 'n', symbol: 'n', cssClass: 'knight gote' },
        { label: 'gSilver', value: 's', symbol: 's', cssClass: 'silver gote' },
        { label: 'gGold', value: 'g', symbol: 'g', cssClass: 'gold gote' },
        { label: 'gBishop', value: 'b', symbol: 'b', cssClass: 'bishop gote' },
        { label: 'gRook', value: 'r', symbol: 'r', cssClass: 'rook gote' },
        { label: 'gKing', value: 'k', symbol: 'k', cssClass: 'king gote' },
        { section: 'Gote Promoted' },
        { label: 'g+Pawn', value: '+p', symbol: '+p', cssClass: 'tokin gote' },
        { label: 'g+Lance', value: '+l', symbol: '+l', cssClass: 'promotedlance gote' },
        { label: 'g+Knight', value: '+n', symbol: '+n', cssClass: 'promotedknight gote' },
        { label: 'g+Silver', value: '+s', symbol: '+s', cssClass: 'promotedsilver gote' },
        { label: 'g+Bishop', value: '+b', symbol: '+b', cssClass: 'horse gote' },
        { label: 'g+Rook', value: '+r', symbol: '+r', cssClass: 'dragon gote' }
    ];

    initPiecePicker();

    function initPiecePicker() {
        piecePickerGrid.innerHTML = '';
        piecePickerTypes.forEach(pt => {
            if (pt.section) {
                const label = document.createElement('div');
                label.className = 'picker-section-label';
                label.innerText = pt.section;
                piecePickerGrid.appendChild(label);
            } else {
                const item = document.createElement('div');
                item.className = 'picker-item';
                if (pt.value === '') {
                    item.classList.add('empty-item');
                    item.innerText = pt.symbol;
                } else {
                    // Create piece element to use background image from Portella.css
                    const pieceInner = document.createElement('piece');
                    const classes = pt.cssClass.split(' ');
                    classes.forEach(c => pieceInner.classList.add(c));
                    item.appendChild(pieceInner);
                }
                
                item.title = pt.label;
                item.dataset.value = pt.value;
                item.addEventListener('click', () => {
                    piecePickerGrid.querySelectorAll('.picker-item').forEach(i => i.classList.remove('active'));
                    item.classList.add('active');
                    selectedPieceInPicker = pt.value;
                    
                    // If it's board, OK directly. If it's hand, let user adjust count.
                    if (currentEditingItem && !currentEditingItem.dataset.type.includes('Hand')) {
                        updateItemPiece(currentEditingItem, selectedPieceInPicker);
                        piecePickerModal.style.display = 'none';
                    }
                });
                if (pt.value === selectedPieceInPicker) item.classList.add('active');
                piecePickerGrid.appendChild(item);
            }
        });
    }

    btnConfirmPiece.addEventListener('click', () => {
        if (currentEditingItem) {
            if (currentEditingItem.dataset.type.includes('Hand')) {
                const count = parseInt(piecePickerCountInput.value) || 0;
                currentEditingItem.dataset.currentCount = count;
                const countDisplay = currentEditingItem.querySelector('.piece-count');
                if (countDisplay) countDisplay.innerText = `x${count}`;
            }
            updateItemPiece(currentEditingItem, selectedPieceInPicker);
        }
        piecePickerModal.style.display = 'none';
    });

    function updateItemPiece(item, newValue) {
        const currentPieceDisplay = item.querySelector('.current-piece');
        currentPieceDisplay.innerText = newValue || '-';
        item.dataset.currentPiece = newValue;

        // Update isGote based on piece case: Uppercase = Sente, Lowercase = Gote
        if (newValue) {
            // Find first letter (skip '+' for promoted pieces)
            const firstLetter = newValue.replace('+', '')[0];
            if (firstLetter) {
                item.dataset.isGote = (firstLetter === firstLetter.toLowerCase());
            }
        }

        if (item.dataset.type.includes('Hand')) {
            const currentCount = parseInt(item.dataset.currentCount || '0');
            if (!newValue) {
                item.dataset.currentCount = '0';
            } else if (currentCount === 0) {
                item.dataset.currentCount = '1';
            }
            const countDisplay = item.querySelector('.piece-count');
            if (countDisplay) countDisplay.innerText = `x${item.dataset.currentCount}`;
        }

        if (newValue !== item.dataset.originalPiece || item.dataset.currentCount !== item.dataset.originalCount) {
            item.classList.add('changed');
        } else {
            item.classList.remove('changed');
        }
        
        if (!newValue) {
            item.classList.add('is-empty');
        } else {
            item.classList.remove('is-empty');
        }
        
        item.classList.remove('verified');

        validatePieceCounts();
    }

    function validatePieceCounts() {
        if (!pieceCountStatus) return;

        const maxCounts = {
            'P': 18, 'L': 4, 'N': 4, 'S': 4, 'G': 4, 'B': 2, 'R': 2, 'K': 2
        };

        const currentCounts = {
            'P': 0, 'L': 0, 'N': 0, 'S': 0, 'G': 0, 'B': 0, 'R': 0, 'K': 0
        };

        // Helper to get base role from SFEN piece char
        const getBaseRole = (p) => {
            const up = p.toUpperCase().replace('+', '');
            return up;
        };

        const allSquares = document.querySelectorAll('.square-item');
        let sentePiecesCount = 0;
        let gotePiecesCount = 0;
        
        allSquares.forEach(item => {
            const p = item.dataset.currentPiece;
            if (p) {
                const base = getBaseRole(p);
                const count = parseInt(item.dataset.currentCount || '1');
                if (currentCounts.hasOwnProperty(base)) {
                    currentCounts[base] += count;
                }
                
                // Track per color count
                const isSente = p === p.toUpperCase();
                if (isSente) sentePiecesCount += count;
                else gotePiecesCount += count;
            }
        });

        let errors = [];
        let totalPieces = 0;
        for (const [role, count] of Object.entries(currentCounts)) {
            totalPieces += count;
            if (count > maxCounts[role]) {
                errors.push(`${role}: ${count}/${maxCounts[role]}`);
            }
        }

        pieceCountStatus.style.display = 'block';
        let statusText = `Piece count: ${totalPieces}/40 (S: ${sentePiecesCount}, G: ${gotePiecesCount})`;
        
        if (errors.length > 0) {
            pieceCountStatus.className = 'small text-danger mt-2';
            pieceCountStatus.innerText = `Too many pieces: ${errors.join(', ')} (${statusText})`;
        } else if (totalPieces > 40) {
            pieceCountStatus.className = 'small text-danger mt-2';
            pieceCountStatus.innerText = `Total pieces exceeds 40 (${statusText})`;
        } else if (sentePiecesCount > 20 || gotePiecesCount > 20) {
            pieceCountStatus.className = 'small text-danger mt-2';
            pieceCountStatus.innerText = `Too many pieces for one side (${statusText})`;
        } else if (totalPieces < 40) {
            pieceCountStatus.className = 'small text-warning mt-2';
            pieceCountStatus.innerText = `${statusText} - Some pieces might be missing`;
        } else {
            pieceCountStatus.className = 'small text-success mt-2';
            pieceCountStatus.innerText = `Piece count correct: 40/40 (S: 20, G: 20)`;
        }
    }

    initPiecePicker();

    const identifySquaresBtn = document.getElementById('identifySquares');
    if (identifySquaresBtn) {
        identifySquaresBtn.addEventListener('click', async () => {
            if (!originalImageData) return;
            await window.identifySquaresWithOCR(true);
        });
    }

    window.loadFromDb = () => {
        if (ocrInitialData && ocrInitialData.value && ocrInitialData.value !== 'handled-via-script') {
            let data;
            try {
                data = JSON.parse(ocrInitialData.value);
                if (data.sfen) {
                    sfenResult.value = data.sfen;
                    initBoard(data.sfen);
                }
                if (data.comment) {
                    ocrComment.value = data.comment;
                }
            } catch (e) {
                console.error('Error parsing initial data:', e);
                return;
            }
                
            // Identify squares first to get the base images/grid
            window.identifySquaresWithOCR(true).then(() => {
                console.log("[OCR] Squares identified, applying DB state...");
                
                const items = document.querySelectorAll('.square-item');
                
                // Apply manual adjustments from DB
                if (data.manualAdjustments) {
                    console.log("[OCR] Applying manual adjustments from DB:", data.manualAdjustments);
                    items.forEach(item => {
                        let adjValue = data.manualAdjustments[item.dataset.coords];
                        if (adjValue !== undefined) {
                            if (item.dataset.type.includes('Hand') && adjValue.includes(':')) {
                                const [piece, count] = adjValue.split(':');
                                item.dataset.currentCount = count;
                                const countDisplay = item.querySelector('.piece-count');
                                if (countDisplay) countDisplay.innerText = `x${count}`;
                                updateItemPiece(item, piece);
                            } else {
                                updateItemPiece(item, adjValue);
                            }
                        }
                    });
                }

                // Apply piece references if any
                if (data.pieceReferences) {
                    console.log("[OCR] Applying piece references from DB:", data.pieceReferences);
                    items.forEach(item => {
                        const refId = data.pieceReferences[item.dataset.coords];
                        if (refId) {
                            item.dataset.pieceId = refId;
                            item.classList.add('verified'); // Mark as loaded from DB
                        }
                    });
                }
                
                // Final SFEN update after all adjustments are applied
                if (data.sfen) {
                    sfenResult.value = data.sfen;
                    initBoard(data.sfen);
                    updateLishogiLink(data.sfen);
                }
                
                if (ocrStatus) ocrStatus.innerText = 'State loaded from database.';
                validatePieceCounts();
            });
        }
    };


    window.identifySquaresWithMode = async (mode) => {
        if (!originalImageData) return;
        await window.identifySquaresWithOCR(false, mode);
    };

    window.identifySquaresWithOCR = async (skipOCR, mode = 'all') => {
        try {
            if (skipOCR) {
                const identifySquares = document.getElementById('identifySquares');
                if (identifySquares) identifySquares.disabled = true;
                if (ocrStatus) ocrStatus.innerText = 'Processing: Identifying squares...';
            } else {
                const processModel = document.getElementById('processModel');
                if (processModel) processModel.disabled = true;
                if (ocrStatus) ocrStatus.innerText = `Processing: Running ${mode.toUpperCase()} on squares...`;
            }
            
            if (ocrStatus) ocrStatus.style.display = 'block';
            
            // Collect current manual adjustments to preserve them
            const manualAdjustments = {};
            document.querySelectorAll('.square-item.changed, .square-item.verified').forEach(item => {
                const piece = item.dataset.currentPiece;
                const count = item.dataset.currentCount;
                if (item.dataset.type.includes('Hand')) {
                    manualAdjustments[item.dataset.coords] = `${piece}:${count}`;
                } else {
                    manualAdjustments[item.dataset.coords] = piece;
                }
            });

            const response = await fetch('/ocr/identify-squares', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    image: originalImageData,
                    boardRect: regions.board,
                    senteRect: regions.sente,
                    goteRect: regions.gote,
                    skipOCR: skipOCR,
                    mode: mode
                })
            });

            if (response.ok) {
                const data = await response.json();
                
                renderSquares(data.squares);
                
                // Re-apply preserved manual adjustments
                const items = document.querySelectorAll('.square-item');
                items.forEach(item => {
                    let adjValue = manualAdjustments[item.dataset.coords];
                    if (adjValue !== undefined) {
                        if (item.dataset.type.includes('Hand') && adjValue.includes(':')) {
                            const [piece, count] = adjValue.split(':');
                            item.dataset.currentCount = count;
                            const countDisplay = item.querySelector('.piece-count');
                            if (countDisplay) countDisplay.innerText = `x${count}`;
                            updateItemPiece(item, piece);
                        } else {
                            updateItemPiece(item, adjValue);
                        }
                    }
                });
                
                validatePieceCounts();
                saveAllVerified.style.display = 'block';
                updateSfenFromVerified.style.display = 'block';
                
                // Automatically update SFEN if we did OCR
                if (!skipOCR) {
                    updateSfenFromVerified.click();
                    if (ocrStatus) ocrStatus.innerText = `${mode.toUpperCase()} complete.`;
                } else {
                    updateLishogiLink(sfenResult.value);
                    if (ocrStatus) ocrStatus.innerText = 'Squares identified.';
                }
            } else {
                const err = await response.json();
                sfenResult.value = 'Error: ' + (err.error || 'Unknown error');
                ocrStatus.innerText = 'Error processing squares';
            }
        } catch (error) {
            console.error('Process Error:', error);
            sfenResult.value = 'Error: ' + error.message;
            ocrStatus.innerText = 'Error: ' + error.message;
        } finally {
            const identifySquares = document.getElementById('identifySquares');
            const processModel = document.getElementById('processModel');
            if (identifySquares) identifySquares.disabled = false;
            if (processModel) processModel.disabled = false;
        }
    }

    function renderSquares(squares) {
        squaresGrid.innerHTML = '';
        senteHandGrid.innerHTML = '';
        goteHandGrid.innerHTML = '';
        
        let hasSente = false;
        let hasGote = false;

        squares.forEach(sq => {
            const item = document.createElement('div');
            item.className = 'square-item';
            if (!sq.piece) item.classList.add('is-empty');
            item.dataset.originalPiece = sq.piece;
            item.dataset.currentPiece = sq.piece;
            item.dataset.image = sq.image;
            item.dataset.coords = sq.coords;
            item.dataset.type = sq.type;
            if (sq.pieceId) {
                item.dataset.pieceId = sq.pieceId;
                item.classList.add('verified');
            }
            
            // Initial isGote should match the piece case if piece exists
            let isGote = sq.isGote === 'true';
            if (sq.piece) {
                const firstLetter = sq.piece.replace('+', '')[0];
                if (firstLetter) {
                    isGote = (firstLetter === firstLetter.toLowerCase());
                }
            }
            item.dataset.isGote = isGote;
            
            item.dataset.currentCount = sq.count || (sq.piece ? '1' : '0');
            item.dataset.originalCount = item.dataset.currentCount;

            const img = document.createElement('img');
            img.src = sq.image;
            item.appendChild(img);

            if (sq.piece) {
                const badge = document.createElement('div');
                badge.className = 'ocr-badge';
                badge.innerText = `${sq.label || 'OCR'}: ${sq.piece}`;
                item.appendChild(badge);
            }

            const current = document.createElement('div');
            current.className = 'current-piece';
            current.innerText = sq.piece || '-';
            item.appendChild(current);

            if (sq.type.includes('Hand')) {
                const countDisplay = document.createElement('div');
                countDisplay.className = 'piece-count';
                countDisplay.innerText = `x${item.dataset.currentCount}`;
                countDisplay.title = 'Click to change count';
                countDisplay.addEventListener('click', (e) => {
                    e.stopPropagation();
                    let count = parseInt(item.dataset.currentCount);
                    count = (count + 1) % 19; // Max 18 of one piece (pawns)
                    item.dataset.currentCount = count;
                    countDisplay.innerText = `x${count}`;
                    
                    if (item.dataset.currentPiece !== item.dataset.originalPiece || item.dataset.currentCount !== item.dataset.originalCount) {
                        item.classList.add('changed');
                    } else {
                        item.classList.remove('changed');
                    }
                    item.classList.remove('verified');

                    if (count > 0 && !item.dataset.currentPiece) {
                       // Auto-pick piece type if count > 0 but no piece selected
                       // This is just a helper, coords already have the type
                       const pieceFromCoords = item.dataset.coords.split('-')[1];
                       updateItemPiece(item, item.dataset.type === 'goteHand' ? pieceFromCoords.toLowerCase() : pieceFromCoords.toUpperCase());
                    } else if (count === 0 && item.dataset.currentPiece) {
                       updateItemPiece(item, '');
                    }
                });
                item.appendChild(countDisplay);
            }

            const coords = document.createElement('div');
            coords.className = 'small text-muted';
            coords.style.fontSize = '9px';
            coords.innerText = sq.coords;
            item.appendChild(coords);

            item.addEventListener('click', () => {
                currentEditingItem = item;
                selectedPieceInPicker = item.dataset.currentPiece;
                
                initPiecePicker();

                if (item.dataset.type.includes('Hand')) {
                    piecePickerCountGroup.style.display = 'block';
                    piecePickerCountInput.value = item.dataset.currentCount;
                } else {
                    piecePickerCountGroup.style.display = 'none';
                }
                
                piecePickerModal.style.display = 'flex';
            });

            if (sq.type === 'senteHand') {
                if (!hasSente) {
                    const label = document.createElement('div');
                    label.className = 'hand-label';
                    label.innerText = 'Sente Hand Pieces (Training Only)';
                    senteHandGrid.appendChild(label);
                    hasSente = true;
                    senteHandGrid.style.display = 'grid';
                }
                senteHandGrid.appendChild(item);
            } else if (sq.type === 'goteHand') {
                if (!hasGote) {
                    const label = document.createElement('div');
                    label.className = 'hand-label';
                    label.innerText = 'Gote Hand Pieces (Training Only)';
                    goteHandGrid.appendChild(label);
                    hasGote = true;
                    goteHandGrid.style.display = 'grid';
                }
                goteHandGrid.appendChild(item);
            } else {
                squaresGrid.appendChild(item);
            }
        });
    }

    updateSfenFromVerified.addEventListener('click', () => {
        window.updateSfenFromVerified();
    });

    saveAllVerified.addEventListener('click', async () => {
        window.saveAllVerified();
    });
});
