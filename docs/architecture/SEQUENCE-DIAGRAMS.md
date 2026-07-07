# Sequence Diagrams

Status: Frozen for MVP implementation

These diagrams define the frozen MVP runtime flows. They are architecture contracts, not implementation code.

## Admin Upload

```mermaid
sequenceDiagram
    actor Admin
    participant Frontend
    participant Nginx
    participant Backend
    participant PostgreSQL
    participant MinIO
    participant RabbitMQ

    Admin->>Frontend: Select movie and video file
    Frontend->>Nginx: POST /api/v1/admin/movies/{movieId}/video
    Nginx->>Backend: Forward request with Authorization
    Backend->>Backend: Validate JWT, ROLE_ADMIN, MIME type, size, non-empty file
    Backend->>MinIO: Store raw video in vod-raw
    Backend->>PostgreSQL: Upsert video_assets status UPLOADED
    Backend->>PostgreSQL: Create encoding_jobs status QUEUED
    Backend->>RabbitMQ: Publish job to video-encoding-queue
    Backend->>PostgreSQL: Mark video_assets status QUEUED
    Backend-->>Nginx: 202 Accepted
    Nginx-->>Frontend: 202 Accepted with videoAssetId and QUEUED status
    Frontend-->>Admin: Show queued processing status
```

## Worker Encoding

```mermaid
sequenceDiagram
    participant RabbitMQ
    participant Worker
    participant PostgreSQL
    participant MinIO
    participant FFprobe
    participant FFmpeg

    RabbitMQ-->>Worker: Deliver encoding job
    Worker->>PostgreSQL: Mark encoding_jobs and video_assets PROCESSING via direct JDBC
    Worker->>MinIO: Read raw object from vod-raw
    Worker->>FFprobe: Extract duration, codec, bitrate, resolution
    FFprobe-->>Worker: Metadata result
    Worker->>PostgreSQL: Save media metadata
    Worker->>FFmpeg: Generate single-quality HLS output
    FFmpeg-->>Worker: master.m3u8 and .ts segments
    Worker->>MinIO: Upload HLS artifacts to vod-hls
    Worker->>PostgreSQL: Mark video_assets and encoding_jobs READY via direct JDBC
    Worker-->>RabbitMQ: Ack job
```

Failure path:

```mermaid
sequenceDiagram
    participant Worker
    participant PostgreSQL
    participant RabbitMQ

    Worker->>Worker: FFprobe, FFmpeg, storage, or validation error
    Worker->>PostgreSQL: Mark video_assets FAILED with safe failure reason via direct JDBC
    Worker->>PostgreSQL: Mark encoding_jobs FAILED via direct JDBC
    Worker-->>RabbitMQ: Ack failed job after state is persisted
```

## Admin Retry Failed Encoding

```mermaid
sequenceDiagram
    actor Admin
    participant Frontend
    participant Nginx
    participant Backend
    participant PostgreSQL
    participant RabbitMQ

    Admin->>Frontend: Click retry on failed video asset
    Frontend->>Nginx: POST /api/v1/admin/video-assets/{videoAssetId}/retry
    Nginx->>Backend: Forward request with Authorization
    Backend->>Backend: Validate JWT and ROLE_ADMIN
    Backend->>PostgreSQL: Load video_assets row and latest encoding_jobs attempt
    alt asset is FAILED
        Backend->>PostgreSQL: Create new encoding_jobs row with next attempt and QUEUED
        Backend->>RabbitMQ: Publish job to video-encoding-queue
        Backend->>PostgreSQL: Mark video_assets status QUEUED
        Backend-->>Nginx: 202 Accepted with QUEUED status
        Nginx-->>Frontend: 202 Accepted
        Frontend-->>Admin: Show retry queued
    else asset is not FAILED
        Backend-->>Nginx: 409 Conflict
        Nginx-->>Frontend: 409 Conflict
        Frontend-->>Admin: Show retry not available
    end
```

