# Sprint 3 Movie CRUD closeout

Audit date: 2026-10-28. Implementation baseline: `7e9668e` on `main`, after [PR #160](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/160).

Sprint 3 Movie CRUD is closed: all ten DoD items pass on the merged implementation. Each item maps to the backlog issues below. This is verification evidence, not a replacement for frozen contracts. Catalog completion does not establish upload, encoding, playback or public-production readiness.

## Acceptance criteria

| Backlog item | GitHub | Verified behavior and evidence |
|---|---|---|
| 3.1 Entities and repositories | [#84](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/84) / [PR #148](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/148) | Movie, genre, person and credit entities map the frozen V1 schema. Repository tests cover paging and the visibility queries. No entity is serialised: controllers return records, and contract validation (#120) checks every response. |
| 3.2 Movie service | [#85](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/85) / [PR #149](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/149) | Service tests cover validation, the single visibility rule (ADR-009), pessimistic locking, slug conflicts and archive or restore. The PostgreSQL runtime smoke covers concurrent slug conflicts and constraint-to-409 mapping. |
| 3.3 Genres and people | [#86](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/86) / [PR #150](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/150) | Admin create, rename, list and search, slug-based duplicate detection (`409 GENRE_CONFLICT`) and ADR-009 credit matching. Newman requests 06-12 cover them through Nginx. |
| 3.4 List and detail APIs | [#87](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/87) / [PR #152](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/152) | Covered: paged `GET /api/v1/movies` with genre, year and sort; `GET /api/v1/movies/{id}`; canonical-UUID binding; non-published movies returning `404 MOVIE_NOT_FOUND` to non-admins. Newman requests 14, 18, 26-27 and 31 cover visibility; request 22 covers Vietnamese title order (#140, [PR #160](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/160)) and page metadata; requests 23-24 cover `size=1` paging and an empty page beyond the end. |
| 3.5 Admin movie CRUD | [#88](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/88) / [PR #155](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/155) | Create, full-replacement update, archive (`DELETE` 204), restore by status, and `GET /api/v1/admin/movies?status=`. URL authorization (#83, [PR #147](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/147)) returns 403 to `ROLE_USER` and 401 without a token; this is covered by integration tests and by Newman requests 04-05, 08, 12, 16 and 29. |
| 3.6 Movie list page | [#89](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/89) / [PR #157](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/157) | TanStack Query list with pagination, URL-held filters and loading, empty and error states. Cards link to the detail page. The PR records the browser checks on a real stack. |
| 3.7 Movie detail page | [#90](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/90) / [PR #158](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/158) | Full metadata in the issue's order, video availability, and Watch only for READY and PUBLISHED. Admin-only status panel. Not-found for missing, non-published and invalid ids. Browser-checked on a real stack. |
| 3.8 Admin movie UI | [#91](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/91) / [PR #159](https://github.com/Duyphanb/Self-hosted-VoD-Platform/pull/159) | List with a status filter, create and edit with genres and credits, archive and restore, unsaved-change protection under the data router, and `ROLE_ADMIN` guards. Browser-checked on a real stack, including the manual pass below. |

Supporting issues: [#83](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/83) (catalog API foundation), [#113](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/113) (shared error model), [#114](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/114) (frontend ESLint gate), [#120](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/120) (contract validation), [#140](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/140) (Vietnamese collation) and [#153](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/153) (movie response schemas).

## Definition of Done evidence

The ten checklist entries in [BACKLOG.md](../requirements/BACKLOG.md#sprint-3-definition-of-done) map to the table above:

| DoD item | Evidence |
|---|---|
| Backend movie CRUD APIs work with pagination and DTOs | 3.2, 3.4 and 3.5. Newman paging assertions run against PostgreSQL. |
| Genre and people management works for admin | 3.3. Newman requests 06-12. |
| Movie list and detail APIs return correct data with status filtering | 3.4. Newman visibility requests 14, 18, 26-27 and 31. Admin `status=` filter in request 28. |
| Admin movie CRUD endpoints enforce `ROLE_ADMIN` | 3.5. Integration tests, and Newman 403/401 requests. |
| Frontend movie list page displays paginated movies | 3.6. |
| Frontend movie detail page shows full metadata and video status | 3.7. |
| Admin UI for movie CRUD is functional and protected | 3.8, and the manual UI pass below. |
| Unit and integration tests cover movie service and APIs | Backend: 725 tests (auth 68, catalog 321 with 7 opt-in skips, common 255, shared 76, user 5). Frontend: 580 tests. Contract validation of responses (#120). |
| Postman collection includes movie endpoints | [Sprint 3 catalog collection](postman/README.md#sprint-3-catalog-collection): 31 requests and 51 assertions. CI runs it with `--silent` after promoting an admin through the documented SQL. |
| No JPA entities exposed in API responses | Controllers return DTO records only, and the frozen-contract response validator (#120) rejects any extra field. |

### Global Definition of Done

The [Global DoD](../requirements/BACKLOG.md#global-definition-of-done-all-sprints) items hold for the Sprint 3 changes:

| Global DoD item | Status for Sprint 3 |
|---|---|
| AGENTS.md rules (constructor injection, DTOs, no exposed entities) | Met. There is no field `@Autowired` in backend main sources, and controllers return DTO records only. |
| Unit tests cover service logic | Met. Catalog service, validation, visibility and credit-matching unit tests run in the catalog module (321 tests). |
| Integration tests cover API endpoints | Met. MockMvc integration tests cover every catalog endpoint, including authorization (common module, 255 tests). The JVM suite runs on H2, and PostgreSQL behaviour is covered by the runtime smoke and Newman instead ([#119](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/119) tracks moving it to PostgreSQL). |
| SLF4J logging, no `System.out` | Met. There is no `System.out`, `System.err` or `printStackTrace` in backend main sources. |
| Secrets externalised; none committed | Met. Runtime secrets come from environment variables (`.env.example` holds placeholders). CI uses run-only values, and the new Newman admin password is generated per run and never printed. No credential files are tracked. |
| `API-CONTRACT.yaml` updated or confirmed; responses validated in tests | Met. Contract-first per the owner decision in [#120](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/120): controller responses are validated against the frozen `API-CONTRACT.yaml` in tests, and #153 completed the movie response schemas. The frontend decoders are also tested against the contract's required fields. Serving the contract in Swagger UI is Issue 10.2 (Sprint 10). |
| Frontend loading, error, empty and success states | Met. Covered for the list, detail and admin pages by page tests and the browser checks in each PR. |
| Protected and admin routes enforced | Met. `ProtectedRoute` and `AdminRoute` on the frontend; URL authorization on the backend (#83) with 401 and 403 checks in integration tests and Newman. |
| Docs updated with behaviour, config and contracts | Met. ADR-009 (catalog visibility and collation), API-CONTRACT updates (#95, #153), the deployment README (first administrator, collation), and this closeout. |
| Conventional commits; git status reviewed before staging | Met for Sprint 3. Every Sprint 3 pull request (#146-#160) is squash-merged with a conventional title. The only non-conventional subjects on `main` are older merge commits from before the squash-only rule (#81). |

### Manual UI pass

Done on 2026-10-28 on a disposable Compose stack built from this branch (PostgreSQL with the ICU `vi` collation, the backend, Nginx and the built frontend), using a test admin and a test viewer:

1. **Admin creates a movie:** "Đất Rừng Phương Nam UI 92" with genre "Phiêu Lưu". The slug `dat-rung-phuong-nam-ui-92` is suggested, the status defaults to Draft, and the list shows "Movie created" and a Draft row.
2. **Viewer, draft:** the detail page shows "Movie not found", and the genre list shows "0 movies".
3. **Admin publishes:** set Published and saved; the list shows "Changes saved".
4. **Viewer, published:** the detail page shows the title, and the genre list shows "1 movie, page 1 of 1".
5. **Admin archives:** archived through the confirmation dialog; the list shows "Archived". The admin's detail view shows the "archived and not visible to viewers" banner.
6. **Viewer, archived:** the detail page shows "Movie not found" again, and the genre list shows "0 movies".

The same stack ran the Newman collection: 31 requests, 51 assertions, 0 failures.

## Verification

- **Backend:** `mvn -B -ntp verify` in `backend/` passed, with 725 tests, 0 failures or errors and 7 opt-in skips.
- **Frontend:** `npm run lint`, `npx tsc -b`, `npx vitest run` (580 tests in 33 files) and `npm run build` passed.
  - Under heavy parallel host load (a concurrent Maven build), 1-2 tests in `AdminMovieFormPage.test.tsx` timed out. The file passes alone (42/42), and the full suite passes on a re-run. See the follow-ups below.
- **CI on the baseline:** [CI](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/40660902881), [dependency security](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/40660902914), [CodeQL](https://github.com/Duyphanb/Self-hosted-VoD-Platform/actions/runs/40660902747).
- **Required "Skeleton build checks" job:** it now also runs the catalog Newman collection after the existing auth and catalog smokes. The pull request for this closeout links its first passing run.
- **Reviews:** every Sprint 3 pull request carries an independent AI review with an author response.

## Remaining work and boundaries

| Classification | Tracking | Boundary |
|---|---|---|
| Blocker before Sprint 4 | [#14](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/14) | The object-store image cannot be pulled on a clean machine. Upload, encoding and playback depend on it. The catalog CI fixture deliberately excludes storage. |
| Test-quality follow-up | Not yet tracked; to be raised at the next triage | `AdminMovieFormPage.test.tsx` intermittently times out under CPU contention. This matches the #91 review's uncertain finding, which was not reproduced at the time. CI runners have not shown the failure, and no timeouts were raised to hide it. |
| Non-blocking | [#119](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/119) | Spring integration tests still use H2. PostgreSQL behaviour is covered by the runtime smoke and Newman, not by the JVM suite. |
| Sprint 6 | [#125](https://github.com/Duyphanb/Self-hosted-VoD-Platform/issues/125) | The upload → HLS smoke reuses this Newman step once #14 is resolved. |
| UI overhaul (after Sprint 6) | — | Basic UI by decision. Example: person search shows full UUIDs to disambiguate names. |
