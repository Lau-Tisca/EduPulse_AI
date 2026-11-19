# `ingest-static` — Technical Specification (Story 1)

- **Owner:** Laurențiu (PM & Architect)
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

### Task 1 — PDF Ingestion (`POST /ingest/static`)
- Parse local PDFs (including multi-column layouts and tables) via Spring AI PDF/Tika document readers.
- Perform structure-aware chunking (`APP_CHUNK_SIZE=512`, `APP_CHUNK_OVERLAP=64`).
- Attach full metadata to every `Document` chunk:
  - `doc_id`, `version`, `source`, `page`, `chunk_index`, `checksum` (SHA-256 of file content), `is_latest=true`.
- **Idempotency:** Re-running ingestion on an unchanged file produces zero duplicate chunks.

### Task 2 — Atomic Update Process
- Compare incoming file SHA-256 checksum against the manifest/store; skip unchanged files.
- Wrap deletion of old chunks and insertion of new chunks in a single database `@Transactional` boundary:
  - Concurrent readers never observe partial/empty intermediate state.
  - Any mid-update failure rolls back cleanly, leaving the previous document version intact.

### Task 3 — Delete Process (`DELETE /ingest/static/{docId}`)
- Delete all vector chunks matching `doc_id` (and update/clean manifest).
- Run a verification query confirming zero orphaned vectors remain for `doc_id`.
- Deleting a non-existent `docId` returns `200 OK` or `404 Not Found` with a structured JSON message (never `500`).

### Task 4 — Ingestion Manifest & Re-Index
- Persist an `ingestion_manifest` table in PostgreSQL tracking `doc_id`, `checksum`, `version`, `ingested_at`, `embedding_model`, and `chunk_count`.
- **`GET /ingest/static/manifest`:** List all tracked documents and their metadata.
- **`GET /ingest/static/reconcile`:** Verify zero drift between `ingestion_manifest.chunk_count` and `vector_store` counts.
- **`POST /ingest/static/reindex`:** Re-index all stored source files when switching embedding models.

---

## Definition of Done
- Success + failure/edge unit & integration tests for every task (`mvn test -pl ingest-static`).
- Structured `SLF4J` logging for ingestion lifecycle, checksum skips, rollbacks, and deletions.
- Exposed actuator health endpoint at `/actuator/health`.
