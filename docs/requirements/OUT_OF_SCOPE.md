# Out Of Scope

This document prevents scope creep. Features listed here must not be implemented unless a later task explicitly moves them into scope.

On 2026-11-25 and 2026-11-26 the owner moved several items into scope (#258). Each section lists them under "Moved into scope" with the design that will govern them; until that design merges, the frozen ADRs govern. The "Allowed focus" lists keep the scope approved before 2026-11-25; `BACKLOG.md` and `TRACEABILITY.md`, not this document, say what is delivered. Items marked "conditional" start only when their trigger is met; the trigger is evidence, a dependency or a third party, never an "after MVP" label.

## Architecture And Platform

Out of scope:

- microservices split
- Kubernetes
- Kafka
- Elasticsearch (the single rule is in the Search section)
- managed AWS production stack such as ECS, RDS, ALB, or full S3-first architecture
- service mesh
- multi-region deployment
- more than one active video encode: several encode workers, encode concurrency above one, or overlapping worker replicas. A separately budgeted admin-image job, when it is enabled after measurement, is not a second video encoder (ADR-020, planned under #199)
- paid hosting, a paid storage service, or a CDN for video (Cloudflare's free CDN terms exclude video; a private Cloudflare One route or a direct HTTPS origin is compared in #250)
- search-engine indexing (SEO), at any stage
- a large simultaneous-viewer target: the platform serves a handful of private viewers (#233 measures the reference load)

Reason: the project direction is a modular monolith plus worker running through Docker Compose on one VM. Public exposure is a separate owner decision after HTTPS, backup and resource evidence (#136, #250).

## Media And Streaming

Out of scope:

- live streaming
- DRM
- payments-gated streaming
- 4K output, native HDR or Dolby Vision output, and real-time transcoding
- bulk re-encoding of the existing library, to 1080p or otherwise
- AI scanning of the whole library (for example automatic intro detection), heavy AI analysis, model training, a self-hosted LLM or persistent embeddings
- MP4 range streaming as the primary playback architecture
- real-time encoding updates through WebSocket or SSE
- native HLS playback (`video.src`), AirPlay and casting, which cannot send the bearer header ([ADR-011 D1](../architecture/adr/ADR-011.md#player-transport-and-authentication-d1-d4))
- subtitle authoring beyond importing timings, and OCR of bitmap subtitles
- surround-sound passthrough (audio is encoded to AAC stereo)
- importing an HLS package prepared elsewhere
- automatic skipping of intros or endings, and automatic playback of the next episode

Moved into scope (owner, 2026-11-25/26):

- **1080p default for new encodes (conditional):** after the target-VM gate (#124, #233) passes, with preset, CRF and maxrate taken from that measurement; no upscaling; existing 720p assets stay as they are (ADR-016, planned)
- **multi-audio:** every usable audio track of the source (up to four), packaged so the player can switch tracks (ADR-016, ADR-018, planned)
- **wider source support:** DTS and TrueHD audio converted to AAC; MPEG-2 video once real samples and a deinterlace policy exist (conditional); VC-1 only after the open Debian fix and a media sandbox exist (conditional); PGS subtitles kept as a sidecar file, never dropped silently (ADR-016, planned)
- **remux:** a spike only; copying source video into the package ships only if the spike measures a gain on the target VM (ADR-016, planned)
- **HDR-to-SDR conversion (conditional):** a corrective conversion when HDR sources exist; native HDR output stays out
- **an extra 720p rendition with adaptive switching (conditional):** only if #233 shows that viewers need it
- **subtitles:** protected WebVTT tracks, SRT converted on upload, embedded text subtitles extracted by the worker, a remembered language preference; styled ASS and PGS rendering only if the #248 spike passes (ADR-019, planned; #247, #248)
- **custom player controls:** an own control bar with focus-scoped hotkeys, menus and captions, with a temporary switch back to native controls until keyboard and screen-reader checks pass; picture-in-picture and Media Session later (ADR-018, planned; #249)
- **timed markers:** intro, ending and chapter markers confirmed by the admin, shown as skip buttons and chapters
- **"Tập tiếp theo" (next episode):** an explicit button that starts the next episode once; never on reload or a shared link

Allowed focus:

- one reliable HLS output path
- one fixed output profile that fits a 1280x720 box (720x1280 for portrait) without upscaling ([ADR-010 D27](../architecture/adr/ADR-010.md#sprint-5-worker-media-decisions-d25-d28))
- admin-visible encoding status

## Upload Experience

Out of scope:

- presigned direct upload to object storage
- virus scanning
- raising the deployed upload limit above 2048 MiB before #190 disk admission and the test-only 8 GiB qualification of #180 on the target VM

Moved into scope (owner, 2026-11-26):

- **resumable, chunked browser upload** with a free-disk pre-check, at the current size limit first (ADR-017, planned)
- **a larger, configurable size limit (conditional):** 8 GiB is the target once #180's test-only row qualifies it on the target VM; if it fails, 2048 MiB stays; no hard-coded long-term cap

Allowed focus:

- backend receives `MultipartFile`
- validates type and size
- stores raw file in MinIO
- publishes an encoding job

## Search

Out of scope:

- Elasticsearch or OpenSearch, until a relevance fixture shows that PostgreSQL cannot meet quality or latency
- personalized search ranking
- indexing provider descriptions (TMDB or AniList)
- indexing TMDB-derived values, unless TMDB's permission is recorded in ADR-014 first (eligible AniList name aliases are indexed)

Moved into scope (owner, 2026-11-03 and 2026-11-26):

- **accent-insensitive search with admin-managed aliases**, through a versioned Java text normalizer rather than the `unaccent` extension (ADR-015, planned under #128; #261)
- **a `pg_trgm` typo fallback (conditional):** only if the Issue 7.5 relevance fixture shows a benefit, and then only when the strict tiers find no visible title under the same filters (#263)

Allowed focus:

- PostgreSQL full-text search over real title metadata
- title suggestions, at most five

## Metadata And Providers

In scope (owner, 2026-11-24 and 2026-11-26): admin-only TMDB import (#245) and AniList import one title at a time (#246), each reviewed before apply (ADR-014, planned). The display title and slug stay the admin's own, and provider names become provenance-tracked aliases. The admin's own description always wins; provider text is used only when the admin has none, and admin-edited content is never overwritten automatically. TMDB data is refreshed by the admin or removed before its six-month limit.

Out of scope:

- bulk import and scheduled synchronisation (automatic TMDB refresh only if the owner asks)
- mirroring provider images, such as TMDB posters
- provider calls triggered by viewers
- external scores, such as IMDb ratings
- NFO import or export
- other providers (MAL, TheTVDB, OMDb) unless a measured gain justifies one
- AniList staff, voice actors, characters and studios, until a follow-up issue after #246
- airing schedules (needs provider terms and reliable dates)

## User Product Features

Out of scope:

- comments
- social features, including friends
- public profiles
- notifications
- learned or collaborative recommendation engines, and numeric "% match" scores
- a trending row computed from this site's own viewers (a handful of private viewers is no meaningful population, and it would expose other accounts' history); provider trending charts are a different item, below
- sharing lists
- a "Top Rated" row, brand hubs, a franchise type of collection, and third-party brand logos used as decoration (attribution that a provider's terms require, such as TMDB's, stays)
- mobile app
- offline download
- subscription plans

Moved into scope (owner, 2026-11-24 to 2026-11-26):

- **series, seasons, OVAs and films** with episodes and an optional franchise (ADR-013, planned under #244)
- **rule-based recommendations without scores:** "Tương tự" (similar titles) on the title page, the next part of a franchise, and one Home row "Vì bạn đã xem X"; with no eligible seed there is no personal row and Home shows "Mới thêm" beside any other eligible row; reasons are shown as text (ADR-015, planned)
- **collections:** editorial and smart collections built from the owner's own data, a Collections page and up to three pinned Home rows (ADR-021, planned)
- **studio and creator pages (conditional):** once enough titles carry that data
- **personal named lists (conditional):** only if the owner asks for them after using the watchlist
- **worldwide trending:** first an admin-only list of trending titles missing from the library (provider charts, fetched on demand, nothing stored); a viewer row of playable titles only (conditional) after the provider terms allow it and a two-week pilot shows at least four playable matches on most days, hidden whenever fewer than four playable titles remain (ADR-014, planned)

Allowed focus:

- profile
- watchlist
- watch history
- rating
- continue watching

## Admin And Operations

Out of scope:

- rich analytics dashboard
- storage billing dashboard
- multi-tenant admin
- complex user moderation
- admin user lock/unlock unless explicitly requested

Moved into scope (owner, 2026-11-25/26):

- **activation of encoded assets with Admin Preview:** a separate admin-only preview route, audited, `no-store`, writing no viewer progress or history (ADR-013, ADR-019, planned)
- **release of source files (conditional):** originals are archived long-term on the owner's PC or NAS, not on the VM; a file on the VM is released only after activation, a verified SHA-256 of the off-VM archive copy, the current HLS package in a verified backup set and a passed R2 volume-restore drill (#136), with an explicit owner confirmation for the exact files released (ADR-016, planned)
- **backups:** a database dump, small files and one verified archive copy are mandatory; block-volume backups with rotation inside the free limit; restore tests; a disaster-recovery document; an admin download of one title's HLS package. No step may cost money or delete the source volume without the owner's consent (ADR-016, planned; #136)

Allowed focus:

- title, episode and metadata CRUD
- video upload
- encoding status
- failed-job retry

## Observability

Out of scope:

- Prometheus beyond the bounded instance used for the target-VM measurements (#232)
- Grafana
- Loki
- distributed tracing
- a general alerting system

Allowed focus:

- health endpoint
- basic logs
- Docker health checks if useful
- bounded internal metrics (#232)
- backup-failure email alerts and an admin banner (ADR-016, planned)

## Rule For Reconsidering Scope

A deferred item can be reconsidered only after:

1. upload -> transcode -> HLS playback is stable
2. local Docker Compose is working
3. core tests and docs are updated
4. the user explicitly asks to promote the item into scope

The items moved on 2026-11-25/26 met these conditions: Sprint 6 verified the media flow end to end, and the owner promoted each item explicitly (#258).
