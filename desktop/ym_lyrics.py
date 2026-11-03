# -*- coding: utf-8 -*-
"""
Кнопка «Текст» для десктопной Яндекс Музыки (Windows) — v6.

  * Текст Яндекса (если есть) показывается в этом же экране; если нет — берёт синхронизированный текст из LRCLIB / NetEase (или обычный из Genius)
    и показывает его прямо в приложении: подсветка строки «караоке», плавная прокрутка
    под музыку, фон в цветах обложки, клик по строке — перемотка.
  * Полноэкранный экран текста со своим плеером: пауза, треки, перемотка,
    лайк, «Моя волна по треку», добавление в плейлист.
  * Правый клик по кнопке или Alt+L — всегда текст из интернета. Кнопку можно перетащить.
  * Источники опрашиваются параллельно, найденное кэшируется на диске.

Файлы приложения НЕ изменяются. Запускайте Яндекс Музыку через ym_lyrics.bat.
"""
import base64
import concurrent.futures as cf
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser


def _ensure_websocket():
    try:
        import websocket  # noqa: F401
    except ImportError:
        print("Устанавливаю websocket-client (один раз)…")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "--user", "-q", "websocket-client"])
    import websocket
    return websocket


websocket = _ensure_websocket()

PORT = 9333
EXE_NAME = "Яндекс Музыка.exe"
EXE = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "YandexMusic", EXE_NAME)
BINDING = "__ymLyrics"
UA = "ym-lyrics-button/6.0"

