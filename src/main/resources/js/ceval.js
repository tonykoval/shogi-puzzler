'use strict';

/**
 * ceval.js — Client-side evaluation using YaneuraOu WASM (v7.6.3)
 *
 * Requires:
 *   - COOP/COEP headers on the page for SharedArrayBuffer support
 *   - /engine-wasm/yaneuraou.k-p.js
 *   - /engine-wasm/yaneuraou.k-p.wasm
 *   - /engine-wasm/yaneuraou.k-p.worker.js
 *
 * Usage:
 *   const ceval = new ClientEval({
 *     multiPv: 3,
 *     onEval: (ev) => { ev.depth, ev.cp, ev.mate, ev.pvs[] },
 *     onStatus: (msg) => { ... },
 *     onReady: (engineName) => { ... },
 *   });
 *   ceval.init();
 *   ceval.analyze('lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1');
 *   ceval.stop();
 *   ceval.destroy();
 */

class ClientEval {
  constructor(opts = {}) {
    this.opts = opts;
    this.engine      = null;
    this.state       = 'idle';   // idle | loading | ready | computing
    this.engineName  = '';
    this.isReady     = false;
    this.stopRequested = false;

    this.currentWork  = null;    // work being computed
    this.nextWork     = null;    // queued work
    this.currentEval  = null;
    this.pvs          = [];
    this.currentDepth = 0;
    this._activeMultiPv = 0;     // currently configured MultiPV value (0 = not set)
  }

  // ── Capability check ───────────────────────────────────────────────────────

  static isSupported() {
    if (typeof WebAssembly === 'undefined') return false;
    if (typeof SharedArrayBuffer === 'undefined') return false;
    try {
      // Test shared memory (requires COOP + COEP headers on the page)
      new WebAssembly.Memory({ shared: true, initial: 1, maximum: 2 });
      // Test WASM SIMD — required by yaneuraou.k-p
      const simd = new Uint8Array([
        0,97,115,109,1,0,0,0,1,5,1,96,0,1,123,3,2,1,0,
        10,10,1,8,0,65,0,253,15,253,98,11,
      ]);
      if (!WebAssembly.validate(simd)) return false;
      return true;
    } catch (_) {
      return false;
    }
  }

  // ── Initialisation ─────────────────────────────────────────────────────────

  static _ENGINE_BASE = '/engine-wasm/';
  static _ENGINE_JS   = '/engine-wasm/yaneuraou.k-p.js';

  async init() {
    if (this.state !== 'idle') return;
    this.state = 'loading';
    this.opts.onStatus?.('Loading engine…');

    try {
      const wasmMemory = this._allocateMemory();

      // Fetch the pthread worker script and wrap it in a Blob URL.
      // This sidesteps any COEP/CORS complications with new Worker(url)
      // and ensures the worker is always treated as same-origin.
      const workerResp = await fetch(ClientEval._ENGINE_BASE + 'yaneuraou.k-p.worker.js');
      if (!workerResp.ok) throw new Error('Worker fetch failed: ' + workerResp.status);
      const workerText = await workerResp.text();
      const workerBlobUrl = URL.createObjectURL(
        new Blob([workerText], { type: 'application/javascript' })
      );
      this._workerBlobUrl = workerBlobUrl;

      await this._loadScript(ClientEval._ENGINE_JS);

      if (typeof window.YaneuraOu_K_P !== 'function') {
        console.error('[ceval] YaneuraOu_K_P not found after script load');
        this.state = 'idle';
        this.opts.onStatus?.('Engine failed to load: module not found');
        return;
      }

      this.engine = await window.YaneuraOu_K_P({
        locateFile: (path) => {
          // Redirect worker creation to Blob URL; everything else to engine-wasm/
          if (path === 'yaneuraou.k-p.worker.js') return workerBlobUrl;
          return ClientEval._ENGINE_BASE + path;
        },
        // Tell Emscripten what URL the worker should importScripts() to load
        // the main module. Must be absolute — blob workers have an opaque-path
        // base URL, so /‐relative paths fail URL resolution inside the worker.
        mainScriptUrlOrBlob: new URL(ClientEval._ENGINE_JS, location.href).href,
        wasmMemory,
      });

      this.engine.addMessageListener(this._onLine.bind(this));
      this.state = 'ready';
      this._send('usi');
    } catch (e) {
      this.state = 'idle';
      console.error('[ceval] init failed:', e);
      this.opts.onStatus?.('Engine failed to load: ' + (e?.message || e));
    }
  }

