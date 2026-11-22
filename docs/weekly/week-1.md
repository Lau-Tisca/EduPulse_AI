# Weekly Sync — Week 1 (2026-11-19)

**Sprint Focus:** Repository restructure (monolith -> 6 microservices), baseline build & Docker Compose infrastructure, module specifications, and aligned 3-person team plan (`epic1_team_plan.md`).

---

## Laurențiu (PM & Architect) — Branch: `feat/ingest-static-and-dynamic`
- **Done:**
  - Defined architecture rules, tech stack, and numeric targets in `AGENTS.md` and `epic1_team_plan.md` (Java 21, Spring Boot 4.0.7, Spring Cloud 2025.1.2, Spring AI 2.0.0, `pgvector` HNSW `1536` dims).
  - Executed Phases 0–1: converted monolith into multi-module Maven parent POM (`com.edupulse:edupulse-parent`) and scaffolded all 6 microservices (`eureka-server`, `api-gateway`, `ingest-static`, `ingest-dynamic`, `retrieval-pre`, `retrieval-post`) with passing `mvn clean test-compile` and `mvn test`.
  - Executed Phases 2–3: added multi-stage `Dockerfile` (with BuildKit `.m2` cache locking), `docker-compose.yml` (with configurable `POSTGRES_HOST_PORT` and strict startup/port rules), `.env.example`, module `SPEC.md` files, `docs/weekly/week-1.md`, and root `README.md`.
  - Verified full containerized stack startup: all 7 containers (`pgvector`, `eureka-server`, `ingest-static`, `ingest-dynamic`, `retrieval-pre`, `retrieval-post`, `api-gateway`) built and running `healthy`.
  - Executed Phase 4 — Story 1 Task 1 (`ingest-static`): implemented `POST /ingest/static` and `POST /ingest/static/upload`, `PagePdfDocumentReader` + Tika fallback with `\u0000` sanitization, `TokenTextSplitter` (`512` / `64`), full metadata (`doc_id`, `version`, `source`, `page`, `chunk_index`, `checksum`, `is_latest=true`), SHA-256 idempotency, and `VectorStoreBatchWriter` with HTTP `429` backoff retry. Verified unit tests (`mvn test -pl ingest-static`) and live Docker indexing of 4 Cloud/K8s PDFs (`385` chunks total in `pgvector` with idempotent re-runs returning `SKIPPED`).
- **In Progress:**
  - Phase 5 — Story 2 Tasks 1, 2, 3 (`ingest-dynamic`): web/GitHub/remote PDF ingestion with SSRF protection & Jsoup cleanup, `is_latest` versioning, 30s file watcher & HMAC `X-Hub-Signature-256` webhook (planned for next week).
- **Blockers:**
  - None.

---

## Alex — Branch: `feat/retrieval-pre`
- **Done:**
  - *(To be updated by Alex)*
- **In Progress / To Do:**
  - Implement **Story 3 Complete (`retrieval-pre`: Tasks 1, 2, 3, 4)** per `retrieval-pre/SPEC.md` & `epic1_team_plan.md`:
    - Task 1: Safe metadata filtering (`ValidatedFilter` with field/operator allowlists, `400 Bad Request` on invalid input, `is_latest=true` default, Postman collection at `retrieval-pre/postman/retrieval-pre-filters.postman_collection.json`).
    - Task 2: ReDoS-safe query normalization (`static final Pattern`, raw query logging, `>= 15` parameterized JUnit cases + adversarial `< 50ms` timing test).
    - Task 3: External acronym expansion loaded from `classpath:acronyms.yml` (`K8s`, `CNI`, `RBAC`, `PVC`, `HPA`, `CRD`, `OCI`, `TLS`).
    - Task 4: Multi-query expansion (`APP_MULTI_QUERY_VARIANTS=3`, `1500ms` timeout with fallback) and Reciprocal Rank Fusion (RRF).
    - Automated unit & integration tests covering success and failure/edge paths (`mvn test -pl retrieval-pre`).
- **Blockers:**
  - None (4 sample Cloud/K8s PDFs — `385` chunks — are now indexed in `pgvector` from Phase 4 for end-to-end integration tests).

---