# ----------------------------------------------------------------------------
# Скрипт, который встраивается в окно Яндекс Музыки
# ----------------------------------------------------------------------------
INJECT = r"""
(() => {
  if (window.__ymlx) return;
  window.__ymlx = true;

  // ===================== утилиты =====================
  const $ = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const NS = 'http://www.w3.org/2000/svg';
  const icon = d => {
    const s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('class', 'ymlx-ic');
    const p = document.createElementNS(NS, 'path');
    p.setAttribute('d', d);
    s.appendChild(p);
    return s;
  };
  const I = {
    lyrics: 'M4 6h10M4 10h10M4 14h6M17 18.5a2.5 2.5 0 1 1-2.5-2.5H17V5l4 1.5',
    close: 'M6 6l12 12M18 6L6 18',
    refresh: 'M20 12a8 8 0 1 1-2.6-5.9M20 4v4.5h-4.5',
    web: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M3.5 9h17M3.5 15h17M12 3c-2.8 3-2.8 15 0 18M12 3c2.8 3 2.8 15 0 18',
    left: 'M14 6l-6 6 6 6',
    right: 'M10 6l6 6-6 6',
    full: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5',
    heart: 'M12 20.3s-7.8-4.7-7.8-10.4A4.5 4.5 0 0 1 12 7.1a4.5 4.5 0 0 1 7.8 2.8c0 5.7-7.8 10.4-7.8 10.4z',
    wave: 'M2.5 12c1.6-3.6 3.2-3.6 4.8 0s3.2 3.6 4.8 0 3.2-3.6 4.8 0 3.2 3.6 4.6 0',
    addlist: 'M4 6h12M4 11h12M4 16h7M18 13.5v7M14.5 17h7',
    more: 'M5 12h.01M12 12h.01M19 12h.01',
  };
  const F = {   // залитые иконки плеера
    play: 'M8 5.5v13a1 1 0 0 0 1.5.86l10.5-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z',
    pause: 'M7 4.5h3.2a.8.8 0 0 1 .8.8v13.4a.8.8 0 0 1-.8.8H7a.8.8 0 0 1-.8-.8V5.3a.8.8 0 0 1 .8-.8zM13.8 4.5H17a.8.8 0 0 1 .8.8v13.4a.8.8 0 0 1-.8.8h-3.2a.8.8 0 0 1-.8-.8V5.3a.8.8 0 0 1 .8-.8z',
    prev: 'M5.5 5h2v14h-2zM19.5 6.3v11.4a1 1 0 0 1-1.55.83L9.6 12.83a1 1 0 0 1 0-1.66l8.35-5.7a1 1 0 0 1 1.55.83z',
    next: 'M16.5 5h2v14h-2zM4.5 6.3v11.4a1 1 0 0 0 1.55.83l8.35-5.7a1 1 0 0 0 0-1.66L6.05 5.47A1 1 0 0 0 4.5 6.3z',
  };
  const ficon = d => { const s = icon(d); s.setAttribute('class', 'ymlx-ic ymlx-fill'); return s; };
  const clamp = (v, a, b) => v < a ? a : v > b ? b : v;
  const tb = (child, title, cls) => {
    const b = $('button', 'ymlx-tb' + (cls ? ' ' + cls : ''));
    b.append(typeof child === 'string' ? document.createTextNode(child) : child);
    if (title) b.title = title;
    return b;
  };

  // ===================== настройки =====================
  const LS = 'ymlx-settings';
  const S = Object.assign({ offset: 0, size: 1, right: 24, bottom: 104 },
    (() => { try { return JSON.parse(localStorage.getItem(LS)) || {}; } catch (e) { return {}; } })());
  const save = () => { try { localStorage.setItem(LS, JSON.stringify(S)); } catch (e) {} };

  // ===================== позиция трека =====================
  const medias = new Set();
  const origPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () { medias.add(this); return origPlay.apply(this, arguments); };

  let msPos = null;   // из navigator.mediaSession.setPositionState
  try {
    const ms = navigator.mediaSession;
    const orig = ms && ms.setPositionState && ms.setPositionState.bind(ms);
    if (orig) ms.setPositionState = st => {
      if (st && st.duration) msPos = { p: st.position || 0, d: st.duration, r: st.playbackRate || 1, at: performance.now() };
      return orig(st);
    };
  } catch (e) {}

  let media = null, mediaScan = 0, range = null, rangeScan = 0;
  const findMedia = () => {
    if (media && media.duration > 0 && !media.paused) return media;
    const now = performance.now();
    if (now - mediaScan < 500) return media && media.duration > 0 ? media : null;
    mediaScan = now;
    document.querySelectorAll('audio,video').forEach(m => medias.add(m));
    let best = null;
    for (const m of medias) {
      if (!(m.duration > 0)) continue;
      if (!m.paused) { best = m; break; }
      if (!best) best = m;
    }
    return (media = best);
  };
  const findRange = () => {
    const now = performance.now();
    if (range && range.isConnected && now - rangeScan < 1000) return range;
    rangeScan = now; range = null;
    for (const r of document.querySelectorAll('input[type="range"]')) {
      const max = +r.max;
      if (max > 20 && max < 7200 && max !== 100 && (!range || max > +range.max)) range = r;
    }
    return range;
  };
  const getTime = () => {
    const m = findMedia();
    if (m) return { t: m.currentTime, d: m.duration };
    if (msPos) {
      const playing = navigator.mediaSession.playbackState !== 'paused';
      return { t: msPos.p + (playing ? (performance.now() - msPos.at) / 1000 * msPos.r : 0), d: msPos.d };
    }
    const r = findRange();
    return r ? { t: +r.value, d: +r.max } : null;
  };
  // ===================== токен Яндекса =====================
  // Приложение само ходит в api.music.yandex.net с заголовком «Authorization: OAuth …».
  // Токен передаётся только локальному лаунчеру — чтобы запросить текст трека у Яндекса.
  let ymToken = '';
  const grab = v => {
    if (typeof v !== 'string' || !/^OAuth\s+\S+/.test(v) || v === ymToken) return;
    ymToken = v;
    try { window.__ymLyrics(JSON.stringify({ action: 'token', value: v.replace(/^OAuth\s+/, '') })); } catch (e) {}
  };
  try {
    const of = window.fetch;
    window.fetch = function (input, init) {
      try {
        const h = (init && init.headers) || (input && input.headers);
        if (h) {
          if (typeof Headers !== 'undefined' && h instanceof Headers) grab(h.get('authorization'));
          else if (Array.isArray(h)) h.forEach(kv => /^authorization$/i.test(kv[0]) && grab(kv[1]));
          else for (const k in h) if (/^authorization$/i.test(k)) grab(h[k]);
        }
      } catch (e) {}
      return of.apply(this, arguments);
    };
    const osh = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.setRequestHeader = function (k, v) { if (/^authorization$/i.test(k)) grab(v); return osh.apply(this, arguments); };
  } catch (e) {}

  const scanToken = () => {
    try {
      for (const store of [localStorage, sessionStorage]) {
        for (let i = 0; i < store.length; i++) {
          const v = store.getItem(store.key(i)) || '';
          const m = v.match(/(y0_[A-Za-z0-9_-]{20,}|AQAAAA[A-Za-z0-9_-]{20,})/);
          if (m) window.__ymLyrics(JSON.stringify({ action: 'token_candidate', value: m[1] }));
        }
      }
    } catch (e) {}
  };
  setTimeout(scanToken, 1500);

  // ===================== управление плеером =====================
  // 1) обработчики Media Session самого приложения  2) кнопки плеера Яндекса  3) медиаклавиши Windows
  const handlers = {};
  try {
    const ms = navigator.mediaSession;
    const origSet = ms.setActionHandler.bind(ms);
    ms.setActionHandler = (a, h) => { handlers[a] = h; return origSet(a, h); };
  } catch (e) {}
  const RE = {
    prev: /предыдущ|previous|(^|[^a-z])prev([^a-z]|$)/i,
    next: /следующ|(^|[^a-z])next([^a-z]|$)/i,
    pause: /пауз|pause/i,
    play: /воспроизв|продолжить|resume|(^|[^a-z])play([^a-z]|$)/i,
    not: /контекст|меню|menu|плейлист|playlist|автопл|autoplay|видео|video/i,
  };
  const findBtn = re => {
    let best = null, bestTop = -1;
    for (const b of document.querySelectorAll('button,[role="button"]')) {
      if (b.closest('#ymlx-root')) continue;
      const s = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.getAttribute('data-test-id') || '');
      if (!re.test(s) || RE.not.test(s)) continue;
      const r = b.getBoundingClientRect();
      if (!r.width || !r.height) continue;
      if (r.top > bestTop) { best = b; bestTop = r.top; }   // ближе к низу — это плеер
    }
    return best;
  };
  const visible = r => r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight;
  const label = b => (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.getAttribute('data-test-id') || '');
  const bottomMost = (sel, test) => {
    let best = null, bestTop = -1;
    for (const e of document.querySelectorAll(sel)) {
      if (e.closest('#ymlx-root') || !test(e)) continue;
      const r = e.getBoundingClientRect();
      if (visible(r) && r.top > bestTop) { best = e; bestTop = r.top; }
    }
    return best;
  };
  RE.menu = /контекст|меню|menu|ещё|еще|(^|[^a-z])more([^a-z]|$)|действия/i;
  RE.like = /нрав|(^|[^a-z])like|лайк/i;
  RE.notLike = /не нрав|dislike|не рекоменд|неинтерес/i;
  const findMenuBtn = () => bottomMost('button,[role="button"]', b => RE.menu.test(label(b)));
  const findLikeBtn = () => bottomMost('button,[role="button"]', b => { const s = label(b); return RE.like.test(s) && !RE.notLike.test(s); });
  const isLiked = b => {
    if (!b) return false;
    const pr = b.getAttribute('aria-pressed') || b.getAttribute('aria-checked');
    if (pr != null) return pr === 'true';
    return /удал|убра|remove|unlike|liked|active/i.test(label(b) + ' ' + b.className);
  };
  const getTrackId = title => {
    const want = (title || '').trim().toLowerCase();
    const pick = exact => bottomMost('a[href*="/track/"]', a => /\/track\/\d+/.test(a.getAttribute('href') || '') &&
      (!exact || a.textContent.trim().toLowerCase() === want));
    const a = (want && pick(true)) || pick(false);
    const m = a && /\/track\/(\d+)/.exec(a.getAttribute('href'));
    return m ? m[1] : '';
  };
  const findMenuItem = re => {
    let best = null;
    for (const e of document.querySelectorAll('[role="menuitem"],[role="option"],button,li,a,[role="button"]')) {
      if (e.closest('#ymlx-root')) continue;
      const t = (e.textContent || '').trim();
      if (!t || t.length > 60 || !re.test(t)) continue;
      if (!visible(e.getBoundingClientRect())) continue;
      if (!best || e.getAttribute('role') === 'menuitem') best = e;
    }
    return best;
  };
  const popupOpen = () => {
    for (const e of document.querySelectorAll('[role="menu"],[role="dialog"],[role="listbox"],[aria-modal="true"],[data-floating-ui-portal]')) {
      if (e.closest('#ymlx-root')) continue;
      const t = e.firstElementChild && e.hasAttribute('data-floating-ui-portal') ? e.firstElementChild : e;
      const r = t.getBoundingClientRect();
      if (r.width > 20 && r.height > 20) return true;
    }
    return false;
  };
  const waitFor = (fn, ms) => new Promise(res => {
    const t0 = performance.now();
    const step = () => { const v = fn(); if (v || performance.now() - t0 > ms) return res(v || null); setTimeout(step, 80); };
    step();
  });
  let playCache = { at: 0, v: false };
  const isPlaying = () => {
    const m = findMedia();
    if (m) return !m.paused;
    const ps = navigator.mediaSession && navigator.mediaSession.playbackState;
    if (ps === 'playing') return true;
    if (ps === 'paused') return false;
    const now = performance.now();
    if (now - playCache.at > 500) playCache = { at: now, v: !!findBtn(RE.pause) };
    return playCache.v;
  };
  const mediaKey = a => { try { window.__ymLyrics(JSON.stringify({ action: 'mediakey', key: a })); } catch (e) {} };
  const control = a => {
    if (a === 'toggle') {
      const playing = isPlaying(), h = handlers[playing ? 'pause' : 'play'];
      if (h) { try { h({ action: playing ? 'pause' : 'play' }); return; } catch (e) {} }
      const b = findBtn(RE.pause) || findBtn(RE.play);
      if (b) return void b.click();
      const m = findMedia();
      if (m) return void (m.paused ? m.play() : m.pause());
      return mediaKey(a);
    }
    const h = handlers[a];
    if (h) { try { h({ action: a }); return; } catch (e) {} }
    const b = findBtn(a === 'previoustrack' ? RE.prev : RE.next);
    if (b) return void b.click();
    mediaKey(a);
  };

  const seek = sec => {
    if (handlers.seekto) { try { handlers.seekto({ action: 'seekto', seekTime: sec }); return; } catch (e) {} }
    const m = findMedia();
    if (m) { m.currentTime = sec; return; }
    const r = findRange();
    if (r) {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(r, sec);
      r.dispatchEvent(new Event('input', { bubbles: true }));
      r.dispatchEvent(new Event('change', { bubbles: true }));
    }
  };

  // ===================== текущий трек =====================
  const txt = sel => { const e = document.querySelector(sel); return e ? e.textContent.trim() : ''; };
  const getTrack = () => {
    const m = navigator.mediaSession && navigator.mediaSession.metadata;
    if (m && m.title) {
      const art = m.artwork && m.artwork.length ? m.artwork[m.artwork.length - 1].src : '';
      return { artist: m.artist || '', title: m.title, art };
    }
    const title = txt('[data-test-id*="TRACK_TITLE"]') || txt('[class*="Meta_title"]');
    const artist = txt('[data-test-id*="TRACK_ARTIST"]') || txt('[data-test-id*="SEPARATED_ARTIST"]') || txt('[class*="Meta_artists"]');
    if (title) return { artist, title, art: '' };
    const parts = document.title.split(/\s+[—–-]\s+/);
    if (parts.length >= 2 && !/яндекс/i.test(parts[0])) return { title: parts[0], artist: parts[1], art: '' };
    return null;
  };
  const keyOf = t => t ? t.artist + '\u0000' + t.title : '';

  // ===================== родная кнопка текста Яндекса =====================
  const nativeLyricsBtn = () => {
    for (const b of document.querySelectorAll('button,[role="button"]')) {
      if (b.closest('#ymlx-root')) continue;
      const s = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.getAttribute('data-test-id') || '');
      if (/(^|[^а-яё])текст([^а-яё]|$)|lyric/i.test(s) && !/контекст|меню|menu/i.test(s)) {
        if (b.disabled || b.getAttribute('aria-disabled') === 'true') return null;
        return b;
      }
    }
    return null;
  };

  // ===================== цвет обложки =====================
  const hashHue = s => { let h = 7; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360; return h; };
  const rgb2hsl = (r, g, b) => {
    r /= 255; g /= 255; b /= 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
    if (mx === mn) return [0, 0, l * 100];
    const d = mx - mn, s = l > .5 ? d / (2 - mx - mn) : d / (mx + mn);
    const h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
    return [h * 60, s * 100, l * 100];
  };

  // ===================== стили =====================
  const FONT = '"YS Text","YS Display",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif';
  const DISPLAY = '"YS Display","YS Text",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif';
  const EASE = 'cubic-bezier(.22,1,.36,1)';
  const CSS = `
#ymlx-root{font-family:${FONT};-webkit-font-smoothing:antialiased}
#ymlx-root *{box-sizing:border-box}
.ymlx-ic{width:18px;height:18px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;flex:none}
.ymlx-ic.ymlx-fill{fill:currentColor;stroke:none}

.ymlx-fab{position:fixed;z-index:2147483646;width:44px;height:44px;border-radius:50%;border:0;display:grid;place-items:center;color:#fff;background:rgba(28,28,30,.6);-webkit-backdrop-filter:blur(14px) saturate(1.5);backdrop-filter:blur(14px) saturate(1.5);box-shadow:0 8px 24px rgba(0,0,0,.35),inset 0 0 0 1px rgba(255,255,255,.12);cursor:pointer;touch-action:none;transition:transform .25s ${EASE},background .25s,color .25s,box-shadow .35s}
.ymlx-fab:hover{transform:scale(1.08);background:rgba(48,48,52,.75)}
.ymlx-fab:active{transform:scale(.94)}
.ymlx-fab.on{background:#ffdb4d;color:#111;box-shadow:0 0 0 5px rgba(255,219,77,.22),0 8px 24px rgba(0,0,0,.35)}
.ymlx-fab.drag{transition:none;cursor:grabbing}
.ymlx-fab .ymlx-ic{width:20px;height:20px}
#ymlx-root.open .ymlx-fab{opacity:0;transform:scale(.6);pointer-events:none}
#ymlx-root.yield .ymlx-ov{opacity:0!important;visibility:hidden!important;transition:none!important}
.ymlx-acts{display:flex;align-items:center;gap:8px;margin-bottom:4px}
@media (max-height:780px){.ymlx-keys{display:none}}
.ymlx-ab{height:36px;padding:0 14px;display:inline-flex;align-items:center;gap:8px;border:0;border-radius:99px;background:rgba(255,255,255,.1);color:#fff;font:700 13px/1 ${FONT};white-space:nowrap;cursor:pointer;transition:background .2s,transform .15s,color .2s}
.ymlx-ab:hover{background:rgba(255,255,255,.2)}
.ymlx-ab:active{transform:scale(.93)}
.ymlx-ab.icon{width:36px;padding:0;justify-content:center}
.ymlx-ab.liked{color:#ff4d6d;background:rgba(255,77,109,.16)}
.ymlx-ab.liked .ymlx-ic{fill:currentColor}
.ymlx-ab.pop .ymlx-ic{animation:ymlx-pop .45s ${EASE}}
@keyframes ymlx-pop{40%{transform:scale(1.35)}}
.ymlx-ab .ymlx-ic.dots{stroke-width:3.2}
.ymlx-toast{position:fixed;left:50%;bottom:34px;z-index:2147483647;transform:translate(-50%,16px);max-width:min(90vw,520px);padding:11px 16px;border-radius:12px;background:rgba(18,18,20,.94);color:#fff;font:600 13px/1.35 ${FONT};box-shadow:0 12px 32px rgba(0,0,0,.45);opacity:0;pointer-events:none;transition:opacity .25s,transform .4s ${EASE}}
.ymlx-toast.show{opacity:1;transform:translate(-50%,0)}

.ymlx-ov{--h:220;--s:50%;--l:45%;--sz:1;position:fixed;inset:0;z-index:2147483647;display:grid;grid-template-columns:minmax(300px,36%) 1fr;overflow:hidden;color:#fff;background:hsl(var(--h),22%,8%);opacity:0;visibility:hidden;transform:scale(1.02);transition:opacity .4s ease,transform .6s ${EASE},visibility 0s linear .6s}
.ymlx-ov.open{opacity:1;visibility:visible;transform:none;transition:opacity .4s ease,transform .6s ${EASE}}
.ymlx-ov:not(.open) *{animation-play-state:paused!important}

.ymlx-bg{position:absolute;inset:0;overflow:hidden;pointer-events:none}
.ymlx-bgc{position:absolute;inset:-15%;background:center/cover no-repeat;filter:blur(90px) saturate(1.6) brightness(.55);opacity:.9;transform:scale(1.1)}
.ymlx-blob{position:absolute;width:75vmax;height:75vmax;border-radius:50%;will-change:transform;opacity:.6}
.ymlx-blob.b1{left:-25vmax;top:-30vmax;background:radial-gradient(closest-side,hsla(var(--h),var(--s),var(--l),.85),transparent);animation:ymlx-b1 24s ease-in-out infinite alternate}
.ymlx-blob.b2{right:-30vmax;bottom:-35vmax;background:radial-gradient(closest-side,hsla(calc(var(--h) + 35),var(--s),calc(var(--l) - 10%),.8),transparent);animation:ymlx-b2 31s ease-in-out infinite alternate}
@keyframes ymlx-b1{to{transform:translate(20vmax,14vmax) scale(1.15)}}
@keyframes ymlx-b2{to{transform:translate(-16vmax,-18vmax) scale(.88)}}
.ymlx-shade{position:absolute;inset:0;background:radial-gradient(120% 100% at 70% 35%,transparent 0,rgba(0,0,0,.4) 100%),linear-gradient(rgba(0,0,0,.1),rgba(0,0,0,.35))}

.ymlx-left{position:relative;display:flex;flex-direction:column;justify-content:center;gap:clamp(12px,2.4vh,24px);padding:clamp(24px,5vh,56px) 28px clamp(20px,4vh,40px) clamp(28px,5vw,72px);min-width:0;min-height:0}
.ymlx-side{display:flex;flex-direction:column;gap:clamp(10px,2.2vh,22px);min-width:0}
.ymlx-player{display:flex;flex-direction:column;gap:8px;width:min(100%,360px)}
.ymlx-bar{position:relative;height:20px;display:flex;align-items:center;cursor:pointer;touch-action:none}
.ymlx-track{position:relative;width:100%;height:4px;border-radius:4px;background:rgba(255,255,255,.2);transition:height .2s ${EASE}}
.ymlx-bar:hover .ymlx-track,.ymlx-bar.drag .ymlx-track{height:7px}
.ymlx-fillbar{position:absolute;left:0;top:0;bottom:0;width:var(--pr,0%);border-radius:inherit;background:#fff}
.ymlx-knob{position:absolute;top:50%;left:var(--pr,0%);width:14px;height:14px;margin:-7px 0 0 -7px;border-radius:50%;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.45);transform:scale(0);transition:transform .2s ${EASE}}
.ymlx-bar:hover .ymlx-knob,.ymlx-bar.drag .ymlx-knob{transform:scale(1)}
.ymlx-hover{position:absolute;bottom:22px;left:var(--hx,0%);transform:translateX(-50%);padding:4px 7px;border-radius:7px;background:rgba(0,0,0,.65);font-size:11px;font-weight:700;font-variant-numeric:tabular-nums;opacity:0;pointer-events:none;transition:opacity .15s}
.ymlx-bar:hover .ymlx-hover,.ymlx-bar.drag .ymlx-hover{opacity:1}
.ymlx-times{display:flex;justify-content:space-between;font-size:12px;font-weight:600;font-variant-numeric:tabular-nums;color:rgba(255,255,255,.6)}
.ymlx-ctl{display:flex;align-items:center;justify-content:center;gap:26px;margin-top:4px}
.ymlx-cb{width:48px;height:48px;border:0;border-radius:50%;background:transparent;color:#fff;display:grid;place-items:center;cursor:pointer;opacity:.92;transition:transform .2s ${EASE},background .2s,opacity .2s}
.ymlx-cb:hover{background:rgba(255,255,255,.12);opacity:1}
.ymlx-cb:active{transform:scale(.88)}
.ymlx-cb .ymlx-ic{width:26px;height:26px}
.ymlx-cb.big{width:66px;height:66px;background:#fff;color:#111;opacity:1;box-shadow:0 12px 30px -8px rgba(0,0,0,.5)}
.ymlx-cb.big:hover{background:#fff;transform:scale(1.06)}
.ymlx-cb.big:active{transform:scale(.92)}
.ymlx-cb.big .ymlx-ic{width:30px;height:30px}
.ymlx-art{width:min(100%,360px,36vh);aspect-ratio:1;border-radius:20px;background:rgba(255,255,255,.08) center/cover no-repeat;box-shadow:0 40px 80px -30px rgba(0,0,0,.75),inset 0 0 0 1px rgba(255,255,255,.08);flex:none}
.ymlx-info{display:flex;flex-direction:column;gap:6px;min-width:0}
.ymlx-tt{font-family:${DISPLAY};font-weight:800;font-size:clamp(20px,2vw,30px);line-height:1.12;letter-spacing:-.02em;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.ymlx-ta{font-size:16px;color:rgba(255,255,255,.7);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ymlx-src{align-self:flex-start;display:inline-flex;align-items:center;gap:7px;margin-top:8px;padding:5px 11px;border-radius:99px;background:rgba(255,255,255,.1);font-size:12px;font-weight:600;color:rgba(255,255,255,.85)}
.ymlx-src:empty{display:none}
.ymlx-src::before{content:"";width:6px;height:6px;border-radius:50%;background:hsl(var(--h),90%,68%);box-shadow:0 0 10px hsl(var(--h),90%,68%)}
.ymlx-keys{font-size:11px;color:rgba(255,255,255,.38);margin-top:4px}

.ymlx-main{position:relative;min-width:0;min-height:0}
.ymlx-scroll{position:absolute;inset:0;overflow:hidden;-webkit-mask-image:linear-gradient(transparent 0,#000 16%,#000 76%,transparent 100%);mask-image:linear-gradient(transparent 0,#000 16%,#000 76%,transparent 100%)}
.mode-plain .ymlx-scroll{overflow-y:auto;scrollbar-width:none}
.ymlx-scroll::-webkit-scrollbar{display:none}
.ymlx-list{position:relative;max-width:900px;padding:0 clamp(24px,5vw,80px) 0 clamp(16px,2vw,32px);will-change:transform;transition:transform .85s ${EASE}}
.mode-plain .ymlx-list,.mode-msg .ymlx-list{transform:none!important;transition:none;padding-top:18vh;padding-bottom:26vh}
.ymlx-pad{height:70vh}

.ymlx-line{--p:-8%;font-family:${DISPLAY};font-weight:800;font-size:calc(clamp(26px,3.2vw,46px)*var(--sz));line-height:1.18;letter-spacing:-.02em;padding:.3em 0;color:rgba(255,255,255,.28);cursor:pointer;transform-origin:0 50%;transform:scale(.965);overflow-wrap:anywhere;transition:color .5s ease,transform .7s ${EASE};animation:ymlx-in .7s ${EASE} backwards;animation-delay:var(--dl,0ms)}
@keyframes ymlx-in{from{opacity:0;translate:0 22px}}
.ymlx-line:hover{color:rgba(255,255,255,.62)}
.ymlx-line.past{color:rgba(255,255,255,.46)}
.free .ymlx-line{color:rgba(255,255,255,.62)}
.ymlx-line.active{transform:none;color:transparent;background:linear-gradient(90deg,#fff calc(var(--p) - 8%),rgba(255,255,255,.4) calc(var(--p) + 8%));-webkit-background-clip:text;background-clip:text;filter:drop-shadow(0 0 24px hsla(var(--h),90%,72%,.38))}
.ymlx-gap{display:flex;gap:.32em;align-items:center;padding:.6em 0;cursor:default}
.ymlx-gap.active{background:none;filter:none;animation:ymlx-breathe 2.6s ease-in-out infinite}
@keyframes ymlx-breathe{50%{scale:1.1}}
.ymlx-dot{display:block;width:.36em;height:.36em;border-radius:50%;background:#fff;opacity:.25;transform:scale(.8);transition:opacity .25s,transform .25s}
.ymlx-gap:not(.active) .ymlx-dot{opacity:.25!important;transform:scale(.8)!important}

.ymlx-plain{font-family:${DISPLAY};font-weight:700;font-size:calc(clamp(20px,2.3vw,30px)*var(--sz));line-height:1.45;letter-spacing:-.01em;color:rgba(255,255,255,.9);white-space:pre-wrap;animation:ymlx-in .6s ${EASE} backwards}
.ymlx-note{display:inline-block;font-size:12px;font-weight:600;color:rgba(255,255,255,.65);margin-bottom:24px;padding:5px 11px;border-radius:99px;background:rgba(255,255,255,.08)}
.ymlx-sk{height:calc(clamp(26px,3.2vw,46px)*.72*var(--sz));border-radius:14px;margin:0 0 .95em;background:linear-gradient(100deg,rgba(255,255,255,.06) 30%,rgba(255,255,255,.16) 50%,rgba(255,255,255,.06) 70%);background-size:300% 100%;animation:ymlx-shim 1.5s ease-in-out infinite,ymlx-in .5s ${EASE} backwards}
@keyframes ymlx-shim{from{background-position:100% 0}to{background-position:0 0}}
.ymlx-empty{display:flex;flex-direction:column;align-items:flex-start;gap:12px;animation:ymlx-in .6s ${EASE} backwards}
.ymlx-empty b{font-family:${DISPLAY};font-size:clamp(24px,2.6vw,36px);font-weight:800;letter-spacing:-.02em}
.ymlx-empty span{font-size:15px;color:rgba(255,255,255,.65);max-width:460px;line-height:1.5}
.ymlx-cta{margin-top:10px;height:42px;padding:0 20px;display:inline-flex;align-items:center;gap:8px;border:0;border-radius:99px;background:#fff;color:#111;font:700 14px/1 ${FONT};cursor:pointer;transition:transform .2s ${EASE}}
.ymlx-cta:hover{transform:scale(1.04)}

.ymlx-tools{position:absolute;top:18px;right:20px;display:flex;align-items:center;gap:8px;z-index:3}
.ymlx-grp{display:flex;align-items:center;border-radius:99px;background:rgba(255,255,255,.1);-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px)}
.ymlx-tb{height:34px;min-width:34px;padding:0 10px;display:inline-flex;align-items:center;justify-content:center;gap:6px;border:0;border-radius:99px;background:rgba(255,255,255,.1);color:#fff;font:700 12px/1 ${FONT};cursor:pointer;-webkit-backdrop-filter:blur(16px);backdrop-filter:blur(16px);transition:background .2s,transform .15s}
.ymlx-grp .ymlx-tb{background:transparent;-webkit-backdrop-filter:none;backdrop-filter:none}
.ymlx-tb:hover{background:rgba(255,255,255,.2)}
.ymlx-tb:active{transform:scale(.92)}
.ymlx-lbl{font:700 12px/1 ${FONT};font-variant-numeric:tabular-nums;min-width:58px;text-align:center;color:rgba(255,255,255,.85);cursor:pointer;user-select:none}
.ymlx-ov:not(.mode-synced) .ymlx-sync{display:none}

@media (max-width:860px){
  .ymlx-ov{grid-template-columns:1fr;grid-template-rows:auto 1fr auto}
  .ymlx-left{display:contents}
  .ymlx-side,.ymlx-player{position:relative}
  .ymlx-side{grid-row:1;flex-direction:row;align-items:center;gap:14px;padding:16px 370px 12px 20px}
  .ymlx-main{grid-row:2}
  .ymlx-player{grid-row:3;width:auto;max-width:560px;margin:0 auto;padding:6px 20px 18px;align-self:stretch;width:100%}
  .ymlx-ctl{gap:18px}
  .ymlx-cb.big{width:56px;height:56px}
  .ymlx-acts{justify-content:center}
  .ymlx-ab span{display:none}
  .ymlx-ab{width:38px;padding:0;justify-content:center}
  .ymlx-art{width:56px;border-radius:10px}
  .ymlx-tt{font-size:17px;-webkit-line-clamp:1}
  .ymlx-ta{font-size:13px}
  .ymlx-src,.ymlx-keys{display:none}
}
@media (max-width:600px){ .ymlx-side{padding-right:20px;padding-top:64px} }
@media (prefers-reduced-motion:reduce){ .ymlx-list,.ymlx-line{transition:none} .ymlx-blob{animation:none} }
`;

  // ===================== интерфейс =====================
  const st = { open: false, key: '', track: null, mode: '', lines: [], active: -2, tops: [], viewH: 0,
               y: 0, manual: 0, check: 0, raf: 0, result: null };
  const cache = new Map();

  function setup() {
    if (!document.body || !document.head) return void setTimeout(setup, 200);
    if (document.getElementById('ymlx-root')) return;

    const style = $('style'); style.textContent = CSS; document.head.appendChild(style);
    const root = $('div'); root.id = 'ymlx-root';

    const fab = $('button', 'ymlx-fab');
    fab.append(icon(I.lyrics));
    fab.title = 'Текст песни  ·  Alt+L  ·  ПКМ — родной экран Яндекса  ·  можно перетащить';

    const ov = $('div', 'ymlx-ov');
    const bg = $('div', 'ymlx-bg'), bgc = $('div', 'ymlx-bgc');
    bg.append(bgc, $('div', 'ymlx-blob b1'), $('div', 'ymlx-blob b2'), $('div', 'ymlx-shade'));

    const side = $('div', 'ymlx-side'), art = $('div', 'ymlx-art'), info = $('div', 'ymlx-info');
    const tTitle = $('div', 'ymlx-tt'), tArtist = $('div', 'ymlx-ta'), tSrc = $('div', 'ymlx-src');
    info.append(tTitle, tArtist, tSrc, $('div', 'ymlx-keys', 'Пробел — пауза  ·  ← → — перемотка  ·  [ ] — сдвиг текста  ·  Esc — закрыть'));
    side.append(art, info);

    const player = $('div', 'ymlx-player');
    const bar = $('div', 'ymlx-bar'), trk = $('div', 'ymlx-track'), hov = $('div', 'ymlx-hover');
    trk.append($('div', 'ymlx-fillbar'), $('div', 'ymlx-knob'));
    bar.append(trk, hov);
    const times = $('div', 'ymlx-times'), tCur = $('span', null, '0:00'), tDur = $('span', null, '0:00');
    times.append(tCur, tDur);
    const ctl = $('div', 'ymlx-ctl');
    const bPrev = $('button', 'ymlx-cb'); bPrev.append(ficon(F.prev)); bPrev.title = 'Предыдущий трек';
    const bPlay = $('button', 'ymlx-cb big'); const icPlay = ficon(F.play); bPlay.append(icPlay); bPlay.title = 'Пауза / воспроизведение  ·  Пробел';
    const bNext = $('button', 'ymlx-cb'); bNext.append(ficon(F.next)); bNext.title = 'Следующий трек';
    ctl.append(bPrev, bPlay, bNext);
    const acts = $('div', 'ymlx-acts');
    const ab = (ic, text, title, cls) => {
      const b = $('button', 'ymlx-ab' + (cls ? ' ' + cls : ''));
      b.append(ic); if (text) b.append($('span', null, text));
      b.title = title; return b;
    };
    const icHeart = icon(I.heart);
    const aLike = ab(icHeart, '', 'Мне нравится', 'icon');
    const aWave = ab(icon(I.wave), 'Волна по треку', 'Запустить «Мою волну» по этому треку');
    const aList = ab(icon(I.addlist), 'В плейлист', 'Добавить в плейлист');
    const icMore = icon(I.more); icMore.classList.add('dots');
    const aMore = ab(icMore, '', 'Все действия Яндекса с треком', 'icon');
    acts.append(aLike, aWave, aList, aMore);
    player.append(acts, bar, times, ctl);
    const toastEl = $('div', 'ymlx-toast');
    const left = $('div', 'ymlx-left');
    left.append(side, player);

    const main = $('div', 'ymlx-main'), scroller = $('div', 'ymlx-scroll'), list = $('div', 'ymlx-list');
    scroller.append(list); main.append(scroller);

    const tools = $('div', 'ymlx-tools');
    const offGrp = $('div', 'ymlx-grp ymlx-sync');
    const bLater = tb(icon(I.left), 'Текст позже  ·  [');
    const offLbl = $('span', 'ymlx-lbl'); offLbl.title = 'Сдвиг текста. Двойной клик — сбросить';
    const bEarlier = tb(icon(I.right), 'Текст раньше  ·  ]');
    offGrp.append(bLater, offLbl, bEarlier);
    const sizeGrp = $('div', 'ymlx-grp');
    const bSmall = tb('A−', 'Мельче'), bBig = tb('A+', 'Крупнее');
    sizeGrp.append(bSmall, bBig);
    const bFull = tb(icon(I.full), 'Во весь экран  ·  F');
    const bWeb = tb(icon(I.web), 'Найти текст в браузере');
    const bRef = tb(icon(I.refresh), 'Искать заново');
    const bClose = tb(icon(I.close), 'Закрыть  ·  Esc');
    tools.append(offGrp, sizeGrp, bFull, bWeb, bRef, bClose);

    ov.append(bg, left, main, tools);
    root.append(fab, ov, toastEl);
    document.body.append(root);

    // ---------- кнопка: позиция и перетаскивание ----------
    const placeFab = () => {
      S.right = clamp(S.right, 8, innerWidth - 52);
      S.bottom = clamp(S.bottom, 8, innerHeight - 52);
      fab.style.right = S.right + 'px';
      fab.style.bottom = S.bottom + 'px';
    };
    placeFab();
    addEventListener('resize', placeFab);
    let drag = null, dragged = false;
    fab.addEventListener('pointerdown', e => {
      if (e.button !== 0) return;
      drag = { x: e.clientX, y: e.clientY, r: S.right, b: S.bottom }; dragged = false;
      fab.setPointerCapture(e.pointerId);
    });
    fab.addEventListener('pointermove', e => {
      if (!drag) return;
      const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
      if (!dragged && Math.hypot(dx, dy) < 5) return;
      dragged = true; fab.classList.add('drag');
      S.right = drag.r - dx; S.bottom = drag.b - dy; placeFab();
    });
    fab.addEventListener('pointerup', () => { if (dragged) save(); drag = null; fab.classList.remove('drag'); });

    // ---------- режимы и отрисовка ----------
    const setMode = m => {
      st.mode = m;
      ov.classList.toggle('mode-synced', m === 'synced');
      ov.classList.toggle('mode-plain', m === 'plain');
      ov.classList.toggle('mode-msg', m === 'msg');
      ov.classList.remove('free');
      st.manual = 0;
      if (m !== 'synced') { list.style.transform = ''; scroller.scrollTop = 0; st.lines = []; st.active = -2; }
    };
    const showSkeleton = () => {
      setMode('msg'); list.textContent = '';
      [74, 58, 86, 44, 68, 52, 78].forEach((w, i) => {
        const s = $('div', 'ymlx-sk'); s.style.width = w + '%'; s.style.animationDelay = '0s,' + i * 40 + 'ms'; list.append(s);
      });
    };
    const showEmpty = (title, sub, withSearch) => {
      setMode('msg'); list.textContent = '';
      const box = $('div', 'ymlx-empty');
      box.append($('b', null, title), $('span', null, sub));
      if (withSearch) {
        const cta = $('button', 'ymlx-cta'); cta.append(icon(I.web), document.createTextNode('Искать в браузере'));
        cta.onclick = () => st.track && call('browser', st.track);
        box.append(cta);
      }
      list.append(box);
    };

    const measure = () => {
      st.viewH = scroller.clientHeight;
      st.tops = st.lines.map(l => l.el.offsetTop + l.el.offsetHeight / 2);
    };
    const targetY = i => st.viewH * 0.36 - (st.tops[Math.max(i, 0)] || 0);
    const setY = (y, instant) => {
      st.y = y;
      list.style.transition = instant ? 'none' : '';
      list.style.transform = `translate3d(0,${y.toFixed(1)}px,0)`;
      if (instant) void list.offsetHeight;
    };
    const setActive = i => {
      const L = st.lines, prev = st.active;
      st.active = i;
      const a = Math.max(0, Math.min(prev, i)), b = Math.min(L.length - 1, Math.max(prev, i));
      for (let k = a; k <= b; k++) { L[k].el.classList.toggle('past', k < i); L[k].el.classList.toggle('active', k === i); }
      if (i >= 0) L[i].p = -1;
      if (!st.manual) setY(targetY(i));
    };

    const renderSynced = synced => {
      const items = [];
      if (synced.length && synced[0][0] > 5) items.push({ t: 0, gap: true });
      synced.forEach(([t, text], k) => {
        const next = synced[k + 1];
        if (!text.trim()) { if (next && next[0] - t >= 4) items.push({ t, gap: true }); return; }
        items.push({ t, text });
      });
      setMode('synced'); list.textContent = '';
      const frag = document.createDocumentFragment();
      st.lines = items.map((it, k) => {
        const el = $('div', 'ymlx-line' + (it.gap ? ' ymlx-gap' : ''));
        el.style.setProperty('--dl', Math.min(k, 16) * 30 + 'ms');
        let dots = null;
        if (it.gap) { dots = [$('i', 'ymlx-dot'), $('i', 'ymlx-dot'), $('i', 'ymlx-dot')]; el.append(...dots); }
        else { el.textContent = it.text; el.onclick = () => { seek(Math.max(0, it.t - S.offset + 0.02)); unfree(); }; }
        frag.append(el);
        return { t: it.t, el, gap: !!it.gap, dots, p: -1 };
      });
      frag.append($('div', 'ymlx-pad'));
      list.append(frag);
      st.active = -2;
      measure();
      const tm = getTime();
      setY(targetY(tm ? lineAt(tm.t + S.offset) : 0), true);
    };
    const renderPlain = (text, note) => {
      setMode('plain'); list.textContent = '';
      if (note) list.append($('div', 'ymlx-note', note));
      list.append($('div', 'ymlx-plain', text));
    };
    const show = r => {
      st.result = r;
      tSrc.textContent = r.source ? r.source + (r.synced ? '  ·  синхронный' : '') : '';
      if (r.synced && r.synced.length) renderSynced(r.synced);
      else if (r.plain) renderPlain(r.plain, 'Синхронного текста не нашлось — показываю обычный');
      else if (r.instrumental) showEmpty('♪ Инструментальная композиция', 'В этом треке нет слов. Просто слушайте.', false);
      else showEmpty('Текст не найден', 'Ни в одной из баз нет текста для этого трека. Можно поискать его в интернете.', true);
    };

    const call = (action, t, extra) => {
      try {
        window.__ymLyrics(JSON.stringify(Object.assign({ action, key: keyOf(t), artist: t.artist, title: t.title }, extra || {})));
        return true;
      } catch (e) {
        showEmpty('Нет связи с лаунчером', 'Запустите Яндекс Музыку через ym_lyrics.bat.', false);
        return false;
      }
    };

    let accentToken = 0;
    const applyAccent = t => {
      const token = ++accentToken;
      const set = (h, s, l) => { ov.style.setProperty('--h', h); ov.style.setProperty('--s', s + '%'); ov.style.setProperty('--l', l + '%'); };
      set(hashHue(keyOf(t) || 'x'), 55, 42);
      const url = t && t.art;
      bgc.style.backgroundImage = art.style.backgroundImage = url ? 'url("' + url.replace(/"/g, '%22') + '")' : '';
      if (!url) return;
      const img = new Image();
      img.crossOrigin = 'anonymous';
      img.onload = () => {
        if (token !== accentToken) return;
        try {
          const c = document.createElement('canvas'); c.width = c.height = 24;
          const x = c.getContext('2d', { willReadFrequently: true });
          x.drawImage(img, 0, 0, 24, 24);
          const d = x.getImageData(0, 0, 24, 24).data;
          let r = 0, g = 0, b = 0, w = 0;
          for (let i = 0; i < d.length; i += 4) {
            const mx = Math.max(d[i], d[i + 1], d[i + 2]), mn = Math.min(d[i], d[i + 1], d[i + 2]);
            const wt = (mx ? (mx - mn) / mx : 0) ** 2 * (mx / 255) + 0.01;
            r += d[i] * wt; g += d[i + 1] * wt; b += d[i + 2] * wt; w += wt;
          }
          const [h, s, l] = rgb2hsl(r / w, g / w, b / w);
          set(Math.round(h), Math.round(clamp(s, 25, 85)), Math.round(clamp(l, 30, 55)));
        } catch (e) { /* обложка без CORS — остаётся цвет по названию */ }
      };
      img.src = url;
    };

    const load = force => {
      const t = getTrack();
      st.track = t; st.key = keyOf(t);
      tTitle.textContent = t ? t.title : 'Ничего не играет';
      tArtist.textContent = t ? t.artist : '';
      tSrc.textContent = '';
      applyAccent(t);
      if (!t) return showEmpty('Не вижу, что сейчас играет', 'Включите трек — текст подтянется сам.', false);
      const c = cache.get(st.key);
      if (!force && c && (c.source === 'Яндекс' || c.ym_checked)) return show(c);
      showSkeleton();
      const tm = getTime();
      call('fetch', t, { duration: tm && tm.d > 0 ? Math.round(tm.d) : 0, force: !!force, trackId: getTrackId(t.title) });
    };
    window.__ymlxRefresh = () => {
      if (st.open && st.track && (!st.result || st.result.source !== 'Яндекс')) load(false);
    };
    window.__ymLyricsShow = r => {
      if (!r) return;
      cache.set(r.key, r);
      if (st.open && r.key === st.key) show(r);
    };

    // ---------- синхронизация (работает только пока экран открыт) ----------
    const lineAt = t => {
      const L = st.lines;
      let lo = 0, hi = L.length - 1, i = -1;
      while (lo <= hi) { const m = (lo + hi) >> 1; if (L[m].t <= t) { i = m; lo = m + 1; } else hi = m - 1; }
      return i;
    };
    const unfree = () => { st.manual = 0; ov.classList.remove('free'); setY(targetY(st.active)); };
    const fmt = s => { s = Math.max(0, Math.floor(s || 0)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };
    const pl = { playing: null, sec: -1, dur: -1, drag: false, dragFrac: 0 };
    const updPlayer = tm => {
      const playing = isPlaying();
      if (playing !== pl.playing) {
        pl.playing = playing;
        icPlay.firstChild.setAttribute('d', playing ? F.pause : F.play);
      }
      const d = tm && tm.d > 0 ? tm.d : 0;
      const cur = pl.drag ? pl.dragFrac * d : tm ? tm.t : 0;
      if (!pl.drag) bar.style.setProperty('--pr', d ? (clamp(cur / d, 0, 1) * 100).toFixed(2) + '%' : '0%');
      const sec = Math.floor(cur);
      if (sec !== pl.sec) { pl.sec = sec; tCur.textContent = fmt(cur); }
      if (Math.round(d) !== pl.dur) { pl.dur = Math.round(d); tDur.textContent = fmt(d); }
    };
    const fracAt = e => { const r = trk.getBoundingClientRect(); return clamp((e.clientX - r.left) / r.width, 0, 1); };
    bar.addEventListener('pointermove', e => {
      const f = fracAt(e), tm = getTime();
      bar.style.setProperty('--hx', (f * 100).toFixed(2) + '%');
      hov.textContent = fmt(f * (tm && tm.d || 0));
      if (pl.drag) { pl.dragFrac = f; bar.style.setProperty('--pr', (f * 100).toFixed(2) + '%'); }
    });
    bar.addEventListener('pointerdown', e => {
      if (e.button !== 0) return;
      pl.drag = true; pl.dragFrac = fracAt(e); bar.classList.add('drag');
      bar.style.setProperty('--pr', (pl.dragFrac * 100).toFixed(2) + '%');
      bar.setPointerCapture(e.pointerId);
    });
    const endDrag = () => {
      if (!pl.drag) return;
      pl.drag = false; bar.classList.remove('drag');
      const tm = getTime();
      if (tm && tm.d > 0) { seek(pl.dragFrac * tm.d); if (st.mode === 'synced') unfree(); }
    };
    bar.addEventListener('pointerup', endDrag);
    bar.addEventListener('pointercancel', endDrag);
    const skip = d => { const tm = getTime(); if (tm) seek(clamp(tm.t + d, 0, tm.d > 0 ? tm.d - 1 : 1e9)); };
    let toastT = 0;
    const toast = m => { toastEl.textContent = m; toastEl.classList.add('show'); clearTimeout(toastT); toastT = setTimeout(() => toastEl.classList.remove('show'), 2800); };

    // На время меню/диалогов Яндекса прячем свой экран и возвращаем, когда они закроются.
    let yieldTimer = 0;
    const startYield = () => {
      root.classList.add('yield');
      const since = performance.now();
      let seen = false, gone = 0;
      clearInterval(yieldTimer);
      yieldTimer = setInterval(() => {
        const now = performance.now();
        if (popupOpen()) { seen = true; gone = 0; return; }
        if (!seen && now - since < 2500) return;
        gone = gone || now;
        if (now - gone > 450) { clearInterval(yieldTimer); root.classList.remove('yield'); }
      }, 150);
    };
    const menuAction = async (re, interactive, what) => {
      const mb = findMenuBtn();
      if (!mb) return toast('Не нашёл меню трека в плеере Яндекса');
      if (interactive) startYield();
      mb.click();
      if (!re) return;
      const item = await waitFor(() => findMenuItem(re), 1800);
      if (!item) {
        if (!interactive) startYield();
        return toast('Не нашёл пункт «' + what + '» — выберите его в меню');
      }
      for (const t of ['pointerover', 'pointerenter', 'mouseover', 'mouseenter', 'pointermove'])
        item.dispatchEvent(new MouseEvent(t, { bubbles: true }));
      item.click();
      if (!interactive) toast(what);
    };

    let likeState = null;
    const refreshLike = () => {
      const b = findLikeBtn();
      const v = b ? isLiked(b) : null;
      if (v === likeState) return;
      likeState = v;
      aLike.classList.toggle('liked', !!v);
      aLike.style.display = b ? '' : 'none';
      aLike.title = v ? 'Убрать из «Мне нравится»' : 'Мне нравится';
    };
    aLike.onclick = () => {
      const b = findLikeBtn();
      if (!b) return toast('Не нашёл кнопку лайка в плеере Яндекса');
      b.click();
      aLike.classList.remove('pop'); void aLike.offsetWidth; aLike.classList.add('pop');
      aLike.classList.toggle('liked', !likeState); likeState = !likeState;
      setTimeout(() => { likeState = null; refreshLike(); }, 500);
    };
    aWave.onclick = () => menuAction(/волна по треку|волну по треку|радио по треку|my wave|моя волна/i, false, 'Моя волна по треку');
    aList.onclick = () => menuAction(/в плейлист|playlist/i, true, 'Добавить в плейлист');
    aMore.onclick = () => menuAction(null, true, '');

    bPrev.onclick = () => control('previoustrack');
    bNext.onclick = () => control('nexttrack');
    bPlay.onclick = () => { control('toggle'); playCache.at = 0; };

    const tick = now => {
      if (!st.open) { st.raf = 0; return; }
      st.raf = requestAnimationFrame(tick);
      if (now - st.check > 700) {
        st.check = now;
        if (keyOf(getTrack()) !== st.key) { load(); return; }
        refreshLike();
      }
      const tm = getTime();
      updPlayer(tm);
      if (st.mode !== 'synced' || !st.lines.length) return;
      if (!tm) return;
      const t = tm.t + S.offset + 0.12;
      const i = lineAt(t);
      if (i !== st.active) setActive(i);
      if (st.manual && now > st.manual) unfree();
      if (i < 0) return;
      const cur = st.lines[i], next = st.lines[i + 1];
      const end = next ? next.t : cur.t + 4;
      const p = clamp((t - cur.t) / Math.max(0.25, end - cur.t), 0, 1);
      if (Math.abs(p - cur.p) < 0.002) return;
      cur.p = p;
      if (cur.gap) cur.dots.forEach((d, k) => {
        const f = clamp(p * 3 - k, 0, 1);
        d.style.opacity = (0.25 + 0.75 * f).toFixed(3);
        d.style.transform = 'scale(' + (0.8 + 0.4 * f).toFixed(3) + ')';
      });
      else cur.el.style.setProperty('--p', (p * 116 - 8).toFixed(1) + '%');
    };

    scroller.addEventListener('wheel', e => {
      if (st.mode !== 'synced' || !st.lines.length) return;
      const top = targetY(0), bottom = targetY(st.lines.length - 1);
      setY(clamp(st.y - e.deltaY, bottom, top), true);
      st.manual = performance.now() + 2500;
      ov.classList.add('free');
    }, { passive: true });
    new ResizeObserver(() => {
      if (st.mode !== 'synced') return;
      measure();
      if (!st.manual) setY(targetY(st.active), true);
    }).observe(scroller);

    // ---------- управление ----------
    const updOffset = () => { offLbl.textContent = (S.offset > 0 ? '+' : S.offset < 0 ? '−' : '') + Math.abs(S.offset).toFixed(2) + ' с'; };
    const nudge = d => { S.offset = Math.round((S.offset + d) * 100) / 100; updOffset(); save(); };
    const setSize = d => {
      S.size = Math.round(clamp(S.size + d, 0.7, 1.6) * 10) / 10;
      ov.style.setProperty('--sz', S.size); save();
      if (st.mode === 'synced') requestAnimationFrame(() => { measure(); setY(targetY(st.active), true); });
    };
    updOffset(); ov.style.setProperty('--sz', S.size);
    bLater.onclick = () => nudge(-0.25);
    bEarlier.onclick = () => nudge(0.25);
    offLbl.ondblclick = () => { S.offset = 0; updOffset(); save(); };
    bSmall.onclick = () => setSize(-0.1);
    bBig.onclick = () => setSize(0.1);
    bWeb.onclick = () => st.track && call('browser', st.track);
    let weFull = false;
    const toggleFull = () => {
      try {
        if (document.fullscreenElement) { document.exitFullscreen(); weFull = false; }
        else { document.documentElement.requestFullscreen(); weFull = true; }
      } catch (e) {}
    };
    bFull.onclick = toggleFull;
    bRef.onclick = () => load(true);

    const open = () => {
      st.open = true; st.check = 0;
      ov.classList.add('open'); fab.classList.add('on'); root.classList.add('open');
      pl.playing = null; pl.sec = -1; pl.dur = -1; likeState = undefined; refreshLike();
      load();
      if (!st.raf) st.raf = requestAnimationFrame(tick);
    };
    const close = () => {
      st.open = false; ov.classList.remove('open'); fab.classList.remove('on'); root.classList.remove('open');
      if (weFull && document.fullscreenElement) { try { document.exitFullscreen(); } catch (e) {} weFull = false; }
    };
    const toggle = native => {
      if (st.open) return close();
      if (native) { const nb = nativeLyricsBtn(); if (nb) return nb.click(); }
      open();
    };
    bClose.onclick = close;
    fab.addEventListener('click', e => { if (dragged) { dragged = false; e.preventDefault(); return; } toggle(false); });
    fab.addEventListener('contextmenu', e => { e.preventDefault(); toggle(true); });  // ПКМ — родной экран текста Яндекса
    window.addEventListener('keydown', e => {
      const tg = e.target;
      if (tg && (tg.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(tg.tagName))) return;
      if (e.altKey && e.code === 'KeyL') { e.preventDefault(); toggle(false); return; }
      if (root.classList.contains('yield')) return;
      if (!st.open || e.ctrlKey || e.metaKey || e.altKey) return;
      const keys = {
        Escape: () => { if (document.fullscreenElement && weFull) toggleFull(); else close(); },
        Space: () => { control('toggle'); playCache.at = 0; },
        ArrowLeft: () => skip(-5), ArrowRight: () => skip(5),
        BracketLeft: () => nudge(-0.25), BracketRight: () => nudge(0.25),
        KeyF: toggleFull,
      };
      const fn = keys[e.code] || keys[e.key];
      if (!fn) return;
      e.preventDefault(); e.stopImmediatePropagation();
      fn();
    }, true);

    // Возвращаем интерфейс, если приложение перерисовало страницу.
    setInterval(() => {
      if (!style.isConnected && document.head) document.head.appendChild(style);
      if (!root.isConnected && document.body) document.body.append(root);
    }, 2000);
  }
  setup();
})();
"""

