# Real workspace tools and Spider-owned learning — 17 October 2026

## What changed

The registered Agent Broker toolset is now four operations in the app-private workspace:

- `LIST_WORKSPACE_FILES`: bounded inventory, with independent inventory proof.
- `SEARCH_WORKSPACE_CONTENT`: case-insensitive substring search across supported text files, with a second-pass verifier.
- `READ_WORKSPACE_FILE`: read one supported UTF-8 text file, with secret-pattern redaction and a read-back/digest verifier.
- `WRITE_WORKSPACE_FILE`: atomic UTF-8 write of exact text provided in the native UI, with SHA-256 read-back verification. Overwriting an existing file requires a separate explicit checkbox in addition to the one-time action approval.

All tool requests go through the fail-closed `SpiderToolBroker` with policy, a durable one-shot request ledger, pre/post audit, bounded output and an independent verifier. The model only proposes typed tool IDs; it cannot supply arbitrary paths as trusted values, invent permissions or call an unregistered handler. Relative paths are limited to the selected workspace; traversal, symlinks, known secret-looking locations, unsupported file formats, oversized payloads and incomplete search scans are refused. Search/read/write require individual approval. Output and content are not inserted into the audit summary or episodic learning memory, and common credential formats are redacted before being shown.

The local GGUF planner, Yandex backend planner and OpenCode Zen planner share the same four-tool allowlist. Backend Pydantic validation rejects tool IDs outside this set; Android parsing and actual handler registration are also checked. The built-in skill library now seeds one non-executable candidate for each actual tool. After a successful step passes its independent verifier, the native flow adds task-ID/verifier/version evidence to the matching candidate (only while internal learning is enabled). A skill is recommended only after three distinct independently verified tasks; it never runs automatically, and it stores no file contents or outputs.

## Spider's internal brain

“Self-learning” here means Spider-owned episodic memory and behavioral adaptation, not fine-tuning of the connected provider model. After a task becomes terminal, memory records privacy-minimized topic tokens, outcome, safe error code, the IDs of steps that actually succeeded or the final failed step, and explicit user feedback. A plan that was merely suggested is not treated as executed experience. Future similar plans retrieve relevant success/failure hints across local GGUF, Yandex AI Studio and OpenCode Zen. Model weights are unchanged. Full transcripts, file contents, raw tool output and credentials are not retained as experience.

## Intentionally not enabled

This does **not** add an arbitrary shell, PTY command executor for the agent, project-test runner, downloader, package installer, delete operation, general web automation or external-app control. The write operation persists text that the user entered and confirmed; it does not yet autonomously draft or refactor source code based on a model-generated file patch. This boundary is explicit so the capability map does not claim unsupported automation.

## Verification

- `python3 -m unittest discover -s tests -p 'test_*.py'`: **104/104 passed**.
- `bash scripts/test_agent_core_jvm.sh`: **15 grouped Agent Core scenarios passed**, including guarded read, bounded search, atomic write, overwrite consent, path traversal/symlink blocking, token redaction, audit privacy and independent verification.
- Additional individual checks passed: Media URL Policy JVM (15 assertions), browser smoke (8 scenarios), historical OpenCode Server connector smoke (pre-removal), OpenCode Zen source smoke, local-LLM Kotlin source typecheck, SQLite repository Kotlin typecheck with signature stubs, Python bytecode compilation and shell syntax checks. The aggregate `verify_project.sh` was not claimed as passing.
- Android Gradle build/APK/device acceptance and live Yandex/OpenCode Zen calls are **not** claimed: Android SDK/Gradle/NDK and a live provider/device were unavailable in this environment.
