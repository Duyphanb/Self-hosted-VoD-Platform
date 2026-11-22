"""Explicit cookie transport for local HTTP smokes; never print returned values."""
from http.cookies import SimpleCookie
from urllib.parse import urlsplit


class AuthPayload(dict):
    """Wire JSON plus an in-memory cookie, deliberately absent from JSON keys."""
    refresh_cookie = None


def auth_headers(base_url, path, headers, cookie=None):
    if path.startswith('/api/v1/auth/'):
        url = urlsplit(base_url)
        headers.setdefault('Origin', f'{url.scheme}://{url.netloc}')
        if cookie is not None:
            headers['Cookie'] = 'vod_refresh=' + cookie
    return headers


def capture_auth_cookie(response, payload, path):
    """Validate the wire contract before attaching a cookie to an internal result."""
    if path not in ('/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout'):
        return payload
    assert not isinstance(payload, dict) or 'refreshToken' not in payload, 'Refresh token leaked into JSON'
    issuing = response.status == 200
    clearing = (
        (path.endswith('/refresh') and response.status == 401)
        or (path.endswith('/logout') and response.status == 204)
    )
    if not issuing and not clearing:
        return payload
    lines = response.headers.get_all('Set-Cookie') or []
    parsed = SimpleCookie()
    for line in lines:
        parsed.load(line)
    assert 'vod_refresh' in parsed, 'Missing refresh cookie'
    cookie = parsed['vod_refresh']
    assert (
        cookie['httponly'] and cookie['secure'] and cookie['samesite'].lower() == 'strict'
    ), 'Cookie security attributes differ'
    assert cookie['path'] == '/api/v1/auth' and not cookie['domain'], 'Cookie scope differs'
    age = int(cookie['max-age'])
    if clearing:
        assert age == 0 and cookie.value == '', 'Cookie was not cleared'
    else:
        assert 0 < age <= 604800 and 0 < len(cookie.value) <= 128, 'Cookie lifetime differs'
    if isinstance(payload, dict):
        result = AuthPayload(payload)
        result.refresh_cookie = cookie.value if issuing else None
        return result
    return payload