# ----------------------------------------------------------------------------
# Поиск текста
# ----------------------------------------------------------------------------
_LRC = re.compile(r"\[(\d+):(\d+(?:[.:]\d+)?)\]")


def http_json(url, timeout=8):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def parse_synced(lrc):
    out = []
    for raw in (lrc or "").splitlines():
        stamps = _LRC.findall(raw)
        if not stamps:
            continue
        text = _LRC.sub("", raw).strip()
        for m, s in stamps:
            out.append([int(m) * 60 + float(s.replace(":", ".")), text])
    out.sort(key=lambda x: x[0])
    return out


BROWSER_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/130.0 Safari/537.36")


def http_text(url, headers=None, timeout=8):
    req = urllib.request.Request(url, headers=dict({"User-Agent": BROWSER_UA}, **(headers or {})))
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def norm(s):
    s = (s or "").lower().replace("ё", "е")
    return re.sub(r"[^0-9a-zа-яÀ-ɏ぀-鿿]+", "", s)


def same_song(want_artist, want_title, got_artists, got_title):
    wt, gt = norm(want_title), norm(got_title)
    if not wt or not gt or not (wt in gt or gt in wt):
        return False
    wa = norm(want_artist)
    return any(norm(a) and (norm(a) in wa or wa in norm(a)) for a in got_artists)


