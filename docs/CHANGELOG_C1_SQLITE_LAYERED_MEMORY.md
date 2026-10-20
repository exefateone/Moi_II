# Stage C1 — SQLite episodic memory and separate search index

**Checkpoint:** 16 October 2026 · product version remains v0.38.
**LLM integration:** intentionally not performed in this checkpoint.

## User goal

Remove the arbitrary 64 MiB ceiling from active learning memory, avoid count-based eviction, keep a balanced storage/retrieval design, use SQLite and a separate search index, and develop memory in layers without connecting an LLM yet.

## Implemented in source

- `SpiderAgentRuntime` now instantiates `SqliteSpiderLearningMemory` for active episodic experience and `SqliteSpiderEmbeddingIndex` for embeddings/search vectors.
- Primary database (`spider-learning-v2.db`) contains `learning_experiences`, normalized `learning_topic_tokens` and `learning_tools`, `memory_metadata`, and a durable `memory_index_outbox`. Appropriate indices and foreign-key cascades support scoped/page retrieval and cleanup. WAL is enabled.
- Separate search database (`spider-search-index.db`) contains indexed `search_postings`, model-specific `dense_embeddings`, backfill cursors, and migration metadata. This separates the episodic source of truth from rebuildable search structures.
- Memory and index mutations enqueue UPSERT/DELETE/CLEAR operations within the main-store transaction. The consumer commits the corresponding index transaction before acknowledging the outbox item. An interrupted sync is retried later; retrieval falls back to a bounded recent page if the index cannot be made fresh.
- There is no application-level byte ceiling and no count-based eviction for the active SQLite memory or search index. Disk capacity is still finite; write failures must be reported, and old episodes must never be silently evicted to make room. `storageStats()` reports episode/token counts, pending index changes and primary/index DB plus WAL/SHM file sizes.
- Page APIs (`listExperiencesPage`, `listExperiencesAfter`) bound result materialization and provide keyset traversal for indexing. Dense top-K scans only one model’s rows and maintains a bounded priority queue rather than deserializing the whole vector index into RAM. Dense scan latency is still O(N); an approximate-nearest-neighbor extension is a later evidence-based choice, not assumed necessary before profiling.
- The previous binary experience memory and vector store remain compatibility/migration inputs. Imports stream in bounded batches, are idempotent across restarts, preserve the source during import, and archive it as `.migrated-v2` after successful completion. The old 64 MiB guard remains only for legacy single-file format handling; it no longer limits the active SQLite stores.
- `clearAll()` clears the episode source and queues the index clear; if index synchronization fails, it no longer erases the resulting error notice.
- SQLite is deliberately the first memory layer in this step. Task history and skill library still use their previous versioned repositories, and no derived long-term summary layer has been auto-created. Later steps can migrate those independently and add provenance-linked summaries after they have rebuild/delete rules.

## Migration safety

- Main store is the source of truth. Outbox processing is idempotent. Index records can be rebuilt from sanitized episode topic tokens.
- Legacy import uses batches and conflict-ignore semantics, so a partial import can be retried without duplicating task IDs. Existing source files are only renamed after successful import and the migration marker is written. If migration fails, the original is preserved and a recovery notice is surfaced.
- Clearing/forgetting an episode also removes its lexical postings and associated dense embedding rows via the outbox, but does not delete independent task history.
- Scope isolation remains enforced: a workspace can only retrieve global experiences and its own workspace-scoped episodes.
- Tokens are still sanitized; full prompts, transcripts, file contents and raw tool output are not written into the memory tables.

## Tests and limits

- Python suite: **72/72 passed** in this checkpoint. This includes four SQLite contract tests; schema DDL is executed by Python's SQLite engine and scope-isolated inverted-index behavior is checked.
- HTML/JavaScript static check: **129 unique IDs**, inline JS syntax passes, packaged asset matches source.
- Chromium smoke: **8 scenarios passed**, no uncaught page errors.
- Media URL policy smoke: **15 assertions passed**.
- `SqliteSpiderLearningMemory.kt` and `SqliteSpiderEmbeddingIndex.kt` type-check with Kotlin/JVM against temporary minimal Android API stubs. This is not the real Android SDK compiler.
- `SqliteSpiderMemoryInstrumentedTest.kt` was added for 540 legacy episodes plus paging/search/forget/clear, and 1,100 vectors plus bounded nearest results. These Android instrumentation tests have **not** been run, because this environment lacks Gradle and Android SDK.
- Main memory write paths now catch storage exceptions and surface a safe recovery notice rather than allowing memory persistence failures to break task completion. `clearAll()` preserves the index-sync warning if the outbox cannot be drained.
- The aggregate verification script reached the pure Kotlin Agent Core compile step but timed out during the long Kotlin compilation; do not report the Agent Core smoke suite as passing for this new checkpoint. The Media URL smoke did pass separately.
- No Android Gradle compile, APK, device testing, live embeddings or LLM/provider calls were performed. User instruction is to defer LLM integration.

## Next substeps

1. Run the instrumentation test on Android emulator/device; validate legacy migration, re-open, forget/clear, pending-outbox replay, corrupted legacy-store recovery, and low storage.
2. Add a memory diagnostics panel using `storageStats()` without exposing topic text or private workspace paths.
3. Benchmark with 1k, 10k and 100k experiences. Only if dense scan misses target latency should an ANN index such as a supported SQLite vector extension be chosen.
4. Migrate task history and skill evidence to SQLite as separate schema migrations.
5. Add a compact derived semantic-summary layer with source-episode provenance and explicit rebuild/deletion semantics.
6. Only after these storage tasks and an explicit user go-ahead return to LLM integration.
