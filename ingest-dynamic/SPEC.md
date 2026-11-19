# `ingest-dynamic` — Technical Specification (Story 2)

- **Owner:** Alex (Security & Dynamic Ingestion)
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

### Task 1 — Web & Remote Document Ingestion (`POST /ingest/dynamic/url`)
- Ingest web pages (HTML cleaned via `jsoup` to strip nav/footer/script boilerplate), remote PDFs, and third-party GitHub repository docs.
- Enforce payload ceiling (`APP_INGEST_MAX_BYTES`) and timeout (`APP_INGEST_TIMEOUT_MS`).
- Chunk and store with metadata (`doc_id`, `version`, `source`, `checksum`, `is_latest=true`).

### Task 2 — Document Versioning (`is_latest`)
- When a remote or watched document changes (SHA-256 differs), mark prior chunks `is_latest=false` and insert new version chunks with `is_latest=true` atomically.

### Task 3 — 30-Second Directory File Watcher
- Poll `APP_WATCHER_RESOURCES_DIR` every `APP_WATCHER_INTERVAL_SEC` seconds (`30`s default).
- Detect added/modified files by SHA-256 checksum and trigger idempotent ingestion.

### Task 4 — HMAC-Verified Webhooks & Retry Queue (`POST /ingest/dynamic/webhook`)
- Verify incoming webhook signatures using HMAC-SHA256 against `VENDOR_WEBHOOK_SECRET` (reject invalid/missing signatures with `401`/`403`).
- Enqueue failed ingestion jobs into a bounded retry queue with backoff and structured error logging.

### Task 5 — SSRF Guardrails (Mandatory)
- Enforce `APP_INGEST_BLOCK_PRIVATE_NETWORKS=true` in Java before opening any outbound HTTP connection:
  - Reject non-`https://` URLs.
  - Enforce domain allowlist (`APP_INGEST_ALLOWED_DOMAINS`).
  - Resolve DNS prior to connecting and reject loopback (`127.0.0.0/8`, `::1`), site-local/private (`10/8`, `172.16/12`, `192.168/16`), and link-local (`169.254/16` cloud metadata) addresses.

---

## Definition of Done
- Success + failure/SSRF/HMAC-rejection automated tests (`mvn test -pl ingest-dynamic`).
- Structured `SLF4J` logging for SSRF blocks, HMAC failures, watcher events, and retries.
- Exposed actuator health endpoint at `/actuator/health`.