def split_query(artist, title):
    clean = re.sub(r"\s*[\(\[].*?[\)\]]", "", title).strip() or title
    main_artist = re.split(r",|&| feat\.| ft\.| x ", artist)[0].strip() or artist
    return clean, main_artist


# ---- Яндекс Музыка (текст из самого приложения) ---------------------------
YM_TOKEN = None                     # токен приложения — только в памяти, на диск не пишется
_YM_SIGN_KEY = b"p93jhgh689SBReK6ghtw62"


YM_LRC = {}                         # тексты, которые само приложение загрузило: id трека → результат
LAST_PLAY_ID = ""                   # id трека из последнего запроса на воспроизведение
TABS = []


def _refresh_tabs():
    for tab in list(TABS):
        tab.eval("window.__ymlxRefresh && window.__ymlxRefresh()")


def set_token(tok):
    global YM_TOKEN
    if tok and tok != YM_TOKEN:
        first = YM_TOKEN is None
        YM_TOKEN = tok
        if first:
            print("Тексты из Яндекс Музыки подключены.")
            _refresh_tabs()


def check_token_candidate(tok):
    """Токен, найденный в хранилище приложения, — принимаем, только если Яндекс его признаёт."""
    if YM_TOKEN or not tok:
        return
    try:
        req = urllib.request.Request("https://api.music.yandex.net/account/status",
                                     headers={"Authorization": "OAuth " + tok, "User-Agent": BROWSER_UA})
        with urllib.request.urlopen(req, timeout=8) as r:
            info = json.loads(r.read().decode("utf-8"))
        if ((info.get("result") or {}).get("account") or {}).get("uid"):
            set_token(tok)
    except Exception:
        pass


