# `ingest-dynamic` — Technical Specification (Story 2)

- **Owner:** Laurențiu (PM & Architect) — **Branch:** `feat/ingest-dynamic`
- **Epic 1 Scope:** **Story 2 — Tasks 1, 2, 3** (per `epic1_team_plan.md` & `plan.md` Phase 5)
- **Service Name:** `ingest-dynamic` (`server.port: ${SERVER_PORT:8082}`)
- **Schema Role:** `spring.ai.vectorstore.pgvector.initialize-schema=false` (waits for `ingest-static` healthy). Creates only its own operational state tables (`dynamic_ingest_state`, `webhook_retry_queue`) idempotently.
- **Volume Mount:** `./resources:/app/resources:ro`

---

## Numeric & Security Targets
| Parameter | Default | Env Variable |
|---|---|---|
| Embedding Dimensions | `1536` | `EMBEDDING_DIMENSIONS` |
| Chunk Size / Overlap | `512` / `64` | `APP_CHUNK_SIZE` / `APP_CHUNK_OVERLAP` |
| File Watcher Interval | `30` sec | `APP_WATCHER_INTERVAL_SEC` |
| Watched Resources Dir | `/app/resources` | `APP_WATCHER_RESOURCES_DIR` |
| Max Remote Payload Size | `20971520` (20 MB) | `APP_INGEST_MAX_BYTES` |
| Remote Fetch Timeout | `10000` ms | `APP_INGEST_TIMEOUT_MS` |
| Block Private Networks | `true` | `APP_INGEST_BLOCK_PRIVATE_NETWORKS` |
| Allowed Domains | `kubernetes.io,github.com,raw.githubusercontent.com,cncf.io` | `APP_INGEST_ALLOWED_DOMAINS` |
| Webhook HMAC Secret | *(required)* | `VENDOR_WEBHOOK_SECRET` |

---

## Chunk Metadata Contract
Every chunk indexed into `vector_store` carries all 7 metadata keys aligned with `ingest-static`, `retrieval-pre`, and `retrieval-post`:
- `doc_id` (String)
- `version` (int, starting at `1`)
- `source` (String — final HTTPS URL or watched filename)
- `page` (int — real PDF page number, or `1` for HTML/Markdown/text)
- `chunk_index` (int, 0-based sequential index per document version)
- `checksum` (String — SHA-256 hex digest of raw bytes)
- `is_latest` (boolean — `true` on active version, `false` on historical versions)

---

## Story 2 Tasks & Implemented Behavior

### Task 1 — Web, Remote PDF & GitHub Ingestion (`POST /ingest/dynamic/url`)
- Fetches HTML pages (`kubernetes.io`, `cncf.io`), remote PDFs, and GitHub Markdown docs (`github.com`, `raw.githubusercontent.com`).
- Automatically rewrites `https://github.com/{owner}/{repo}/blob/{ref}/{path}` URLs to `https://raw.githubusercontent.com/{owner}/{repo}/{ref}/{path}` before SSRF validation and fetching.
- Strips HTML boilerplate (`<nav>`, `<footer>`, `<script>`, `<style>`, `<aside>`, `<noscript>`, `<form>`, cookie banners, ads, sidebars, breadcrumbs) using `jsoup` and strips YAML frontmatter / HTML comments from Markdown.
- Enforces SSRF protection (`APP_INGEST_BLOCK_PRIVATE_NETWORKS=true`) before opening any outbound HTTP connection **and on every HTTP redirect hop** (automatic redirects disabled, max 5 hops):
  - Rejects non-`https://` schemes and embedded credentials (`403 Forbidden`).
  - Enforces `APP_INGEST_ALLOWED_DOMAINS` (exact host or subdomain match, e.g., `www.cncf.io`, `v1-30.docs.kubernetes.io`).
  - Resolves all DNS records prior to connecting and rejects loopback (`127.0.0.0/8`, `::1`), private (`10/8`, `172.16/12`, `192.168/16`, `fc00::/7`), link-local (`169.254/16`, `fe80::/10`), CGNAT (`100.64/10`), unspecified, multicast, and IPv4-mapped internal IPv6 addresses.