## Antonio — Branch: `feat/retrieval-post`
- **Done:**
  - Local setup: `.env` (Postgres, Gemini, Cohere trial key, webhook secret), full Docker stack healthy, `pgvector` populated with the 4 sample PDFs (`385` chunks) and metadata verified (`doc_id`, `version`, `source`, `page`, `chunk_index`, `is_latest`).
  - Fixed `ingest-static` stuck-claim bug (pushed to `main` with Laurențiu's agreement, commit `4c008e1`): an ingestion interrupted mid-document left its `static_ingest_state` row in `CLAIMED`, so the document was `SKIPPED` forever with partial vectors. On startup, `CLAIMED` rows are now reset to `FAILED` and re-ingested on the next run (unit tests + verified live in Docker).
  - **Story 4 — Task 1 (Cross-encoder reranking):**
    - `CohereRerankClient` (`POST /v2/rerank`, `rerank-v3.5`) on a dedicated `RestClient` with connect/read timeout `APP_RERANK_TIMEOUT_MS=3000` (new variable, documented in `.env.example`).
    - `RerankService`: top `20` vector candidates → top `5`; graceful fallback to vector order with `fallbackReason` = `MISSING_API_KEY` / `API_ERROR` (4xx/5xx/429) / `TIMEOUT` / `INVALID_RESPONSE`.
    - `CandidateRetriever`: vector search for top `20` chunks filtered by `is_latest == true` (typed filter expression). Interim candidate source until `retrieval-pre` hands over its fused candidates.
    - Endpoint `POST /retrieval-post/rerank` with `400` (blank/too long query, malformed JSON) and `503` (vector search failure).
    - Verified live in Docker: Cohere reorders candidates; invalid key (`401`) → `API_ERROR` fallback; empty key → `MISSING_API_KEY` fallback.
  - **Story 4 — Task 2 (Neighbor stitching):**
    - `NeighborStitchingService`: expands hits with `chunk_index ± 1` (same `doc_id` + `version`), merges adjacent chunks into contiguous passages and removes the ingestion overlap text (no duplicated content).
    - `APP_TOKEN_BUDGET=2500` enforced with the same CL100K tokenizer as ingestion: hits first in rerank order, then neighbors; `totalTokens <= 2500` always. Neighbor lookup failure → hits only (`neighborsAvailable=false`).
    - Endpoint `POST /retrieval-post/context` (vector search → rerank → stitched passages with pages, chunk indexes and scores). Verified live: e.g. RBAC query → 4 passages, `2222/2500` tokens, overlap text appears once.
  - Tests: `mvn test -pl retrieval-post` → `46/46` passing (success + failure/fallback paths for every component). Endpoints and rules documented in `retrieval-post/SPEC.md`.
- **In Progress / To Do (Week 2):**
  - Task 3: mandatory citations (`source`, `page`, `chunk_index`) + exact `"I don’t know."` fallback; answer generation with `gemini-3.5-flash-lite` on top of `POST /context`.
  - Golden set `classpath:eval/golden-set.json` (`>= 35` Cloud/K8s questions incl. `10` out-of-scope).
  - Agree the candidate contract with Alex (`retrieval-pre` output → `retrieval-post` input: `doc_id`, `version`, `source`, `page`, `chunk_index`, `content`, score) and add an endpoint accepting fused candidates.
  - PR `feat/retrieval-post` → `main` with Laurențiu as reviewer.
- **Blockers / Risks:**
  - `APP_SIMILARITY_THRESHOLD=0.65` is too high for our scores. Live measurements: in-scope RBAC query → top `vectorScore 0.60`, top `rerankScore 0.33`; out-of-scope (cookie recipe) → `0.46` / `0.04`. With `0.65` even in-scope questions would return `"I don’t know."`. `rerankScore` separates in/out-of-scope much better than `vectorScore`, so Task 3 should gate on it (vector score only when Cohere falls back) and the threshold must be calibrated on the golden set (Week 3 calibration).
  - Corpus has no official Kubernetes docs yet (only CNCF/AWS whitepapers + kubectl reference), which lowers scores for core K8s questions (e.g. RBAC) — depends on `ingest-dynamic` (Week 2).
  - Token budget fills fast: chunks are ~400–500 tokens, so 5 hits use ~1700 of 2500 tokens and only ~2 neighbors fit; to revisit during calibration.
