(function() {
    try {
        var nx = __NX__;
        var ny = __NY__;
        var cfg = __CFG_JSON__;
        var vw = window.innerWidth || document.documentElement.clientWidth || 360;
        var vh = window.innerHeight || document.documentElement.clientHeight || 800;
        var hasPoint = (nx >= 0 && ny >= 0);
        var cx = hasPoint ? nx * vw : Math.round(vw * 0.5);
        var cy = hasPoint ? ny * vh : Math.round(vh * 0.5);

        var uiNoise = (cfg && cfg.uiNoiseSubstrings) || [];
        var feedPaths = (cfg && cfg.feedPathMarkers) || [];
        var badPermalinks = (cfg && cfg.permalinkNoiseMarkers) || [];
        var hiPermalinks = (cfg && cfg.highPriorityPermalinkMarkers) || [];
        var medPermalinks = (cfg && cfg.generalPostPermalinkMarkers) || [];
        var hintKeywords = (cfg && cfg.videoHintKeywords) || [];
        var iframeHosts = (cfg && cfg.videoIframeHostMarkers) || [];
        var postSelectors = ((cfg && cfg.postContainerSelectors) || ['article', '[role="article"]']).join(', ');
        var dataAttrs = (cfg && cfg.dataAttributeNames) || [];
        var jsonVKeys = (cfg && cfg.dataJsonVideoKeys) || [];
        var jsonPKeys = (cfg && cfg.dataJsonPermalinkKeys) || [];
        var genericTitles = (cfg && cfg.genericPlaceholderTitles) || [];
        var siteHostBase = (location.hostname || '').replace(/^www\./i, '').split('.')[0].toLowerCase();

        function isGoodHttpOrData(u) {
            return u && typeof u === 'string' && (u.indexOf('http') === 0 || u.indexOf('data:image/') === 0);
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
        function absUrl(u) {
            if (!u || typeof u !== 'string') return '';
            if (u.indexOf('data:image/') === 0 || u.indexOf('http') === 0 || u.indexOf('blob:') === 0) return u;
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
            if (url && /t51\.2885-19|[\/_](s|p)(32|40|48|50|60|64|80|100|150|160)x(32|40|48|50|60|64|80|100|150|160)|profile_pic/i.test(url)) {
                return true;
            }
            if (rect && rect.width > 0 && rect.height > 0 && rect.width <= 76 && rect.height <= 76) {
                return true;
            }
            return false;
        }
        function findPrimaryViewportVideo() {
            var vids = document.querySelectorAll('video');
            var bestVid = null;
            var bestScore = 0;
            for (var i = 0; i < vids.length; i++) {
                var v = vids[i];
                var r = v.getBoundingClientRect ? v.getBoundingClientRect() : null;
                if (!r || r.width <= 0 || r.height <= 0) continue;
                var ow = Math.min(r.right, vw) - Math.max(r.left, 0);
                var oh = Math.min(r.bottom, vh) - Math.max(r.top, 0);
                if (ow < 100 || oh < 100) continue;
                var playing = (!v.paused && v.readyState >= 2);
                var sc = ow * oh * (playing ? 3 : 1);
                if (sc > bestScore) {
                    bestScore = sc;
                    bestVid = v;
                }
            }
            return bestVid;
        }
        function isFeedUrl(urlStr) {
            if (!urlStr || typeof urlStr !== 'string') return true;
            try {
                var u = new URL(urlStr);
                var p = (u.pathname || '').replace(/\/+$/, '').toLowerCase();
                if (!p) return true;
                if (p === '/watch' && /[?&]v=[A-Za-z0-9_-]+/i.test(u.search || '')) return false;
                for (var fi = 0; fi < feedPaths.length; fi++) {
                    if ((feedPaths[fi] || '').toLowerCase() === p) return true;
                }
                return false;
            } catch (e) {
                return false;
            }
        }
        function canonicalizePermalinkUrl(urlStr) {
            if (!urlStr || typeof urlStr !== 'string') return { url: '', wasSubpage: false };
            try {
                var u = new URL(urlStr);
                var m = u.pathname.match(/^\/(p|reel|reels|tv)\/([^\/]+)\/(liked_by|comments|c\/[^\/]+)\/?$/i);
                if (m) {
                    u.pathname = '/' + m[1] + '/' + m[2] + '/';
                    return { url: u.toString(), wasSubpage: true };
                }
                return { url: u.toString(), wasSubpage: false };
            } catch (e) {
                return { url: urlStr, wasSubpage: false };
            }
        }
        function isGenericTitle(s) {
            if (!s || typeof s !== 'string') return true;
            var norm = s.replace(/\s+/g, ' ').trim();
            if (norm.length <= 2) return true;
            var lower = norm.toLowerCase();
            var trimmed = lower.replace(/[\.\u2026:\-\s]+$/, '').trim();
            if (trimmed.length <= 2) return true;
            if (isProfileAvatar('', norm, null)) return true;
            if (siteHostBase && (lower === siteHostBase || trimmed === siteHostBase)) return true;
            for (var gi = 0; gi < genericTitles.length; gi++) {
                var gt = (genericTitles[gi] || '').toLowerCase().trim();
                if (gt && (lower === gt || trimmed === gt)) return true;
            }
            var prefixes = [
                'no hay ninguna descripci\u00f3n de la foto',
                'no photo description available',
                'video player',
                'reproductor de video',
                'reproductor de v\u00eddeo'
            ];
            for (var pi = 0; pi < prefixes.length; pi++) {
                if (trimmed.indexOf(prefixes[pi]) === 0) return true;
            }
            return false;
        }
        function scorePermalinkUrl(urlStr, isWrapper) {
            if (!urlStr || typeof urlStr !== 'string') return -1000;
            var rawLower = urlStr.toLowerCase();
            if (rawLower.indexOf('http') !== 0) return -1000;
            var canon = canonicalizePermalinkUrl(urlStr);
            var effectiveUrl = canon.url || urlStr;
            var l = effectiveUrl.toLowerCase();
            for (var b = 0; b < badPermalinks.length; b++) {
                var bad = (badPermalinks[b] || '').toLowerCase();
                if (bad && (rawLower.indexOf(bad) >= 0 || l.indexOf(bad) >= 0)) return -1000;
            }
            if (isFeedUrl(effectiveUrl)) return -500;
            var rawPath = '';
            try { rawPath = new URL(effectiveUrl).pathname.toLowerCase(); } catch (e) {}
            var path = rawPath.replace(/\/+$/, '');
            if (!path || path === '/') return -500;
            var sc = 0;
            var matched = false;
            for (var h = 0; h < hiPermalinks.length; h++) {
                var hi = (hiPermalinks[h] || '').toLowerCase();
                if (!hi) continue;
                if (hi.charAt(0) === '/' && hi.slice(-1) === '/') {
                    var hiIdx = rawPath.indexOf(hi);
                    if (hiIdx >= 0 && rawPath.slice(hiIdx + hi.length).replace(/\/+$/, '').length > 0) {
                        sc = 100;
                        matched = true;
                        break;
                    }
                } else if (l.indexOf(hi) >= 0) {
                    sc = 100;
                    matched = true;
                    break;
                }
            }
            if (!matched && /[?&]v=[A-Za-z0-9_-]+/.test(l)) { sc = 100; matched = true; }
            if (!matched) {
                for (var m = 0; m < medPermalinks.length; m++) {
                    if (l.indexOf(medPermalinks[m]) >= 0) { sc = 60; matched = true; break; }
                }
            }
            if (!matched && path.split('/').filter(Boolean).length >= 2) sc = 15;
            if (canon.wasSubpage && sc > 0) sc -= 10;
            if (isWrapper && sc >= 0) sc += 20;
            return sc;
        }
        function hasVideoTextHint(s) {
            if (!s || typeof s !== 'string') return false;
            var l = s.toLowerCase();
            for (var i = 0; i < hintKeywords.length; i++) {
                if (l.indexOf(hintKeywords[i]) >= 0) return true;
            }
            return /\b\d{1,2}:\d{2}\b/.test(l);
        }
        function isVideoPlayerIframe(u) {
            if (!u) return false;
            var l = u.toLowerCase();
            for (var i = 0; i < iframeHosts.length; i++) {
                if (l.indexOf(iframeHosts[i]) >= 0) return true;
            }
            return false;
        }
        function extractDataAttrUrls(el) {
            var vids = [];
            var perms = [];
            var node = el;
            var depth = 0;
            while (node && node !== document.body && depth < 6) {
                if (node.getAttribute) {
                    for (var ai = 0; ai < dataAttrs.length; ai++) {
                        var raw = node.getAttribute(dataAttrs[ai]);
                        if (!raw) continue;
                        if (raw.charAt(0) === '{') {
                            try {
                                var obj = JSON.parse(raw);
                                for (var vk = 0; vk < jsonVKeys.length; vk++) {
                                    var vv = obj[jsonVKeys[vk]];
                                    if (vv && typeof vv === 'string' && vv.indexOf('http') === 0) vids.push(vv);
                                }
                                for (var pk = 0; pk < jsonPKeys.length; pk++) {
                                    var pv = obj[jsonPKeys[pk]];
                                    if (pv && typeof pv === 'string' && pv.indexOf('http') === 0) perms.push(pv);
                                }
                            } catch (e) {}
                        } else {
                            var resolved = absUrl(raw);
                            if (resolved && resolved.indexOf('http') === 0) {
                                if (dataAttrs[ai].indexOf('video') >= 0 || /\.(mp4|m3u8|mpd|webm)(\?|$)/i.test(resolved)) {
                                    vids.push(resolved);
                                } else if (scorePermalinkUrl(resolved, false) > 0) {
                                    perms.push(resolved);
                                }
                            }
                        }
                    }
                }
                node = node.parentElement;
                depth++;
            }
            return { videos: vids, permalinks: perms };
        }
        function extractPermalinksIn(root, directAnchor) {
            var out = [];
            if (!root || !root.querySelectorAll) return out;
            var anchors = root.querySelectorAll('a[href]');
            for (var i = 0; i < Math.min(anchors.length, 60); i++) {
                var a = anchors[i];
                var rawHref = a.href;
                var canon = canonicalizePermalinkUrl(rawHref);
                var href = canon.url || rawHref;
                var sc = scorePermalinkUrl(rawHref, a === directAnchor);
                if (sc > 0 && a.querySelector && a.querySelector('time, abbr[data-utime]')) {
                    sc = Math.max(sc + 15, 95);
                }
                if (sc > 0) out.push({ url: href, score: sc });
            }
            return out;
        }
        function findSelectedScope(startEl) {
            if (!startEl) return null;
            var dlg = startEl.closest ? startEl.closest('[role="dialog"], dialog[open]') : null;
            if (dlg) return dlg;

            var sem = (postSelectors && startEl.closest) ? startEl.closest(postSelectors) : null;
            if (sem) {
                var sr = sem.getBoundingClientRect ? sem.getBoundingClientRect() : null;
                if (!sr || sr.height <= Math.max(vh * 2.4, 2400)) return sem;
            }

            var feedCont = startEl.closest ? startEl.closest('[role="feed"], [role="list"]') : null;
            var node = startEl;
            var fallbackScope = null;
            while (node && node !== document.body && node !== document.documentElement) {
                var role = (node.getAttribute && node.getAttribute('role')) || '';
                if (role === 'feed' || role === 'main') break;
                var nr = node.getBoundingClientRect ? node.getBoundingClientRect() : { width: 0, height: 0 };
                if (nr.height >= 80 && nr.height <= Math.max(vh * 2.2, 2200)) {
                    var parent = node.parentElement;
                    var hasChildArticles = node.querySelectorAll ? node.querySelectorAll('article, [role="article"]').length > 1 : false;
                    if (!hasChildArticles) {
                        var linksCount = extractPermalinksIn(node, null).length;
                        if (feedCont && parent === feedCont && linksCount > 0) return node;
                        if (parent && parent !== document.body && nr.width >= vw * 0.7 && nr.height >= 160 && linksCount > 0) {
                            var sameTagSibs = 0;
                            var sibs = parent.children;
                            for (var si = 0; si < sibs.length; si++) {
                                if (sibs[si].tagName === node.tagName) sameTagSibs++;
                            }
                            if (sameTagSibs >= 3) return node;
                        }
                        if (!fallbackScope && linksCount > 0) fallbackScope = node;
                    }
                }
                node = node.parentElement;
            }
            return fallbackScope;
        }
        function tryBlobToDataUrl(imgEl) {
            try {
                if (!imgEl || !imgEl.complete || !(imgEl.naturalWidth > 60 && imgEl.naturalHeight > 60)) return '';
                var cv = document.createElement('canvas');
                var maxSide = 1280;
                var w = imgEl.naturalWidth;
                var h = imgEl.naturalHeight;
                if (Math.max(w, h) > maxSide) {
                    var scale = maxSide / Math.max(w, h);
                    w = Math.round(w * scale);
                    h = Math.round(h * scale);
                }
                cv.width = w;
                cv.height = h;
                var ctx2d = cv.getContext('2d');
                ctx2d.drawImage(imgEl, 0, 0, w, h);
                return cv.toDataURL('image/jpeg', 0.92);
            } catch (e) {
                return '';
            }
        }
        function extractImageFromNode(el) {
            if (!el || !el.tagName) return null;
            var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : { width: 0, height: 0, top: 0, bottom: 0, left: 0, right: 0 };
            var tag = el.tagName.toLowerCase();
            var candUrl = '';
            var nw = 0;
            var nh = 0;
            var alt = '';

            if (tag === 'img') {
                nw = el.naturalWidth || Math.round(rect.width) || 0;
                nh = el.naturalHeight || Math.round(rect.height) || 0;
                alt = (el.alt || el.getAttribute('aria-label') || '').trim();
                var pic = el.closest ? el.closest('picture') : null;
                if (pic) {
                    var srcs = pic.querySelectorAll('source[srcset]');
                    for (var si = 0; si < srcs.length; si++) {
                        var bu = pickBestSrcset(srcs[si].getAttribute('srcset'));
                        if (bu) { candUrl = absUrl(bu); break; }
                    }
                }
                if (!candUrl && el.srcset) candUrl = absUrl(pickBestSrcset(el.srcset));
                if (!candUrl) candUrl = absUrl(el.currentSrc || el.src || el.getAttribute('data-full-src') || el.getAttribute('data-original') || el.getAttribute('data-src') || el.getAttribute('data-lazy-src') || el.getAttribute('data-thumb'));
                if ((!candUrl || candUrl.indexOf('blob:') === 0) && (el.src || '').indexOf('blob:') === 0) {
                    var dataUrl = tryBlobToDataUrl(el);
                    if (dataUrl) candUrl = dataUrl;
                }
            } else if (tag === 'image' && el.getAttribute) {
                var href = el.getAttribute('xlink:href') || el.getAttribute('href');
                candUrl = absUrl(href);
                nw = Math.round(rect.width) || 0;
                nh = Math.round(rect.height) || 0;
            } else if (el.style || window.getComputedStyle) {
                var bg = '';
                try { bg = window.getComputedStyle(el).backgroundImage || ''; } catch (e) {}
                if (bg && bg.indexOf('url(') >= 0) {
                    var m = bg.match(/url\(["']?([^"')]+)["']?\)/i);
                    if (m && m[1]) {
                        candUrl = absUrl(m[1]);
                        nw = Math.round(rect.width) || 0;
                        nh = Math.round(rect.height) || 0;
                        alt = (el.getAttribute('aria-label') || el.getAttribute('title') || '').trim();
                    }
                }
            }
            if (!isGoodHttpOrData(candUrl) || isUiNoise(candUrl)) return null;
            var area = (nw || 1) * (nh || 1);
            var avatar = isProfileAvatar(candUrl, alt, rect);
            return { url: candUrl, w: nw, h: nh, area: area, title: alt, isAvatar: avatar, el: el, rect: rect };
        }
        function rectsTouchPoint(r) {
            return r && r.width > 0 && r.height > 0 &&
                cx >= (r.left - 18) && cx <= (r.right + 18) &&
                cy >= (r.top - 18) && cy <= (r.bottom + 18);
        }

        var pointImages = [];
        var pointVideos = [];
        var seenPV = {};
        function addPV(u) {
            if (u && typeof u === 'string' && u.indexOf('http') === 0 && !seenPV[u]) {
                seenPV[u] = true;
                pointVideos.push(u);
            }
        }
        var hasVideoEl = false;
        var bestPostUrl = '';
        var bestPostScore = -1000;
        function considerPermalink(urlStr, isDirectWrapper, overrideScore) {
            var canon = canonicalizePermalinkUrl(urlStr);
            var cleanUrl = canon.url || urlStr;
            var sc = (typeof overrideScore === 'number') ? overrideScore : scorePermalinkUrl(urlStr, isDirectWrapper);
            if (sc > bestPostScore && cleanUrl) {
                bestPostScore = sc;
                bestPostUrl = cleanUrl;
            }
        }
        if (scorePermalinkUrl(location.href, true) >= 80) {
            considerPermalink(location.href, true);
        }
        if (!isFeedUrl(location.href)) {
            var canTag = document.querySelector('link[rel="canonical"], meta[property="og:url"]');
            var canHref = canTag ? (canTag.getAttribute('href') || canTag.getAttribute('content') || '') : '';
            if (canHref) {
                var absCanHref = absUrl(canHref);
                if (absCanHref && !isFeedUrl(absCanHref)) {
                    considerPermalink(absCanHref, false);
                }
            }
        }

        var bestTitle = '';
        var stack = document.elementsFromPoint ? document.elementsFromPoint(cx, cy) : [];
        var primaryVideo = findPrimaryViewportVideo();
        var activeEl = document.activeElement;
        var usableActiveEl = (!hasPoint && activeEl && activeEl !== document.body && activeEl !== document.documentElement) ? activeEl : null;
        if (usableActiveEl && primaryVideo && usableActiveEl.getBoundingClientRect) {
            var ar = usableActiveEl.getBoundingClientRect();
            if (ar.width > 0 && ar.width < vw * 0.45 && ar.height > 0 && ar.height < vh * 0.25) {
                usableActiveEl = primaryVideo;
            }
        }
        var startEl = usableActiveEl || (stack.length > 0 ? stack[0] : null) || primaryVideo;
        var selectedScope = findSelectedScope(startEl);
        var directAnchor = startEl && startEl.closest ? startEl.closest('a[href]') : null;
        if (directAnchor && directAnchor.href) considerPermalink(directAnchor.href, true);

        var scanNodes = [];
        if (hasPoint) {
            if (primaryVideo && rectsTouchPoint(primaryVideo.getBoundingClientRect())) {
                hasVideoEl = true;
                scanNodes.push(primaryVideo);
            }
            for (var i = 0; i < stack.length; i++) {
                var node = stack[i];
                scanNodes.push(node);
                if (node.querySelectorAll) {
                    var nested = node.querySelectorAll('img, video, source, iframe, [style*="background-image"], i.img, [data-sigil*="photo"]');
                    for (var j = 0; j < Math.min(nested.length, 10); j++) {
                        if (rectsTouchPoint(nested[j].getBoundingClientRect())) {
                            scanNodes.push(nested[j]);
                        }
                    }
                }
            }
            var boxRoot = selectedScope || document;
            var boxCandidates = boxRoot.querySelectorAll('img, video, source, iframe, [style*="background-image"], [data-sigil*="photo"], i.img, [data-video-id], [data-testid*="video"]');
            for (var k = 0; k < boxCandidates.length; k++) {
                if (rectsTouchPoint(boxCandidates[k].getBoundingClientRect())) {
                    scanNodes.push(boxCandidates[k]);
                }
            }
        } else {
            if (primaryVideo) {
                hasVideoEl = true;
                scanNodes.push(primaryVideo);
            }
            for (var si0 = 0; si0 < stack.length; si0++) {
                scanNodes.push(stack[si0]);
            }
            if (usableActiveEl) {
                scanNodes.push(usableActiveEl);
                if (usableActiveEl.querySelectorAll) {
                    var activeNested = usableActiveEl.querySelectorAll('img, video, source, iframe, [style*="background-image"], [data-sigil*="photo"], i.img, [data-video-id], [data-testid*="video"]');
                    for (var an = 0; an < Math.min(activeNested.length, 12); an++) {
                        scanNodes.push(activeNested[an]);
                    }
                }
            }
            if (selectedScope) {
                var scopeEls = selectedScope.querySelectorAll('img, video, source, iframe, [style*="background-image"], [data-sigil*="photo"], i.img, [data-video-id], [data-testid*="video"]');
                for (var scIdx = 0; scIdx < scopeEls.length; scIdx++) scanNodes.push(scopeEls[scIdx]);
            }
        }

        var seenUrls = {};
        for (var mIdx = 0; mIdx < scanNodes.length; mIdx++) {
            var el = scanNodes[mIdx];
            var tag = el.tagName ? el.tagName.toLowerCase() : '';
            if (el.getAttribute) {
                var ariaLabel = (el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('alt') || '').trim();
                if (!bestTitle && ariaLabel && !isGenericTitle(ariaLabel) && ariaLabel.length < 180) {
                    bestTitle = ariaLabel;
                }
                if (el.getAttribute('data-video-id') ||
                    ((el.getAttribute('data-sigil') || '').toLowerCase().indexOf('video') >= 0) ||
                    ((el.getAttribute('data-testid') || '').toLowerCase().indexOf('video') >= 0) ||
                    hasVideoTextHint(ariaLabel)) {
                    hasVideoEl = true;
                }
            }
            if (tag === 'video' || tag === 'source') {
                hasVideoEl = true;
                addPV(absUrl(el.currentSrc || el.src));
                var vEl = tag === 'video' ? el : (el.closest ? el.closest('video') : null);
                if (vEl && vEl.poster) {
                    var pUrl = absUrl(vEl.poster);
                    if (isGoodHttpOrData(pUrl) && !isUiNoise(pUrl) && !isProfileAvatar(pUrl, '', null) && !seenUrls[pUrl]) {
                        seenUrls[pUrl] = true;
                        pointImages.push({ url: pUrl, w: vEl.videoWidth || 480, h: vEl.videoHeight || 480, title: '', isAvatar: false, el: vEl });
                    }
                }
            } else if (tag === 'iframe' && isVideoPlayerIframe(el.src)) {
                hasVideoEl = true;
                addPV(absUrl(el.src));
            }
            var daInfo = extractDataAttrUrls(el);
            for (var dv = 0; dv < daInfo.videos.length; dv++) {
                hasVideoEl = true;
                addPV(daInfo.videos[dv]);
            }
            for (var dp = 0; dp < daInfo.permalinks.length; dp++) {
                considerPermalink(daInfo.permalinks[dp], false);
            }
            var imgInfo = extractImageFromNode(el);
            if (imgInfo && !seenUrls[imgInfo.url]) {
                seenUrls[imgInfo.url] = true;
                pointImages.push(imgInfo);
            }
        }

        if (selectedScope && !hasVideoEl) {
            var scopeVidEls = selectedScope.querySelectorAll('video, [data-video-id], [data-sigil*="video" i], [data-testid*="video" i]');
            for (var sv = 0; sv < scopeVidEls.length; sv++) {
                var svr = scopeVidEls[sv].getBoundingClientRect ? scopeVidEls[sv].getBoundingClientRect() : null;
                if (!hasPoint || !svr || (cx >= svr.left - 40 && cx <= svr.right + 40 && cy >= svr.top - 40 && cy <= svr.bottom + 80)) {
                    hasVideoEl = true;
                    break;
                }
            }
        }

        if (selectedScope) {
            var scopePerms = extractPermalinksIn(selectedScope, directAnchor);
            for (var sp = 0; sp < scopePerms.length; sp++) {
                considerPermalink(scopePerms[sp].url, false, scopePerms[sp].score);
            }
            if (!bestTitle && selectedScope.getAttribute) {
                var sAria = (selectedScope.getAttribute('aria-label') || '').trim();
                if (sAria && !isGenericTitle(sAria) && sAria.length < 180) bestTitle = sAria;
            }
        }
        if (bestPostScore < 60 && primaryVideo) {
            var pvScope = findSelectedScope(primaryVideo);
            if (pvScope && pvScope !== selectedScope) {
                var pvPerms = extractPermalinksIn(pvScope, directAnchor);
                for (var pvp = 0; pvp < pvPerms.length; pvp++) {
                    considerPermalink(pvPerms[pvp].url, false, pvPerms[pvp].score);
                }
            }
        }

        pointImages.sort(function(a, b) {
            var aNonAvatar = a.isAvatar ? 0 : 1;
            var bNonAvatar = b.isAvatar ? 0 : 1;
            if (aNonAvatar !== bNonAvatar) return bNonAvatar - aNonAvatar;
            var aw = (a.w || 0) >= 80 && (a.h || 0) >= 80 ? 1 : 0;
            var bw = (b.w || 0) >= 80 && (b.h || 0) >= 80 ? 1 : 0;
            if (aw !== bw) return bw - aw;
            return (b.area || 0) - (a.area || 0);
        });

        // STRICT POST-ONLY MEDIA SCOPING: never scan the entire document feed when a single card/dialog is active
        var mediaRoot = selectedScope || document;
        var postImgs = [];
        var seenPostImg = {};
        var pageNodes = mediaRoot.querySelectorAll('img, [style*="background-image"], [data-sigil*="photo"], i.img');
        var maxScan = selectedScope ? pageNodes.length : Math.min(pageNodes.length, 20);
        for (var pi = 0; pi < maxScan; pi++) {
            var pImg = extractImageFromNode(pageNodes[pi]);
            if (!pImg || pImg.isAvatar || seenPostImg[pImg.url]) continue;
            var r = pImg.rect;
            if (r && r.width > 0 && (r.width < 80 || r.height < 80)) continue;
            if (Math.max(pImg.w, r.width || 0) < 90 || Math.max(pImg.h, r.height || 0) < 90) continue;
            seenPostImg[pImg.url] = true;
            postImgs.push({
                url: pImg.url,
                w: pImg.w,
                h: pImg.h,
                title: pImg.title || bestTitle || '',
                postUrl: bestPostUrl || '',
                src: 'dom'
            });
        }
        postImgs.sort(function(a, b) { return (b.w * b.h) - (a.w * a.h); });
        if (postImgs.length > 12) postImgs = postImgs.slice(0, 12);

        var postVids = [];
        var seenPostVid = {};
        var pageThumb = '';
        var vidEls = mediaRoot.querySelectorAll('video, source, iframe, [data-store], [data-video-url]');
        var maxVidScan = selectedScope ? vidEls.length : Math.min(vidEls.length, 10);
        for (var vi = 0; vi < maxVidScan; vi++) {
            var vel = vidEls[vi];
            var vtag = vel.tagName.toLowerCase();
            if (vtag === 'video' || vtag === 'source') {
                if (selectedScope) hasVideoEl = true;
                var vs = absUrl(vel.currentSrc || vel.src);
                if (vs && vs.indexOf('http') === 0 && !seenPostVid[vs]) {
                    seenPostVid[vs] = true;
                    postVids.push(vs);
                }
                var vNode = vtag === 'video' ? vel : (vel.closest ? vel.closest('video') : null);
                if (vNode && vNode.poster && !pageThumb) {
                    var vpUrl = absUrl(vNode.poster);
                    if (isGoodHttpOrData(vpUrl) && !isUiNoise(vpUrl) && !isProfileAvatar(vpUrl, '', null)) {
                        pageThumb = vpUrl;
                    }
                }
            } else if (vtag === 'iframe' && isVideoPlayerIframe(vel.src)) {
                if (selectedScope) hasVideoEl = true;
                var ifs = absUrl(vel.src);
                if (ifs && !seenPostVid[ifs]) { seenPostVid[ifs] = true; postVids.push(ifs); }
            }
            var dsv = extractDataAttrUrls(vel).videos;
            for (var dvi = 0; dvi < dsv.length; dvi++) {
                if (selectedScope) hasVideoEl = true;
                if (!seenPostVid[dsv[dvi]]) {
                    seenPostVid[dsv[dvi]] = true;
                    postVids.push(dsv[dvi]);
                }
            }
        }

        var ogTitle = document.querySelector('meta[property="og:title"]');
        var pageTitle = (ogTitle && ogTitle.content) || document.title || '';
        if (!pageThumb) {
            var ogImg = document.querySelector('meta[property="og:image:secure_url"], meta[property="og:image"]');
            if (ogImg && ogImg.content && ogImg.content.indexOf('http') === 0 && !isUiNoise(ogImg.content) && !isProfileAvatar(ogImg.content, '', null)) {
                pageThumb = ogImg.content;
            }
        }

        var nonAvatarPointImg = null;
        for (var ci = 0; ci < pointImages.length; ci++) {
            if (!pointImages[ci].isAvatar) { nonAvatarPointImg = pointImages[ci]; break; }
        }
        var chosen = nonAvatarPointImg || (postImgs.length > 0 ? postImgs[0] : ((!hasVideoEl && hasPoint && pointImages.length > 0) ? pointImages[0] : null));
        var chosenTitleRaw = (chosen && chosen.title) ? chosen.title : bestTitle;
        var safeChosenTitle = (chosenTitleRaw && !isGenericTitle(chosenTitleRaw)) ? chosenTitleRaw : '';
        return JSON.stringify({
            imageUrl: chosen ? chosen.url : '',
            imageW: chosen ? (chosen.w || 0) : 0,
            imageH: chosen ? (chosen.h || 0) : 0,
            imageTitle: safeChosenTitle,
            videos: pointVideos,
            hasVideoEl: hasVideoEl,
            postUrl: bestPostUrl,
            pageTitle: pageTitle,
            pageThumb: pageThumb || (chosen ? chosen.url : ''),
            postImages: postImgs,
            postVideos: postVids
        });
    } catch (e) {
        return JSON.stringify({
            imageUrl: '', imageW: 0, imageH: 0, imageTitle: '',
            videos: [], hasVideoEl: false, postUrl: '', pageTitle: '', pageThumb: '',
            postImages: [], postVideos: []
        });
    }
})();