def lyrics_from_download(url, fmt):
    body = http_text(url)
    if fmt == "LRC" or _LRC.search(body):
        synced = parse_synced(body)
        if sum(1 for _, t in synced if t) >= 2:
            return {"synced": synced, "plain": "\n".join(t for _, t in synced), "source": "Яндекс"}
    return {"plain": body.strip(), "source": "Яндекс"} if body.strip() else None


def from_yandex(track_id):
    if not YM_TOKEN or not str(track_id or "").isdigit():
        return None
    hdr = {"Authorization": "OAuth " + YM_TOKEN, "X-Yandex-Music-Client": "YandexMusicAndroid/24023621"}
    for fmt in ("LRC", "TEXT"):
        ts = int(time.time())
        sign = base64.b64encode(hmac.new(_YM_SIGN_KEY, f"{track_id}{ts}".encode(), hashlib.sha256).digest()).decode()
        url = f"https://api.music.yandex.net/tracks/{track_id}/lyrics?" + urllib.parse.urlencode(
            {"format": fmt, "timeStamp": ts, "sign": sign})
        try:
            info = json.loads(http_text(url, hdr))
            dl = (info.get("result") or {}).get("downloadUrl")
            if not dl:
                print(f"  Яндекс ({fmt}): нет ссылки на текст")
                continue
            res = lyrics_from_download(dl, fmt)
            if res and (res.get("synced") or fmt == "TEXT"):
                return res
        except urllib.error.HTTPError as e:
            print(f"  Яндекс ({fmt}): HTTP {e.code}" + ("" if e.code == 404 else " — запрос отклонён"))
        except Exception as e:
            print(f"  Яндекс ({fmt}):", e)
    return None


