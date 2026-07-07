# Vision

## Purpose

Build a self-hosted Video-on-Demand platform that demonstrates professional backend and full-stack engineering through a constrained, production-style MVP.

The product must prove one complete media workflow:

1. An admin uploads a video.
2. The backend stores the raw asset and queues an encoding job.
3. The worker transcodes the asset into HLS.
4. A logged-in user plays the video in the browser.
5. Playback progress, search, and user library features work with real persisted data.

## Product Goal

Deliver a portfolio-grade VoD system that can run locally through Docker Compose and later run as a public demo on an Oracle Cloud Free Tier VPS.

The project should be credible for backend or full-stack interviews by showing:

- authentication and RBAC
- database-backed domain modeling
- asynchronous worker processing
- object storage integration
- media processing with FFmpeg and FFprobe
- HLS playback
- measurable tests and documentation
- deployable operations baseline

## Target Users

| User | Goal |
|---|---|
| Guest | Register or log in before accessing protected playback features. |
| User | Browse, search, watch, resume, rate, and manage personal movie lists. |
| Admin | Manage movie metadata, upload videos, monitor encoding status, and retry failed processing. |
| Developer / Operator | Run, test, deploy, and diagnose the system using documented commands and health checks. |

## MVP Outcomes

The MVP is successful when all of these outcomes are true:

- Admin can upload a valid video file.
- Uploaded videos are stored outside the app process in MinIO.
- The backend publishes an encoding job to RabbitMQ.
- The worker consumes the job and produces playable HLS output.
- A logged-in user can play the HLS asset in the browser.
- Resume playback works for demo-level use.
- Search returns real movie metadata from PostgreSQL, not mocked data.
- Watchlist, watch history, and rating are persisted per user.
- The full system runs locally through Docker Compose.
- The system can be deployed to an Oracle Cloud Free Tier VPS with HTTPS.

## Scope Principles

- This is a modular monolith plus worker architecture, not a microservices platform.
- Local Docker Compose must work before VPS deployment polish.
- The single-quality HLS flow is more important than advanced encoding options.
- Documentation and tests must stay aligned with implemented behavior.
- Optional features are deferred until the upload -> transcode -> playback path is stable.

## Non-Goals For MVP

- Kubernetes
- Kafka
- Elasticsearch
- live streaming
- DRM
- payments or subscriptions
- mobile applications
- recommendation engines
- managed AWS production architecture
- multi-quality HLS before the single-quality flow is stable

## Definition Of Success

This project is ready for portfolio use only when:

- a recruiter or interviewer can clone the repository and run the local stack from documented steps
- the deployed demo shows a real upload and playback workflow
- Swagger/OpenAPI, Postman, tests, and README match the actual implementation
- claims in the README and CV are supported by working code and evidence
