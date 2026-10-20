import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android-project/app/src/main/java/com/iipauk/spider/SqliteSpiderLearningMemory.kt"
EMBEDDING = ROOT / "android-project/app/src/main/java/com/iipauk/spider/SqliteSpiderEmbeddingIndex.kt"
SEMANTIC = ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderSemanticSearch.kt"
RUNTIME = ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt"


def schema_statements(source: str, class_marker: str, next_marker: str | None = None):
    block = source.split(class_marker, 1)[1]
    if next_marker and next_marker in block:
        block = block.split(next_marker, 1)[0]
    return [bytes(item, "utf-8").decode("unicode_escape") for item in re.findall(r'db\.execSQL\("([^"]+)"\)', block)]


class SqliteMemoryContractTests(unittest.TestCase):
    def test_memory_and_search_schema_execute_on_sqlite(self):
        source = SOURCE.read_text(encoding="utf-8")
        memory_ddl = schema_statements(source, "internal class LearningMemoryDatabase", "internal object LearningMemoryDatabaseRegistry")
        search_ddl = schema_statements(source, "internal class SpiderSearchIndexDatabase", "internal object SpiderVectorCodec")
        self.assertGreaterEqual(len(memory_ddl), 7)
        self.assertGreaterEqual(len(search_ddl), 7)
        memory = sqlite3.connect(":memory:")
        search = sqlite3.connect(":memory:")
        for statement in memory_ddl:
            memory.execute(statement)
        for statement in search_ddl:
            search.execute(statement)
        self.assertIn("learning_experiences", {r[0] for r in memory.execute("SELECT name FROM sqlite_master WHERE type='table'")})
        self.assertIn("search_postings", {r[0] for r in search.execute("SELECT name FROM sqlite_master WHERE type='table'")})
        self.assertIn("dense_embeddings", {r[0] for r in search.execute("SELECT name FROM sqlite_master WHERE type='table'")})
        memory.close(); search.close()

    def test_inverted_search_query_returns_scope_isolated_candidates(self):
        source = SOURCE.read_text(encoding="utf-8")
        memory_ddl = schema_statements(source, "internal class LearningMemoryDatabase", "internal object LearningMemoryDatabaseRegistry")
        search_ddl = schema_statements(source, "internal class SpiderSearchIndexDatabase", "internal object SpiderVectorCodec")
        db = sqlite3.connect(":memory:")
        for statement in search_ddl:
            db.execute(statement)
        db.execute("INSERT INTO search_postings(term, task_id, scope_id, updated_at) VALUES(?,?,?,?)", ("c:workspace", "task_a", "workspace_a", 10))
        db.execute("INSERT INTO search_postings(term, task_id, scope_id, updated_at) VALUES(?,?,?,?)", ("c:workspace", "task_b", "workspace_b", 20))
        db.execute("INSERT INTO search_postings(term, task_id, scope_id, updated_at) VALUES(?,?,?,?)", ("w:files", "task_a", "workspace_a", 10))
        sql = "SELECT task_id, COUNT(*) AS hits, MAX(updated_at) AS updated_at FROM search_postings WHERE term IN (?,?) AND (scope_id = 'global' OR scope_id = ?) GROUP BY task_id ORDER BY hits DESC, updated_at DESC LIMIT ?"
        rows = db.execute(sql, ("c:workspace", "w:files", "workspace_a", "400")).fetchall()
        self.assertEqual(["task_a"], [row[0] for row in rows])
        db.close()

    def test_sqlite_store_has_no_64_mib_application_ceiling_and_uses_paging(self):
        memory = SOURCE.read_text(encoding="utf-8")
        index = EMBEDDING.read_text(encoding="utf-8")
        self.assertNotIn("MAX_STORE_BYTES", memory)
        self.assertNotIn("MAX_STORE_BYTES", index)
        self.assertIn("LIMIT ? OFFSET ?", memory)
        self.assertIn("WHERE updated_at > ? OR (updated_at = ? AND task_id > ?)", memory)
        self.assertIn("nearestEmbeddings", memory)
        self.assertIn("PriorityQueue", memory)
        self.assertIn("fun searchTerms(tokens: List<String>)", SEMANTIC.read_text(encoding="utf-8"))
        self.assertIn("embeddingIndex.nearest(queryEmbedding", RUNTIME.read_text(encoding="utf-8"))
        self.assertIn("memory_index_outbox", memory)
        self.assertIn("OP_CLEAR", memory)

    def test_runtime_switches_to_sqlite_but_preserves_legacy_migration_path(self):
        runtime = RUNTIME.read_text(encoding="utf-8")
        memory = SOURCE.read_text(encoding="utf-8")
        index = EMBEDDING.read_text(encoding="utf-8")
        self.assertIn("SqliteSpiderLearningMemory", runtime)
        self.assertIn("SqliteSpiderEmbeddingIndex", runtime)
        self.assertIn("forEachMigrationBatch", memory)
        self.assertIn("forEachMigrationBatch", (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderLearningMemory.kt").read_text(encoding="utf-8"))
        self.assertIn("forEachMigrationBatch", (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderEmbeddingIndex.kt").read_text(encoding="utf-8"))
        self.assertIn("migrated-v2", memory)
        self.assertIn("migrated-v2", index)

    def test_android_instrumentation_covers_scale_migration_and_index_cleanup(self):
        instrumentation = (ROOT / "android-project/app/src/androidTest/java/com/iipauk/spider/SqliteSpiderMemoryInstrumentedTest.kt").read_text(encoding="utf-8")
        self.assertIn("repeat(540)", instrumentation)
        self.assertIn("540, memory.count())", instrumentation)
        self.assertIn("listExperiencesAfter", instrumentation)
        self.assertIn('memory.forget("task_17")', instrumentation)
        self.assertIn("memory.clearAll()", instrumentation)
        self.assertIn("0 until 1_100", instrumentation)
        self.assertIn("top-K retrieval stays bounded", instrumentation)
        self.assertIn("diagnosticsReportCountsSizesAndReadOnlyIntegrityWithoutPaths", instrumentation)
        self.assertIn("diagnosticsSnapshot()", instrumentation)

    def test_local_runtime_and_learning_memory_are_wired_without_faking_engine_availability(self):
        memory = SOURCE.read_text(encoding="utf-8")
        runtime = RUNTIME.read_text(encoding="utf-8")
        local_runtime = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderLocalLlmRuntime.kt").read_text(encoding="utf-8")
        native = (ROOT / "android-project/app/src/main/cpp/local_llm_jni.cpp").read_text(encoding="utf-8")
        cmake = (ROOT / "android-project/app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        self.assertIn("if (lastError == null) recoveryNotice = null", memory)
        self.assertIn("SqliteSpiderLearningMemory", runtime)
        self.assertIn("SqliteSpiderEmbeddingIndex", runtime)
        self.assertIn("val localLlmRuntime = SpiderLocalLlmRuntime", runtime)
        self.assertIn("SpiderLlmMode.LOCAL_ONLY", runtime)
        self.assertIn('System.loadLibrary("spiderllm")', local_runtime)
        self.assertIn("nativeGenerate", local_runtime)
        self.assertIn("llama_model_load_from_file", native)
        self.assertIn("llama_chat_apply_template", native)
        self.assertIn("GIT_TAG v0.6.0", cmake)
        self.assertNotIn("connectLocalLlm(", runtime)

    def test_memory_diagnostics_are_explicit_read_only_and_do_not_expose_paths(self):
        activity = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentActivity.kt").read_text(encoding="utf-8")
        self.assertIn('button("Диагностика памяти и поиска")', activity)
        self.assertIn("showMemoryDiagnostics()", activity)
        self.assertIn("Диагностика показывает только счётчики и статусы", activity)
        memory = SOURCE.read_text(encoding="utf-8")
        self.assertIn("fun diagnosticsSnapshot(): SpiderMemoryDiagnosticsSnapshot", memory)
        self.assertIn('PRAGMA quick_check(1)', memory)
        self.assertIn('SELECT COUNT(DISTINCT model_id) FROM dense_embeddings', memory)
        self.assertIn("availableStorageBytes", memory)
        snapshot = memory.split("data class SpiderMemoryDiagnosticsSnapshot(", 1)[1].split("\n)", 1)[0]
        self.assertNotIn("absolutePath", snapshot)
        self.assertNotIn("taskGoal", snapshot)
        self.assertNotIn("rawText", snapshot)

    def test_sqlite_diagnostics_query_contract(self):
        memory_source = SOURCE.read_text(encoding="utf-8")
        index_source = EMBEDDING.read_text(encoding="utf-8")
        memory_ddl = schema_statements(memory_source, "internal class LearningMemoryDatabase", "internal object LearningMemoryDatabaseRegistry")
        index_ddl = schema_statements(memory_source, "internal class SpiderSearchIndexDatabase", "internal object SpiderVectorCodec")
        memory = sqlite3.connect(":memory:")
        index = sqlite3.connect(":memory:")
        for statement in memory_ddl:
            memory.execute(statement)
        for statement in index_ddl:
            index.execute(statement)
        memory.execute("INSERT INTO learning_experiences(task_id, updated_at, outcome, verified, scope_id) VALUES(?,?,?,?,?)", ("t1", 10, "VERIFIED_SUCCESS", 1, "global"))
        memory.execute("INSERT INTO learning_topic_tokens(task_id, ordinal, token) VALUES(?,?,?)", ("t1", 0, "workspace"))
        index.execute("INSERT INTO search_postings(term, task_id, scope_id, updated_at) VALUES(?,?,?,?)", ("w:workspace", "t1", "global", 10))
        index.execute("INSERT INTO dense_embeddings(task_id, model_id, dimensions, vector, updated_at) VALUES(?,?,?,?,?)", ("t1", "local-test", 8, bytes(32), 10))
        self.assertEqual(1, memory.execute("SELECT COUNT(*) FROM learning_experiences").fetchone()[0])
        self.assertEqual(1, memory.execute("SELECT COUNT(*) FROM learning_topic_tokens").fetchone()[0])
        self.assertEqual(1, index.execute("SELECT COUNT(*) FROM search_postings").fetchone()[0])
        self.assertEqual(1, index.execute("SELECT COUNT(*) FROM dense_embeddings").fetchone()[0])
        self.assertEqual("ok", memory.execute("PRAGMA quick_check(1)").fetchone()[0])
        self.assertEqual("ok", index.execute("PRAGMA quick_check(1)").fetchone()[0])
        self.assertIn("diagnosticsSnapshot", memory_source)
        self.assertIn("fun count(): Int = synchronized(lock) { helper.embeddingCount() }", index_source)
        memory.close(); index.close()


if __name__ == "__main__":
    unittest.main()
