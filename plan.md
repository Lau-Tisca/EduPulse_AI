# Execution Plan — Refactoring & Implementing Epic 1 (RAG)

> **Instructions for AI Agent:**
> Read `AGENTS.md` and `epic1_team_plan.md` first. Phases 0–3 (scaffolding, Docker Compose, and specs) are complete. Proceed with Phase 4 and Phase 5 strictly following the 3-person team allocation rules.

---

## Phase 0–3: Infrastructure & Scaffolding [COMPLETED]
* Multi-module Maven project (`eureka-server`, `api-gateway`, `ingest-static`, `ingest-dynamic`, `retrieval-pre`, `retrieval-post`) on Java 21, Spring Boot 4.0.7, Spring Cloud 2025.1.2, Spring AI 2.0.0.
* `docker-compose.yml`, multi-stage `Dockerfile`, `.env.example`, and `SPEC.md` files created.

---

## Phase 4: Implement Story 1 — Task 1 (`ingest-static` PDF Ingestion)
**Owner:** Laurențiu | **Branch:** `feat/ingest-static-and-dynamic`

**Goal:** Deliver working PDF ingestion so real Cloud/Kubernetes documents are indexed into `pgvector` for downstream retrieval testing.

1. **PDF Parsing & Structure-Aware Chunking (`POST /ingest/static`):**
   * Read PDFs from `./resources` (or uploaded file path) handling multi-column layouts and tables via Spring AI PDF/Tika document readers.
   * Split text using `TokenTextSplitter` configured with `APP_CHUNK_SIZE=512` and `APP_CHUNK_OVERLAP=64`.
   * Attach mandatory metadata to every chunk: `doc_id`, `version` (`1`), `source`, `page`, `chunk_index`, `checksum` (SHA-256 of file bytes), `is_latest=true`.
   * Ensure idempotency: if a document with the same `doc_id` and `checksum` already exists in `vector_store`, skip re-embedding and return `200 OK` (no duplicate vectors).
2. **Verification:**
   * Add unit/integration tests covering valid PDF ingestion (with table/multi-column text) and idempotent re-ingestion.
   * Run `mvn test -pl ingest-static` and ingest at least 2 sample Kubernetes/CNCF PDFs into running `pgvector`.

---

## Phase 5: Implement Story 2 — Tasks 1, 2, 3 (`ingest-dynamic`)
**Owner:** Laurențiu | **Branch:** `feat/ingest-static-and-dynamic`

**Goal:** Implement secure dynamic ingestion, versioning, file watching, and HMAC-verified webhooks.

1. **Task 1 — Web, Remote PDF & GitHub Ingestion (`POST /ingest/dynamic/url`):**
   * Enforce SSRF protection (`APP_INGEST_BLOCK_PRIVATE_NETWORKS=true`): resolve DNS before HTTP connection and block loopback (`127.0.0.0/8`, `::1`), private (`10/8`, `172.16/12`, `192.168/16`), link-local (`169.254/16`), and non-HTTPS schemes.
   * Enforce domain allowlist (`APP_INGEST_ALLOWED_DOMAINS`: `kubernetes.io,github.com,raw.githubusercontent.com,cncf.io`), max payload (`20MB`), and timeout (`10s`).
   * Clean HTML boilerplate (`nav`, `footer`, `script`, `style`, `aside`) with Jsoup; also support remote PDFs and GitHub raw Markdown docs.
2. **Task 2 — Document Versioning:**
   * When ingesting an updated version of an existing `source` / `doc_id` (different checksum), mark previous chunks as `is_latest=false` and insert new chunks with `version = oldVersion + 1` and `is_latest=true` (preserving history).
3. **Task 3 — File Watcher & Webhook:**
   * Implement `@Scheduled(fixedDelayString = "${APP_WATCHER_INTERVAL_SEC:30}000")` watcher monitoring `./resources` for new/modified files and triggering ingestion automatically.
   * Implement `POST /ingest/dynamic/webhook` verifying `X-Hub-Signature-256` HMAC-SHA256 against `VENDOR_WEBHOOK_SECRET` (returning `401 Unauthorized` on invalid/missing signature) and processing valid payloads asynchronously with retry support.
4. **Verification:**
   * Write unit/integration tests for SSRF blocking, domain allowlist, boilerplate stripping, version increment (`is_latest` flip), watcher detection, and HMAC signature validation (valid vs. invalid).
   * Run `mvn test -pl ingest-dynamic`.