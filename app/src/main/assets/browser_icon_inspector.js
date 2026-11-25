(function() {
    try {
        var icons = [];
        var links = document.querySelectorAll('link[rel*="icon"], link[rel*="apple-touch-icon"]');
        for (var i = 0; i < links.length; i++) {
            var href = links[i].getAttribute('href');
            if (href) {
                try {
                    icons.push({
                        url: new URL(href, location.href).href,
                        rel: (links[i].getAttribute('rel') || '').toLowerCase()
                    });
                } catch (e) {}
            }
        }
        icons.sort(function(a, b) {
            var ap = a.rel.indexOf('apple-touch-icon') >= 0 ? 0 : 1;
            var bp = b.rel.indexOf('apple-touch-icon') >= 0 ? 0 : 1;
            return ap - bp;
        });
        var urls = icons.map(function(x) { return x.url; });
        var og = document.querySelector('meta[property="og:image"]');
        if (og && og.getAttribute('content')) {
            try { urls.push(new URL(og.getAttribute('content'), location.href).href); } catch (e) {}
        }
        return JSON.stringify({
            icons: urls,
            hasVideo: document.querySelectorAll('video').length > 0
        });
    } catch (e) {
        return JSON.stringify({ icons: [], hasVideo: false });
    }
})();