- Enforces streaming payload ceiling (`APP_INGEST_MAX_BYTES=20971520` → `413 Payload Too Large`) and connect/request timeout (`APP_INGEST_TIMEOUT_MS=10000` → `504 Gateway Timeout`).

### Task 2 — Atomic Document Versioning (`is_latest`)
- Queries `vector_store` for existing versions of `doc_id` (interoperating cleanly with PDFs indexed by `ingest-static`).
- Identical SHA-256 checksum → returns `200 OK` with `status: "SKIPPED"` (no re-embedding).
- Different checksum → writes new chunks with `version = maxVersion + 1` and `is_latest=false` first, then executes an atomic `@Transactional` cutover marking prior `is_latest=true` rows as `is_latest=false` and promoting the new version to `is_latest=true` (preserving full history).
- Mid-ingestion failures delete only partial rows of the failed target version; previous versions remain intact. Interrupted `CLAIMED` rows are recovered on startup.
- Version history inspection endpoint: `GET /ingest/dynamic/versions/{docId}`.

### Task 3 — 30-Second File Watcher & HMAC-Verified Webhook
- **File Watcher (`ResourceDirectoryWatcher`):** `@Scheduled(fixedDelayString = "${app.watcher.interval-sec:30}000")` scans `APP_WATCHER_RESOURCES_DIR` for `.pdf`, `.md`, `.markdown`, `.txt`, `.html`, `.htm` files using `ingest-static`'s `doc_id` convention:
  - Unchanged PDFs already indexed by `ingest-static` → skipped.
  - Modified files → versioned (`version = oldVersion + 1`, prior chunks marked `is_latest=false`).
  - New files → ingested as `version = 1`.
- **Webhook (`POST /ingest/dynamic/webhook`):**
  - Verifies `X-Hub-Signature-256` (`sha256=<hex>`) HMAC-SHA256 against `VENDOR_WEBHOOK_SECRET` using constant-time `MessageDigest.isEqual` (`401 Unauthorized` on missing/invalid signature or unconfigured secret).
  - Supports both custom JSON (`{"url": "https://...", "docId": "..."}` / `{"urls": [...]}`) and GitHub `push` webhook payloads (filtering added/modified docs to `raw.githubusercontent.com` URLs).
  - Persists jobs to PostgreSQL `webhook_retry_queue`, returns `202 Accepted`, dispatches asynchronously via bounded executor, and retries transient failures with exponential backoff (up to 3 attempts); permanent errors (SSRF blocks, invalid URLs, 413) move directly to `DEAD_LETTER`.

---

## API Usage (via `api-gateway` on `:8080`)

### 1. Ingest a Remote Web Page, GitHub Doc, or PDF
```bash
curl -X POST http://localhost:8080/ingest-dynamic/ingest/dynamic/url \
  -H "Content-Type: application/json" \
  -d '{"url":"https://kubernetes.io/docs/reference/access-authn-authz/rbac/","docId":"k8s-rbac-official"}'
```

### 2. Inspect Version History of a Document
```bash
curl http://localhost:8080/ingest-dynamic/ingest/dynamic/versions/k8s-rbac-official
```

### 3. Trigger HMAC-Signed Webhook
```bash
BODY='{"url":"https://github.com/kubernetes/website/blob/main/content/en/docs/concepts/overview/_index.md","docId":"k8s-overview"}'
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$VENDOR_WEBHOOK_SECRET" -hex | awk '{print $2}')"

curl -X POST http://localhost:8080/ingest-dynamic/ingest/dynamic/webhook \
  -H "Content-Type: application/json" \
  -H "X-Hub-Signature-256: $SIG" \
  -d "$BODY"
```

---

## Definition of Done
- Success + failure/SSRF/HMAC/versioning/watcher/retry automated tests (`mvn test -pl ingest-dynamic`).
- Structured `SLF4J` logging for SSRF blocks, HMAC failures, version increments, watcher events, and retries.
- Exposed actuator health endpoint at `/actuator/health`.
