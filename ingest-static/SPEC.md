# `ingest-static` — Technical Specification (Story 1)

- **Owner:** Laurențiu (PM & Architect) — **Branch:** `feat/ingest-static-and-dynamic`
- **Epic 1 Scope:** **Story 1 — Task 1** (per `epic1_team_plan.md` & `plan.md` Phase 4)
- **Service Name:** `ingest-static` (`server.port: ${SERVER_PORT:8081}`)
- **Schema Role:** **Sole owner** of `pgvector` schema initialization (`spring.ai.vectorstore.pgvector.initialize-schema=true`). All other RAG services MUST set `initialize-schema=false`.
- **Volume Mount:** `./resources:/app/resources:ro`

---

## Numeric & Infrastructure Targets
| Parameter | Default | Env Variable |
|---|---|---|
| Embedding Dimensions | `1536` | `EMBEDDING_DIMENSIONS` |
| Embedding Model | `gemini-embedding-001` | `LLM_EMBEDDING_MODEL` |
| Chunk Size (tokens) | `512` | `APP_CHUNK_SIZE` |
| Chunk Overlap (tokens) | `64` | `APP_CHUNK_OVERLAP` |
| Static Resources Dir | `/app/resources` | `APP_STATIC_RESOURCES_DIR` |

---

## Story 1 Tasks & Acceptance Criteria

### Task 1 — PDF Ingestion (`POST /ingest/static`) *(Epic 1 Deliverable — Phase 4)*
- Read local PDFs from `./resources` (or uploaded file path), handling multi-column layouts and tables via Spring AI PDF/Tika document readers.
- Split text using `TokenTextSplitter` configured with `APP_CHUNK_SIZE=512` and `APP_CHUNK_OVERLAP=64`.
- Attach full metadata to every `Document` chunk:
  - `doc_id`, `version` (`1`), `source`, `page`, `chunk_index`, `checksum` (SHA-256 of file bytes), `is_latest=true`.
- **Idempotency:** If a document with the same `doc_id` and `checksum` already exists in `vector_store`, skip re-embedding and return `200 OK` (no duplicate vectors).
- **Interrupted ingestion recovery:** On startup (`ApplicationReadyEvent`), any `static_ingest_state` row left in `CLAIMED` (service stopped mid-ingestion) is reset to `FAILED` and logged as `WARN`. The next `POST /ingest/static` re-claims the document, deletes its partial vectors, and re-ingests it instead of returning `SKIPPED`.

### Backlog / Optional Future Extensions (Out of Epic 1 4-Task Allocation)
- **Task 2 — Atomic Update Process:** Transactional delete + insert on checksum mismatch.
- **Task 3 — Delete Process (`DELETE /ingest/static/{docId}`):** Delete chunks + zero-orphan check (`200`/`404`, never `500`).
- **Task 4 — Ingestion Manifest & Re-Index (`GET /ingest/static/manifest`, `/reconcile`, `POST /reindex`):** Persist `ingestion_manifest` table and verify zero drift.

---

## API Usage (via `api-gateway` on `:8080`)

### 1. Ingest all PDFs from `./resources` (`/app/resources`)
```bash
curl -X POST http://localhost:8080/ingest-static/ingest/static \
  -H "Content-Type: application/json" \
  -d '{}'
```

### 2. Ingest a specific PDF from `./resources`
```bash
curl -X POST http://localhost:8080/ingest-static/ingest/static \
  -H "Content-Type: application/json" \
  -d '{"filePath":"kubernetes-architecture.pdf","docId":"k8s-arch"}'
```

### 3. Upload a PDF directly (`multipart/form-data`)
```bash
curl -X POST http://localhost:8080/ingest-static/ingest/static/upload \
  -F "file=@./resources/kubernetes-architecture.pdf" \
  -F "docId=k8s-arch"
```

---

## Definition of Done
- Success + failure/edge unit & integration tests for every task (`mvn test -pl ingest-static`).
- Structured `SLF4J` logging for ingestion lifecycle, checksum skips, rollbacks, and deletions.
- Exposed actuator health endpoint at `/actuator/health`.
