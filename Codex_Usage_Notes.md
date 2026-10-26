# Codex Usage Notes

Use the applicable section when changing code or maintaining Codex guidance. Repository-wide boundaries and completion criteria live in [AGENTS.md](AGENTS.md); contract locations are in [docs/INDEX.md](docs/INDEX.md).

## Implementation conventions

These retain the repository's engineering conventions. Frozen contracts govern behavior; examples in prompts and generic installed skills cannot override them.

### Backend

- Java 21, Spring Boot 4.x, Maven multi-module; the backend owns core business workflows.
- Constructor injection only. Keep controllers thin and business logic in services.
- Use request/response DTOs, never exposed JPA entities; Spring Validation and centralized `@RestControllerAdvice` handle validation/errors.
- Use SLF4J, not `System.out.println`. Keep OpenAPI behavior synchronized with the frozen API contract.
- Full-context `@AutoConfigureMockMvc` tests in `backend/common` validate every response against `docs/architecture/API-CONTRACT.yaml` (`ContractValidation`); update the contract first, and mark only deliberate off-contract requests with `offContract(reason)`.
- Use JUnit Jupiter and Mockito by default, at the versions managed by Spring Boot (do not override them). Persistence, queue, and storage changes need appropriate boundary integration coverage.

### Spring Boot 4 conventions

Backend and worker use Spring Boot 4.x ([ADR-002](docs/architecture/adr/ADR-002.md)). Do not copy Spring Boot 3 names from older examples:

- Starters: `spring-boot-starter-webmvc`, not `spring-boot-starter-web`; `spring-boot-starter-security-oauth2-resource-server`; and the matching `*-test` starter for each technology under test, such as `spring-boot-starter-webmvc-test` or `spring-boot-starter-data-jpa-test`.
- Jackson 3: import `ObjectMapper`, `JsonNode`, and other core/databind types from `tools.jackson.*`, not `com.fasterxml.jackson.*`; `JsonNode.asText()` becomes `asString()`. Jackson annotations stay in `com.fasterxml.jackson.annotation`.
- Security: build request matchers with `PathPatternRequestMatcher`; `AntPathRequestMatcher` no longer exists.
- HTTP tests against a running server: `RestTestClient` with `@AutoConfigureRestTestClient` (`org.springframework.boot.resttestclient.autoconfigure`), not `TestRestTemplate`.
- Test annotation packages: `@DataJpaTest` in `org.springframework.boot.data.jpa.test.autoconfigure`, `@AutoConfigureMockMvc` in `org.springframework.boot.webmvc.test.autoconfigure`, `@AutoConfigureTestDatabase` in `org.springframework.boot.jdbc.test.autoconfigure`. `@EntityScan` is in `org.springframework.boot.persistence.autoconfigure`.
- Mock beans: `@MockitoBean` and `@MockitoSpyBean` from `org.springframework.test.context.bean.override.mockito`; `@MockBean` and `@SpyBean` were removed.

### Frontend

- React 18 functional components, TypeScript, Vite, Tailwind CSS; feature-based folders as features are implemented.
- TanStack Query for server state and a shared API client for JWT handling; do not scatter raw fetch calls across components.
- Handle loading, error, empty, and success request states; use explicit protected-route and admin-role guards.
- Build the actual app experience. Keep business logic out of UI components and cover important user flows/state handling in tests.

### Worker and delivery

- Keep the worker focused on queue consumption, probing, transcoding, artifact upload, and status updates. Avoid abstractions beyond the simple MVP pipeline.
- Preserve lifecycle states `UPLOADED`, `QUEUED`, `PROCESSING`, `READY`, `FAILED` according to the schema and processing flow.
- Provide FFmpeg and FFprobe in the worker runtime `PATH`; honor `FFMPEG_PATH` and `FFPROBE_PATH` when configured.
- Cover command construction, status transitions, and failure handling before production hardening.
- Follow `docs/architecture/INFRASTRUCTURE.md` for internal DNS, private storage, and Nginx exposure. Keep any development-only direct ports in a local override, not the base Compose file.

## Instruction maintenance

This structure applies the selective-context and completion guidance in [OpenAI's GPT-6 Astra article](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra).

- Keep `AGENTS.md` for durable repository boundaries and completion. Keep contract detail in its source document and use `docs/INDEX.md` to help locate it. Task prompts should supply outcomes, non-goals, and evidence, not repeat repository rules.
- Use existing guidance files before adding more. There are currently no repository-local skills or nested agent instructions. Add nested instructions only for durable rules unique to a subtree, without repeating root rules.
- Introduce a skill only for a demonstrated, recurring specialized workflow that benefits from knowledge or tooling beyond these guides. A batch count or a broad topic such as Java, React, databases, or all repository work is not a reason to create or load one.
- For a skill, keep the name/description short and state the specific action that triggers it. Load its body only when applicable; for multiple workflows, use a small router and open only the needed reference or script. Do not preload every skill or supporting file.
- Prefer decision criteria and verifiable outcomes over rigid command recipes. Retain exact sequences when ordering is necessary for correctness or safety, such as migration rollout or recovery.
- When editing instructions, check links, duplicate/conflicting rules, and how a small doc fix, a feature with failing tests, and a frozen-contract conflict would proceed. Routine work should reach verification; a real authorization boundary should pause only the dependent action. Do not claim model behavior was tested when only the text was reviewed.