  _allocateMemory() {
    // 128 MB minimum (2048 × 64 KB pages) for NNUE; try large maximum first
    const minPages = 2048;
    let hi = (navigator.userAgent.includes('Android') || navigator.userAgent.includes('iPad'))
      ? 8192 : 32767;
    let shrink = 4;
    while (true) {
      try {
        return new WebAssembly.Memory({ shared: true, initial: minPages, maximum: hi });
      } catch (e) {
        if (hi <= minPages || !(e instanceof RangeError)) throw e;
        hi = Math.max(minPages, Math.ceil(hi - hi / shrink));
        shrink = shrink === 4 ? 3 : 4;
      }
    }
  }

  _loadScript(url) {
    return new Promise((resolve, reject) => {
      if (document.querySelector(`script[data-ceval-src="${url}"]`)) { resolve(); return; }
      const s = document.createElement('script');
      s.setAttribute('data-ceval-src', url);
      s.src = url;
      s.onload = resolve;
      s.onerror = () => reject(new Error('Failed to load ' + url));
      document.head.appendChild(s);
    });
  }

  // ── USI communication ──────────────────────────────────────────────────────

  _send(cmd) {
    this.engine?.postMessage(cmd);
  }

  _onLine(line) {
    const parts = line.trim().split(/\s+/);
    if (!parts[0]) return;

    switch (parts[0]) {
      case 'id':
        if (parts[1] === 'name') this.engineName = parts.slice(2).join(' ');
        break;

      case 'usiok': {
        const threads  = Math.min(
          this.opts.threads  || Math.max(1, Math.floor((navigator.hardwareConcurrency || 2) / 2)),
          16
        );
        const hashMb   = this.opts.hashMb   || 32;
        const multiPv  = this.opts.multiPv  || 1;
        this._send(`setoption name EnteringKingRule value CSARule27H`);
        this._send(`setoption name USI_Hash value ${hashMb}`);
        this._send(`setoption name Threads value ${threads}`);
        this._send(`setoption name MultiPV value ${multiPv}`);
        this._send('usinewgame');
        this._send('isready');
        break;
      }

      case 'readyok':
        this.isReady = true;
        this.opts.onReady?.(this.engineName);
        if (this.nextWork) this._startWork();
        break;

      case 'bestmove': {
        const finalEval  = this.currentEval;
        const perWorkCb  = this.currentWork?.onComplete;
        const hadWork    = !!this.currentWork;
        this.currentWork = null;
        if (hadWork) {
          if (finalEval) this.opts.onComplete?.(finalEval); // global callback (used by regular ceval)
          perWorkCb?.(finalEval);                           // per-position callback — always called (may be null)
        }
        if (this.nextWork) this._startWork();
        else this.opts.onStatus?.('ready');
        break;
      }

      case 'info':
        if (this.currentWork && !this.stopRequested) this._parseInfo(parts);
        break;
    }
  }

  _parseInfo(parts) {
    let depth = 0, cp = null, mate = null, multipv = 1;
    let pvStart = -1;
    let i = 1;

    while (i < parts.length) {
      switch (parts[i]) {
        case 'depth':   depth   = parseInt(parts[++i]); break;
        case 'multipv': multipv = parseInt(parts[++i]); break;
        case 'score':
          if      (parts[i+1] === 'cp')   { cp   = parseInt(parts[i+2]); i += 2; }
          else if (parts[i+1] === 'mate') { mate = parseInt(parts[i+2]); i += 2; }
          break;
        case 'pv':
          pvStart = i + 1;
          i = parts.length - 1;
          break;
      }
      i++;
    }

    if (depth < 6) return;

    const pvMoves = pvStart >= 0 ? parts.slice(pvStart) : [];
    this.pvs[multipv - 1] = { moves: pvMoves, cp: mate == null ? cp : null, mate: mate != null ? mate : null, depth };
    this.currentDepth = depth;

    // Emit after getting the first PV entry (index 0)
    if (multipv === 1) {
      this.currentEval = {
        depth,
        cp:   mate == null ? cp : null,
        mate: mate != null ? mate : null,
        pvs:  [...this.pvs].filter(Boolean),
      };
      this.opts.onEval?.(this.currentEval);
    }
  }

  // ── Analysis control ───────────────────────────────────────────────────────

