(function () {
    if (!window.__torrtvPlayerOnly || window !== window.top || window.__torrtvPlayerProbe) return;
    window.__torrtvPlayerProbe = true;
    var sent = false, attempts = 0, episodeReady = !location.hash.match(/tvsites-episode=(\d+)/);
    var episodeSelectionStarted = false, previousEpisodeMedia = [];
    function emit(value) { console.info('TORRTV_PLAYER:' + JSON.stringify(value)); }
    function click(node, pointer) {
        if (Date.now() - (node.__torrtvClickAt || 0) < 1500) return;
        node.__torrtvClickAt = Date.now();
        if (pointer) node.dispatchEvent(new Event('pointerup', {bubbles: true}));
        node.click();
    }
    function validFrame(frame) {
        var url = frame.src || '';
        return /^https?:/i.test(url) && !/youtube|doubleclick|googlesyndication|trailer|\/ads\//i.test(url);
    }
    function activate() {
        var wanted = location.hash.match(/tvsites-episode=(\d+)/);
        if (wanted && !episodeReady) {
            var episodes = document.querySelectorAll('button,[data-tv_link]');
            for (var i = 0; i < episodes.length; i++) {
                var e = episodes[i];
                if (e.textContent.trim() === 'Епизод ' + wanted[1] || e.getAttribute('data-tv_link') === wanted[1]) {
                    episodeReady = e.getAttribute('aria-selected') === 'true' || e.getAttribute('data-state') === 'active' || /(?:^|\s)(?:active|is-active|bg-primary|bg-red-600)(?:\s|$)/.test(e.className);
                    if (!episodeReady) {
                        if (!episodeSelectionStarted) {
                            episodeSelectionStarted = true;
                            var oldMedia = document.querySelectorAll('.movieplay iframe,#video-stage iframe,.video-responsive iframe,#player-box iframe,video');
                            for (var old = 0; old < oldMedia.length; old++) previousEpisodeMedia.push(oldMedia[old].currentSrc || oldMedia[old].src || '');
                        }
                        click(e, false);
                    }
                    break;
                }
            }
            if (!episodeReady) return;
        }
        var tab = document.querySelector('.tab-btn[data-player]:not([data-player="trailer"]):not([data-player="help"]),.tabs-block__select [data-link],.player-picker-menu [data-player],button[data-server],.server-btn');
        var existing = document.querySelectorAll('.movieplay iframe,#video-stage iframe,.video-responsive iframe,#player-box iframe');
        var activePlayer = false;
        for (var f = 0; f < existing.length; f++) if (validFrame(existing[f])) activePlayer = true;
        var activeVideo = document.querySelector('video');
        if (activeVideo && (activeVideo.currentSrc || activeVideo.src) && !/trailer|preroll/i.test(activeVideo.currentSrc || activeVideo.src)) activePlayer = true;
        if (tab && !activePlayer) {
            click(tab, true);
        }
        var play = document.querySelector('.container_play .play,.icon_play');
        if (play) click(play, false);
        var buttons = document.querySelectorAll('button');
        for (var b = 0; b < buttons.length; b++) if (/^Гледай( сега| онлайн)?$/i.test(buttons[b].textContent.trim())) {
            click(buttons[b], false); break;
        }
    }
    function isolate(node) {
        var p = node;
        while (p && p !== document.body) {
            var parent = p.parentElement;
            if (!parent) return false;
            for (var i = 0; i < parent.children.length; i++) {
                var child = parent.children[i];
                if (child !== p && child.tagName !== 'SCRIPT' && child.tagName !== 'STYLE') child.style.setProperty('display', 'none', 'important');
            }
            parent.style.setProperty('margin', '0', 'important'); parent.style.setProperty('padding', '0', 'important');
            p = parent;
        }
        var style = document.getElementById('torrtv-player-isolation');
        if (!style) {
            style = document.createElement('style'); style.id = 'torrtv-player-isolation';
            style.textContent = 'html,body{margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important}body *{visibility:hidden!important}[data-torrtv-isolated-player],[data-torrtv-isolated-player] *{visibility:visible!important}[data-torrtv-isolated-player]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;z-index:2147483647!important;background:#000!important}[data-torrtv-isolated-player] iframe,[data-torrtv-isolated-player] video{width:100%!important;height:100%!important}';
            (document.head || document.documentElement).appendChild(style);
        }
        node.setAttribute('data-torrtv-isolated-player', '');
        var controls = node.querySelector('.pmovie__player-controls,.zenoplay-player-strip,.episode-player-picker-wrap');
        var controlsHeight = controls ? Math.max(controls.getBoundingClientRect().height, 40) : 0;
        var stages = node.querySelectorAll('.video-responsive,.tabs-block__content,.video-stage,.player-frame,iframe,video');
        for (var s = 0; s < stages.length; s++) {
            stages[s].style.setProperty('height', 'calc(100vh - ' + controlsHeight + 'px)', 'important');
            stages[s].style.setProperty('max-height', 'none', 'important');
            stages[s].style.setProperty('padding-top', '0', 'important');
        }
        return true;
    }
    function scan() {
        if (sent) return;
        if (document.documentElement.hasAttribute('data-torrtv-player-shell')) { sent = true; emit({ready: true}); return; }
        activate();
        if (!episodeReady) return;
        var frames = document.querySelectorAll('.pmovie__player iframe,.movieplay iframe,#video-stage iframe,.video-responsive iframe,#player-box iframe,#player iframe,article iframe,.entry-content iframe');
        for (var i = 0; i < frames.length; i++) if (validFrame(frames[i])) {
            if (episodeSelectionStarted && previousEpisodeMedia.indexOf(frames[i].src) >= 0) continue;
            if (window.__torrtvOriginalPlayer) {
                var panel = frames[i].closest('.pmovie__player,.movieplay,[data-player-panel],#video-stage,.video-responsive,#player-box,#player');
                if (isolate(panel || frames[i])) { sent = true; emit({ready: true}); return; }
            } else { sent = true; emit({url: frames[i].src, page: location.href}); return; }
        }
        var video = document.querySelector('.jwplayer,.video-js,.plyr,video');
        var nativeVideo = video && (video.tagName === 'VIDEO' ? video : video.querySelector('video'));
        var mediaUrl = nativeVideo && (nativeVideo.currentSrc || nativeVideo.src || (nativeVideo.querySelector('source') || {}).src);
        if (episodeSelectionStarted && previousEpisodeMedia.indexOf(mediaUrl) >= 0) return;
        if (mediaUrl && !/trailer|preroll|doubleclick|\/ads\//i.test(mediaUrl) && !nativeVideo.closest('.trailer,[data-player="trailer"]') && isolate(video)) { sent = true; emit({ready: true}); }
    }
    window.__torrtvFindPlayer = scan;
    new MutationObserver(function () { try { scan(); } catch (e) {} }).observe(document, {childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'aria-selected', 'data-state']});
    function tick() { try { scan(); } catch (e) {} if (++attempts < 40 && !sent) setTimeout(tick, 500); }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', tick); else tick();
})();