## Playback

```mermaid
sequenceDiagram
    actor User
    participant Frontend
    participant Nginx
    participant Backend
    participant PostgreSQL
    participant MinIO

    User->>Frontend: Open watch page
    Frontend->>Nginx: GET /api/v1/movies/{movieId}/playback
    Nginx->>Backend: Forward request with Authorization
    Backend->>Backend: Validate JWT and access rules
    Backend->>PostgreSQL: Load movie, READY video asset, saved progress
    Backend-->>Nginx: Playback info with /hls/video-assets/{videoAssetId}/master.m3u8
    Nginx-->>Frontend: Playback info
    Frontend->>Nginx: GET /hls/video-assets/{videoAssetId}/master.m3u8 with Authorization
    Nginx->>Backend: Forward HLS request with Authorization
    Backend->>Backend: Validate JWT and HLS access
    Backend->>MinIO: Read vod-hls master playlist
    Backend-->>Nginx: HLS playlist with MIME type
    Nginx-->>Frontend: HLS playlist
    Frontend->>Nginx: GET /hls/video-assets/{videoAssetId}/segments/{segmentName} with Authorization
    Nginx->>Backend: Forward HLS segment request
    Backend->>Backend: Validate JWT and HLS access
    Backend->>MinIO: Read vod-hls segment
    Backend-->>Nginx: HLS segment with MIME type
    Nginx-->>Frontend: HLS segment
```

## Playback Progress

```mermaid
sequenceDiagram
    participant Frontend
    participant Nginx
    participant Backend
    participant Redis
    participant PostgreSQL

    Frontend->>Nginx: PUT /api/v1/movies/{movieId}/progress every 5 to 10 seconds
    Nginx->>Backend: Forward progress request with Authorization
    Backend->>Backend: Validate JWT and movie access
    Backend->>Redis: Buffer latest progress by userId/movieId
    Backend-->>Nginx: 202 Accepted
    Nginx-->>Frontend: Progress accepted

    Backend->>Redis: Scheduled read of dirty progress keys
    Backend->>PostgreSQL: Upsert playback_progress and watch_history
    Backend->>Redis: Clear flushed dirty markers
```

## Auth Refresh

```mermaid
sequenceDiagram
    actor User
    participant Frontend
    participant Nginx
    participant Backend
    participant PostgreSQL

    Frontend->>Nginx: POST /api/v1/auth/refresh with refreshToken JSON body
    Nginx->>Backend: Forward refresh request
    Backend->>PostgreSQL: Validate refresh token hash, expiry, revocation
    alt token valid
        Backend->>PostgreSQL: Revoke old refresh token
        Backend->>PostgreSQL: Store new refresh token hash
        Backend-->>Nginx: JSON body with new access token and refresh token
        Nginx-->>Frontend: JSON body with new access token and refresh token
    else token invalid
        Backend-->>Nginx: 401 Unauthorized
        Nginx-->>Frontend: 401 Unauthorized
        Frontend-->>User: Require login
    end
```

## Search

```mermaid
sequenceDiagram
    actor User
    participant Frontend
    participant Nginx
    participant Backend
    participant Redis
    participant PostgreSQL

    User->>Frontend: Type search query
    Frontend->>Frontend: Debounce around 300ms
    Frontend->>Nginx: GET /api/v1/search?q={query}
    Nginx->>Backend: Forward request with Authorization
    Backend->>Redis: Check short-lived search cache
    alt cache miss
        Backend->>PostgreSQL: PostgreSQL full-text search over movie metadata
        PostgreSQL-->>Backend: Ranked movie results
        Backend->>Redis: Store short-lived cache entry
    else cache hit
        Redis-->>Backend: Cached result
    end
    Backend-->>Nginx: Search response
    Nginx-->>Frontend: Search response
    Frontend-->>User: Show results, empty state, or error state
```
