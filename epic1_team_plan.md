# Epic 1 — Team Allocation & Execution Plan (Cloud, DevOps & Kubernetes RAG)

## 1. Domain & Technical Baseline
* **Domain:** Cloud, DevOps & Kubernetes (CNCF whitepapers, Kubernetes official documentation, Docker/Kubernetes GitHub documentation).
* **Stack:** Java 21, Spring Boot 4.0.7, Spring Cloud 2025.1.2, Spring AI 2.0.0, PostgreSQL (`pgvector`), Netflix Eureka, Spring Cloud Gateway.
* **AI Models (Cost-Optimized):**
  * **Embeddings:** Google AI Studio (`gemini-embedding-001`, `1536` dimensions).
  * **Chat / Query Expansion:** Google AI Studio (`gemini-3.5-flash-lite` or active Flash Lite model).
  * **Reranker:** Cohere Rerank API (`rerank-v3.5`, with graceful fallback).

---

## 2. 3-Person Team Allocation (Professor's Rule Compliant)
**Rule for 3-person teams:** All four `Task 1`s (S1.1, S2.1, S3.1, S4.1) must be covered, and remaining tasks must form coherent, unsplit Stories (`1 + 3 = 4`, `4`, `4` = 12 tasks total, zero split ownership within a Story).

| Team Member | Assigned Scope | Microservice(s) | Branch Name | Total Tasks |
| :--- | :--- | :--- | :--- | :--- |
| **Laurențiu (PM & Architect)** | **Infra** + **Story 1 (Task 1)** + **Story 2 (Tasks 1, 2, 3)** | `eureka-server`, `api-gateway`, `ingest-static` (T1), `ingest-dynamic` (T1–T3) | `feat/ingest-static-and-dynamic` | 4 Tasks |
| **Alex** | **Story 3 (Tasks 1, 2, 3, 4)** — Complete Story | `retrieval-pre` | `feat/retrieval-pre` | 4 Tasks |
| **Antonio** | **Story 4 (Tasks 1, 2, 3, 4)** — Complete Story | `retrieval-post` | `feat/retrieval-post` | 4 Tasks |

*(Note: Alex and Antonio may swap Story 3 and Story 4 between themselves before starting, provided each owns their Story completely without splitting tasks.)*

---

## 3. Detailed Task Breakdown per Member

### Laurențiu — Infrastructure + Story 1 (Task 1) + Story 2 (Tasks 1, 2, 3)
* **Infrastructure (Done):** Multi-module Maven setup, `docker-compose.yml`, Eureka discovery, API Gateway routing, `pgvector` schema ownership (`ingest-static`).
* **Story 1 (`ingest-static`) — Task 1: PDF Ingestion (`POST /ingest/static`):**
  * Parse local PDFs (including tables & multi-column layouts) from `./resources`.
  * Structure-aware chunking (`APP_CHUNK_SIZE=512`, `APP_CHUNK_OVERLAP=64`).
  * Store vectors + metadata (`doc_id`, `version`, `source`, `page`, `chunk_index`, `checksum`, `is_latest=true`) in `pgvector` idempotently.
* **Story 2 (`ingest-dynamic`) — Task 1: Web & Repo Ingestion (`POST /ingest/dynamic/url`):**
  * Fetch HTML pages (`kubernetes.io`, `cncf.io`), remote PDFs, and GitHub Markdown docs (`github.com`, `raw.githubusercontent.com`).
  * Strip HTML boilerplate (`<nav>`, `<footer>`, `<script>`, ads) using Jsoup.
  * Enforce SSRF protection (`APP_INGEST_BLOCK_PRIVATE_NETWORKS=true`, DNS check blocking loopback/private/link-local IPs, HTTPS-only, domain allowlist) and payload/timeout limits (`20MB`, `10s`).
* **Story 2 (`ingest-dynamic`) — Task 2: Document Versioning:**
  * Track document versions (`version` increment, `is_latest=true` on current chunks, `is_latest=false` on historical chunks) without deleting history.
