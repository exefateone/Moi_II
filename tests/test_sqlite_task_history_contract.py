import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android-project/app/src/main/java/com/iipauk/spider/SqliteSpiderTaskRepository.kt"
RUNTIME = ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt"
ACTIVITY = ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentActivity.kt"


def schema_statements(source: str):
    block = source.split("internal class SpiderTaskHistoryDatabase", 1)[1]
    block = block.split("private object SpiderTaskDatabaseRegistry", 1)[0]
    return [bytes(item, "utf-8").decode("unicode_escape") for item in re.findall(r'db\.execSQL\("([^"]+)"\)', block)]


class SqliteTaskHistoryContractTests(unittest.TestCase):
    def test_task_schema_executes_and_enforces_step_parent_relationship(self):
        source = SOURCE.read_text(encoding="utf-8")
        statements = schema_statements(source)
        self.assertGreaterEqual(len(statements), 5)
        db = sqlite3.connect(":memory:")
        db.execute("PRAGMA foreign_keys=ON")
        for statement in statements:
            db.execute(statement)
        names = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertTrue({"task_history", "task_steps", "task_history_metadata"}.issubset(names))
        db.execute("INSERT INTO task_history(task_id, goal, status, created_at, updated_at) VALUES(?,?,?,?,?)", ("task-a", "goal", "RUNNING", 1, 2))
        db.execute("INSERT INTO task_steps(task_id, position, step_id, title, status) VALUES(?,?,?,?,?)", ("task-a", 0, "step-a", "Read-only list", "PENDING"))
        db.execute("DELETE FROM task_history WHERE task_id=?", ("task-a",))
        self.assertEqual(0, db.execute("SELECT COUNT(*) FROM task_steps").fetchone()[0])
        db.close()

    def test_recent_query_is_bounded_and_count_is_sql_backed(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn("fun recent(limit: Int)", source)
        self.assertIn("fun recoverableForStartup(): List<SpiderAgentTask>", source)
        self.assertIn("WHERE status IN (?,?,?,?) OR (status = ? AND resume_status IN (?,?,?,?))", source)
        self.assertIn("ORDER BY updated_at DESC, created_at DESC, task_id DESC LIMIT ?", source)
        self.assertIn('"SELECT COUNT(*) FROM task_history"', source)
        self.assertIn("SQLITE_VARIABLE_LIMIT = 400", source)

    def test_legacy_import_is_transactional_and_preserves_source_until_verified(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn("FileSpiderTaskRepository(legacyStoreFile)", source)
        self.assertIn("db.beginTransaction()", source)
        self.assertIn("db.setTransactionSuccessful()", source)
        self.assertIn("imported.associateBy { it.id } != legacyTasks.associateBy { it.id }", source)
        self.assertIn(".migrated-sqlite-v1", source)
        self.assertIn("legacyStoreFile.exists()", source)
        self.assertIn("LEGACY_IMPORT_KEY", source)
        self.assertIn("PENDING_IMPORT_PREFIX", source)
        self.assertIn('PRAGMA quick_check(1)', source)
        self.assertIn("SQLiteDatabase.openDatabase(databaseFile.absolutePath", source)
        self.assertIn("onUpgrade", source)
        self.assertNotIn('db.execSQL("DROP TABLE', source)

    def test_runtime_uses_sqlite_and_history_ui_uses_paged_repository_queries(self):
        runtime = RUNTIME.read_text(encoding="utf-8")
        coordinator = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderTaskCoordinator.kt").read_text(encoding="utf-8")
        activity = ACTIVITY.read_text(encoding="utf-8")
        self.assertIn("SqliteSpiderTaskRepository(", runtime)
        self.assertIn('File(appContext.filesDir, "agent/tasks-v1.spider")', runtime)
        self.assertIn("fun listRecentTasks(limit: Int = 30)", coordinator)
        self.assertIn("fun taskCount(): Int", coordinator)
        self.assertIn("runtime.taskCoordinator.listRecentTasks(30)", activity)
        self.assertIn("runtime.taskCoordinator.taskCount()", activity)
        self.assertNotIn("runtime.taskCoordinator.listTasks()", activity)

    def test_android_instrumentation_covers_migration_reopen_and_future_schema(self):
        instrumentation = (ROOT / "android-project/app/src/androidTest/java/com/iipauk/spider/SqliteSpiderTaskRepositoryInstrumentedTest.kt").read_text(encoding="utf-8")
        self.assertIn("importsLegacyHistoryAtomicallyAndKeepsArchiveAfterRestart", instrumentation)
        self.assertIn("recentAndCountQueriesDoNotRequireHistoryTruncation", instrumentation)
        self.assertIn("unknownLegacySchemaFailsClosedAndPreservesOriginalBytes", instrumentation)
        self.assertIn("databaseWithFutureSchemaIsNotDowngradedOrOverwritten", instrumentation)
        self.assertIn("recoversPendingImportAfterSimulatedProcessDeath", instrumentation)
        self.assertIn('put("value", "pending:1")', instrumentation)
        self.assertIn("repo.recent(20)", instrumentation)
        self.assertIn("FileSpiderTaskRepository.SCHEMA_VERSION + 1", instrumentation)


if __name__ == "__main__":
    unittest.main()
