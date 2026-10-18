"""Browser smoke test against `python3 -m http.server 5174 --directory frontend/dist`.
Uses deterministic API responses; full service contracts are checked separately.
"""
import json
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context(viewport={'width': 390, 'height': 844})
    def api(route):
        path = route.request.url.split('5174', 1)[1].split('?', 1)[0]
        data = []
        if path == '/api/v1/session':
            data = {'userId': 'u1', 'csrfHeader': 'X-XSRF-TOKEN', 'csrfToken': 'fixture', 'expiresInSeconds': 3600}
        elif path == '/api/access/me':
            data = {'userId': 'u1', 'username': 'alice', 'role': 'ADMIN', 'destinations': [{'destination': 'SW001', 'permission': 'READ_WRITE'}]}
        elif path == '/api/v1/portal/admin/users':
            data = [{'userId': 'u1', 'username': 'alice', 'role': 'ADMIN', 'active': True, 'destinations': []}]
        elif path == '/api/v1/portal/admin/folders': data = ['SW001']
        elif path == '/api/v1/portal/files': data = {'files': [], 'nextCursor': None}
        elif '/reports' in path: data = {'content': [], 'totalPages': 0, 'totalElements': 0}
        route.fulfill(status=200, content_type='application/json', body=json.dumps(data))
    context.route('**/api/**', api)
    errors = []
    for name in ['login', 'signup', 'reset-password', 'home', 'file-manager', 'admin', 'activity', 'reports', 'invite']:
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto('http://127.0.0.1:5174/pages/' + name + '.html')
        page.wait_for_selector('#root > *')
        page.wait_for_function("document.documentElement.dataset.controllersReady === 'true'")
        assert page.locator('#root').inner_text().strip(), name
        assert not page.get_by_text('This page could not finish loading.').count(), name
        if name == 'login':
            assert page.locator('#login-form').count() == 1
        if name == 'reset-password':
            assert page.locator('#submit').is_disabled()
            assert 'Invalid reset link' in page.locator('#status').inner_text()
        if name == 'admin':
            page.get_by_text('Create folder', exact=True).first.click()
            assert page.locator('#createFolderDialog').is_visible()
        print('PASS React page + controllers:', name)
        page.close()
    assert not errors, errors
    browser.close()
