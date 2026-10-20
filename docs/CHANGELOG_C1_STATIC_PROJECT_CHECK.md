# Change log — bounded static project checker

**Checkpoint date:** 18 October 2026  
**Source version:** `0.39.1-bounded-static-check` (`versionCode 40`)

## Implemented

- Added `CHECK_WORKSPACE_PROJECT` to the typed Agent Broker enum, strict shared plan parser, Yandex backend allowlist, local GGUF planner and OpenCode Zen planner.
- Registered `WorkspaceProjectCheckHandler` in the app-private runtime and exposed a native confirmation step. The broker uses the normal one-shot approval, request replay ledger and audit-before/after-execution boundary.
- Added a bounded read-only scan: at most 2,000 entries, 500 supported text files, 8 MiB of file content and 64 KiB per file; maximum nesting depth 8. Symlinks, workspace metadata, secret-looking paths and credential-looking file bodies are skipped. Paths stay inside the chosen workspace.
- Checks strict JSON grammar; parses XML/SVG with secure processing, disallowed DTDs and disabled external entities; checks UTF-8 readability for other supported text formats. The tool never executes project code, scripts, `sh`, compilers, test runners, package managers, builds or network access. It does not claim that Java/Kotlin/JavaScript/Python syntax compiles.
- Added a second bounded scan in `WorkspaceProjectCheckVerifier`; an opaque digest over file metadata, inspected content hashes and finding summaries must match. A changed workspace invalidates the prior result.
- Added a typed internal skill candidate `static_project_check_v1`. Verified skill evidence can be recorded only after the repeated checker passes and only when internal learning is enabled. It does not learn from the displayed file contents.
- Updated capability prompts and planner tool allowlists to five implemented workspace tools. `RUN_PROJECT_TESTS` and arbitrary shell execution remain unregistered.

## Boundaries and risks

- This is static source inspection, not a project test/build runner. JSONC, YAML, Kotlin/Java, JavaScript/TypeScript, Python and Gradle language semantics are not parsed by this feature; those supported text files are only checked for bounded UTF-8 reading unless they are JSON/XML/SVG.
- If scan limits are reached, the report says it is partial. A report confirms that the bounded checker ran; it does not mean there are zero source errors in unparsed languages.
- Credential pattern filtering is conservative and cannot guarantee detection of every possible secret. The tool doesn't upload file content, and its output is bounded and passed through the existing secret redactor.
- Android build, APK, emulator/device UI, and live Yandex/OpenCode requests are not verified in this environment.

## Verification

See `docs/BOUNDED_STATIC_PROJECT_CHECK_REPORT_2026-10-18.md` for exact test outcomes. The Agent Core Kotlin/JVM harness exercised JSON/XML findings, stale-result rejection, and the non-execution boundary. The SQLite task repository type-check timed out and is not marked as passed; Android Gradle/APK, device and live-provider testing remain unverified.