  /**
   * Analyze a SFEN position.
   * @param {string} sfen      Full SFEN string
   * @param {object} workOpts  { movetime: ms, depth: N, multiPv: N }
   *   depth and movetime are mutually exclusive — depth takes priority when set.
   */
  analyze(sfen, workOpts = {}) {
    if (this.state === 'idle') { this.init(); }

    const work = {
      sfen,
      movetime:   workOpts.movetime   || 90000,
      depth:      workOpts.depth      || null,
      multiPv:    workOpts.multiPv    || null,
      onComplete: workOpts.onComplete || null,
    };

    if (this.currentWork) {
      this.nextWork = work;
      if (!this.stopRequested) {
        this.stopRequested = true;
        this._send('stop');
      }
    } else {
      this.nextWork = work;
      if (this.isReady) this._startWork();
      // else wait for readyok
    }
  }

  _startWork() {
    if (!this.nextWork) return;
    this.currentWork  = this.nextWork;
    this.nextWork     = null;
    this.currentEval  = null;
    this.pvs          = [];
    this.currentDepth = 0;
    this.stopRequested = false;

    // Update MultiPV if this work overrides it
    const wantMultiPv = this.currentWork.multiPv;
    if (wantMultiPv && wantMultiPv !== this._activeMultiPv) {
      this._send(`setoption name MultiPV value ${wantMultiPv}`);
      this._activeMultiPv = wantMultiPv;
    }

    this._send(`position sfen ${this.currentWork.sfen}`);
    if (this.currentWork.depth) {
      this._send(`go depth ${this.currentWork.depth}`);
    } else {
      this._send(`go movetime ${this.currentWork.movetime}`);
    }
    this.opts.onStatus?.('computing');
  }

  stop() {
    this.nextWork = null;
    if (this.currentWork && !this.stopRequested) {
      this.stopRequested = true;
      this._send('stop');
    }
  }

  destroy() {
    this.stop();
    try { this.engine?.terminate?.(); } catch (_) {}
    this.engine = null;
    this.state  = 'idle';
    this.isReady = false;
  }
}

// ── Eval bar helper ────────────────────────────────────────────────────────

/**
 * CevalBar — renders a vertical evaluation bar and engine info line.
 *
 * @param {string} barEl   CSS selector for the bar fill element
 * @param {string} infoEl  CSS selector for the info text element
 */
class CevalBar {
  constructor(barEl, infoEl) {
    this.bar  = document.querySelector(barEl);
    this.info = document.querySelector(infoEl);
  }

  /** winChance: -1 (gote wins) … +1 (sente wins) */
  static cpToWinChance(cp) {
    const MULT = -0.0007;
    const clamped = Math.max(-5500, Math.min(5500, cp));
    return 2 / (1 + Math.exp(MULT * clamped)) - 1;
  }

  static evalToWinChance(ev) {
    if (ev.mate != null) {
      // mate in N: treat as extreme cp
      const extreme = (80 - Math.min(20, Math.abs(ev.mate))) * 100;
      const sign    = ev.mate > 0 ? 1 : -1;
      return CevalBar.cpToWinChance(extreme * sign);
    }
    if (ev.cp != null) return CevalBar.cpToWinChance(ev.cp);
    return 0;
  }

  static evalLabel(ev) {
    if (ev.mate != null) {
      const sign = ev.mate > 0 ? '▲' : '▽';
      return `${sign} 詰 ${Math.abs(ev.mate)}`;
    }
    if (ev.cp != null) {
      const sign = ev.cp >= 0 ? '▲' : '▽';
      return `${sign} ${Math.abs(ev.cp)}`;
    }
    return '';
  }

  update(ev, engineName, isComputing) {
    if (!this.bar && !this.info) return;

    const wc = ev ? CevalBar.evalToWinChance(ev) : 0;
    // wc: +1 = sente winning, -1 = gote winning
    // Horizontal bar: sente fills from left, width = sente's share
    const sentePct = Math.round(((wc + 1) / 2) * 100);

    if (this.bar) {
      this.bar.style.width = sentePct + '%';
    }

    if (this.info) {
      if (!ev) {
        this.info.textContent = isComputing ? '…' : '';
      } else {
        const label = CevalBar.evalLabel(ev);
        const depth = ev.depth ? ` d${ev.depth}` : '';
        this.info.textContent = label + depth;
      }
    }
  }

  reset() {
    if (this.bar)  this.bar.style.width = '50%';
    if (this.info) this.info.textContent = '';
  }
}
