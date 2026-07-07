# Out Of Scope

This document prevents MVP scope creep. Features listed here must not be implemented unless a later task explicitly moves them into scope.

## Architecture And Platform

Out of scope for MVP:

- microservices split
- Kubernetes
- Kafka
- Elasticsearch
- managed AWS production stack such as ECS, RDS, ALB, or full S3-first architecture
- service mesh
- multi-region deployment

Reason: the project direction is a modular monolith plus worker running through Docker Compose.

## Media And Streaming

Out of scope for MVP:

- live streaming
- DRM
- payments-gated streaming
- multi-quality adaptive bitrate ladder before the single-quality flow is stable
- advanced CRF/preset tuning
- subtitle authoring or subtitle conversion
- MP4 range streaming as the primary playback architecture
- real-time encoding updates through WebSocket or SSE

Allowed MVP focus:

- one reliable HLS output path
- optional 720p target if needed for stability
- admin-visible encoding status

## Upload Experience

Out of scope for MVP:

- presigned direct upload
- resumable upload
- chunked browser upload
- advanced upload progress persistence
- virus scanning

Allowed MVP focus:

- backend receives `MultipartFile`
- validates type and size
- stores raw file in MinIO
- publishes an encoding job

## Search

Out of scope for MVP:

- Elasticsearch
- OpenSearch
- fuzzy ranking beyond PostgreSQL full-text search
- `pg_trgm` unless the MVP is already green
- unaccent search unless explicitly promoted later
- personalized ranking

Allowed MVP focus:

- PostgreSQL full-text search over real movie metadata
- basic search suggestions

## User Product Features

Out of scope for MVP:

- comments
- social features
- public profiles
- notifications
- recommendation engine
- real production trending algorithm
- mobile app
- offline download
- subscription plans

Allowed MVP focus:

- profile
- watchlist
- watch history
- rating
- continue watching

## Admin And Operations

Out of scope for MVP:

- rich analytics dashboard
- storage billing dashboard
- multi-tenant admin
- complex user moderation
- admin user lock/unlock unless explicitly requested

Allowed MVP focus:

- movie metadata CRUD
- video upload
- encoding status
- failed-job retry

## Observability

Out of scope for MVP:

- Prometheus
- Grafana
- Loki
- distributed tracing
- alerting system

Allowed MVP focus:

- health endpoint
- basic logs
- Docker health checks if useful

## Rule For Reconsidering Scope

A deferred item can be reconsidered only after:

1. upload -> transcode -> HLS playback is stable
2. local Docker Compose is working
3. core tests and docs are updated
4. the user explicitly asks to promote the item into scope