# ---- LRCLIB ----------------------------------------------------------------
def from_lrclib(artist, title, duration):
    clean, main_artist = split_query(artist, title)
    attempts = [{"track_name": title, "artist_name": artist}]
    if (clean, main_artist) != (title, artist):
        attempts.append({"track_name": clean, "artist_name": main_artist})
    attempts.append({"q": f"{main_artist} {clean}".strip()})
    best, best_score = None, -1
    for params in attempts:
        try:
            results = http_json("https://lrclib.net/api/search?" + urllib.parse.urlencode(params))
        except Exception as e:
            print("  LRCLIB:", e)
            continue
        for item in results or []:
            if "q" in params and not same_song(main_artist, clean, [item.get("artistName", "")],
                                               item.get("trackName", "")):
                continue
            score = (2 if item.get("syncedLyrics") else 0) + (1 if item.get("plainLyrics") else 0)
            if duration and item.get("duration") and abs(item["duration"] - duration) <= 3:
                score += 2
            if score > best_score:
                best, best_score = item, score
        if best_score >= 2:
            break
    if not best:
        return None
    synced = parse_synced(best.get("syncedLyrics"))
    plain = best.get("plainLyrics") or "\n".join(t for _, t in synced)
    if not synced and not plain:
        return {"instrumental": True, "source": "LRCLIB"} if best.get("instrumental") else None
    return {"synced": synced or None, "plain": plain or None, "source": "LRCLIB"}


