#!/usr/bin/env python3
"""Catalog regression checks against a fresh, disposable local Compose project.

Caller creates/destroys the project; never point this at a database that holds real
data. It can run after auth-runtime-smoke.py in the same project, so it requires an
empty catalog rather than an empty users table. The admin account is promoted with
the first-administrator SQL documented in docs/deployment/README.md. Uses real HTTP
through Nginx, PostgreSQL constraints and row locks, and concurrent requests, not
mocks. Output names the checks only, never tokens or response bodies.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

# The developer's own local stack (name: in deploy/docker-compose.yml) is never a smoke target.
LOCAL_STACK_PROJECT = "vod-platform"
DEPLOYMENT_README = Path(__file__).resolve().parents[2] / "docs" / "deployment" / "README.md"
MOVIES = "/api/v1/movies"
SUMMARY_KEYS = {"id", "title", "slug", "description", "releaseYear", "posterUrl", "genres", "averageRating",
                "status", "videoStatus"}
DETAIL_KEYS = SUMMARY_KEYS | {"maturityRating", "people", "userRating", "inWatchlist"}
CONTROL = "must not contain control characters"
SLUG_PATTERN = 'must match "^[a-z0-9]+(-[a-z0-9]+)*$"'
AMBIGUOUS_NAME = "is required because more than one person has this name"


def documented_first_admin_procedure():
    """The psql command and SQL of the README's First Administrator section, as written there."""
    text = DEPLOYMENT_README.read_text(encoding="utf-8")
    section = text.split("\n## First Administrator\n", 1)[1].split("\n## ", 1)[0]
    match = re.search(r"exec -T postgres \\\n\s*sh -c '([^']*)' sh \"\$ADMIN_EMAIL\" <<'SQL'\n(.*?\n)SQL\n", section, re.S)
    assert match, "First-administrator procedure not found in docs/deployment/README.md"
    return match.group(1), match.group(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--base-url", required=True)
    args = parser.parse_args()
    assert args.project != LOCAL_STACK_PROJECT, "Use a dedicated, disposable Compose project"
    assert urllib.parse.urlparse(args.base_url).hostname in ("localhost", "127.0.0.1")
    container = args.project + "-postgres-1"
    label = subprocess.check_output([
        "docker", "inspect", "--format",
        '{{index .Config.Labels "com.docker.compose.project"}}', container
    ], text=True).strip()
    assert label == args.project

    def psql(command, statements, *arguments):
        result = subprocess.run(["docker", "exec", "-i", container, "sh", "-c", command, "sh", *arguments],
                                input=statements, text=True, encoding="utf-8", capture_output=True)
        assert result.returncode == 0, "SQL failed (details withheld)"
        return result.stdout

    def sql(statement):
        return psql('psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At', statement).strip()

    assert sql("SELECT (SELECT count(*) FROM movies) + (SELECT count(*) FROM genres) + (SELECT count(*) FROM people);") == "0", \
        "Catalog must be fresh"

    def request(path, body=None, token=None, method=None, raw=None):
        data = raw if raw is not None else None if body is None else json.dumps(body).encode()
        headers = {} if data is None else {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(args.base_url + path, data=data, headers=headers,
                                     method=method or ("POST" if data is not None else "GET"))
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read()
            parsed = json.loads(content) if content and response.headers.get_content_type() == "application/json" else None
            return response.status, parsed, content

    def call(path, body=None, token=None, method=None, raw=None):
        status, parsed, _ = request(path, body, token, method, raw)
        return status, parsed

    def concurrent(callables):
        barrier = threading.Barrier(len(callables))
        def run(fn):
            barrier.wait(timeout=10)
            return fn()
        with ThreadPoolExecutor(max_workers=len(callables)) as pool:
            return list(pool.map(run, callables))

    def without_timestamp(error):
        return {key: value for key, value in error.items() if key != "timestamp"}

    def movie(slug, status="PUBLISHED", **fields):
        body = {"title": "Smoke " + slug, "slug": slug, "description": "Catalog runtime smoke fixture.",
                "releaseYear": 2024, "maturityRating": "T13", "genres": ["Smoke Drama"], "people": [],
                "status": status}
        body.update(fields)
        return body

    def create(body, token):
        status, created = call(MOVIES, body, token)
        assert status == 201 and set(created) == DETAIL_KEYS, "Create did not return a MovieDetail"
        return created

    def is_api_error(response, status, code, field_errors=None):
        actual_status, error = response
        return (actual_status == status and error is not None and error["status"] == status
                and error["code"] == code and error.get("fieldErrors") == field_errors)

    # ---- accounts: a viewer and an administrator candidate, both ROLE_USER ----
    run = uuid.uuid4().hex[:10]
    password = "Catalog-fixture-only!0088"
    viewer_email = f"catalog.viewer.{run}@example.test"
    admin_email = f"Catalog.Admin.{run}@Example.test"
    for address in (viewer_email, admin_email):
        status, profile = call("/api/v1/auth/register", {"email": address, "password": password,
                                                        "displayName": "Catalog Smoke"})
        assert status == 201 and profile["roles"] == ["ROLE_USER"]

    def login(address):
        status, session = call("/api/v1/auth/login", {"email": address, "password": password})
        assert status == 200
        return session

    viewer = login(viewer_email)["accessToken"]
    candidate_session = login(admin_email)
    candidate = candidate_session["accessToken"]

    missing_id = str(uuid.uuid4())
    for token in (viewer, candidate):
        for method, path, body, raw in [
            ("POST", MOVIES, movie("smoke-forbidden"), None),
            ("POST", MOVIES, movie("smoke-forbidden", title="Nul\x00title"), None),
            ("POST", MOVIES, None, b"{"),
            ("PUT", f"{MOVIES}/{missing_id}", movie("smoke-forbidden"), None),
            ("PUT", f"{MOVIES}/not-a-uuid", {}, None),
            ("DELETE", f"{MOVIES}/{missing_id}", None, None),
            ("DELETE", f"{MOVIES}/not-a-uuid", None, None),
        ]:
            assert is_api_error(call(path, body, token, method, raw), 403, "FORBIDDEN"), f"{method} must be 403"
    assert is_api_error(call(MOVIES, movie("smoke-anonymous")), 401, "UNAUTHORIZED")
    assert sql("SELECT count(*) FROM movies;") == "0"
    print("PASS ROLE_USER gets 403 on admin movie writes, even with NUL, malformed or missing targets")

    # ---- promote the candidate with the documented first-administrator SQL ----
    command, statements = documented_first_admin_procedure()

    def promote(address):
        lines = [line.strip() for line in psql(command, statements, address).splitlines()]
        return lines[0], lines[lines.index("admin_role_granted") + 2]

    # The documented match is lower(email), so the address may differ in case from the registration.
    assert promote(admin_email.swapcase()) == ("INSERT 0 1", "1")
    assert promote(admin_email.lower()) == ("INSERT 0 0", "1"), "The documented SQL must be idempotent"
    assert sql("SELECT count(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id JOIN roles r ON r.id = ur.role_id "
               f"WHERE r.name = 'ROLE_ADMIN' AND lower(u.email) = '{viewer_email}';") == "0"
    # Roles are carried in the access token: the existing token stays ROLE_USER until a refresh.
    assert is_api_error(call(MOVIES, movie("smoke-forbidden"), candidate), 403, "FORBIDDEN")
    status, refreshed = call("/api/v1/auth/refresh", {"refreshToken": candidate_session["refreshToken"]})
    assert status == 200
    admin = refreshed["accessToken"]
    assert "ROLE_ADMIN" in call("/api/v1/users/me", token=admin)[1]["roles"]
    print("PASS documented first-admin SQL grants ROLE_ADMIN idempotently; the role applies after refresh")

    # ---- create ----
    published = create(movie("smoke-published", genres=["Hành   Động", "Hài"], people=[
        {"name": "Victor Vũ", "role": "DIRECTOR"}, {"name": "Kaity Nguyễn", "role": "ACTOR"}]), admin)
    assert published["genres"] == ["Hài", "Hành Động"] and published["status"] == "PUBLISHED"
    assert [(person["name"], person["role"]) for person in published["people"]] == [
        ("Victor Vũ", "DIRECTOR"), ("Kaity Nguyễn", "ACTOR")]
    published_id = str(uuid.UUID(published["id"]))
    draft = create(movie("smoke-draft", status="DRAFT"), admin)
    draft_id = str(uuid.UUID(draft["id"]))
    assert sql(f"SELECT status FROM movies WHERE id = '{published_id}';") == "PUBLISHED"
    print("PASS admin creates movies with genres and credits (201 MovieDetail)")

    # ---- NUL and other control characters ----
    for field, body, message in [
        ("title", movie("smoke-nul", title="Nul\x00title"), CONTROL),
        ("title", movie("smoke-nul", title="Trailing nul\x00"), CONTROL),
        ("title", movie("smoke-nul", title="Bell\x07"), CONTROL),
        ("description", movie("smoke-nul", description="Line one\n\x00"), CONTROL),
        ("genres[0]", movie("smoke-nul", genres=["Drama\x00"]), CONTROL),
        ("people[0].name", movie("smoke-nul", people=[{"name": "Nul\x00Name", "role": "ACTOR"}]), CONTROL),
        ("slug", movie("smoke\x00nul", title="Smoke slug with NUL"), SLUG_PATTERN),
    ]:
        for method, path in (("POST", MOVIES), ("PUT", f"{MOVIES}/{published_id}")):
            assert is_api_error(call(path, body, admin, method), 400, "VALIDATION_ERROR",
                                [{"field": field, "message": message}]), f"{method} with NUL in {field} must be 400"
    for raw in (b"{", json.dumps(movie("smoke-nul", releaseYear=2019.5)).encode()):
        assert is_api_error(call(MOVIES, token=admin, raw=raw), 400, "MALFORMED_REQUEST")
    # An enum ordinal is never read as a constant: status 1 or "1" would otherwise publish the movie.
    for body in (movie("smoke-nul", status=1), movie("smoke-nul", status="1"), movie("smoke-nul", maturityRating=0),
                 movie("smoke-nul", people=[{"name": "Smoke Ordinal", "role": "1"}])):
        for method, path in (("POST", MOVIES), ("PUT", f"{MOVIES}/{published_id}")):
            assert is_api_error(call(path, body, admin, method), 400, "MALFORMED_REQUEST"), \
                f"{method} with an enum ordinal must be 400 MALFORMED_REQUEST"
    assert sql("SELECT count(*) FROM movies;") == "2"
    assert sql("SELECT count(*) FROM genres;") == "3" and sql("SELECT count(*) FROM people;") == "2"
    assert call(f"{MOVIES}/{published_id}", token=admin)[1] == published, "A rejected PUT changed the movie"
    print("PASS NUL and control characters return 400 VALIDATION_ERROR and store nothing")

    # ---- visibility, archive and restore ----
    status, not_found = call(f"{MOVIES}/{missing_id}", token=viewer)
    assert is_api_error((status, not_found), 404, "MOVIE_NOT_FOUND")

    def hidden_like_missing(movie_id, token):
        status, error = call(f"{MOVIES}/{movie_id}", token=token)
        return status == 404 and without_timestamp(error) == without_timestamp(not_found)

    def list_ids(path, token):
        status, page = call(path, token=token)
        assert status == 200
        return [item["id"] for item in page["items"]], {item["status"] for item in page["items"]}

    assert call(f"{MOVIES}/{published_id}", token=viewer) == (200, published)
    assert hidden_like_missing(draft_id, viewer)
    assert call(f"{MOVIES}/{draft_id}", token=admin) == (200, draft)
    for token in (viewer, admin):
        assert list_ids(f"{MOVIES}?size=100", token) == ([published_id], {"PUBLISHED"})
    assert set(list_ids("/api/v1/admin/movies?size=100", admin)[0]) == {published_id, draft_id}

    for _ in range(2):
        status, _, content = request(f"{MOVIES}/{published_id}", token=admin, method="DELETE")
        assert status == 204 and content == b"", "Archive must return 204 without a body, also when repeated"
    assert sql(f"SELECT status FROM movies WHERE id = '{published_id}';") == "ARCHIVED"
    assert sql(f"SELECT (SELECT count(*) FROM movie_genres WHERE movie_id = '{published_id}') || ',' || "
               f"(SELECT count(*) FROM movie_credits WHERE movie_id = '{published_id}');") == "2,2"
    assert hidden_like_missing(published_id, viewer)
    assert call(f"{MOVIES}/{published_id}", token=admin) == (200, dict(published, status="ARCHIVED"))
    for token in (viewer, admin):
        assert list_ids(f"{MOVIES}?size=100", token) == ([], set())
    assert list_ids("/api/v1/admin/movies?status=ARCHIVED", admin)[0] == [published_id]

    status, restored = call(f"{MOVIES}/{published_id}", movie("smoke-published", genres=["Hài"]), admin, "PUT")
    assert status == 200 and restored["status"] == "PUBLISHED" and restored["genres"] == ["Hài"]
    assert call(f"{MOVIES}/{published_id}", token=viewer) == (200, restored)
    status, archived_by_put = call(f"{MOVIES}/{draft_id}", movie("smoke-draft", status="ARCHIVED"), admin, "PUT")
    assert status == 200 and archived_by_put["status"] == "ARCHIVED" and hidden_like_missing(draft_id, viewer)
    assert is_api_error(call(f"{MOVIES}/{missing_id}", movie("smoke-missing"), admin, "PUT"), 404, "MOVIE_NOT_FOUND")
    assert is_api_error(call(f"{MOVIES}/{missing_id}", token=admin, method="DELETE"), 404, "MOVIE_NOT_FOUND")
    print("PASS admin versus user visibility, idempotent soft archive and restore")

    # ---- slug conflicts, including writes racing on ux_movies_slug ----
    assert is_api_error(call(MOVIES, movie("smoke-published"), admin), 409, "MOVIE_SLUG_CONFLICT")
    assert is_api_error(call(f"{MOVIES}/{draft_id}", movie("smoke-published"), admin, "PUT"), 409, "MOVIE_SLUG_CONFLICT")
    for round_number in range(3):
        slug = f"smoke-race-{round_number}"
        # Each request also brings the same new genre and person, so the loser must fail on the slug.
        body = movie(slug, genres=[f"Race Genre {round_number}"],
                     people=[{"name": f"Race Person {round_number}", "role": "ACTOR"}])
        statuses = sorted(status for status, _ in concurrent([lambda: call(MOVIES, body, admin)] * 4))
        assert statuses == [201, 409, 409, 409], f"Concurrent creates returned {statuses}"
        assert sql(f"SELECT count(*) FROM movies WHERE slug = '{slug}';") == "1"
        left, right = (create(movie(f"smoke-race-{round_number}-{side}"), admin)["id"] for side in ("left", "right"))
        target = movie(f"smoke-race-{round_number}-target")
        statuses = sorted(status for status, _ in concurrent(
            [lambda movie_id=movie_id: call(f"{MOVIES}/{movie_id}", target, admin, "PUT") for movie_id in (left, right)]))
        assert statuses == [200, 409], f"Concurrent updates to one new slug returned {statuses}"
    print("PASS slug conflicts return 409 MOVIE_SLUG_CONFLICT, including concurrent creates and updates")

    # ---- concurrent PUTs never return 500 ----
    def assert_no_server_error(responses, allowed):
        statuses = [status for status, _ in responses]
        assert all(status in allowed for status in statuses), f"Unexpected statuses {sorted(statuses)}"

    def is_ambiguous_name(response):
        # ADR-009: two requests that create a person with the same new name at the same time each
        # create one, so a request that looks the name up after both committed must name a personId.
        status, error = response
        return status == 400 and error["code"] == "VALIDATION_ERROR" and all(
            field_error["message"] == AMBIGUOUS_NAME for field_error in error["fieldErrors"])

    # Several PUTs of one movie, each bringing new genres and people in a different order: the movie
    # row lock serializes them, and the last write wins as a whole.
    for round_number in range(2):
        genres = [f"Same Movie Genre {round_number}-{index}" for index in range(4)]
        people = [f"Same Movie Person {round_number}-{index}" for index in range(4)]
        bodies = [movie("smoke-published", title=f"Concurrent title {index}",
                        genres=genres[index:] + genres[:index],
                        people=[{"name": name, "role": "ACTOR"} for name in people[index:] + people[:index]])
                  for index in range(8 if round_number else 6)]
        responses = concurrent([lambda body=body: call(f"{MOVIES}/{published_id}", body, admin, "PUT") for body in bodies])
        assert_no_server_error(responses, {200})
        final = call(f"{MOVIES}/{published_id}", token=admin)[1]
        assert final in [body for _, body in responses], "The stored movie is not one of the written versions"
        assert sql(f"SELECT count(*) FROM movie_genres WHERE movie_id = '{published_id}';") == str(len(final["genres"]))
        assert sql(f"SELECT count(*) FROM movie_credits WHERE movie_id = '{published_id}';") == str(len(final["people"]))

    # PUTs of different movies that create the same new genres and people in different orders.
    movie_ids = [create(movie(f"smoke-parallel-{index}"), admin)["id"] for index in range(4)]
    for round_number in range(3):
        genres = [f"Parallel Genre {round_number}-{index}" for index in range(3)]
        people = [f"Parallel Person {round_number}-{index}" for index in range(3)]
        responses = concurrent([
            lambda index=index, movie_id=movie_id: call(f"{MOVIES}/{movie_id}", movie(
                f"smoke-parallel-{index}", genres=genres[index % 3:] + genres[:index % 3],
                people=[{"name": name, "role": "ACTOR"} for name in reversed(people[index % 3:] + people[:index % 3])]),
                admin, "PUT")
            for index, movie_id in enumerate(movie_ids)])
        assert_no_server_error(responses, {200, 400})
        assert all(status == 200 or is_ambiguous_name((status, body)) for status, body in responses)
        assert any(status == 200 for status, _ in responses)
        assert sql("SELECT count(*) FROM genres WHERE slug LIKE "
                   f"'parallel-genre-{round_number}-%';") == "3", "A new genre was created twice"

    # Updates and an archive of one movie racing with admin renames of the genre and person they use.
    # Each rename takes the new name that a movie write is creating at the same time, the case in
    # which a rename and a movie write can deadlock and CatalogWriteRetry runs the victim again.
    person_id = next(person["personId"] for person in published["people"] if person["role"] == "DIRECTOR")
    for round_number in range(4):
        old_genre = f"Rename Source {round_number}"
        new_genre, new_person = f"Rename Genre {round_number}", f"Rename Person {round_number}"
        status, source = call("/api/v1/admin/genres", {"name": old_genre}, admin)
        assert status == 201
        genre_id = source["id"]
        responses = concurrent([
            lambda: call(f"{MOVIES}/{published_id}", movie("smoke-published", genres=[old_genre, new_genre], people=[
                {"personId": person_id, "role": "DIRECTOR"}, {"name": new_person, "role": "ACTOR"}]), admin, "PUT"),
            lambda: call(f"{MOVIES}/{draft_id}", movie("smoke-draft", status="DRAFT", genres=[new_genre, old_genre],
                         people=[{"personId": person_id, "role": "ACTOR"}]), admin, "PUT"),
            lambda: call(f"{MOVIES}/{published_id}", token=admin, method="DELETE"),
            lambda: call(f"/api/v1/admin/genres/{genre_id}", {"name": new_genre}, admin, "PUT"),
            lambda: call(f"/api/v1/admin/people/{person_id}", {"name": new_person}, admin, "PUT"),
        ])
        movie_writes, archive, genre_rename, person_rename = responses[:2], responses[2], responses[3], responses[4]
        assert_no_server_error(movie_writes, {200})
        assert archive[0] == 204 and person_rename[0] == 200, f"Archive or person rename returned {archive[0]}, {person_rename[0]}"
        # The rename loses with 409 when a movie write created the genre name first.
        assert genre_rename[0] == 200 or is_api_error(genre_rename, 409, "GENRE_CONFLICT"), \
            f"Genre rename returned {genre_rename[0]}"
        final_status = call(f"{MOVIES}/{published_id}", token=admin)[1]["status"]
        assert final_status in ("PUBLISHED", "ARCHIVED")
        assert sql(f"SELECT status FROM movies WHERE id = '{published_id}';") == final_status
        assert sql(f"SELECT count(*) FROM genres WHERE slug = 'rename-genre-{round_number}';") == "1"
    print("PASS concurrent PUTs (one movie, shared new genres and people, archive and renames) never return 500")


if __name__ == "__main__":
    main()
