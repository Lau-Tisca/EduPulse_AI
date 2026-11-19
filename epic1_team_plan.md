# Team Plan & Task Breakdown — Epic 1: RAG

* **Epic 1 Deadline:** December 7, 2026
* **Team:** Laurențiu (PM & System Architect), Alex (Security & Dynamic Ingestion), Antonio (AI, RAG Quality & Evaluation)
* **Data Domain:** Cloud, DevOps & Kubernetes (CNCF/AWS PDF's, docs from kubernetes.io and GitHub repos)
* **Tech Stack & Models:**
  * **Backend & Infra:** Java 21, Spring Boot `4.0.7`, Spring Cloud `2025.1.2`, Spring AI `2.0.0`, PostgreSQL + `pgvector`, Eureka Server, Spring Cloud Gateway, Docker Compose.
  * **Embeddings:** Google AI Studio — `gemini-embedding-001` configured to **`1536` dimensions** (`EMBEDDING_DIMENSIONS=1536`, required for compatibility with `pgvector`'s HNSW index, which has a hard 2000-dimension limit).
  * **LLM Generation & Query Expansion:** Google AI Studio — `gemini-3.5-flash-lite` (low latency, minimal operating cost, generous Free Tier rate limits).
  * **Reranker:** **Cohere Rerank (`rerank-v3.5`)** via REST API (pinned model version, 1,000 free requests/month on trial/free tier, zero additional Docker RAM overhead).

---

## 1. Architecture & Module Structure

The project is structured as a multi-module microservices system inside a single repository, orchestrated via `docker-compose.yml`:

1. `eureka-server` — Service Discovery (port `8761`, bound to `localhost` only).
2. `api-gateway` — Single public entry point (`8080`), dynamically routing via Eureka (`/ingest-static/**`, `/ingest-dynamic/**`, `/retrieval-pre/**`, `/retrieval-post/**`).
3. `ingest-static` — Local PDF ingestion, checksum-based updates, deletion, and manifest management (the only service with `initialize-schema: true` for the `vector_store` table).
4. `ingest-dynamic` — Web/remote/repo ingestion, document versioning, file watcher, webhooks, and SSRF protection.
5. `retrieval-pre` — Metadata filter validation, query normalization, acronym expansion, and multi-query expansion.
6. `retrieval-post` — Reranking, neighbor stitching, citations + grounding, and evaluation harness.

### Baseline Definition of Done (Mandatory for Every Task)
No task is considered "Done" unless all of the following criteria are met:
* Code is peer-reviewed via Pull Request and merged into the `main` branch.
* Automated tests pass: **at least 1 test for the success path** and **at least 1 test for the failure path** per task.
* Zero hardcoded secrets (API keys, passwords) in the repository; all configuration variables are documented in `.env.example`.
* Errors and key events are logged cleanly.
* `README.md` / module documentation is updated.

---

## 2. Role & Responsibility Breakdown

### A. Laurențiu — Project Manager, System Architect & Data Plumbing
**Owned Modules:** `eureka-server`, `api-gateway`, `docker-compose.yml`, **`ingest-static` (Story 1 complete)**, and **`retrieval-pre` (Story 3: Task 1 & Task 2)**.

#### Concrete Tasks:
1. **Infrastructure & Orchestration:**
   * Configure `docker-compose.yml` with strict startup ordering: `pgvector` and `eureka-server` start first and report `healthy` $\rightarrow$ `ingest-static` creates the `vector_store` schema (`1536` dims) $\rightarrow$ the remaining services and `api-gateway` start afterward.
   * Configure dynamic routes in `api-gateway` using the `spring.cloud.gateway.server.webflux.*` property prefix and enable Actuator healthchecks across all 6 modules.
2. **Story 1 — Task 1 (`PDF ingestion`):**
   * Parse Cybersecurity PDFs (e.g., *OWASP ASVS* for complex structured tables and *NIST Cybersecurity Framework* for multi-column layouts).
   * Structure-aware chunking with configurable parameters (`APP_CHUNK_SIZE=512`, `APP_CHUNK_OVERLAP=64`) and storage in `pgvector` with the full metadata schema (`doc_id`, `version`, `source`, `page`, `chunk_index`, `checksum`). Re-running ingestion on the same file must create zero duplicates.
3. **Story 1 — Task 2 (`Update process`):**
   * Detect changed files via SHA-256 checksum comparison; unchanged files are skipped.
   * Atomic replacement of old chunks (`@Transactional`), ensuring queries never see a mix of old and new content or empty results; if an update fails mid-way, the previous version remains intact.
4. **Story 1 — Task 3 (`Delete process`):**
   * Delete all chunks by `doc_id` + run an orphan check confirming zero leftover vectors. Deleting a non-existent `doc_id` executes safely and returns a clear message without a 500 error.
5. **Story 1 — Task 4 (`Manifest and re-index`):**
   * Maintain an ingestion manifest (`doc_id`, `checksum`, `version`, `timestamp`, `embedding_model`, `chunk_count`), provide a reconciliation check confirming zero drift between the manifest and `pgvector`, and support full re-indexing when switching embedding models.
6. **Story 3 — Task 1 (`Safe metadata filtering`) & Task 2 (`Query normalization`):**
   * Parse URL parameters into a validated filter object with a strict whitelist of fields (`doc_id`, `version`, `source`, `page`, `is_latest`) and operators (`AND`/`OR`), returning `400 Bad Request` listing valid fields on invalid input (raw strings never reach the database). Include a committed Postman collection.
   * Implement query normalization with ordered, precompiled, idempotent, ReDoS-safe regex rules (tested against adversarial input `< 50ms`), stripping polite prefixes and trailing question marks (covered by at least 15 test cases), while preserving the original query in logs.

#### Key Concepts & Technologies to Master:
* **Spring Cloud Gateway (WebFlux) + Netflix Eureka:** Service discovery and service-name-based dynamic routing.
* **Spring AI `PgVectorStore` & `DocumentReader`:** Vector dimension configuration (`1536`), `initialize-schema: true` isolated to a single service, and clean text extraction from tables/multi-column PDFs (Apache Tika / PDFBox).
* **SQL Transactions (`@Transactional`) & JDBC:** Guaranteeing atomic deletion of stale chunks and insertion of updated chunks in PostgreSQL.
* **Regex Security (ReDoS):** Preventing catastrophic backtracking in Java regular expressions (`java.util.regex.Pattern` precompiled).

---

### B. Alex — Security & Dynamic Ingestion Engineer
**Owned Modules:** **`ingest-dynamic` (Story 2 complete)** + input-level security hardening.

#### Concrete Tasks:
1. **Story 2 — Task 1 (`Internet ingestion`):**
   * Automated ingestion for:
     * **HTML Web Page:** e.g., *OWASP SSRF Prevention Cheat Sheet* (`cheatsheetseries.owasp.org`).
     * **Remote PDF (via URL):** e.g., an official *OWASP Top 10 / Testing Guide* PDF.
     * **Third-Party Repository:** The `/cheatsheets` directory from the public `OWASP/CheatSheetSeries` GitHub repository.
   * Strip boilerplate (navigation bars, footers, cookie banners) and store `source_url` and `fetch_date` in metadata. If one source in a batch fails, log the error without halting the rest of the batch.
2. **Story 2 — Task 2 (`Versioning`):**
   * When ingesting a new version of an existing document, keep old chunks marked with `is_latest=false` and `valid_from`.
   * Default retrieval queries return only `is_latest=true`, while queries specifying an explicit version parameter return the older content.
3. **Story 2 — Task 3 (`Automation`):**
   * **File Watcher:** Detect added, modified, and deleted files in the mounted directory within **30 seconds** and trigger ingestion.
   * **Webhook Receiver:** Secure endpoint verifying vendor HMAC signatures (rejecting invalid requests with `401 Unauthorized`), deduplicating repeated events, and using an async retry queue with exponential backoff for failed jobs.
4. **Story 2 — Task 4 (`Security & SSRF Protection`):**
   * Block requests targeting `localhost`, private IP ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), and cloud metadata endpoints (`169.254.169.254`).
   * Enforce a strict domain allowlist (`cheatsheetseries.owasp.org`, `owasp.org`, `raw.githubusercontent.com`, `github.com`), `HTTPS-only` rule, connection/read timeouts, max file size limits (e.g., `20MB`), and `Content-Type` validation.
   * Sanitize ingested HTML content (stripping `<script>` tags and active content) + commit a documented suite of negative tests proving every security control.

#### Key Concepts & Technologies to Master:
* **HTML Parsing & Sanitization:** **Jsoup** library (extracting main content, removing `<nav>`, `<footer>`, `<script>`, and XSS sanitization).
* **SSRF (Server-Side Request Forgery) Prevention:** Resolving DNS prior to connection and inspecting IP addresses via `java.net.InetAddress` (`isLoopbackAddress()`, `isSiteLocalAddress()`, `isLinkLocalAddress()`, `isAnyLocalAddress()`), plus disabling automatic HTTP redirects to unapproved hosts.
* **HMAC Signature Verification:** Computing `HmacSHA256` hashes in Java (`javax.crypto.Mac`) using `VENDOR_WEBHOOK_SECRET` and performing constant-time comparison (`MessageDigest.isEqual`).
* **File Watching & Async Retry:** `java.nio.file.WatchService` or Spring `@Scheduled` polling, combined with async retry queues with backoff.

---

### C. Antonio — AI, RAG Quality & Evaluation Engineer
**Owned Modules:** **`retrieval-post` (Story 4 complete)** and **`retrieval-pre` (Story 3: Task 3 & Task 4)**.

#### Concrete Tasks:
1. **Story 3 — Task 3 (`Acronym and synonym expansion`):**
   * Load the acronym dictionary from an external editable file (`classpath:acronyms.yml`) populated with domain terminology (e.g., `SSRF`, `CSRF`, `XSS`, `OWASP`, `ASVS`, `JWT`, `RBAC`, `HMAC`, `IDOR`, `SQLi`).
   * Append the full form alongside the acronym in the query while strictly respecting word boundaries (`\bACRONYM\b`). Zero matches pass the original query through unchanged.
2. **Story 3 — Task 4 (`Multi-query expander`):**
   * Generate **3 query variants** using `gemini-3.5-flash-lite`, execute searches, and merge results via **Reciprocal Rank Fusion (RRF)**.
   * Measure and log added latency and token cost against a configured cap, falling back automatically to the original query on failure or timeout. Report comparative `Recall@5` with vs. without the expander.
3. **Story 4 — Task 1 (`Reranker`):**
   * Retrieve a wider candidate pool (`top-N = 20`) from `pgvector` and rerank down to `top-k = 5` using **Cohere Rerank (`rerank-v3.5`)** (pinned version).
   * Document added latency, demonstrate measured improvement in `MRR` or `Recall@5` on the golden set, and fall back to the original order if the reranker fails.
4. **Story 4 — Task 2 (`Neighbor stitching`):**
   * Merge adjacent chunks by `chunk_index` while deduplicating overlapping text.
   * Enforce a strict token budget (`APP_TOKEN_BUDGET = 2500`) and never stitch across document boundaries (`doc_id`). Include unit tests for the first chunk, last chunk, and single-chunk documents.
5. **Story 4 — Task 3 (`Citations and grounding`):**
   * Format every context block with a structured header (`[Source: <source> | Version: <version> | Page: <page>]`).
   * Enforce a grounded system prompt instructing `gemini-3.5-flash-lite` to answer strictly from the provided context and cite sources; below the similarity threshold (`APP_SIMILARITY_THRESHOLD = 0.65`), return `"I don’t know."`. Justify the threshold with evaluation data and verify refusal across **10 out-of-scope questions**.
6. **Story 4 — Task 4 (`Evaluation harness`):**
   * Build `classpath:eval/golden-set.json` containing at least **35 question-and-source pairs** from the OWASP/NIST dataset (25 answerable questions + 10 out-of-scope unanswerable questions).
   * Create a single-command runner that computes and reports `Recall@5` (target `>= 0.80`), `MRR` (target `>= 0.70`), and a `Faithfulness` check (target `>= 0.90`), committing a baseline report to the repository for pre-merge regression runs.

#### Key Concepts & Technologies to Master:
* **Spring AI `ChatClient` & Advisors:** Building pre-retrieval and post-retrieval pipelines, structured LLM calls, and timeout handling.
* **Cohere Rerank API (`rerank-v3.5`):** Lightweight REST integration (`RestClient`) sending `query` + top-20 candidate texts and parsing reordered indices with `relevance_score`.
* **Information Retrieval Metrics:** `Recall@k`, `MRR` (Mean Reciprocal Rank), and `Faithfulness` evaluation (verifying 100% grounding in retrieved context).
* **Reciprocal Rank Fusion (RRF):** Rank aggregation formula across multiple query result lists ($\text{score}(d) = \sum \frac{1}{k + \text{rank}(d)}$, with $k=60$).

---

## 3. Execution Timeline (Weekly Sync)

* **Week 1 (By November 23) — Foundation & Core Ingestion:**
  * **Laurențiu:** Stand up Docker Compose (`pgvector` + `eureka-server`), `api-gateway`, and `ingest-static` (Tasks 1 & 2) so the `vector_store` table (`1536` dims) is created and populated with initial OWASP/NIST PDFs.
  * **Alex:** Build the web/remote/repo scraper in `ingest-dynamic` (Task 1) + SSRF security controls (Task 4).
  * **Antonio:** Build the `retrieval-post` skeleton (basic `pgvector` retrieval endpoint) + `acronyms.yml` expansion (Story 3, Task 3) and begin curating the 35 pairs for `golden-set.json`.
* **Week 2 (November 24 – November 30) — Advanced Pipeline Features:**
  * **Laurențiu:** Complete delete + orphan check + manifest/re-index (`ingest-static` Tasks 3 & 4) and metadata filters + ReDoS-safe query normalization (`retrieval-pre` Tasks 1 & 2).
  * **Alex:** Implement document versioning (`is_latest`), file watcher (`<= 30s`), and HMAC webhook receiver with retry queue (`ingest-dynamic` Tasks 2 & 3).
  * **Antonio:** Implement Multi-Query Expander (Story 3, Task 4), Cohere Reranker integration, and Neighbor Stitching (`retrieval-post` Tasks 1 & 2).
* **Week 3 (December 1 – December 7) — Grounding, Evaluation & Final Integration:**
  * **All:** End-to-end integration through `api-gateway` (`docker compose up --build`), run the evaluation harness (Antonio), complete missing success/failure tests across all tasks, update README/docs, and prepare the Product Owner demo.