# ---- NetEase (много синхронных текстов) ------------------------------------
_CREDIT = re.compile(r"(作词|作曲|编曲|制作|混音|母带|录音|和声|吉他|贝斯|鼓|监制|出品|OP|SP)\s*[:：]")


def from_netease(artist, title, duration):
    clean, main_artist = split_query(artist, title)
    hdr = {"Referer": "https://music.163.com/"}
    try:
        res = json.loads(http_text("https://music.163.com/api/search/get?" + urllib.parse.urlencode(
            {"s": f"{main_artist} {clean}", "type": 1, "limit": 10}), hdr))
    except Exception as e:
        print("  NetEase:", e)
        return None
    for song in (res.get("result") or {}).get("songs") or []:
        artists = [a.get("name", "") for a in song.get("artists") or []]
        if not same_song(main_artist, clean, artists, song.get("name", "")):
            continue
        if duration and song.get("duration") and abs(song["duration"] / 1000 - duration) > 5:
            continue
        try:
            lyr = json.loads(http_text(f"https://music.163.com/api/song/lyric?id={song['id']}&lv=1&kv=1&tv=-1", hdr))
        except Exception as e:
            print("  NetEase:", e)
            continue
        lrc = ((lyr.get("lrc") or {}).get("lyric")) or ""
        if "纯音乐" in lrc:
            return {"instrumental": True, "source": "NetEase"}
        synced = [x for x in parse_synced(lrc) if not _CREDIT.search(x[1])]
        if sum(1 for _, t in synced if t) >= 4:
            return {"synced": synced, "plain": "\n".join(t for _, t in synced), "source": "NetEase"}
    return None


# ---- Genius (обычный текст) ------------------------------------------------
from html.parser import HTMLParser


class _GeniusParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.depth = 0      # глубина внутри data-lyrics-container
        self.skip = 0       # глубина внутри исключённого блока
        self.parts = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if self.depth:
            if tag == "br":
                if not self.skip:
                    self.parts.append("\n")
                return
            if tag == "div":
                self.depth += 1
            if self.skip or a.get("data-exclude-from-selection") == "true":
                self.skip += 1
        elif tag == "div" and a.get("data-lyrics-container") == "true":
            self.depth = 1
            if self.parts:
                self.parts.append("\n")

    def handle_startendtag(self, tag, attrs):
        if tag == "br" and self.depth and not self.skip:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if not self.depth:
            return
        if self.skip:
            self.skip -= 1
        if tag == "div":
            self.depth -= 1

    def handle_data(self, data):
        if self.depth and not self.skip:
            self.parts.append(data)


def from_genius(artist, title, duration):
    clean, main_artist = split_query(artist, title)
    try:
        res = json.loads(http_text("https://genius.com/api/search/multi?" + urllib.parse.urlencode(
            {"q": f"{main_artist} {clean}"})))
    except Exception as e:
        print("  Genius:", e)
        return None
    for section in (res.get("response") or {}).get("sections") or []:
        for hit in section.get("hits") or []:
            if hit.get("type") != "song":
                continue
            r = hit.get("result") or {}
            names = [(r.get("primary_artist") or {}).get("name", ""), r.get("artist_names", "")]
            if not same_song(main_artist, clean, names, r.get("title", "")):
                continue
            try:
                page = http_text(r["url"])
            except Exception as e:
                print("  Genius:", e)
                continue
            p = _GeniusParser()
            p.feed(page)
            text = re.sub(r"\n{3,}", "\n\n", "".join(p.parts)).strip()
            if text:
                return {"plain": text, "source": "Genius"}
    return None


# ---- кэш на диске: повторное открытие трека — мгновенно -----------------
CACHE_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "ym-lyrics")
CACHE_FILE = os.path.join(CACHE_DIR, "cache.json")
MISS_TTL = 24 * 3600          # «не найдено» перепроверяем раз в сутки
CACHE_MAX = 3000
_cache_lock = threading.Lock()


