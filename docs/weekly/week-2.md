# Weekly Sync — Week 2 (2026-11-27)

**Sprint Focus:** Dynamic ingestion & versioning (`ingest-dynamic` Story 2 Tasks 1–3), acronym expansion & multi-query RRF (`retrieval-pre` Story 3 Tasks 3–4), citations, `"I don’t know."` guardrail & golden set (`retrieval-post` Story 4 Task 3), plus cross-module metadata contract alignment.

---

## Laurențiu (PM & Architect) — Branch: `feat/ingest-dynamic`
- **Done:**
  - **Story 2 — Task 1 (`ingest-dynamic` web, remote PDF & GitHub ingestion + SSRF protection):**
    - Implemented `SsrfGuard` + testable `DnsResolver`: enforces HTTPS-only, rejects embedded credentials, validates `APP_INGEST_ALLOWED_DOMAINS` (exact + subdomain match), and pre-resolves DNS to block loopback (`127/8`, `::1`), RFC 1918 private (`10/8`, `172.16/12`, `192.168/16`), link-local/cloud metadata (`169.254/16`, `fe80::/10`), CGNAT (`100.64/10`), IPv6 ULA (`fc00::/7`), multicast, and IPv4-mapped internal IPv6 addresses.
    - Implemented `GitHubUrlNormalizer` (auto-rewriting `github.com/.../blob/...` → `raw.githubusercontent.com/...`) and `RemoteContentFetcher` (`FollowRedirects.NEVER` with hop-by-hop SSRF re-validation up to 5 hops, `APP_INGEST_TIMEOUT_MS`, streaming `APP_INGEST_MAX_BYTES` ceiling).
    - Implemented Jsoup `HtmlContentParser` (boilerplate removal), `MarkdownContentParser` (frontmatter/comment cleanup), `PdfDocumentParser` (PagePdf + Tika fallback), and `POST /ingest/dynamic/url`.
  - **Story 2 — Task 2 (`is_latest` document versioning):**
    - Implemented `JdbcDocumentVersionRepository` + `DynamicIngestService`: writes all 7 metadata keys (`doc_id`, `version`, `source`, `page`, `chunk_index`, `checksum`, `is_latest`), writes new versions initially with `is_latest=false`, executes an atomic `@Transactional` cutover flipping prior chunks to `is_latest=false` and new chunks to `is_latest=true` (preserving history), rolls back only failed version rows on error, recovers interrupted claims on startup, and exposes `GET /ingest/dynamic/versions/{docId}`.
  - **Story 2 — Task 3 (30s file watcher & HMAC webhook with PG retry queue):**
    - Implemented `ResourceDirectoryWatcher` (`@Scheduled` 30s) synced against `vector_store` using `ingest-static`'s `doc_id` convention (skips unchanged static PDFs, versions modified files, auto-ingests new `.pdf`/`.md`/`.txt`/`.html` files).
    - Implemented `HmacSignatureVerifier` (constant-time `X-Hub-Signature-256` verification → `401` on invalid/missing), `WebhookPayloadParser` (custom JSON + GitHub push payloads), `JdbcWebhookRetryRepository` (`webhook_retry_queue` table), `WebhookProcessingService` (async dispatch + exponential backoff retry + `DEAD_LETTER`), and `POST /ingest/dynamic/webhook` (`202 Accepted`).
  - Updated `ingest-dynamic/SPEC.md` and verified automated tests (`mvn -B -o test -pl ingest-dynamic` → `62/62` passing).
  - Completed live Docker Compose smoke test through `api-gateway` (`:8080`): verified health `UP`, SSRF `403` blocks (cloud metadata & disallowed domain), validation `400`, real `kubernetes.io` HTML redirect + Jsoup scraping, GitHub `/blob/` → `raw.githubusercontent.com` auto-rewrite, checksum idempotency (`SKIPPED`), atomic `is_latest` versioning (`v1` `is_latest=false` + `v2` `is_latest=true` verified via `GET /versions/{docId}` and `pgvector`), HMAC `401`/`202` + async `webhook_retry_queue` `COMPLETED`, and 30s file watcher skipping the 4 unchanged `ingest-static` PDFs (`385` chunks untouched).
- **In Progress:**
  - Open PR and merge `feat/ingest-dynamic` into `main`.
  - Review Alex's and Antonio's Week 2 PRs.
- **Blockers:**
  - None.

---

## Cross-Module Audit & Alignment Notes (PM Review of Week 1 PRs)
- **`retrieval-pre` (Alex) — Action items for Week 2 PR:**
  1. Convert `ValidatedFilter` string values for `is_latest` (`Boolean`), `version` (`Integer`), and `page` (`Integer`) before constructing Spring AI `Filter.Expression` against `pgvector` JSON metadata.
  2. Decide whether non-empty filter requests that omit both `is_latest` and `version` should auto-append `is_latest=true` so historical versions created by `ingest-dynamic` aren't mixed into results.
  3. Update `retrieval-pre/postman/retrieval-pre-filters.postman_collection.json` (`source=kubectl-quick-reference` → `doc_id=kubectl-quick-reference` or `source=kubectl-quick-reference.pdf`).
  4. Review `MAX_VALUE_LENGTH=200` / comma-splitting in `FilterParser` for longer `ingest-dynamic` HTTPS `source` URLs, align blank/overlong query validation in `QueryController` with `400 Bad Request`, and document `/filters/validate` & `/query/normalize` in `retrieval-pre/SPEC.md`.
- **`retrieval-post` (Antonio) — Alignment confirmed:**
  1. `ingest-dynamic` emits all 7 metadata keys (`doc_id`, `version`, `source`, `page`, `chunk_index`, `checksum`, `is_latest`) so `NeighborStitchingService` and Task 3 citations work seamlessly on dynamically ingested web/GitHub/PDF documents.
  2. Once `ingest-dynamic` indexes official Kubernetes docs, re-evaluate `APP_SIMILARITY_THRESHOLD` and `APP_TOKEN_BUDGET` during Week 3 calibration.

---

## Alex — Branch: `feat/retrieval-pre`
- **Done:** *(pending Week 2 update)*
- **In Progress:** Story 3 Tasks 3 & 4 (external acronym expansion from `classpath:acronyms.yml`, multi-query expansion + RRF fusion).
- **Blockers:** None reported.

---

## Antonio — Branch: `feat/retrieval-post`
- **Done:** *(pending Week 2 update)*
- **In Progress:** Story 4 Task 3 (mandatory citations + `"I don’t know."` fallback) and `golden-set.json` preparation.
- **Blockers:** Official Kubernetes docs corpus via `ingest-dynamic` (unblocked once `feat/ingest-dynamic` merges).
