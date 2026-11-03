// TVCouchBrowser page script. Injected at document start into every frame by BrowserActivity.
// The app calls window.__tvcb.* in the top frame; the page talks back through the TVCB bridge.
(function () {
  'use strict';
  if (window.__tvcb) return;
  const bridge = window.TVCB;
  const top = window === window.top;
  const host = location.hostname;

  // ---- YouTube: drop the ad fields from player responses (what uBO's json-prune scriptlets do) ----
  if (/(^|\.)youtube\.com$/.test(host) && bridge && bridge.adblockOn(host)) {
    const AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];
    const prune = (o) => {
      if (o && typeof o === 'object') {
        for (const k of AD_KEYS) if (k in o) delete o[k];
        if (o.playerResponse) prune(o.playerResponse);
      }
      return o;
    };
    for (const name of ['ytInitialPlayerResponse', 'playerResponse']) {
      let v;
      try {
        Object.defineProperty(window, name, {
          configurable: true,
          get: () => v,
          set: (x) => { v = prune(x); },
        });
      } catch (e) { /* already defined */ }
    }
    const parse = JSON.parse;
    JSON.parse = function () { return prune(parse.apply(this, arguments)); };
    const json = Response.prototype.json;
    Response.prototype.json = function () { return json.apply(this, arguments).then(prune); };
  }

  // ---- cosmetic filtering (HTML documents only: a <style> in XML or plain text would show as text) ----
  const isHtml = document.documentElement ? document.documentElement.namespaceURI === 'http://www.w3.org/1999/xhtml'
    : /html/.test(document.contentType || '');
  if (bridge && host && isHtml) {
    const addCss = (css) => {
      if (!css) return;
      const s = document.createElement('style');
      s.textContent = css;
      (document.head || document.documentElement).appendChild(s);
    };
    addCss(bridge.cosmetic(host, top));
    const seen = new Set();
    let queued = [];
    const collect = (root) => {
      const els = root.querySelectorAll ? root.querySelectorAll('[id],[class]') : [];
      const each = (el) => {
        if (el.id && !seen.has('#' + el.id)) { seen.add('#' + el.id); queued.push('#' + el.id); }
        const cl = el.classList;
        if (cl) for (const c of cl) if (!seen.has('.' + c)) { seen.add('.' + c); queued.push('.' + c); }
      };
      if (root.nodeType === 1) each(root);
      for (const el of els) each(el);
    };
    let timer = 0;
    const flush = () => {
      timer = 0;
      if (!queued.length) return;
      const batch = queued; queued = [];
      addCss(bridge.generic(host, JSON.stringify(batch)));
    };
    const schedule = () => { if (!timer) timer = setTimeout(flush, 300); };
    const start = () => {
      collect(document);
      flush();
      new MutationObserver((muts) => {
        for (const m of muts) for (const n of m.addedNodes) if (n.nodeType === 1) collect(n);
        if (queued.length) schedule();
      }).observe(document.documentElement, { childList: true, subtree: true });
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
  }

  // ---- media: keep the screen on while something plays ----
  if (bridge) {
    const report = () => {
      const playing = [...document.querySelectorAll('video,audio')].some((m) => !m.paused && !m.ended);
      bridge.media(playing);
    };
    for (const ev of ['play', 'playing', 'pause', 'ended', 'emptied']) document.addEventListener(ev, report, true);
  }

  if (!top) { window.__tvcb = {}; return; }

  // ---- link mode ----
  const FOCUSABLE = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],' +
    '[role=tab],[role=menuitem],[role=option],[role=checkbox],[onclick],[tabindex]:not([tabindex="-1"]),' +
    '[contenteditable=""],[contenteditable=true],iframe';
  let current = null;
  let ring = null;

  const visibleRect = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return null;
    if (r.bottom < 0 || r.right < 0 || r.top > innerHeight || r.left > innerWidth) return null;
    const st = getComputedStyle(el);
    if (st.visibility === 'hidden' || st.display === 'none' || +st.opacity === 0) return null;
    return r;
  };

  const candidates = () => {
    const out = [];
    for (const el of document.querySelectorAll(FOCUSABLE)) {
      if (el.disabled) continue;
      const r = visibleRect(el);
      if (!r) continue;
      // skip elements covered by something else (e.g. hidden behind a dialog)
      const cx = Math.min(Math.max(r.left + r.width / 2, 0), innerWidth - 1);
      const cy = Math.min(Math.max(r.top + r.height / 2, 0), innerHeight - 1);
      const hit = document.elementFromPoint(cx, cy);
      if (hit && hit !== el && !el.contains(hit) && !hit.contains(el)) continue;
      out.push({ el, r });
    }
    // drop wrappers that contain another candidate, keep the innermost
    return out.filter((c) => !out.some((o) => o !== c && c.el.contains(o.el) && c.el.tagName !== 'A'));
  };

  const showRing = (el) => {
    if (!ring) {
      ring = document.createElement('div');
      // White ring, dark outline, blue glow: visible on light, dark and blue pages alike.
      ring.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:4px solid #fff;' +
        'border-radius:8px;box-shadow:0 0 0 3px #000,0 0 0 6px #4DA3FF,0 0 22px 6px rgba(77,163,255,.7);' +
        'transition:all .08s';
    }
    if (!ring.isConnected) document.documentElement.appendChild(ring);
    const r = el.getBoundingClientRect();
    Object.assign(ring.style, {
      // 8px clear of the element on every side (4px gap + 4px border)
      left: r.left - 8 + 'px', top: r.top - 8 + 'px', width: r.width + 8 + 'px', height: r.height + 8 + 'px',
      display: 'block',
    });
  };

  const hideRing = () => { if (ring) ring.style.display = 'none'; };

  const center = (r) => ({ x: r.left + r.width / 2, y: r.top + r.height / 2 });

  // Picks the nearest candidate in a direction: distance along it plus 2x the sideways offset.
  const nav = (dir) => {
    const list = candidates();
    let from = current && current.isConnected ? visibleRect(current) : null;
    if (!from) {
      const first = list.sort((a, b) => (a.r.top - b.r.top) || (a.r.left - b.r.left))[0];
      if (first) { focusEl(first.el); return 'ok'; }
      return scrollDir(dir);
    }
    const c = center(from);
    let best = null; let bestScore = Infinity;
    for (const { el, r } of list) {
      if (el === current) continue;
      const p = center(r);
      let along; let side;
      if (dir === 'right') { along = r.left - from.right; side = Math.abs(p.y - c.y); if (p.x <= c.x) continue; }
      if (dir === 'left') { along = from.left - r.right; side = Math.abs(p.y - c.y); if (p.x >= c.x) continue; }
      if (dir === 'down') { along = r.top - from.bottom; side = Math.abs(p.x - c.x); if (p.y <= c.y) continue; }
      if (dir === 'up') { along = from.top - r.bottom; side = Math.abs(p.x - c.x); if (p.y >= c.y) continue; }
      const score = Math.max(along, 0) + 2 * side;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (best) { focusEl(best); return 'ok'; }
    return scrollDir(dir);
  };

  const focusEl = (el) => {
    current = el;
    el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    showRing(el);
    try { el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true })); } catch (e) { /* ignore */ }
  };

  const scrollDir = (dir) => {
    const dx = dir === 'left' ? -1 : dir === 'right' ? 1 : 0;
    const dy = dir === 'up' ? -1 : dir === 'down' ? 1 : 0;
    window.scrollBy({ left: dx * innerWidth * 0.4, top: dy * innerHeight * 0.4, behavior: 'smooth' });
    current = null; hideRing();
    return 'scrolled';
  };

  // Where the app should tap to activate the focused element, as fractions of the viewport.
  const target = () => {
    const r = current && current.isConnected ? visibleRect(current) : null;
    if (!r) return '';
    const p = center(r);
    return JSON.stringify({ x: p.x / innerWidth, y: p.y / innerHeight });
  };

  // ---- cursor mode: scroll whatever is under the cursor ----
  const scrollAt = (fx, fy, dx, dy) => {
    let el = document.elementFromPoint(fx * innerWidth, fy * innerHeight);
    while (el && el !== document.body && el !== document.documentElement) {
      const st = getComputedStyle(el);
      const canY = dy && /(auto|scroll)/.test(st.overflowY) && el.scrollHeight > el.clientHeight + 1;
      const canX = dx && /(auto|scroll)/.test(st.overflowX) && el.scrollWidth > el.clientWidth + 1;
      if (canY || canX) {
        const beforeT = el.scrollTop; const beforeL = el.scrollLeft;
        el.scrollBy(dx, dy);
        if (el.scrollTop !== beforeT || el.scrollLeft !== beforeL) return;
      }
      el = el.parentElement;
    }
    window.scrollBy(dx, dy);
  };

  // ---- video keys ----
  const activeVideo = () => {
    const vids = [...document.querySelectorAll('video')];
    return vids.find((v) => !v.paused) ||
      vids.map((v) => ({ v, a: v.clientWidth * v.clientHeight })).sort((a, b) => b.a - a.a).map((o) => o.v)[0];
  };

  const media = (cmd) => {
    const v = activeVideo();
    if (!v) return 'none';
    switch (cmd) {
      case 'playpause': v.paused ? v.play() : v.pause(); break;
      case 'play': v.play(); break;
      case 'pause': v.pause(); break;
      case 'ff': v.currentTime = Math.min(v.currentTime + 10, v.duration || Infinity); break;
      case 'rew': v.currentTime = Math.max(v.currentTime - 10, 0); break;
      case 'next': v.currentTime = Math.min(v.currentTime + 60, v.duration || Infinity); break;
      case 'prev': v.currentTime = Math.max(v.currentTime - 60, 0); break;
    }
    return 'ok';
  };

  const exitFullscreen = () => {
    if (document.fullscreenElement) { document.exitFullscreen(); return true; }
    return false;
  };

  // ---- typing from the phone ----
  const editable = (el) => el && (el.isContentEditable ||
    (el.tagName === 'TEXTAREA') ||
    (el.tagName === 'INPUT' && /^(text|search|url|email|password|tel|number|)$/i.test(el.type)));

  const type = (text) => {
    let el = document.activeElement;
    if (!editable(el) && current && editable(current)) { el = current; el.focus(); }
    if (!editable(el)) return el && el.tagName === 'IFRAME' ? 'iframe' : 'nofield';
    if (!document.execCommand('insertText', false, text)) {
      if (el.isContentEditable) el.textContent += text;
      else {
        const s = el.selectionStart ?? el.value.length; const e = el.selectionEnd ?? el.value.length;
        el.setRangeText(text, s, e, 'end');
        el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
      }
    }
    return 'ok';
  };

  // ---- clean up: overlays, cookie walls, sticky ad bars, scroll locks ----
  const ANNOY = /(^|[-_\s])(ad|ads|advert|adsbox|banner|sponsor|promo|cookie|consent|gdpr|cmp|newsletter|subscribe|popup|pop-up|modal|overlay|interstitial|paywall|lightbox|backdrop)([-_\s]|$)/i;
  const nameOf = (el) => (el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '');

  const unlockScroll = () => {
    let changed = false;
    for (const el of [document.documentElement, document.body]) {
      if (!el) continue;
      const st = getComputedStyle(el);
      if (st.overflow === 'hidden' || st.overflowY === 'hidden') {
        el.style.setProperty('overflow', 'auto', 'important');
        el.style.setProperty('overflow-y', 'auto', 'important');
        changed = true;
      }
      if (el === document.body && st.position === 'fixed') {
        // Sites lock scrolling by fixing the body at -scrollY; put it back where it was.
        const y = -parseInt(el.style.top || '0', 10) || 0;
        el.style.setProperty('position', 'static', 'important');
        el.style.setProperty('top', 'auto', 'important');
        if (y) window.scrollTo(0, y);
        changed = true;
      }
    }
    // Paywalls and cookie walls blur the article behind them.
    for (const el of document.querySelectorAll('body, body > *, main, article')) {
      const f = getComputedStyle(el).filter;
      if (f && f.includes('blur')) { el.style.setProperty('filter', 'none', 'important'); changed = true; }
    }
    return changed;
  };

  const cleanup = () => {
    let n = 0;
    const vw = innerWidth; const vh = innerHeight;
    const links = document.querySelectorAll('a[href]').length;
    const fs = document.fullscreenElement;
    for (const el of document.querySelectorAll('body *')) {
      if (!el.isConnected) continue;
      const st = getComputedStyle(el);
      if (st.position !== 'fixed' && st.position !== 'sticky') continue;
      if (fs && (el.contains(fs) || fs.contains(el))) continue;
      if (el === ring) continue;
      const r = el.getBoundingClientRect();
      const w = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
      const h = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
      if (!w || !h) continue;
      const cover = (w * h) / (vw * vh);
      // Never take the page's own layout: something holding most of its links is the site itself.
      const own = el.querySelectorAll('a[href]').length;
      if (links > 20 && own > links * 0.5) continue;
      const big = cover >= 0.3 && st.position === 'fixed';
      const annoying = ANNOY.test(nameOf(el)) && own <= 8;
      const adFrame = cover < 0.3 && own <= 3 && el.querySelector('iframe') && (r.top <= 0 || r.bottom >= vh - 1);
      if (big || annoying || adFrame) { el.remove(); n++; }
    }
    if (unlockScroll()) n++;
    return n;
  };

  // ---- remove element ("zap") and a selector to remember it by ----
  const stableName = (x) => /^[A-Za-z_][\w-]{1,40}$/.test(x) && !/\d{4,}/.test(x) && !/^(css|sc|jsx|emotion)-/.test(x);

  const selectorFor = (el) => {
    const tag = el.tagName.toLowerCase();
    let sel = '';
    if (el.id && stableName(el.id)) sel = '#' + CSS.escape(el.id);
    else if (tag === 'iframe' && el.src) {
      try {
        const u = new URL(el.src, location.href);
        if (u.hostname && u.hostname !== location.hostname) sel = 'iframe[src*="' + u.hostname.replace(/"/g, '') + '"]';
      } catch (e) { /* ignore */ }
    }
    if (!sel) {
      const cls = [...el.classList].filter(stableName).slice(0, 3);
      if (!cls.length) return '';
      sel = tag + cls.map((c) => '.' + CSS.escape(c)).join('');
      const p = el.parentElement;
      if (p && p.id && stableName(p.id)) sel = '#' + CSS.escape(p.id) + ' > ' + sel;
    }
    // Only remember selectors that pick out this element and few others.
    try {
      const count = document.querySelectorAll(sel).length;
      return count >= 1 && count <= 3 ? sel : '';
    } catch (e) { return ''; }
  };

  const area = (e) => { const r = e.getBoundingClientRect(); return r.width * r.height; };

  // Returns '' (nothing there), '-' (removed, not remembered) or the selector to remember.
  const zapEl = (el) => {
    if (!el || el === document.body || el === document.documentElement || el === ring) return '';
    // Climb out of wrappers that are barely bigger than what was pointed at.
    while (el.parentElement && el.parentElement !== document.body && el.parentElement !== document.documentElement &&
      area(el.parentElement) <= area(el) * 1.25) el = el.parentElement;
    const sel = selectorFor(el);
    el.remove();
    unlockScroll();
    if (current && !current.isConnected) { current = null; hideRing(); }
    return sel || '-';
  };

  const zapAt = (fx, fy) => {
    if (ring) ring.style.display = 'none';
    const el = document.elementFromPoint(fx * innerWidth, fy * innerHeight);
    return zapEl(el);
  };

  const zapCurrent = () => (current && current.isConnected ? zapEl(current) : '');

  // Not writable or extensible, so a page can't swap in its own versions of these.
  const api = Object.freeze({ nav, target, hideRing, scrollAt, media, exitFullscreen, type, cleanup, zapAt, zapCurrent,
    reset: () => { current = null; hideRing(); } });
  try {
    Object.defineProperty(window, '__tvcb', { value: api, writable: false, configurable: false });
  } catch (e) { window.__tvcb = api; }
})();