def _load_cache():
    try:
        with open(CACHE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


CACHE = _load_cache()


def cache_get(key):
    e = CACHE.get(key)
    if not e:
        return None
    if not e.get("res") and time.time() - e.get("ts", 0) > MISS_TTL:
        return None
    return e["res"]


def cache_put(key, res):
    with _cache_lock:
        CACHE[key] = {"ts": time.time(), "res": res}
        if len(CACHE) > CACHE_MAX:
            for k in sorted(CACHE, key=lambda k: CACHE[k].get("ts", 0))[:len(CACHE) - CACHE_MAX]:
                CACHE.pop(k, None)
        try:
            os.makedirs(CACHE_DIR, exist_ok=True)
            tmp = CACHE_FILE + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(CACHE, f, ensure_ascii=False)
            os.replace(tmp, CACHE_FILE)
        except Exception as e:
            print("  кэш:", e)


# ---- поиск: все источники параллельно ------------------------------------
SOURCES = (("LRCLIB", lambda a, t, d: from_lrclib(a, t, d)),
           ("NetEase", lambda a, t, d: from_netease(a, t, d)),
           ("Genius", lambda a, t, d: from_genius(a, t, d)))
POOL = cf.ThreadPoolExecutor(max_workers=12)


def _search_web(artist, title, duration):
    futures = {POOL.submit(fn, artist, title, duration): name for name, fn in SOURCES}
    found, complete = {}, True
    try:
        for f in cf.as_completed(futures, timeout=15):
            try:
                res = f.result()
            except Exception as e:
                print(f"  {futures[f]}: {e}")
                continue
            if not res:
                continue
            if res.get("synced"):
                return res, True          # первый найденный синхронный текст — сразу отдаём
            found[futures[f]] = res
    except cf.TimeoutError:
        complete = False
        print("  часть источников не ответила за 15 с")
    for name, _ in SOURCES:               # приоритет: LRCLIB → NetEase → Genius
        if name in found:
            return found[name], complete
    return {}, complete


_reported = set()


def find_lyrics(artist, title, duration=0, force=False, track_id=""):
    key = norm(artist) + "|" + norm(title)
    if not str(track_id).isdigit() and LAST_PLAY_ID:
        track_id = LAST_PLAY_ID
    if str(track_id) in YM_LRC:
        return dict(YM_LRC[str(track_id)])
    can_ym = bool(YM_TOKEN and str(track_id).isdigit())
    if not can_ym and key not in _reported:
        _reported.add(key)
        why = "ещё нет токена приложения (переключите трек — он появится)" if not YM_TOKEN else \
            "не нашёл id трека на странице"
        print(f"  Яндекс пропущен: {why}")
    hit = None if force else cache_get(key)
    if hit is not None and (hit.get("source") == "Яндекс" or hit.get("ym_checked") or not can_ym):
        return dict(hit, cached=True)

    # Яндекс и интернет — параллельно; текст Яндекса в приоритете.
    ym_future = POOL.submit(from_yandex, track_id) if can_ym else None
    if hit is not None:
        web, complete = hit, True             # интернет уже искали — проверяем только Яндекс
    else:
        web, complete = _search_web(artist, title, duration)
    ym = None
    if ym_future:
        try:
            ym = ym_future.result(timeout=12)
        except Exception as e:
            print("  Яндекс:", e)
    if ym and (ym.get("synced") or not web.get("synced")):
        result = ym
    else:
        result = dict(web)
    result.pop("cached", None)
    if can_ym:
        result["ym_checked"] = True
    if result.get("source") or complete:
        cache_put(key, result)
    return result


# ----------------------------------------------------------------------------
# Подключение к окну через DevTools Protocol
# ----------------------------------------------------------------------------
class Tab(threading.Thread):
    def __init__(self, target):
        super().__init__(daemon=True)
        self.target = target
        self.ws = None
        self.lock = threading.Lock()
        self.msg_id = 0
        self.pending = {}
        self.lyric_reqs = {}

    def send(self, method, params=None, callback=None):
        with self.lock:
            self.msg_id += 1
            if callback:
                self.pending[self.msg_id] = callback
            self.ws.send(json.dumps({"id": self.msg_id, "method": method, "params": params or {}}))

    def eval(self, expr):
        try:
            self.send("Runtime.evaluate", {"expression": expr})
        except Exception:
            pass

    def _on_lyrics_body(self, track_id, fmt, msg):
        try:
            res = msg.get("result") or {}
            body = res.get("body") or ""
            if res.get("base64Encoded"):
                body = base64.b64decode(body).decode("utf-8", "replace")
            dl = (json.loads(body).get("result") or {}).get("downloadUrl")
            got = lyrics_from_download(dl, fmt) if dl else None
        except Exception:
            got = None
        if got and (got.get("synced") or track_id not in YM_LRC):
            YM_LRC[track_id] = got
            print(f"  перехвачен текст Яндекса для трека {track_id}"
                  + (" (синхронный)" if got.get("synced") else ""))
            _refresh_tabs()

    def run(self):
        try:
            self.ws = websocket.create_connection(self.target["webSocketDebuggerUrl"],
                                                  suppress_origin=True, http_no_proxy=["127.0.0.1", "localhost"])
        except Exception as e:
            print("Не удалось подключиться к окну:", e)
            return
        self.send("Runtime.enable")
        self.send("Page.enable")
        self.send("Network.enable", {"maxTotalBufferSize": 0, "maxResourceBufferSize": 0})
        self.send("Runtime.addBinding", {"name": BINDING})
        self.send("Page.addScriptToEvaluateOnNewDocument", {"source": INJECT})
        self.send("Runtime.evaluate", {"expression": INJECT})
        print("Кнопка «Текст» добавлена в окно:", self.target.get("title") or self.target.get("url"))
        TABS.append(self)

        while True:
            try:
                msg = json.loads(self.ws.recv())
            except Exception:
                break
            if "id" in msg and msg["id"] in self.pending:
                cb = self.pending.pop(msg["id"])
                POOL.submit(cb, msg)
                continue
            method = msg.get("method")
            prm = msg.get("params") or {}
            if method in ("Network.requestWillBeSent", "Network.requestWillBeSentExtraInfo"):
                hdrs = (prm.get("request") or {}).get("headers") or prm.get("headers") or {}
                for k, v in hdrs.items():
                    if k.lower() == "authorization" and isinstance(v, str) and v.startswith("OAuth "):
                        set_token(v[6:].strip())
                url = (prm.get("request") or {}).get("url") or ""
                m = _LYRICS_URL.search(url)
                if m:
                    fm = re.search(r"[?&]format=(\w+)", url)
                    self.lyric_reqs[prm.get("requestId")] = (m.group(1), (fm.group(1) if fm else "LRC").upper())
                m = _PLAY_URL.search(url)
                if m:
                    global LAST_PLAY_ID
                    LAST_PLAY_ID = m.group(1) or m.group(2)
                continue
            if method == "Network.loadingFinished" and prm.get("requestId") in self.lyric_reqs:
                tid, fmt = self.lyric_reqs.pop(prm["requestId"])
                self.send("Network.getResponseBody", {"requestId": prm["requestId"]},
                          callback=lambda r, tid=tid, fmt=fmt: self._on_lyrics_body(tid, fmt, r))
                continue
            if method == "Runtime.bindingCalled" and msg["params"].get("name") == BINDING:
                try:
                    data = json.loads(msg["params"]["payload"])
                except Exception:
                    continue
                threading.Thread(target=self.handle, args=(data,), daemon=True).start()

    def handle(self, data):
        artist, title = data.get("artist", ""), data.get("title", "")
        if data.get("action") == "token":
            set_token(data.get("value"))
            return
        if data.get("action") == "token_candidate":
            check_token_candidate(data.get("value"))
            return
        if data.get("action") == "mediakey":
            press_media_key(data.get("key"))
            return
        if data.get("action") == "browser":
            q = f"{artist} {title} текст песни".strip()
            webbrowser.open("https://www.google.com/search?q=" + urllib.parse.quote(q))
            return
        t0 = time.time()
        res = find_lyrics(artist, title, data.get("duration") or 0, bool(data.get("force")),
                          data.get("trackId") or "")
        kind = "синхронный" if res.get("synced") else "обычный" if res.get("plain") else \
            "инструментал" if res.get("instrumental") else "не найден"
        src = f", {res['source']}" if res.get("source") else ""
        how = "из кэша" if res.get("cached") else f"{time.time() - t0:.1f} с"
        tid = data.get("trackId") or "?"
        print(f"{artist} — {title} [id {tid}]: {kind}{src} ({how})")
        payload = json.dumps(dict(res, key=data.get("key")), ensure_ascii=False)
        try:
            self.send("Runtime.evaluate",
                      {"expression": f"window.__ymLyricsShow && window.__ymLyricsShow({payload})"})
        except Exception:
            pass


_LYRICS_URL = re.compile(r"/tracks/(\d+)(?::\d+)?/lyrics")
_PLAY_URL = re.compile(r"/tracks/(\d+)(?::\d+)?/download-info|get-file-info\?[^#]*?trackId=(\d+)")

_VK = {"toggle": 0xB3, "nexttrack": 0xB0, "previoustrack": 0xB1}


def press_media_key(name):
    """Запасной путь управления: системная медиаклавиша Windows."""
    vk = _VK.get(name)
    if not vk or os.name != "nt":
        return
    try:
        import ctypes
        ctypes.windll.user32.keybd_event(vk, 0, 0, 0)
        ctypes.windll.user32.keybd_event(vk, 0, 2, 0)
    except Exception as e:
        print("  медиаклавиша:", e)


_LOCAL = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # localhost — мимо прокси/VPN


def list_targets():
    try:
        with _LOCAL.open(f"http://127.0.0.1:{PORT}/json/list", timeout=3) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception:
        return None


def main():
    if not os.path.exists(EXE):
        print("Не найдено приложение:", EXE)
        input("Enter — выход")
        sys.exit(1)

    if list_targets() is None:
        print("Перезапускаю Яндекс Музыку с поддержкой кнопки «Текст»…")
        subprocess.run(["taskkill", "/F", "/IM", EXE_NAME], capture_output=True)
        time.sleep(2)
        flags = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        subprocess.Popen([EXE, f"--remote-debugging-port={PORT}", "--remote-allow-origins=*"],
                         creationflags=flags, close_fds=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    deadline = time.time() + 60
    tabs = {}
    seen_any = False
    reported = False
    while True:
        targets = list_targets()
        if targets is None:
            if seen_any:
                print("Яндекс Музыка закрыта — выхожу.")
                return
            if time.time() > deadline:
                print("\nПриложение не открыло порт отладки за 60 секунд.")
                print("Напишите об этом Claude и пришлите этот вывод.")
                input("Enter — выход")
                sys.exit(1)
        else:
            if not seen_any and not reported:
                reported = True
                print("Окна приложения:")
                for t in targets:
                    print(f"  [{t.get('type')}] {t.get('url', '')[:120]}")
            for t in targets:
                if t.get("type") not in ("page", "webview") or "webSocketDebuggerUrl" not in t:
                    continue
                if t.get("url", "").startswith(("devtools://", "chrome-extension://")):
                    continue
                old = tabs.get(t["id"])
                if old is None or not old.is_alive():
                    tab = Tab(t)
                    tab.start()
                    tabs[t["id"]] = tab
                    seen_any = True
        time.sleep(2)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
