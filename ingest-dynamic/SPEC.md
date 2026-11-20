# `ingest-dynamic` — Technical Specification (Story 2)

- **Owner:** Laurențiu (PM & Architect) — **Branch:** `feat/ingest-static-and-dynamic`
- **Epic 1 Scope:** **Story 2 — Tasks 1, 2, 3** (per `epic1_team_plan.md` & `plan.md` Phase 5)
- **Service Name:** `ingest-dynamic` (`server.port: ${SERVER_PORT:8082}`)
- **Schema Role:** `spring.ai.vectorstore.pgvector.initialize-schema=false` (waits for `ingest-static` healthy).
- **Volume Mount:** `./resources:/app/resources:ro`

---

## Numeric & Security Targets
| Parameter | Default | Env Variable |
|---|---|---|
| Embedding Dimensions | `1536` | `EMBEDDING_DIMENSIONS` |
| Chunk Size / Overlap | `512` / `64` | `APP_CHUNK_SIZE` / `APP_CHUNK_OVERLAP` |
| File Watcher Interval | `30` sec | `APP_WATCHER_INTERVAL_SEC` |
| Max Remote Payload Size | `20971520` (20 MB) | `APP_INGEST_MAX_BYTES` |
| Remote Fetch Timeout | `10000` ms | `APP_INGEST_TIMEOUT_MS` |
| Block Private Networks | `true` | `APP_INGEST_BLOCK_PRIVATE_NETWORKS` |
| Allowed Domains | `kubernetes.io,github.com,raw.githubusercontent.com,cncf.io` | `APP_INGEST_ALLOWED_DOMAINS` |
| Webhook HMAC Secret | *(required)* | `VENDOR_WEBHOOK_SECRET` |

---

## Story 2 Tasks & Acceptance Criteria

### Task 1 — Web, Remote PDF & GitHub Ingestion (`POST /ingest/dynamic/url`)
- Fetch HTML pages (`kubernetes.io`, `cncf.io`), remote PDFs, and GitHub raw Markdown docs (`github.com`, `raw.githubusercontent.com`).
- Strip HTML boilerplate (`<nav>`, `<footer>`, `<script>`, `<style>`, `<aside>`) using `jsoup`.
- Enforce SSRF protection (`APP_INGEST_BLOCK_PRIVATE_NETWORKS=true`) before opening any outbound HTTP connection:
  - Reject non-`https://` schemes.
  - Enforce domain allowlist (`APP_INGEST_ALLOWED_DOMAINS`).
  - Resolve DNS prior to connecting and reject loopback (`127.0.0.0/8`, `::1`), private (`10/8`, `172.16/12`, `192.168/16`), and link-local (`169.254/16` cloud metadata) addresses.
- Enforce payload ceiling (`APP_INGEST_MAX_BYTES=20971520`) and timeout (`APP_INGEST_TIMEOUT_MS=10000`).
- Chunk and store with metadata (`doc_id`, `version`, `source`, `checksum`, `is_latest=true`).

### Task 2 — Document Versioning (`is_latest`)
- When ingesting an updated version of an existing `source` / `doc_id` (different SHA-256 checksum), mark prior chunks `is_latest=false` and insert new chunks with `version = oldVersion + 1` and `is_latest=true`, preserving history.

### Task 3 — 30-Second File Watcher & HMAC-Verified Webhook
- **File Watcher:** `@Scheduled(fixedDelayString = "${APP_WATCHER_INTERVAL_SEC:30}000")` monitoring `./resources` (`APP_WATCHER_RESOURCES_DIR`) for new/modified files by SHA-256 checksum and triggering ingestion automatically.
- **Webhook (`POST /ingest/dynamic/webhook`):** Verify `X-Hub-Signature-256` HMAC-SHA256 against `VENDOR_WEBHOOK_SECRET` (returning `401 Unauthorized` on invalid/missing signature) and process valid payloads asynchronously with retry support.

---

## Definition of Done
- Success + failure/SSRF/HMAC-rejection automated tests (`mvn test -pl ingest-dynamic`).
- Structured `SLF4J` logging for SSRF blocks, HMAC failures, version increments, watcher events, and retries.
- Exposed actuator health endpoint at `/actuator/health`.
