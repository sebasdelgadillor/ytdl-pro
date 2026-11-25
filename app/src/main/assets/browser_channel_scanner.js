(function() {
    try {
        var mode = __MODE__;
        var cfg = __CFG_JSON__;
        var downloadedKeysList = __DOWNLOADED_KEYS__;
        var inProgressKeysList = __IN_PROGRESS_KEYS__;
        var downloadedLabel = __DOWNLOADED_LABEL__;
        var inProgressLabel = __IN_PROGRESS_LABEL__;

        var uiNoise = (cfg && cfg.uiNoiseSubstrings) || [];
        var feedPaths = (cfg && cfg.feedPathMarkers) || [];
        var badPermalinks = (cfg && cfg.permalinkNoiseMarkers) || [];
        var hiPermalinks = (cfg && cfg.highPriorityPermalinkMarkers) || [];
        var medPermalinks = (cfg && cfg.generalPostPermalinkMarkers) || [];
        var genericTitles = (cfg && cfg.genericPlaceholderTitles) || [];
        var siteHostBase = (location.hostname || '').replace(/^www\./i, '').split('.')[0].toLowerCase();

        var trackingParams = {
            si: true, pp: true, feature: true, t: true, list: true, index: true,
            start_radio: true, igsh: true, igshid: true, fbclid: true, s: true,
            ref: true, ref_src: true, ref_url: true, sender_device: true,
            is_from_webapp: true, rdid: true, share_url: true, app: true,
            attribution_link: true, ab_channel: true, context: true
        };

        var downloadedMap = {};
        if (downloadedKeysList && downloadedKeysList.length) {
            for (var di = 0; di < downloadedKeysList.length; di++) {
                downloadedMap[downloadedKeysList[di]] = true;
            }
        }
        var inProgressMap = {};
        if (inProgressKeysList && inProgressKeysList.length) {
            for (var ii = 0; ii < inProgressKeysList.length; ii++) {
                inProgressMap[inProgressKeysList[ii]] = true;
            }
        }

        function absUrl(u) {
            if (!u || typeof u !== 'string') return '';
            if (u.indexOf('http://') === 0 || u.indexOf('https://') === 0) return u;
            if (u.indexOf('//') === 0) return location.protocol + u;
            try { return new URL(u, location.href).href; } catch (e) { return ''; }
        }

        function isUiNoise(u) {
            if (!u) return true;
            var lu = u.toLowerCase();
            for (var i = 0; i < uiNoise.length; i++) {
                if (lu.indexOf(uiNoise[i]) >= 0) return true;
            }
            return false;
        }

        function isProfileAvatar(url, alt, rect) {
            if (alt && /(foto del perfil|foto de perfil|imagen de perfil|profile picture|profile photo|avatar)/i.test(alt)) {
                return true;
            }
            if (url && /t51\.2885-19|[\/_](s|p)(32|40|48|50|60|64|80|100|150|160)x(32|40|48|50|60|64|80|100|150|160)|profile_pic|yt3\.ggpht\.com\/.*=s(32|40|48|68|88)\-/i.test(url)) {
                return true;
            }
            if (rect && rect.width > 0 && rect.height > 0 && rect.width <= 64 && rect.height <= 64 && Math.abs(rect.width - rect.height) <= 10) {
                return true;
            }
            return false;
        }

        function pickBestSrcset(ss) {
            if (!ss || typeof ss !== 'string') return '';
            var entries = ss.trim().split(/,\s+(?=https?:\/\/|\/|data:)/);
            var bestU = '';
            var bestScore = -1;
            for (var i = 0; i < entries.length; i++) {
                var parts = entries[i].trim().split(/\s+/);
                var u = parts[0] || '';
                if (!u) continue;
                var desc = (parts[1] || '').toLowerCase();
                var sc = i + 1;
                if (desc.slice(-1) === 'w') sc = parseInt(desc, 10) || 1;
                else if (desc.slice(-1) === 'x') sc = (parseFloat(desc) || 1) * 1000;
                if (sc >= bestScore) { bestScore = sc; bestU = u; }
            }
            return bestU;
        }

        function cleanTextWithoutBadges(el) {
            if (!el) return '';
            if (el.classList && el.classList.contains('ytdlp-dl-badge')) return '';
            if (!el.querySelector || !el.querySelector('.ytdlp-dl-badge')) {
                return (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
            }
            try {
                var clone = el.cloneNode(true);
                var badges = clone.querySelectorAll('.ytdlp-dl-badge');
                for (var bi = 0; bi < badges.length; bi++) {
                    if (badges[bi].parentNode) badges[bi].parentNode.removeChild(badges[bi]);
                }
                return (clone.innerText || clone.textContent || '').replace(/\s+/g, ' ').trim();
            } catch (e) {
                return (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
            }
        }

        function isGenericTitle(s) {
            if (!s || typeof s !== 'string') return true;
            var norm = s.replace(/\s+/g, ' ').trim();
            if (norm.length <= 2) return true;
            if ((downloadedLabel && norm.indexOf(downloadedLabel) >= 0) ||
                (inProgressLabel && norm.indexOf(inProgressLabel) >= 0)) return true;
            var lower = norm.toLowerCase();
            var trimmed = lower.replace(/[\.\u2026:\-\s]+$/, '').trim();
            if (trimmed.length <= 2) return true;
            if (isProfileAvatar('', norm, null)) return true;
            if (siteHostBase && (lower === siteHostBase || trimmed === siteHostBase)) return true;
            for (var gi = 0; gi < genericTitles.length; gi++) {
                var gt = (genericTitles[gi] || '').toLowerCase().trim();
                if (gt && (lower === gt || trimmed === gt)) return true;
            }
            if (/^(\d+:\d{2}(:\d{2})?)$/.test(trimmed)) return true;
            if (/^(reproducir|play|ver video|watch video|video|reel|shorts)$/i.test(trimmed)) return true;
            if (trimmed.indexOf('no hay ninguna descripci') === 0 ||
                trimmed.indexOf('no photo description available') === 0 ||
                trimmed.indexOf('video player') === 0 ||
                trimmed.indexOf('reproductor de video') === 0) {
                return true;
            }
            return false;
        }

        function parseUrlSafe(rawUrl) {
            try { return new URL(rawUrl, location.href); } catch (e) { return null; }
        }

        function extractKeysAndCleanUrl(rawUrl) {
            var u = parseUrlSafe(rawUrl);
            if (!u || (u.protocol !== 'http:' && u.protocol !== 'https:')) {
                return { keys: [], primaryKey: '', cleanUrl: '' };
            }
            var host = (u.hostname || '').toLowerCase()
                .replace(/^(www\.|m\.|mobile\.|web\.)/, '');
            var rawPath = u.pathname || '/';
            var lowerPath = rawPath.toLowerCase();
            var pathNoTrailing = rawPath.replace(/\/+$/, '') || '/';

            var queryPairs = [];
            var vParam = '';
            u.searchParams.forEach(function(val, key) {
                var lk = (key || '').toLowerCase();
                if (!lk || !val) return;
                if (lk === 'v' && !vParam) vParam = val;
                if (lk.indexOf('utm_') === 0 || trackingParams[lk]) return;
                queryPairs.push([key, val]);
            });
            queryPairs.sort(function(a, b) {
                var ak = a[0].toLowerCase();
                var bk = b[0].toLowerCase();
                return ak < bk ? -1 : (ak > bk ? 1 : 0);
            });

            var keys = [];
            if (host === 'youtube.com' || host === 'music.youtube.com' || host === 'youtu.be') {
                var ytId = '';
                if (host === 'youtu.be') {
                    ytId = pathNoTrailing.replace(/^\/+/, '').split('/')[0] || '';
                } else if (lowerPath === '/watch') {
                    ytId = vParam;
                } else if (lowerPath.indexOf('/shorts/') === 0) {
                    ytId = rawPath.slice('/shorts/'.length).split('/')[0] || '';
                } else if (lowerPath.indexOf('/live/') === 0) {
                    ytId = rawPath.slice('/live/'.length).split('/')[0] || '';
                } else if (lowerPath.indexOf('/embed/') === 0) {
                    ytId = rawPath.slice('/embed/'.length).split('/')[0] || '';
                }
                if (ytId && /^[A-Za-z0-9_-]{8,15}$/.test(ytId)) {
                    keys.push('yt:' + ytId);
                }
            }

            var igCanonPath = '';
            if (host === 'instagram.com' || host === 'instagr.am') {
                var igM = rawPath.match(/^(?:\/[A-Za-z0-9._]+)?\/(p|reel|reels|tv)\/([A-Za-z0-9_-]+)(?:\/.*)?$/i);
                if (igM && igM[2]) {
                    keys.push('ig:' + igM[2]);
                    igCanonPath = '/' + igM[1].toLowerCase() + '/' + igM[2] + '/';
                }
            }

            if (host === 'tiktok.com' || host.slice(-11) === '.tiktok.com') {
                var ttM = rawPath.match(/\/(?:video|photo)\/(\d+)/i) || rawPath.match(/\/v\/(\d+)/i);
                if (ttM && ttM[1]) keys.push('tt:' + ttM[1]);
            }

            if (host === 'facebook.com' || host === 'fb.watch') {
                var fbM = rawPath.match(/\/(?:reel|videos)\/(\d+)/i);
                var fbId = (fbM && fbM[1]) || (lowerPath.indexOf('/watch') === 0 ? vParam : '');
                if (fbId && /^\d+$/.test(fbId)) keys.push('fb:' + fbId);
            }

            if (host === 'x.com' || host === 'twitter.com') {
                var xM = rawPath.match(/\/status\/(\d+)/i);
                if (xM && xM[1]) keys.push('x:' + xM[1]);
            }

            if (host === 'reddit.com' || host === 'old.reddit.com' || host === 'redd.it') {
                var rdM = rawPath.match(/\/comments\/([A-Za-z0-9]+)/i);
                if (rdM && rdM[1]) keys.push('rd:' + rdM[1]);
            }

            var qStr = queryPairs.length > 0
                ? ('?' + queryPairs.map(function(p) { return p[0] + '=' + p[1]; }).join('&'))
                : '';
            var normPath = igCanonPath ? igCanonPath.replace(/\/+$/, '') : pathNoTrailing;
            var urlKey = 'url:' + host + normPath + qStr;
            keys.push(urlKey);

            var cleanU = new URL(u.href);
            if (igCanonPath) cleanU.pathname = igCanonPath;
            var cleanedSearch = new URLSearchParams();
            for (var qi = 0; qi < queryPairs.length; qi++) {
                cleanedSearch.append(queryPairs[qi][0], queryPairs[qi][1]);
            }
            cleanU.search = cleanedSearch.toString() ? ('?' + cleanedSearch.toString()) : '';
            cleanU.hash = '';

            return {
                keys: keys,
                primaryKey: keys.length > 1 ? keys[0] : urlKey,
                cleanUrl: cleanU.toString()
            };
        }

        function isFeedUrl(urlStr) {
            var u = parseUrlSafe(urlStr);
            if (!u) return true;
            var p = (u.pathname || '').replace(/\/+$/, '').toLowerCase();
            if (!p) return true;
            if (p === '/watch' && /[?&]v=[A-Za-z0-9_-]+/i.test(u.search || '')) return false;
            for (var fi = 0; fi < feedPaths.length; fi++) {
                if ((feedPaths[fi] || '').toLowerCase() === p) return true;
            }
            return false;
        }

        function isMediaCandidateHref(urlStr, keysInfo) {
            if (!urlStr || !keysInfo || !keysInfo.primaryKey) return false;
            var lower = urlStr.toLowerCase();
            for (var b = 0; b < badPermalinks.length; b++) {
                var bad = (badPermalinks[b] || '').toLowerCase();
                if (bad && lower.indexOf(bad) >= 0) return false;
            }
            if (isFeedUrl(urlStr)) return false;

            if (/^(yt|ig|tt|fb|x|rd):/.test(keysInfo.primaryKey)) {
                return true;
            }
            var u = parseUrlSafe(urlStr);
            var rawPath = u ? (u.pathname || '').toLowerCase() : '';
            for (var h = 0; h < hiPermalinks.length; h++) {
                var hi = (hiPermalinks[h] || '').toLowerCase();
                if (!hi) continue;
                if (hi.charAt(0) === '/' && hi.slice(-1) === '/') {
                    var idx = rawPath.indexOf(hi);
                    if (idx >= 0 && rawPath.slice(idx + hi.length).replace(/\/+$/, '').length > 0) return true;
                } else if (lower.indexOf(hi) >= 0) {
                    return true;
                }
            }
            for (var m = 0; m < medPermalinks.length; m++) {
                if (lower.indexOf(medPermalinks[m]) >= 0) return true;
            }
            return false;
        }

        var semanticCardSelector = [
            'ytm-rich-item-renderer',
            'ytm-video-with-context-renderer',
            'ytm-compact-video-renderer',
            'ytm-reel-item-renderer',
            'ytm-shorts-lockup-view-model',
            'ytm-playlist-video-renderer',
            'ytd-rich-item-renderer',
            'ytd-grid-video-renderer',
            'ytd-video-renderer',
            'ytd-compact-video-renderer',
            'ytd-reel-item-renderer',
            'article',
            '[role="article"]'
        ].join(', ');

        function countDistinctKeys(el) {
            if (!el || !el.querySelectorAll) return 0;
            var seen = {};
            var count = 0;
            var links = el.querySelectorAll('a[href]');
            for (var i = 0; i < Math.min(links.length, 24); i++) {
                var info = extractKeysAndCleanUrl(links[i].href);
                if (isMediaCandidateHref(links[i].href, info) && !seen[info.primaryKey]) {
                    seen[info.primaryKey] = true;
                    count++;
                    if (count > 1) return count;
                }
            }
            return count;
        }

        function findCardRoot(anchor) {
            if (!anchor) return null;
            var sem = anchor.closest ? anchor.closest(semanticCardSelector) : null;
            if (sem) {
                var sr = sem.getBoundingClientRect ? sem.getBoundingClientRect() : null;
                if (!sr || (sr.height > 0 && sr.height <= 900 && countDistinctKeys(sem) <= 1)) {
                    return sem;
                }
            }
            var node = anchor.parentElement;
            var best = anchor;
            var depth = 0;
            while (node && node !== document.body && node !== document.documentElement && depth < 5) {
                var nr = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
                if (!nr || nr.height > 760 || nr.height <= 0) break;
                if (countDistinctKeys(node) > 1) break;
                if (node.querySelector && node.querySelector('img')) {
                    best = node;
                }
                node = node.parentElement;
                depth++;
            }
            return best;
        }

        function extractBestThumb(root, anchor) {
            var containers = [anchor, root];
            var bestUrl = '';
            var bestArea = -1;
            for (var ci = 0; ci < containers.length; ci++) {
                var c = containers[ci];
                if (!c || !c.querySelectorAll) continue;
                var imgs = c.querySelectorAll('img');
                for (var i = 0; i < Math.min(imgs.length, 8); i++) {
                    var img = imgs[i];
                    var r = img.getBoundingClientRect ? img.getBoundingClientRect() : null;
                    var w = img.naturalWidth || (r ? Math.round(r.width) : 0);
                    var h = img.naturalHeight || (r ? Math.round(r.height) : 0);
                    var alt = (img.alt || '').trim();
                    var u = '';
                    if (img.srcset) u = absUrl(pickBestSrcset(img.srcset));
                    if (!u) {
                        u = absUrl(
                            img.currentSrc || img.src ||
                            img.getAttribute('data-thumb') ||
                            img.getAttribute('data-src') ||
                            img.getAttribute('data-lazy-src') || ''
                        );
                    }
                    if (!u || u.indexOf('http') !== 0 || isUiNoise(u)) continue;
                    if (isProfileAvatar(u, alt, r)) continue;
                    var area = (w || 120) * (h || 90);
                    if (area > bestArea) {
                        bestArea = area;
                        bestUrl = u;
                    }
                }
                if (bestUrl) return bestUrl;
            }
            return '';
        }

        function extractTitle(root, anchor, fallbackKey) {
            var candidates = [];
            if (root && root.querySelectorAll) {
                var titleNodes = root.querySelectorAll('h1, h2, h3, h4, .media-item-headline, #video-title, [id*="video-title"], [data-testid*="title"]');
                for (var i = 0; i < Math.min(titleNodes.length, 8); i++) {
                    var tn = titleNodes[i];
                    var val = cleanTextWithoutBadges(tn) || (tn.getAttribute && (tn.getAttribute('aria-label') || tn.getAttribute('title'))) || '';
                    if (val) candidates.push(val);
                }
                var altImgs = root.querySelectorAll('img[alt]');
                for (var ai = 0; ai < Math.min(altImgs.length, 4); ai++) {
                    var altVal = altImgs[ai].getAttribute('alt') || '';
                    if (altVal) candidates.push(altVal);
                }
            }
            if (anchor) {
                var aa = anchor.getAttribute && (anchor.getAttribute('aria-label') || anchor.getAttribute('title'));
                if (aa) candidates.push(aa);
                var txtA = cleanTextWithoutBadges(anchor);
                if (txtA) candidates.push(txtA);
            }
            for (var ci = 0; ci < candidates.length; ci++) {
                var raw = (candidates[ci] || '').replace(/\s+/g, ' ').trim();
                if (!raw || raw.length > 220) continue;
                if (!isGenericTitle(raw)) return raw;
            }
            if (fallbackKey && fallbackKey.indexOf('ig:') === 0) return 'Post ' + fallbackKey.slice(3);
            return '';
        }

        function extractDuration(root) {
            if (!root || !root.querySelectorAll) return '';
            var durEls = root.querySelectorAll('ytm-thumbnail-overlay-time-status-renderer, ytd-thumbnail-overlay-time-status-renderer, .badge-shape-wiz__text, time, [class*="duration"], [aria-label*=":"]');
            for (var i = 0; i < Math.min(durEls.length, 6); i++) {
                var t = cleanTextWithoutBadges(durEls[i]);
                if (/^\d{1,2}:\d{2}(?::\d{2})?$/.test(t)) return t;
            }
            return '';
        }

        function findHighlightHost(anchor, cardRoot) {
            var sources = [anchor, cardRoot];
            for (var si = 0; si < sources.length; si++) {
                var src = sources[si];
                if (!src || !src.querySelectorAll) continue;
                var imgs = src.querySelectorAll('img');
                for (var ii = 0; ii < Math.min(imgs.length, 6); ii++) {
                    var img = imgs[ii];
                    var r = img.getBoundingClientRect ? img.getBoundingClientRect() : null;
                    if (!r || r.width < 56 || r.height < 40) continue;
                    if (isProfileAvatar(img.currentSrc || img.src || '', img.alt || '', r)) continue;
                    var host = (img.closest && img.closest('ytd-thumbnail, ytm-thumbnail-container, .ytm-thumbnail-container, a[href], div, span')) || anchor;
                    if (host && host.getBoundingClientRect) {
                        var hr = host.getBoundingClientRect();
                        if (hr.width >= 56 && hr.height >= 40) return host;
                    }
                }
            }
            var ar = anchor && anchor.getBoundingClientRect ? anchor.getBoundingClientRect() : null;
            if (ar && ar.width >= 56 && ar.height >= 36) return anchor;
            return null;
        }

        function matchState(keysInfo) {
            for (var i = 0; i < keysInfo.keys.length; i++) {
                if (inProgressMap[keysInfo.keys[i]]) return 'in_progress';
            }
            for (var j = 0; j < keysInfo.keys.length; j++) {
                if (downloadedMap[keysInfo.keys[j]]) return 'downloaded';
            }
            return 'new';
        }

        function ensureBadgeStyles() {
            if (document.getElementById('ytdlp-dl-badge-style')) return;
            var st = document.createElement('style');
            st.id = 'ytdlp-dl-badge-style';
            st.textContent =
                '.ytdlp-dl-badge{position:absolute!important;top:6px!important;left:6px!important;z-index:25!important;' +
                'pointer-events:none!important;font:700 11px/1.25 sans-serif!important;padding:3px 8px!important;' +
                'border-radius:999px!important;color:#fff!important;box-shadow:0 1px 4px rgba(0,0,0,0.45)!important;' +
                'letter-spacing:0.2px!important;max-width:88%!important;white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important;}' +
                '.ytdlp-dl-badge--downloaded{background:#1B873F!important;}' +
                '.ytdlp-dl-badge--in_progress{background:#D97706!important;}' +
                '[data-ytdlp-dl-host="downloaded"]{outline:2px solid #1B873F!important;outline-offset:-2px!important;}' +
                '[data-ytdlp-dl-host="in_progress"]{outline:2px solid #D97706!important;outline-offset:-2px!important;}';
            (document.head || document.documentElement).appendChild(st);
        }

        if (mode === 'highlight') {
            ensureBadgeStyles();
            var existingBadges = document.querySelectorAll('.ytdlp-dl-badge');
            for (var eb = 0; eb < existingBadges.length; eb++) {
                if (existingBadges[eb].parentNode) {
                    existingBadges[eb].parentNode.removeChild(existingBadges[eb]);
                }
            }
            var oldHosts = document.querySelectorAll('[data-ytdlp-dl-host]');
            for (var oh = 0; oh < oldHosts.length; oh++) {
                oldHosts[oh].removeAttribute('data-ytdlp-dl-host');
            }

            var anchors = document.querySelectorAll('a[href]');
            var markedHosts = [];
            var dlCount = 0;
            var progCount = 0;

            for (var ai = 0; ai < Math.min(anchors.length, 400); ai++) {
                var a = anchors[ai];
                var info = extractKeysAndCleanUrl(a.href);
                if (!isMediaCandidateHref(a.href, info)) continue;
                var state = matchState(info);
                if (state === 'new') continue;

                var root = findCardRoot(a);
                var host = findHighlightHost(a, root);
                if (!host || host.getAttribute('data-ytdlp-dl-host')) continue;

                try {
                    if (window.getComputedStyle(host).position === 'static') {
                        host.style.position = 'relative';
                    }
                } catch (e) {}

                host.setAttribute('data-ytdlp-dl-host', state);
                var badge = document.createElement('span');
                badge.className = 'ytdlp-dl-badge ytdlp-dl-badge--' + state;
                badge.setAttribute('aria-hidden', 'true');
                badge.textContent = (state === 'in_progress') ? inProgressLabel : downloadedLabel;
                host.appendChild(badge);
                markedHosts.push(host);
                if (state === 'in_progress') progCount++;
                else dlCount++;
            }

            return JSON.stringify({
                highlightedDownloaded: dlCount,
                highlightedInProgress: progCount
            });
        }

        // Default mode: "scan"
        var pageTitle = (document.title || '').replace(/\s*-\s*YouTube\s*$/i, '').trim();
        var itemsByKey = {};
        var orderedKeys = [];
        var allAnchors = document.querySelectorAll('a[href]');

        for (var idx = 0; idx < allAnchors.length && orderedKeys.length < 150; idx++) {
            var anchorEl = allAnchors[idx];
            var rectA = anchorEl.getBoundingClientRect ? anchorEl.getBoundingClientRect() : null;
            if (!rectA || (rectA.width <= 0 && rectA.height <= 0)) continue;
            if (anchorEl.closest && anchorEl.closest('header, nav') && !anchorEl.closest('main, [role="main"]')) continue;

            var kInfo = extractKeysAndCleanUrl(anchorEl.href);
            if (!isMediaCandidateHref(anchorEl.href, kInfo)) continue;

            var rootEl = findCardRoot(anchorEl);
            var thumb = extractBestThumb(rootEl, anchorEl);
            var title = extractTitle(rootEl, anchorEl, kInfo.primaryKey);
            var duration = extractDuration(rootEl);
            var lowerUrl = kInfo.cleanUrl.toLowerCase();
            var isVideo = duration.length > 0 ||
                /^(yt|tt|fb):/.test(kInfo.primaryKey) ||
                lowerUrl.indexOf('/reel/') >= 0 ||
                lowerUrl.indexOf('/reels/') >= 0 ||
                lowerUrl.indexOf('/tv/') >= 0 ||
                lowerUrl.indexOf('/videos/') >= 0 ||
                lowerUrl.indexOf('/shorts/') >= 0;

            var existing = itemsByKey[kInfo.primaryKey];
            if (!existing) {
                itemsByKey[kInfo.primaryKey] = {
                    key: kInfo.primaryKey,
                    url: kInfo.cleanUrl,
                    title: title,
                    thumb: thumb,
                    duration: duration,
                    author: pageTitle,
                    isVideo: isVideo
                };
                orderedKeys.push(kInfo.primaryKey);
            } else {
                if (!existing.title && title) existing.title = title;
                if (!existing.thumb && thumb) existing.thumb = thumb;
                if (!existing.duration && duration) existing.duration = duration;
                if (!existing.isVideo && isVideo) existing.isVideo = true;
            }
        }

        var items = [];
        for (var oi = 0; oi < orderedKeys.length; oi++) {
            var it = itemsByKey[orderedKeys[oi]];
            if (!it.title) {
                it.title = it.isVideo ? ('Video · ' + pageTitle) : (pageTitle || 'Media');
            }
            items.push(it);
        }

        return JSON.stringify({
            pageTitle: pageTitle,
            pageAuthor: pageTitle,
            items: items
        });
    } catch (e) {
        return JSON.stringify({ pageTitle: '', pageAuthor: '', items: [], highlightedDownloaded: 0, highlightedInProgress: 0 });
    }
})();