* **Story 2 (`ingest-dynamic`) — Task 3: File Watcher & Webhook:**
  * Scheduled watcher (`APP_WATCHER_INTERVAL_SEC=30`) detecting new/modified files in `./resources`.
  * `POST /ingest/dynamic/webhook` verifying `X-Hub-Signature-256` HMAC using `VENDOR_WEBHOOK_SECRET` (rejecting invalid signatures with `401`) and queuing updates asynchronously.

---

### Alex — Story 3 Complete (`retrieval-pre`: Tasks 1, 2, 3, 4)
* **Task 1 — Safe Metadata Filtering:**
  * Parse URL query filter params into a typed `ValidatedFilter` object (never concatenate raw strings).
  * Allowlist fields (`doc_id`, `version`, `source`, `page`, `is_latest`) and operators (`EQ`, `IN`, `AND`, `OR`). Return `400 Bad Request` on invalid fields/operators; default to `is_latest=true` when empty.
  * Deliver Postman collection (`retrieval-pre/postman/retrieval-pre-filters.postman_collection.json`).
* **Task 2 — Query Normalization:**
  * Precompiled (`static final Pattern`), ReDoS-safe regex pipeline stripping polite prefixes and trailing punctuation while preserving/logging the raw query.
  * Parameterized JUnit suite (`>= 15` cases) + adversarial ReDoS timing test (`< 50ms`).
* **Task 3 — Acronym & Domain Expansion:**
  * Load domain acronyms externally from `classpath:acronyms.yml` (K8s, CNI, RBAC, PVC, HPA, CRD, etc.) with hot-reload or clean config binding.
* **Task 4 — Multi-Query Expander & RRF Fusion:**
  * Generate `APP_MULTI_QUERY_VARIANTS=3` query variants via LLM (`APP_MULTI_QUERY_TIMEOUT_MS=1500` timeout with fallback to single query) and merge candidate results using Reciprocal Rank Fusion (RRF).

---

### Antonio — Story 4 Complete (`retrieval-post`: Tasks 1, 2, 3, 4)
* **Task 1 — Cross-Encoder Reranking:**
  * Rerank top `APP_RERANK_TOP_N=20` candidates down to `APP_RERANK_TOP_K=5` via Cohere Rerank API (`rerank-v3.5`), falling back gracefully to vector similarity order on timeout/error/missing key.
* **Task 2 — Neighbor Stitching & Token Budget:**
  * Fetch adjacent chunks (`chunk_index - 1`, `chunk_index + 1`) for top results within `APP_TOKEN_BUDGET=2500` tokens, deduplicating overlapping text.
* **Task 3 — Mandatory Citations & `"I don’t know."` Guardrail:**
  * Enforce inline/structured citations (`source`, `page`, `chunk_index`) in generated answers.
  * If top retrieval/rerank score is below `APP_SIMILARITY_THRESHOLD=0.65`, bypass LLM generation and return the exact string: `"I don’t know."`.
* **Task 4 — Evaluation Harness:**
  * Build evaluation runner executing `classpath:eval/golden-set.json` (`>= 35` Cloud/Kubernetes questions, including `10` out-of-scope questions).
  * Compute and report `Recall@5` (target `>= 0.80`), `MRR` (target `>= 0.70`), and `Faithfulness` (target `>= 0.90`).

---

## 4. Numeric Targets Summary
* Chunking: `512` tokens size / `64` tokens overlap
* Watcher interval: `30s` | Max ingest size: `20MB` | Timeout: `10s`
* Multi-query: `3` variants (`1500ms` timeout)
* Reranking: Top `20` $\rightarrow$ Top `5` | Context budget: `2500` tokens
* Fallback threshold: `0.65` $\rightarrow$ `"I don’t know."`
* Evaluation: `Recall@5 >= 0.80`, `MRR >= 0.70`, `Faithfulness >= 0.90` (`>= 35` questions, `10` out-of-scope)