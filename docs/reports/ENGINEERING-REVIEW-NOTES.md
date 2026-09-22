# Ghi chú review kỹ thuật và hướng phát triển VoD

Ngày tổng hợp: 2026-09-15. Nguồn: hai đoạn trao đổi với GPT do người dùng cung cấp và yêu cầu lưu để áp dụng cho dự án.

**Vai trò:** ghi nhớ lập luận, rủi ro và đề xuất theo thời điểm; không phải ADR được duyệt, backlog triển khai mới hay bằng chứng production-ready. Chỉ đọc phần liên quan khi lập kế hoạch hoặc làm tính năng tương ứng. Các quyết định trong [tài liệu frozen](../INDEX.md#source-of-truth) vẫn có hiệu lực; ghi chú không tự cho phép đổi API, schema, kiến trúc hoặc security.

## 1. Định hướng cần giữ

- Mục tiêu là portfolio backend/full-stack có luồng VoD chạy thật, đo được và kiểm thử được khi lỗi. Giữ modular monolith + worker, Docker Compose, một VPS; hoàn thiện pipeline trước khi tăng độ phức tạp công nghệ.
- Ưu tiên luồng: catalog → admin upload → MinIO raw → PostgreSQL/job → RabbitMQ → worker FFprobe/FFmpeg → HLS trên MinIO → playback có kiểm tra quyền → progress/resume.
- Single-quality HLS trước; ABR (nhiều mức bitrate/chất lượng) chỉ xem xét khi luồng này ổn định và được đưa vào phạm vi. Không lấy ABR làm điều kiện hoàn thành MVP hiện tại.
- Phân biệt thiết kế, code đã có, kiểm thử đã chạy, kết quả đã merge và vận hành thực tế. Điểm số hoặc lời khen trong trao đổi là nhận xét định tính, không dùng làm bằng chứng CV hay tiêu chí nghiệm thu.
- GitHub Issues/PR và bằng chứng từ checkout dùng để xác minh tiến độ; docs giữ lý do, contract và kiến trúc. Tránh nhiều bảng trạng thái trùng lặp hoặc tạo hàng loạt issue chỉ để lưu ý tưởng.

## 2. Thứ tự ưu tiên tham khảo

Các mức dưới đây tổng hợp hai trao đổi, không xác nhận severity/CVE hay trạng thái issue hiện tại. Trước mỗi batch, kiểm tra phạm vi ảnh hưởng và dependency thực tế; chỉ chặn phần công việc bị ảnh hưởng bởi blocker.

| Thời điểm | Trọng tâm | Điều kiện áp dụng |
|---|---|---|
| Hiện tại / trước feature bị ảnh hưởng | Dependency/CVE, production secrets fail-closed, CORS, mạng nội bộ, JWT/RBAC/ownership | Xác minh phiên bản, cấu hình và issue/PR hiện tại; có bản vá trên nhánh không đồng nghĩa đã merge/deploy |
| Sprint Upload / Worker / Playback | Media validation, FFmpeg isolation, DB–queue consistency, idempotency, cleanup, HLS authorization | Làm rõ quyết định trước phần implementation phụ thuộc; bổ sung test lỗi cùng tính năng |
| Trước public VPS demo | HTTPS, CSP/HSTS sau kiểm chứng, rate limit, request limit, non-root, pin image, scan, auth E2E, backup/restore và smoke | Đối chiếu yêu cầu frozen với các đề xuất bổ sung; chốt phạm vi triển khai trước public demo |
| Sau core MVP | Auth recovery, session/device UX, admin MFA, review token storage, audit nâng cao, ABR, QoE | Không chen vào các sprint hiện tại chỉ vì đây là tính năng thường có ở production |
| Khi có nhu cầu tải thực tế | CDN/signed delivery, origin protection, WAF/DDoS ở nhà cung cấp, worker scaling, quan sát tập trung | Có số đo tải và ADR cho thay đổi ranh giới hệ thống |
| Khi kinh doanh nội dung có bản quyền | DRM, geo/rights window, entitlement, concurrent-stream policy, watermarking | Là thay đổi sản phẩm lớn, ngoài MVP; không suy ra nhu cầu từ mục tiêu portfolio |

## 3. Các điểm cần giải quyết trong pipeline

### ENG-01 — Upload và FFmpeg là ranh giới xử lý dữ liệu không tin cậy

Baseline đã có trong [SECURITY.md](../architecture/SECURITY.md#upload-validation): admin-only, giới hạn dung lượng, MIME allowlist, reject empty, object key do server tạo và bucket private. Không dùng tên file người dùng làm đường dẫn thực thi.

Đề xuất cần cụ thể hóa khi làm Sprint 4/5:

- Không coi `Content-Type` do client gửi là bằng chứng nội dung hợp lệ. Xem xét kiểm tra extension, signature/container và FFprobe; quyết định rõ bước nào chạy ở API, bước nào ở worker, cùng phản hồi/trạng thái khi reject. Không tự chuyển FFmpeg vào request thread hoặc đổi allowlist frozen.
- Chạy FFmpeg/FFprobe bằng danh sách argument; kiểm soát file đầu vào, đường dẫn và protocol để media không trở thành cách đọc file hoặc truy cập mạng tùy ý.
- Worker non-root, quyền filesystem tối thiểu, vùng temp riêng, giới hạn CPU/RAM/disk và timeout; không Docker socket; chỉ có kết nối mạng cần cho công việc. Theo dõi bản vá media tools.
- Kiểm thử input rỗng/hỏng/giả MIME, oversized request, filename nguy hiểm, timeout, storage lỗi và cleanup sau thành công/thất bại/shutdown. Không xem việc chỉ admin được upload là lý do bỏ kiểm tra input.

Virus scanning/quarantine vẫn deferred theo MVP. Nếu chuyển sang user-generated content, cần đánh giá lại threat model, moderation và abuse thay vì áp dụng nguyên giả định admin-only.

### ENG-02 — Nhất quán giữa PostgreSQL, RabbitMQ và MinIO

[BACKLOG.md](../requirements/BACKLOG.md), Issue 4.5 vẫn có câu `Transaction ensures consistency between DB and queue`. Đây là điểm cần làm rõ trước triển khai: transaction DB cục bộ không tự làm cho DB commit, RabbitMQ publish và MinIO upload thành một thao tác nguyên tử.

Đề xuất trong trao đổi là transactional outbox + publisher relay, hoặc cơ chế reconciliation/recovery được mô tả cụ thể. **Chưa chọn hoặc phê duyệt outbox.** Nếu thêm bảng/event, phải có quyết định kiến trúc, cập nhật ERD/flow và migration mới trong batch được duyệt. Outbox không tự giải quyết object MinIO bị mồ côi hay message trùng.

Owner khi thiết kế: backend upload/encoding, phối hợp worker/status và storage. Cần chỉ rõ ai phục hồi, khi nào retry, cách phát hiện trạng thái kẹt và bằng chứng xử lý các cửa sổ lỗi:

- Raw upload thành công nhưng ghi DB thất bại → object mồ côi.
- DB commit thành công nhưng publish chưa thành công → job không được giao.
- Publish đã thành công nhưng kết quả xác nhận/cập nhật bị mất → gửi trùng.
- Worker hoặc relay chết giữa các bước → khôi phục được mà không báo hoàn thành sai.

### ENG-03 — Worker idempotency và retry có giới hạn

Idempotency ở đây là cùng job được giao lại không làm sai trạng thái hoặc hỏng artifact. Khi làm Sprint 5, xác định job/attempt identity, điều kiện trạng thái trước khi cập nhật, xử lý message cũ, đường dẫn output và việc ghi lại an toàn; attempt cũ không được ghi đè kết quả attempt mới.

Giữ concurrency = 1 và direct JDBC status writes theo [SYSTEM-ARCHITECTURE.md](../architecture/SYSTEM-ARCHITECTURE.md). Theo [SEQUENCE-DIAGRAMS.md](../architecture/SEQUENCE-DIAGRAMS.md#worker-encoding), worker ghi `READY` rồi ACK; lỗi xử lý được ghi `FAILED` rồi ACK sau khi lưu trạng thái. Admin retry tạo job với attempt tiếp theo. Không biến gợi ý ACK/NACK/DLQ trong trao đổi thành tự động requeue mọi lỗi.

Phần còn cần thiết kế rõ: DB không ghi được trạng thái cuối, mất kết nối broker, crash trước ACK, duplicate delivery và poison message. Bounded retry/DLQ là phương án cần đánh giá, không phải contract đã có. Kiểm thử cả crash sau upload, sau ghi `READY` nhưng trước ACK và nhận lại message của attempt cũ.

### ENG-04 — Cleanup và reconciliation

Liên hệ [RISKREGISTER.md](../requirements/RISKREGISTER.md): R-006 (storage/temp leaks), R-007 (queue/status lệch). Cleanup temp thuộc pipeline hiện tại; job reconciliation định kỳ hoặc công cụ vận hành là đề xuất mở rộng cần chốt phạm vi.

Nhận diện object mồ côi, `UPLOADED` không có job, `PROCESSING` bị kẹt và artifact không khớp trạng thái. Ngưỡng stale phải dựa trên timeout/thời lượng job, không chép các ví dụ “2 giờ/3 giờ” thành cấu hình. Trước xóa media phải xác minh ownership, tham chiếu DB và attempt đang hoạt động; tránh cleanup xóa dữ liệu hợp lệ.

### ENG-05 — Playback và progress có bằng chứng

Giữ Browser → Nginx `/hls/*` → backend authorization/proxy → MinIO private. Theo [SECURITY.md](../architecture/SECURITY.md#hls-access-control) và [API-CONTRACT.yaml](../architecture/API-CONTRACT.yaml), kiểm tra token, movie visibility, asset `READY`, và object thuộc đúng asset cho cả manifest lẫn segment.

Khi triển khai, kiểm thử truy cập không quyền, asset chưa sẵn sàng, path traversal và biến thể encoded, MIME manifest/segment, token hết hạn và lỗi player. Không tạo endpoint nhận object key tùy ý. Kiểm chứng progress/resume qua Redis buffer → PostgreSQL; nêu rõ cửa sổ mất dữ liệu nếu còn giới hạn.

`master.m3u8` đã thuộc frozen single-quality MVP; không ghi thành “chưa có, chỉ thêm sau MVP”. Phần thêm sau là nhiều rendition và cách tổ chức playlist tương ứng.

## 4. Trước public demo và các nâng cấp để sau

- Public demo: kiểm chứng HTTPS/certificate và redirect trước HSTS; thử CSP với asset/player thực tế, cân nhắc Report-Only trước enforcement. Không tự bật HSTS preload. Login rate limiting cần làm rõ owner edge/app, trusted client IP, response `429`, retry behavior và observability trước đổi API.
- Xác minh secrets fail-closed, service/bucket private, route-specific body limits, non-root, image pinning và scan. CI chạy thành công chưa chứng minh CD, rollback hoặc runtime an toàn.
- Backup phải có restore test trên môi trường tách biệt: PostgreSQL, media cần giữ, metadata/config; app khởi động và đọc dữ liệu sau phục hồi. Ghi rõ RPO/RTO khi chuẩn bị vận hành; không chỉ chứng minh có file backup.
- Giữ application logs/counters theo [OBSERVABILITY.md](../architecture/OBSERVABILITY.md). Audit trail cơ bản cho admin mutation/auth là ứng viên sau core MVP/trước demo hoặc portfolio polish; cần chốt phạm vi riêng. Log vận hành chưa tự tạo thành audit trail. Không log credential hoặc old/new payload nhạy cảm.
- Auth sau MVP: email verification, password reset với phản hồi chung, token random/hash/expiry/single-use và thu hồi session; device/session listing, revoke-all, admin MFA/passkeys. Cần contract/schema rõ trước làm; không tự tạo endpoint/bảng từ ví dụ trao đổi.
- Browser token storage: ghi nhớ rủi ro XSS; đánh giá access token in-memory + refresh cookie `HttpOnly`/`Secure`/`SameSite` hoặc BFF sau MVP. Thay đổi này kéo theo CSRF, CORS credentials, refresh/logout và frontend session design; cần ADR/API/security được duyệt. Giữ BCrypt hiện tại; Argon2id là lựa chọn đánh giá sau, không tự đổi thuật toán.
- Media/QoE sau MVP: ABR, subtitles/multi-audio, thumbnail/trick-play; đo startup time, rebuffer ratio, playback/segment failures, bitrate và watch completion. Không bắt buộc cài Prometheus/Grafana/OpenTelemetry để hoàn thành MVP.
- Khi có tải: đo giới hạn backend HLS proxy rồi mới cân nhắc CDN/signed entitlement, caching và origin protection. Signed cookie cho nhiều segment là một phương án, chưa phải quyết định. Không tự viết hệ thống DDoS hoặc thêm WAF/KMS chỉ để tăng số công nghệ.
- Mở rộng catalog series/season/episode có thể xem xét sau movie-only MVP; codec/DASH/CMAF, multi-region, DRM/rights và tính năng thương mại cần nhu cầu riêng. Giữ PostgreSQL FTS; không tự thêm search engine hay microservices.
- CD/supply chain nâng cao: cân nhắc build-once immutable artifact, SBOM/provenance/signing, staging smoke, phê duyệt production và rollback có kiểm chứng khi tới giai đoạn deployment. Không coi các mục này là đã có.

## 5. Đối chiếu cục bộ và các nhận xét có thể đã cũ

Snapshot ngày tổng hợp: nhánh `fix/49-harden-ci-supply-chain`, HEAD `be81d5f`. Đây là checkout cục bộ, **không phải xác minh `main`, trạng thái issue/PR hay production**. Lần ghi chú này chỉ đọc code/docs, không chạy lại test, scanner hoặc flow runtime.

| Nhận xét từ trao đổi | Bằng chứng đọc trong checkout / cách dùng về sau |
|---|---|
| Spring Boot trên `main` là `3.3.5` | [backend/pom.xml](../../backend/pom.xml) và [worker/pom.xml](../../worker/pom.xml) hiện khai báo `3.5.16`. Không suy ra dependency sạch CVE; kiểm tra resolution/scanner và `main` khi làm hardening |
| CI dùng action tag mutable | [.github/workflows/ci.yml](../../.github/workflows/ci.yml) hiện dùng SHA, có permissions và `persist-credentials: false`. Không suy ra PR đã merge |
| Auth và frontend refresh đã có nhiều cơ chế bảo vệ | Đã thấy source/test trong `backend/auth/`, `authStorage.ts`, `AuthContext.tsx`, `client.ts`: dummy hash, hash upgrade, refresh rotation, localStorage, refreshPromise và generation identity. Sự tồn tại code/test chưa chứng minh test đang pass hoặc mọi race được xử lý |
| Planning có nguy cơ lệch tiến độ | [PROJECTPLAN.md](../requirements/PROJECTPLAN.md#current-next-step) vẫn ghi Sprint 2 active / Issue 2.1 tiếp theo; không lấy đó làm trạng thái xác nhận khi auth code đã tồn tại. Kiểm tra GitHub và bằng chứng trước khi sửa tiến độ |
| Queue consistency chưa rõ | Issue 4.5 còn câu transaction DB–queue; ERD không định nghĩa outbox. Xử lý ENG-02 trước phần triển khai phụ thuộc |
| Retry cần bảo toàn contract | Issue 9.3 trong backlog ghi `200`/`400`; API frozen dùng `202`/`409`, sequence cũng trả `202`. Cần sửa lệch backlog trong batch docs được phép trước triển khai retry; không đổi API theo backlog |

Các issue được hai trao đổi nhắc đến, **chưa kiểm tra trạng thái GitHub trong lần này**:

| Nhóm | Tham chiếu lịch sử |
|---|---|
| Dependency và lifecycle | #45 Spring/Security, #50 Spring support, #56 React Router, #62 pgJDBC |
| Secrets, CORS, CI | #46 production fail-closed, #48 CORS, #49 supply-chain/permissions |
| Trước public demo / deployment | #51 login rate limiting, #52 request size, #53 routing assertions, #54 non-root/pinned images, #55 auth Newman CI, #57 Nginx DNS |
| Storage | #14 MinIO lifecycle/security |

Dùng các số này để tìm issue hiện có trước khi đề xuất issue mới. Không chép danh sách thành checkbox “chưa làm” hoặc “đã xong”; không lưu phiên bản vá/CVE từ chat thành kết luận security hiện tại.

## 6. Cách áp dụng cho những lần làm việc sau

- Chọn phần ghi chú theo feature hoặc quyết định đang làm; đối chiếu frozen docs và current code, không nạp toàn bộ review vào mọi task.
- Với đề xuất cần thay đổi contract, chuẩn bị vấn đề, phương án, ảnh hưởng API/schema/security và tiêu chí kiểm thử; xin duyệt quyết định rồi cập nhật nguồn chính thức trong batch đó.
- Khi một đề xuất đã được quyết định/triển khai, dẫn sang ADR, issue/PR hoặc test evidence thay vì duy trì thêm bản contract ở đây. Không tự bắt đầu hạng mục tiếp theo từ note.
- Khi nói về portfolio/CV, chỉ mô tả mức đã có bằng chứng: thiết kế, implementation, integration test hoặc demo vận hành. Không dùng “production-grade/scalable” chỉ từ sơ đồ, unit test hoặc health check.

## Nguồn trao đổi

- **S1 — Review thứ tự ưu tiên security**, mở đầu “Có. Mình đã đối chiếu lại phần 23…”; attachment `14a10d8a-8e3e-4b44-8487-a741dbc1be3c/pasted-text.txt`. SHA-256: `51c0eb0b905d97359cc37af0cebb9a7d2369c045243336e8c3a1946db9bedc2e`.
- **S2 — Review architecture/engineering**, mở đầu “Mình đánh giá dự án này cao về tư duy kiến trúc…”; attachment `ea7fcc42-e3a4-4602-ac57-afca9bdad858/pasted-text.txt`. SHA-256: `f49e02a866836b8502cb0b0321d521ca7afcc54e62f9571e22826344a3054756`.

Đây là bản tổng hợp chọn lọc, không phải bản chép toàn bộ hội thoại. S1 điều chỉnh thứ tự security tổng quát trong S2; giữ ưu tiên media pipeline và hoãn tính năng enterprise không cần thiết. Hai nguồn dẫn OWASP/AWS và các tài liệu khác, nhưng lần tổng hợp này không xác minh lại nguồn web, CVE hoặc trạng thái GitHub; cần tra nguồn chính thức hiện hành khi ra quyết định triển khai tương ứng.
