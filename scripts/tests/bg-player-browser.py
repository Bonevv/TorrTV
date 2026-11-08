"""Offline Chromium regression checks for the exact player script shipped in the APK."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / 'app/src/main/assets/bg-player.js').read_text()

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(executable_path='/usr/bin/chromium', headless=True, args=['--no-sandbox'])
    context = browser.new_context(viewport={'width': 1280, 'height': 720})
    context.route('**/*', lambda route: route.fulfill(status=200, body='<html><body></body></html>', content_type='text/html'))

    def page_for(html):
        page = context.new_page()
        events = []
        errors = []
        page.on('console', lambda message: events.append(json.loads(message.text[len('TORRTV_PLAYER:'):])) if message.text.startswith('TORRTV_PLAYER:') else None)
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.set_content(html)
        page.evaluate('window.__torrtvPlayerOnly = true')
        page.add_script_tag(content=SCRIPT)
        return page, events, errors

    page, events, errors = page_for('<nav>Movie catalogue</nav><article><div class="pmovie__player"><iframe src="https://player.example.com/embed/movie"></iframe></div></article>')
    page.wait_for_timeout(100)
    assert events == [{'url': 'https://player.example.com/embed/movie', 'page': 'about:blank'}], events
    assert not errors, errors
    page.close()

    page, events, errors = page_for('<button class="tab-btn" data-player="voe" id="delayed">BG player</button><div id="video-stage"></div>')
    page.evaluate("setTimeout(function(){document.getElementById('delayed').onclick=function(){document.getElementById('video-stage').innerHTML='<iframe src=https://voe.sx/e/delayed></iframe>'}},100)")
    page.wait_for_timeout(1800)
    assert events and events[0]['url'] == 'https://voe.sx/e/delayed', events
    assert not errors, errors
    page.close()

    page, events, errors = page_for('''<header>Site navigation</header><span class="tab-btn" data-player="trailer">Trailer</span><button class="tab-btn" data-player="voe" onclick="setTimeout(function(){document.getElementById('video-stage').innerHTML='<iframe src=https://voe.sx/e/example></iframe>'},50)">BG player</button><div id="video-stage"></div>''')
    page.wait_for_timeout(200)
    assert events and events[0]['url'] == 'https://voe.sx/e/example', events
    assert not any(event.get('ready') for event in events), events
    assert not errors, errors
    page.close()

    page, events, errors = page_for('<nav id="navigation">Site menu</nav><main><aside>Related films</aside><div class="jwplayer" id="original-player-id"><video src="https://cdn.example.com/movie.mp4" controls></video></div></main><footer>Other content</footer>')
    page.wait_for_timeout(100)
    assert events == [{'ready': True}], events
    assert not page.locator('#navigation').is_visible()
    assert not page.locator('aside').is_visible()
    assert not page.locator('footer').is_visible()
    assert page.locator('#original-player-id').is_visible()
    assert page.locator('#original-player-id').bounding_box() == {'x': 0, 'y': 0, 'width': 1280, 'height': 720}
    page.evaluate("document.body.insertAdjacentHTML('beforeend','<div id=late-banner>Late site banner</div>')")
    assert not page.locator('#late-banner').is_visible()
    assert not errors, errors
    page.close()

    page, events, errors = page_for('<nav>Catalogue</nav><article><iframe src="https://www.youtube.com/embed/trailer"></iframe></article><video src="https://cdn.example.com/trailer.mp4"></video>')
    page.wait_for_timeout(100)
    assert not events, events  # Native loading cover must remain; never reveal this document.
    assert not errors, errors
    page.close()

    page, events, errors = page_for('<html data-torrtv-player-shell><body><iframe src="https://player.example.com/embed/movie"></iframe></body></html>')
    page.wait_for_timeout(50)
    assert events == [{'ready': True}], events
    assert not errors, errors
    page.close()

    browser.close()
    print('6 player browser checks passed: frame extraction, dynamic and delayed activation, isolated video, no catalogue fallback, minimal shell')
