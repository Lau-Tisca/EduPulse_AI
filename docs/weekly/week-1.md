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
  - *(To be updated by Antonio)*
- **In Progress / To Do:**
  - Implement **Story 4 Complete (`retrieval-post`: Tasks 1, 2, 3, 4)** per `retrieval-post/SPEC.md` & `epic1_team_plan.md`:
    - Task 1: Cross-encoder reranking via Cohere Rerank API (`rerank-v3.5`, `20 -> 5`) with graceful fallback to vector order when `COHERE_API_KEY` is missing or fails.
    - Task 2: Neighbor stitching (`chunk_index ± 1`) with overlap deduplication within `APP_TOKEN_BUDGET=2500`.
    - Task 3: Mandatory inline citations (`source`, `page`, `chunk_index`) and exact `"I don’t know."` fallback when top score `< 0.65`.
    - Task 4: Evaluation harness executing `classpath:eval/golden-set.json` (`>= 35` Cloud/K8s questions including `10` out-of-scope; targets: `Recall@5 >= 0.80`, `MRR >= 0.70`, `Faithfulness >= 0.90`).
    - Automated unit & integration tests covering success and failure/fallback paths (`mvn test -pl retrieval-post`).
- **Blockers:**
  - Waiting on `COHERE_API_KEY` / `LLM_API_KEY` for live evaluation runs (sample documents from Phase 4 are now indexed in `pgvector`).
