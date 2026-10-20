from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]

class PackageContracts(unittest.TestCase):
    def test_release_version_label(self):
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertIn('name="ii-pauk-version" content="0.39.3"', html)
        self.assertIn("Spider Suite v0.39.3", html)

    def test_release_markers_are_consistent_across_current_sources(self):
        gradle = (ROOT / "android-project/app/build.gradle.kts").read_text(encoding="utf-8")
        host = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/MainActivity.kt").read_text(encoding="utf-8")
        backend = (ROOT / "backend/app/main.py").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/android-build.yml").read_text(encoding="utf-8")
        self.assertIn('versionName = "0.39.3"', gradle)
        self.assertIn("appVersion:'0.39.3'", host)
        self.assertIn('APP_VERSION = "0.39.3"', backend)
        self.assertIn("ii-pauk-v0.39.3-debug-apk", workflow)
        root_gradle = (ROOT / "android-project/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('id("com.android.application") version "8.10.2"', root_gradle)

    def test_stop_command_can_interrupt_a_busy_web_chat(self):
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertIn("if(demoBusy&&!isStopCommand)return", html)
        self.assertIn("activeBackendController.abort()", html)

    def test_web_chat_uses_authenticated_contract_and_not_disabled_agent_step(self):
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        backend = (ROOT / "backend/app/main.py").read_text(encoding="utf-8")
        env = (ROOT / "backend/.env.example").read_text(encoding="utf-8")
        self.assertIn("/api/client/check", html)
        self.assertIn("'Authorization':'Bearer '+backendClientToken", html)
        self.assertIn("result=await callConfiguredBackend(value,token)", html)
        self.assertNotIn("callConfiguredAgentLoop", html)
        self.assertNotIn("fetch(base+'/api/agent/step'", html)
        self.assertIn('@app.get("/api/client/check")', backend)
        self.assertIn("SPIDER_CORS_ORIGINS=https://appassets.androidplatform.net", env)
        self.assertIn("allow_origins=_configured_cors_origins()", backend)
        self.assertNotIn('allow_origins=["*"]', backend)

    def test_previous_html_versions_are_preserved(self):
        for name in ("ii-pauk-v0.35.html", "ii-pauk-v0.34.html", "ii-pauk-v0.33.html", "ii-pauk-v0.32.html"):
            self.assertTrue((ROOT / "history" / name).is_file(), name)

    def test_native_browser_has_no_javascript_bridge(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderBrowserActivity.kt").read_text()
        self.assertNotIn("addJavascriptInterface", source)
        self.assertNotIn("addWebMessageListener", source)

    def test_native_bridge_is_allowlisted_and_origin_scoped(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/MainActivity.kt").read_text()
        self.assertIn("WebViewCompat.addWebMessageListener", source)
        self.assertIn("isMainFrame", source)
        self.assertIn("webView.url != APP_URL", source)
        self.assertIn('"open_browser"', source)
        self.assertNotIn('"execute_command"', source)
        self.assertIn("No addJavascriptInterface fallback", source)

    def test_native_browser_bridge_does_not_hijack_ui_clicks(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/MainActivity.kt").read_text()
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertNotIn("document.addEventListener('click'", source)
        self.assertIn("Android.openSpiderBrowser(target)", html)
        self.assertIn("const current=getBrowserTab()", html)

    def test_browser_search_policy_is_present_and_explicit_schemes_fail_closed(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SafeUrlPolicy.kt").read_text()
        self.assertIn("fun addressOrSearch(raw: String)", source)
        self.assertIn('appendQueryParameter("q", text)', source)
        self.assertIn('startsWith("https://", ignoreCase = true)', source)
        self.assertIn("return null", source)

    def test_html_browser_url_policy_rejects_credentials_controls_and_non_web_schemes(self):
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertIn("u.username||u.password", html)
        self.assertIn("raw.length>8192", html)
        self.assertIn(r"/[\u0000-\u001f\u007f\\]/", html)
        self.assertIn("^(javascript|data|file|intent|content):", html)

    def test_native_terminal_ptys_are_wired_but_device_verification_is_pending(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/MainActivity.kt").read_text()
        activity = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderTerminalActivity.kt").read_text()
        gradle = (ROOT / "android-project/app/build.gradle.kts").read_text()
        self.assertIn("open_terminal", source)
        self.assertIn("openSpiderTerminal: function()", source)
        self.assertIn("terminal:true, terminalPty:true, nativeTerminal:true, terminalPtyVerified:false", source)
        self.assertIn('implementation("com.termux.termux-app:terminal-view:0.118.0")', gradle)
        self.assertIn('"/system/bin/sh"', activity)
        self.assertIn("TerminalSession(", activity)
        self.assertIn("TerminalView(this)", activity)
        self.assertIn("finishIfRunning()", activity)
        self.assertIn("MAX_CLIPBOARD_CHARS", activity)
        self.assertIn("not a Linux distribution", activity)
        self.assertIn("linuxRuntime:false", source)

    def test_optional_qwen_model_downloader_is_pinned_and_never_downloads_during_build(self):
        script = (ROOT / "scripts/download_qwen_smoke_model.sh").read_text(encoding="utf-8")
        gradle = (ROOT / "android-project/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/", script)
        self.assertIn("74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", script)
        self.assertIn("sha256sum", script)
        self.assertIn("shasum -a 256", script)
        self.assertIn("--continue-at -", script)
        self.assertNotIn("download_qwen_smoke_model.sh", gradle)
        self.assertIn("does **not** execute operating-system shell commands", (ROOT / "docs/TERMINAL_AND_MODEL_DEPENDENCIES.md").read_text(encoding="utf-8"))

    def test_removed_opencode_server_connector_is_absent(self):
        app = ROOT / "android-project/app/src/main/java/com/iipauk/spider"
        manifest = (ROOT / "android-project/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        host = (app / "MainActivity.kt").read_text(encoding="utf-8")
        runtime = (app / "SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        catalog = (app / "SpiderCapabilityCatalog.kt").read_text(encoding="utf-8")
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        for name in ("SpiderOpenCodeActivity.kt", "SpiderOpenCodeClient.kt", "SpiderOpenCodeConfigStore.kt", "SpiderOpenCodeConnection.kt"):
            self.assertFalse((app / name).exists(), name)
        self.assertNotIn("SpiderOpenCodeActivity", manifest)
        self.assertNotIn("open_opencode", host)
        self.assertNotIn("SpiderOpenCodeConfigStore", runtime)
        self.assertNotIn("opencode_api", catalog)
        self.assertNotIn("openSpiderOpenCode", html)
        self.assertNotIn("spiderCodeOpenCode", html)
        self.assertIn("openCodeZenApiSourceAvailable:true", host)

    def test_opencode_zen_model_api_is_direct_fixed_host_and_keystore_encrypted(self):
        app = ROOT / "android-project/app/src/main/java/com/iipauk/spider"
        protocol = (app / "SpiderOpenCodeZenConfig.kt").read_text(encoding="utf-8")
        client = (app / "SpiderOpenCodeZenClient.kt").read_text(encoding="utf-8")
        store = (app / "SpiderOpenCodeZenConfigStore.kt").read_text(encoding="utf-8")
        activity = (app / "SpiderOpenCodeZenActivity.kt").read_text(encoding="utf-8")
        mode = (app / "SpiderLlmMode.kt").read_text(encoding="utf-8")
        manifest = (ROOT / "android-project/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn("https://opencode.ai/zen/v1/models", protocol)
        self.assertIn("https://opencode.ai/zen/v1/chat/completions", protocol)
        self.assertIn("supportedChatModelIds", protocol)
        self.assertIn("instanceFollowRedirects = false", client)
        self.assertIn('.put("max_tokens", 1)', client)
        self.assertIn("AndroidKeyStore", store)
        self.assertIn("AES/GCM/NoPadding", store)
        self.assertIn("OpenCode Zen API", activity)
        self.assertIn("без сервера и компьютера", activity)
        self.assertIn("OPENCODE_ZEN", mode)
        self.assertIn('android:name=".SpiderOpenCodeZenActivity"', manifest)
        doc = (ROOT / "docs/OPENCODE_ZEN_DIRECT_API.md").read_text(encoding="utf-8")
        self.assertIn("without deploying", doc)
        self.assertIn("local Agent Broker", doc)

    def test_opencode_zen_source_smoke_is_part_of_project_verification(self):
        verify = (ROOT / "scripts/verify_project.sh").read_text(encoding="utf-8")
        self.assertIn("check_opencode_zen_kotlin_sources.sh", verify)
        smoke = ROOT / "scripts/check_opencode_zen_kotlin_sources.sh"
        self.assertTrue(smoke.is_file())

    def test_packaged_html_stays_in_sync_after_opencode_entry_point(self):
        self.assertEqual(
            (ROOT / "app-web/ii-pauk.html").read_bytes(),
            (ROOT / "android-project/app/src/main/assets/web/ii-pauk.html").read_bytes()
        )

    def test_runtime_template_is_not_built(self):
        manifest = (ROOT / "runtime/linux/linux-runtime-manifest.template.json").read_text()
        self.assertIn('"status": "not_built"', manifest)

    def test_workspace_repository_uses_private_root_and_canonical_paths(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/WorkspaceRepository.kt").read_text()
        self.assertIn('File(context.filesDir, "workspaces")', source)
        self.assertIn("canonicalFile", source)
        self.assertIn("deleteRecursively()", source)

    def test_android_packaged_ui_matches_current_ui(self):
        self.assertEqual((ROOT / "app-web/ii-pauk.html").read_bytes(), (ROOT / "android-project/app/src/main/assets/web/ii-pauk.html").read_bytes())

    def test_android_host_advertises_only_real_capabilities(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/MainActivity.kt").read_text()
        self.assertIn("openSpiderBrowser: function(url)", source)
        self.assertIn("terminalPty:true", source)
        self.assertIn("terminalPtyVerified:false", source)
        self.assertIn("linuxRuntime:false", source)
        self.assertIn("openSpiderTerminal: function()", source)
        self.assertNotIn("executeAllowedCommand: function", source)

    def test_native_browser_has_file_upload_and_security_recovery(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderBrowserActivity.kt").read_text()
        for contract in ("onShowFileChooser", "onReceivedSslError", "onSafeBrowsingHit", "onRenderProcessGone", "onReceivedError", "onReceivedHttpError"):
            self.assertIn(contract, source)
        self.assertIn("${error.description}", source)
        self.assertIn("${response.statusCode}", source)
        self.assertNotIn("${'$'}{error.description}", source)
        self.assertNotIn("${'$'}{response.statusCode}", source)
        self.assertIn("if (pageLoadError != null)", source)
        self.assertIn("pageLoadError = \"TLS/сертификат не прошёл проверку; соединение отменено\"", source)
        self.assertIn("pageLoadError = \"Safe Browsing заблокировал потенциально опасную страницу\"", source)
        self.assertNotIn("CookieManager", source, "session cookies must not be forwarded through redirecting DownloadManager requests")
        self.assertIn("pendingFileCallback?.onReceiveValue(null)", source)
        self.assertNotIn("addJavascriptInterface", source)
        self.assertNotIn("addWebMessageListener", source)

    def test_legacy_android_public_download_permission_is_runtime_gated(self):
        manifest = (ROOT / "android-project/app/src/main/AndroidManifest.xml").read_text()
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderBrowserActivity.kt").read_text()
        self.assertIn('android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28"', manifest)
        self.assertIn("Build.VERSION.SDK_INT <= Build.VERSION_CODES.P", source)
        self.assertIn("storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)", source)
        self.assertIn("enqueueDownload(uri, userAgent, contentDisposition, mimeType)", source)

    def test_url_policy_rejects_parser_ambiguity(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SafeUrlPolicy.kt").read_text()
        self.assertIn("raw.any { it.code < 0x20", source)
        self.assertIn(r"'\\' in raw", source)
        self.assertIn("port !in 1..65535", source)

    def test_current_html_distinguishes_integrated_terminal_from_unverified_device_runtime(self):
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertIn("Android system shell", html)
        self.assertIn("Полноценный Linux userland не установлен", html)
        self.assertIn("terminalPtyVerified", html)
        self.assertIn("version:'0.39.3'", html)
        self.assertNotIn("Версия 0.33 · Spider Browser", html)
        self.assertEqual(html.encode(), (ROOT / "android-project/app/src/main/assets/web/ii-pauk.html").read_bytes())

    def test_terminal_activity_launches_only_private_android_system_shell(self):
        source = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderTerminalActivity.kt").read_text()
        self.assertIn('"/system/bin/sh"', source)
        self.assertIn('"PATH=/system/bin:/system/xbin"', source)
        self.assertIn("private app directories", source)
        self.assertNotIn("ProcessBuilder", source)
        self.assertNotIn("su -c", source)
        self.assertNotIn("v0.35", source)


    def test_media3_player_is_wired_as_native_source(self):
        gradle = (ROOT / "android-project/app/build.gradle.kts").read_text()
        manifest = (ROOT / "android-project/app/src/main/AndroidManifest.xml").read_text()
        activity = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderMediaActivity.kt").read_text()
        service = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderPlaybackService.kt").read_text()
        host = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/MainActivity.kt").read_text()
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertIn("androidx.media3:media3-exoplayer:1.11.1", gradle)
        self.assertIn("androidx.media3:media3-exoplayer-hls:1.11.1", gradle)
        self.assertIn("androidx.media3:media3-exoplayer-dash:1.11.1", gradle)
        self.assertIn("androidx.media3:media3-ui:1.11.1", gradle)
        self.assertIn("androidx.media3:media3-session:1.11.1", gradle)
        self.assertIn("ActivityResultContracts.OpenMultipleDocuments()", activity)
        self.assertIn("playHttpsSource", activity)
        self.assertIn("enqueueHttpsSource", activity)
        self.assertIn("controller.addMediaItems(items)", activity)
        self.assertIn("class SpiderMediaActivity : ComponentActivity()", activity)
        url_policy = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderMediaUrlPolicy.kt").read_text()
        self.assertIn('!uri.scheme.equals("https", ignoreCase = true)', url_policy)
        self.assertIn('"application/x-mpegURL"', url_policy)
        self.assertIn('"application/dash+xml"', url_policy)
        self.assertIn("MediaSessionService", service)
        self.assertIn('"open_media"', host)
        self.assertIn("openSpiderMedia", host)
        self.assertIn("mediaNetworkStreamingSourceAvailable:true", host)
        self.assertIn("mediaPlayerVerified:false", host)
        self.assertIn("backgroundPlaybackVerified:false", host)
        self.assertIn('data-page="media"', html)
        self.assertIn("case'media':spiderMediaModal()", html)

    def test_agent_policy_is_typed_fail_closed_and_has_no_generic_shell_tool(self):
        models = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentModels.kt").read_text()
        policy = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderPolicyEngine.kt").read_text()
        audit = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAuditLog.kt").read_text()
        self.assertIn("enum class SpiderToolId", models)
        self.assertNotIn("EXECUTE_SHELL", models)
        self.assertIn("network_permission_required", policy)
        self.assertIn("explicit_approval_required", policy)
        self.assertIn("SpiderPolicyDisposition.DENY", policy)
        self.assertIn("spider-audit.jsonl", audit)
        broker = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderToolBroker.kt").read_text()
        self.assertIn("SpiderAuditRedactor", audit)
        self.assertIn("[REDACTED]", broker)

    def test_stage_c_agent_core_is_typed_and_fail_closed(self):
        root = Path(__file__).resolve().parents[1]
        coordinator = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderTaskCoordinator.kt").read_text(encoding="utf-8")
        broker = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderToolBroker.kt").read_text(encoding="utf-8")
        self.assertIn("class SpiderTaskCoordinator", coordinator)
        self.assertIn("SpiderTaskStatus.VERIFYING", coordinator)
        self.assertIn("class SpiderToolBroker", broker)
        self.assertIn("execution_started", broker)
        self.assertIn("duplicate_request_id", broker)
        self.assertNotIn("executeShell", broker)

    def test_agent_runtime_uses_durable_storage_and_keeps_inventory_handler_read_only(self):
        runtime = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        host = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/MainActivity.kt").read_text(encoding="utf-8")
        handler = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/WorkspaceFileListHandler.kt").read_text(encoding="utf-8")
        self.assertIn('"agent/tasks-v1.spider"', runtime)
        self.assertIn("SqliteSpiderTaskRepository", runtime)
        task_store = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SqliteSpiderTaskRepository.kt").read_text(encoding="utf-8")
        self.assertIn("FileSpiderTaskRepository(legacyStoreFile)", task_store)
        self.assertIn("LEGACY_IMPORT_KEY", task_store)
        self.assertIn("WorkspaceFileListHandler", runtime)
        self.assertIn("SpiderAgentRuntime.get(applicationContext)", host)
        self.assertIn("MAX_ENTRIES", handler)
        self.assertIn("isWithinRoot", handler)
        self.assertNotIn("readText", handler)
        self.assertNotIn("writeText", handler)

    def test_durable_request_replay_ledger_and_startup_recovery_are_wired(self):
        root = Path(__file__).resolve().parents[1]
        ledger = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderRequestLedger.kt").read_text(encoding="utf-8")
        broker = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderToolBroker.kt").read_text(encoding="utf-8")
        runtime = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        coordinator = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderTaskCoordinator.kt").read_text(encoding="utf-8")
        smoke = (root / "tests/kotlin/SpiderPersistenceSmoke.kt").read_text(encoding="utf-8")
        self.assertIn("class FileSpiderRequestLedger", ledger)
        self.assertIn("MessageDigest.getInstance(\"SHA-256\")", ledger)
        self.assertIn("SpiderRequestClaimResult.STORAGE_UNAVAILABLE", broker)
        self.assertIn('"agent/request-ledger-v1.spider"', runtime)
        self.assertIn("recoverInterruptedTasks()", coordinator)
        self.assertIn("repository.recoverableForStartup()", coordinator)
        self.assertIn("approval_expired_after_restart", coordinator)
        self.assertIn("execution_interrupted_unknown_outcome", coordinator)
        self.assertIn("replay is denied after new broker instance", smoke)
        self.assertIn("pending approval expires safely after restart", smoke)

    def test_self_learning_memory_is_persistent_feedback_driven_and_privacy_minimized(self):
        memory = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderLearningMemory.kt").read_text(encoding="utf-8")
        coordinator = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderTaskCoordinator.kt").read_text(encoding="utf-8")
        runtime = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        planner = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/HttpSpiderTaskPlanner.kt").read_text(encoding="utf-8")
        activity = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentActivity.kt").read_text(encoding="utf-8")
        backend = (ROOT / "backend/app/main.py").read_text(encoding="utf-8")
        smoke = (ROOT / "tests/kotlin/SpiderPersistenceSmoke.kt").read_text(encoding="utf-8")
        self.assertIn("class FileSpiderLearningMemory", memory)
        self.assertNotIn("MAX_EXPERIENCES = 500", memory)
        self.assertIn("SpiderSemanticSearch.similarity", memory)
        runtime = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        self.assertNotIn("listExperiences(limit = 512)", runtime)
        self.assertNotIn("learningMemory.listExperiences(limit = learningMemory.count())", runtime)
        sqlite_memory = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SqliteSpiderLearningMemory.kt").read_text(encoding="utf-8")
        sqlite_index = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SqliteSpiderEmbeddingIndex.kt").read_text(encoding="utf-8")
        self.assertIn("class SqliteSpiderLearningMemory", sqlite_memory)
        self.assertIn("CREATE TABLE learning_experiences", sqlite_memory)
        self.assertIn("memory_index_outbox", sqlite_memory)
        self.assertIn("listExperiencesAfter", sqlite_memory)
        self.assertNotIn("MAX_STORE_BYTES", sqlite_memory)
        self.assertIn("class SqliteSpiderEmbeddingIndex", sqlite_index)
        self.assertIn("nearestEmbeddings", sqlite_memory)
        self.assertIn("search_postings", sqlite_memory)
        semantic = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderSemanticSearch.kt").read_text(encoding="utf-8")
        skills = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderSkillLibrary.kt").read_text(encoding="utf-8")
        self.assertIn("class FileSpiderSkillLibrary", skills)
        self.assertIn("minimumVerifiedRuns", skills)
        self.assertIn("recordEvidence", skills)
        self.assertIn("Only declarative, typed tool sequences", skills)
        self.assertIn("multilingual concept aliases", semantic)
        self.assertIn("VERIFIED_SUCCESS", memory)
        self.assertIn("recordFeedback", memory)
        self.assertIn("fun forget(taskId: String)", memory)
        self.assertIn("fun clearAll()", memory)
        self.assertIn("topicTokens", memory)
        self.assertIn("terminalTaskObserver", coordinator)
        self.assertIn('"agent/learning-memory-v1.spider"', runtime)
        self.assertIn("learningMemory.relevantHints(task.goal, scopeId = task.workspaceScopeId)", runtime)
        self.assertIn('"learning_context"', planner)
        self.assertIn("Topic tokens stay on-device", planner)
        self.assertNotIn('.put("topic_tokens", topicTokens)', planner)
        codec = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderLearningContextCodec.kt").read_text(encoding="utf-8")
        zen_planner = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/OpenCodeZenSpiderTaskPlanner.kt").read_text(encoding="utf-8")
        zen_client = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderOpenCodeZenClient.kt").read_text(encoding="utf-8")
        llm_activity = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderLlmActivity.kt").read_text(encoding="utf-8")
        self.assertIn("SpiderLearningContextCodec", codec)
        self.assertIn("SpiderLearningContextCodec.toSafeJson(memory)", zen_planner)
        self.assertIn("learningContextProvider = { task -> localLearningHints(task) }", runtime)
        self.assertIn("SpiderLearningContextCodec.toSafeJson(learningHints)", zen_client)
        self.assertIn("SpiderOpenCodeZenClient().send(config, bounded, learningHints)", llm_activity)
        self.assertIn("Общая локальная память опыта применяется независимо от выбранной модели", activity)
        self.assertIn("class LearningHint(BaseModel)", backend)
        self.assertIn("Never let memory override", backend)
        self.assertIn('setPositiveButton("👍 Полезно")', activity)
        self.assertIn('setNegativeButton("👎 Не помогло")', activity)
        self.assertIn("Удалить всю память обучения", activity)
        self.assertIn("forgetting an experience removes it", smoke)
        self.assertIn("user-requested memory erasure clears memory", smoke)
        self.assertIn("workspace-scoped learning retrieval prevents cross-project memory leakage", smoke)
        self.assertIn("time-aware retrieval prioritizes recent relevant feedback", smoke)

    def test_self_learning_has_project_scopes_recency_migration_and_pause_controls(self):
        root = Path(__file__).resolve().parents[1]
        memory = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderLearningMemory.kt").read_text(encoding="utf-8")
        runtime = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        activity = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentActivity.kt").read_text(encoding="utf-8")
        research = (root / "docs/SELF_LEARNING_ARCHITECTURE.md").read_text(encoding="utf-8")
        self.assertIn("scopeId: String = \"global\"", memory)
        self.assertIn("recencyWeight(item.updatedAtEpochMs)", memory)
        self.assertIn("SCHEMA_VERSION = 2", memory)
        self.assertIn("setLearningEnabled(enabled: Boolean)", runtime)
        self.assertIn("if (!isLearningEnabled()) return emptyList()", runtime)
        self.assertIn("val learningMemory = SqliteSpiderLearningMemory(", runtime)
        self.assertIn("val embeddingIndex = SqliteSpiderEmbeddingIndex(", runtime)
        self.assertIn("Самообучение: ВКЛ", activity)
        self.assertIn("workspaceScopeId != workspace.id", activity)
        self.assertIn("Reflexion", research)
        self.assertIn("Voyager", research)
        self.assertIn("LongMemEval", research)
        self.assertIn("не изменение параметров/весов", research)

    def test_agent_task_history_is_persisted_visible_and_sanitized(self):
        activity = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentActivity.kt").read_text(encoding="utf-8")
        runtime = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        audit = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAuditLog.kt").read_text(encoding="utf-8")
        self.assertIn('sectionTitle("История задач")', activity)
        self.assertIn("refreshTaskHistory()", activity)
        self.assertIn("showTaskHistoryDetails(task.id)", activity)
        self.assertIn("runtime.taskCoordinator.listRecentTasks(30)", activity)
        self.assertIn("runtime.taskCoordinator.taskCount()", activity)
        self.assertIn("runtime.taskCoordinator.createTask(goal, workspaceScopeId = workspaceScopeId)", activity)
        self.assertIn("val workspaceScopeId = selectedWorkspace()?.id", activity)
        self.assertIn("task.workspaceScopeId != workspace.id", activity)
        self.assertIn("runtime.createTaskPlanner().proposePlan(candidate)", activity)
        self.assertIn("safeHistoryText(task.goal", activity)
        self.assertIn("runtime.safeAuditReferences(task.id, 8)", activity)
        self.assertIn("safeAuditReferences(taskId", runtime)
        self.assertIn("safeReferencesForTask", audit)
        self.assertIn("MAX_SCAN_BYTES", audit)
        self.assertIn("class SpiderAuditReference", audit)
        # Audit detail UI must not render raw event summary, request ID, or raw audit payloads.
        self.assertNotIn('append(json.optString("summary"))', audit)
        self.assertIn("resultCode", audit)

    def test_durable_task_store_is_versioned_and_preserves_future_schema(self):
        repo = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/FileSpiderTaskRepository.kt").read_text(encoding="utf-8")
        smoke = (ROOT / "scripts/test_agent_core_jvm.sh").read_text(encoding="utf-8")
        self.assertIn("SCHEMA_VERSION = 2", repo)
        self.assertIn("MIN_SUPPORTED_SCHEMA = 1", repo)
        self.assertIn("readTask(input, schema)", repo)
        self.assertIn("UnsupportedTaskStoreSchemaException", repo)
        self.assertIn("StandardCopyOption.ATOMIC_MOVE", repo)
        self.assertIn("recoveryNotice", repo)
        self.assertIn("SpiderPersistenceSmoke.kt", smoke)

    def test_no_release_signing_secrets(self):
        for p in ROOT.rglob("*"):
            if not p.is_file(): continue
            self.assertNotIn(p.suffix.lower(), {".jks", ".keystore", ".p12"}, str(p))


class V038OverlayAndBuildContractTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]

    def test_overlay_service_and_permission_are_present(self):
        manifest = (self.root / "android-project/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        service = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderOverlayService.kt").read_text(encoding="utf-8")
        self.assertIn("SYSTEM_ALERT_WINDOW", manifest)
        self.assertIn("SpiderOverlayService", manifest)
        self.assertIn("TYPE_APPLICATION_OVERLAY", service)
        self.assertIn("openSection(section)", service)
        self.assertIn('"♫ Медиа" to "media"', service)
        self.assertIn('"⌨ Code/Build" to "code"', service)

    def test_code_studio_has_preview_export_and_honest_apk_status(self):
        html = (self.root / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertIn('data-page="code"', html)
        self.assertIn('id="spiderCodePreview"', html)
        self.assertIn('id="spiderCodeSave"', html)
        self.assertIn('id="spiderCodeApk"', html)
        self.assertIn("Android.saveSpiderHtml", html)
        self.assertIn("APK не создавался", html)


class V038StageCProviderTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]

    def test_provider_rate_and_concurrency_guards_are_present_and_bounded(self):
        backend = (ROOT / "backend/app/main.py").read_text(encoding="utf-8")
        docs = (ROOT / "backend/README.md").read_text(encoding="utf-8")
        self.assertIn("SPIDER_PROVIDER_RATE_LIMIT_PER_MINUTE", backend)
        self.assertIn("PROVIDER_RATE_MAX_PER_MINUTE = 600", backend)
        self.assertIn("PROVIDER_MAX_CONCURRENT_REQUESTS = 4", backend)
        self.assertIn("provider_rate_limit_exceeded", backend)
        self.assertIn("provider_concurrency_limit_exceeded", backend)
        self.assertIn("Retry-After", backend)
        self.assertIn("workers or replicas", docs)

    def test_model_provider_key_stays_on_backend_and_plan_route_is_authenticated(self):
        backend = (self.root / "backend/app/main.py").read_text(encoding="utf-8")
        config = (self.root / "backend/.env.example").read_text(encoding="utf-8")
        self.assertIn("def _require_client_token", backend)
        self.assertIn("hmac.compare_digest", backend)
        self.assertIn('@app.post("/api/agent/plan")', backend)
        self.assertIn("PROVIDER_API_KEY", config)
        self.assertIn("SPIDER_API_TOKEN", config)
        self.assertIn('SUPPORTED_TOOL_IDS = {"LIST_WORKSPACE_FILES", "SEARCH_WORKSPACE_CONTENT", "READ_WORKSPACE_FILE", "WRITE_WORKSPACE_FILE", "CHECK_WORKSPACE_PROJECT"}', backend)
        self.assertNotIn("EXECUTE_SHELL", backend)

    def test_android_planner_rejects_redirects_unknown_tools_and_non_https(self):
        planner = (self.root / "android-project/app/src/main/java/com/iipauk/spider/HttpSpiderTaskPlanner.kt").read_text(encoding="utf-8")
        self.assertIn("instanceFollowRedirects = false", planner)
        self.assertIn('require(uri.scheme == "https")', planner)
        self.assertIn("CURRENTLY_IMPLEMENTED_TOOLS", planner)
        self.assertIn('SpiderPlanParser.parse(raw, expectedTaskId)', planner)
        parser = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderPlanParser.kt").read_text(encoding="utf-8")
        self.assertIn('response.optString("task_id") == expectedTaskId', parser)
        self.assertIn("MAX_RESPONSE_BYTES", planner)

    def test_provider_token_is_encrypted_in_android_keystore_and_task_ui_is_wired(self):
        store = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderProviderConfigStore.kt").read_text(encoding="utf-8")
        runtime = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        activity = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentActivity.kt").read_text(encoding="utf-8")
        manifest = (self.root / "android-project/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        bridge = (self.root / "android-project/app/src/main/java/com/iipauk/spider/MainActivity.kt").read_text(encoding="utf-8")
        html = (self.root / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertIn("AndroidKeyStore", store)
        self.assertIn("AES/GCM/NoPadding", store)
        self.assertIn("createTaskPlanner()", runtime)
        self.assertIn("worker.execute", activity)
        self.assertIn('android:name=".SpiderAgentActivity"', manifest)
        self.assertIn('"open_agent" ->', bridge)
        self.assertIn("openSpiderAgent: function()", bridge)
        self.assertIn('id="quickTaskManager"', html)
        self.assertEqual((self.root / "android-project/app/src/main/assets/web/ii-pauk.html").read_bytes(), (self.root / "app-web/ii-pauk.html").read_bytes())


    def test_agent_ui_requires_workspace_selection_and_explicit_one_time_step_approval(self):
        activity = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentActivity.kt").read_text(encoding="utf-8")
        runtime = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        broker = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderToolBroker.kt").read_text(encoding="utf-8")
        self.assertIn("workspaceSpinner", activity)
        self.assertIn("showCreateWorkspaceDialog", activity)
        self.assertIn("AlertDialog.Builder(this)", activity)
        self.assertIn("Разрешить один раз", activity)
        self.assertIn("approvedRequestId", activity)
        self.assertIn("SpiderPolicyContext(approvedRequestIds = setOf(approvedRequestId))", activity)
        self.assertIn("runtime.toolBroker.execute", activity)
        self.assertIn("LIST_WORKSPACE_FILES", activity)
        self.assertIn("listWorkspaces()", runtime)
        self.assertIn("workspaceRepository.create(name)", runtime)
        self.assertIn("execution_started", broker)
        self.assertIn("Проверка: ", activity)
        self.assertIn("finishVerification", activity)
        self.assertIn("workspaceListingVerifier.verify", activity)
        self.assertIn("workspaceTextToolVerifier.verify", activity)
        self.assertIn("READ_WORKSPACE_FILE", activity)
        self.assertIn("SEARCH_WORKSPACE_CONTENT", activity)
        self.assertIn("WRITE_WORKSPACE_FILE", activity)
        verifier = (self.root / "android-project/app/src/main/java/com/iipauk/spider/WorkspaceListingResultVerifier.kt").read_text(encoding="utf-8")
        evidence = (self.root / "android-project/app/src/main/java/com/iipauk/spider/WorkspaceListingEvidence.kt").read_text(encoding="utf-8")
        handler = (self.root / "android-project/app/src/main/java/com/iipauk/spider/WorkspaceFileListHandler.kt").read_text(encoding="utf-8")
        broker = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderToolBroker.kt").read_text(encoding="utf-8")
        self.assertIn("verification_audit_missing", verifier)
        self.assertIn("verification_workspace_unavailable", verifier)
        self.assertIn("verification_inventory_mismatch", verifier)
        self.assertIn("independentlyEnumerate", verifier)
        self.assertIn("data class WorkspaceListingEvidence", evidence)
        self.assertIn("digestPaths", evidence)
        self.assertIn("verificationEvidence = evidence.encode()", handler)
        self.assertIn("status = if (succeeded && !outputTooLarge)", broker)
        self.assertNotIn("executeShell", activity)


    def test_internal_skill_learning_uses_only_verified_real_workspace_tools(self):
        skill_library = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderSkillLibrary.kt").read_text(encoding="utf-8")
        activity = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentActivity.kt").read_text(encoding="utf-8")
        for skill_id in (
            "inspect_workspace_inventory_v1",
            "search_workspace_content_v1",
            "read_workspace_file_v1",
            "write_workspace_file_v1",
        ):
            self.assertIn(skill_id, skill_library)
        self.assertIn("recordVerifiedBuiltInToolSuccess", skill_library)
        self.assertIn("WorkspaceTextToolVerifier", skill_library)
        self.assertIn("minimumVerifiedRuns = 3", skill_library)
        self.assertIn("if (succeeded && runtime.isLearningEnabled())", activity)
        self.assertIn("recordVerifiedBuiltInToolSuccess(tool, finalTask.id)", activity)
        self.assertIn("SpiderToolId.WRITE_WORKSPACE_FILE", skill_library)
        self.assertIn("IMPLEMENTED_SKILL_TOOLS = setOf(", skill_library)
        self.assertNotIn("SpiderToolId.DELETE_WORKSPACE_FILE,", skill_library.split("IMPLEMENTED_SKILL_TOOLS = setOf(", 1)[1].split(")", 1)[0])

    def test_android_ci_compiles_native_llm_and_checks_jni_on_emulator(self):
        workflow = (self.root / ".github/workflows/android-build.yml").read_text(encoding="utf-8")
        test = (self.root / "android-project/app/src/androidTest/java/com/iipauk/spider/LocalLlmNativeLibraryInstrumentedTest.kt").read_text(encoding="utf-8")
        gradle = (self.root / "android-project/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("ndk;27.2.12479018", workflow)
        self.assertIn("cmake;3.22.1", workflow)
        self.assertIn("connectedDebugAndroidTest", workflow)
        self.assertIn('ndkVersion = "27.2.12479018"', gradle)
        self.assertIn("runtime.engineAvailable", test)
        self.assertIn("runtime.isModelLoaded", test)
        native = (self.root / "android-project/app/src/main/cpp/local_llm_jni.cpp").read_text(encoding="utf-8")
        self.assertIn("utf8_to_utf16", native)
        self.assertIn("cp = 0xFFFD", native)
        self.assertIn("std::numeric_limits<jsize>::max()", native)

    def test_cloud_chat_bounds_actual_utf8_payload_and_does_not_store_transport_errors_as_assistant_turns(self):
        root = Path(__file__).resolve().parents[1]
        chat = (root / "android-project/app/src/main/java/com/iipauk/spider/HttpSpiderChatClient.kt").read_text(encoding="utf-8")
        activity = (root / "android-project/app/src/main/java/com/iipauk/spider/SpiderLlmActivity.kt").read_text(encoding="utf-8")
        instrumentation = (root / "android-project/app/src/androidTest/java/com/iipauk/spider/HttpSpiderChatClientInstrumentedTest.kt").read_text(encoding="utf-8")
        self.assertIn("encodePayload(boundedMessages, safeHints)", chat)
        self.assertIn("while (payload.size > MAX_REQUEST_BYTES && boundedMessages.size > 1)", chat)
        self.assertIn("private const val MAX_REQUEST_BYTES = 14_000", chat)
        self.assertIn("captured.size() <= 14_000", instrumentation)
        self.assertIn("ПОСЛЕДНИЙ ВОПРОС", instrumentation)
        self.assertIn("conversation.removeAt(conversation.lastIndex)", activity)
        self.assertIn("val selectedMode = runtime.llmMode()", activity)
        self.assertIn("respond(prompt, selectedMode)", activity)
        self.assertIn("private fun respond(prompt: String, selectedMode: SpiderLlmMode)", activity)
        self.assertNotIn('conversation += "assistant" to "Не удалось получить ответ:', activity)

    def test_local_then_yandex_fallback_is_lazy_and_not_required_for_local_mode(self):
        planner = (self.root / "android-project/app/src/main/java/com/iipauk/spider/LocalSpiderTaskPlanner.kt").read_text(encoding="utf-8")
        runtime = (self.root / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        self.assertIn("private val remoteFactory: () -> SpiderTaskPlanner", planner)
        self.assertIn("remoteFactory().proposePlan(task)", planner)
        self.assertIn("LocalThenRemoteSpiderTaskPlanner(local) { remotePlanner() }", runtime)
        self.assertIn("SpiderLlmMode.LOCAL_ONLY ->", runtime)

    def test_local_llm_yandex_routing_and_model_import_are_explicit(self):
        app_root = ROOT / "android-project/app/src/main/java/com/iipauk/spider"
        runtime = (app_root / "SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        llm_runtime = (app_root / "SpiderLocalLlmRuntime.kt").read_text(encoding="utf-8")
        model_store = (app_root / "SpiderLocalModelStore.kt").read_text(encoding="utf-8")
        activity = (app_root / "SpiderLlmActivity.kt").read_text(encoding="utf-8")
        native = (ROOT / "android-project/app/src/main/cpp/local_llm_jni.cpp").read_text(encoding="utf-8")
        cmake = (ROOT / "android-project/app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        backend = (ROOT / "backend/app/main.py").read_text(encoding="utf-8")
        env = (ROOT / "backend/.env.example").read_text(encoding="utf-8")
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertIn("SpiderLlmMode.LOCAL_ONLY", runtime)
        self.assertIn("SpiderLlmMode.YANDEX_CLOUD", runtime)
        self.assertIn("SpiderLlmMode.LOCAL_THEN_YANDEX", runtime)
        self.assertIn('System.loadLibrary("spiderllm")', llm_runtime)
        self.assertIn('File(root, "llm")', model_store)
        self.assertIn('contentEquals(byteArrayOf', model_store)
        self.assertIn("ACTION_OPEN_DOCUMENT", activity)
        self.assertIn("nativeGenerate", native)
        self.assertIn("GIT_TAG v0.6.0", cmake)
        self.assertIn("PROVIDER_KIND=yandex_ai_studio", env)
        self.assertIn('Authorization": f"Api-Key', backend)
        self.assertIn('"OpenAI-Project"', backend)
        self.assertIn('"x-data-logging-enabled": "false"', backend)
        self.assertIn("/api/chat", backend)
        self.assertIn('id="quickLlm"', html)
        self.assertIn("openSpiderLlm: function()", (app_root / "MainActivity.kt").read_text(encoding="utf-8"))
        # Large user-supplied models should not exhaust storage during import, and mapped weights
        # must be unloaded before an existing GGUF file with the same name can be replaced.
        self.assertIn("private fun querySize(uri: Uri, resolver: ContentResolver): Long?", model_store)
        self.assertIn("initialSpace >= (sourceSize ?: 0L) + initialReserve", model_store)
        self.assertIn("SPACE_CHECK_INTERVAL_BYTES", model_store)
        self.assertIn("sourceSize == null || total == sourceSize", model_store)
        self.assertIn("val priorLoadedName = runtime.localLlmRuntime.loadedModelName", activity)
        self.assertLess(activity.index("runtime.localLlmRuntime.unloadModel()", activity.index("val priorLoadedName")), activity.index("runtime.localModelStore.importModel(uri, contentResolver)"))


    def test_floating_spider_snaps_retracts_and_crawls_out(self):
        overlay = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderOverlayService.kt").read_text(encoding="utf-8")
        manifest = (ROOT / "android-project/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        html = (ROOT / "app-web/ii-pauk.html").read_text(encoding="utf-8")
        self.assertIn("TYPE_APPLICATION_OVERLAY", overlay)
        self.assertIn("dockBubbleAndScheduleRetract", overlay)
        self.assertIn("retractBubble()", overlay)
        self.assertIn("expandBubble", overlay)
        self.assertIn('"__tuck"', overlay)
        self.assertIn('"Скрыть Паука"', overlay)
        self.assertIn("android.permission.SYSTEM_ALERT_WINDOW", manifest)
        self.assertIn("z-index:260", html)
        self.assertIn("z-index:262", html)
        self.assertIn('id="quickCapabilities"', html)
        self.assertIn("function tuckSpiderToEdge()", html)
        self.assertIn("function revealSpiderFromEdge()", html)
        self.assertIn("edge-tucked", html)
        self.assertEqual(html.encode(), (ROOT / "android-project/app/src/main/assets/web/ii-pauk.html").read_bytes())

    def test_capability_catalog_teaches_only_real_agent_tools(self):
        catalog = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderCapabilityCatalog.kt").read_text(encoding="utf-8")
        runtime = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentRuntime.kt").read_text(encoding="utf-8")
        local_planner = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/LocalSpiderTaskPlanner.kt").read_text(encoding="utf-8")
        backend = (ROOT / "backend/app/main.py").read_text(encoding="utf-8")
        self.assertIn("fun snapshot(flags: SpiderRuntimeCapabilityFlags)", catalog)
        self.assertIn("fun capabilityGuide(): String", runtime)
        self.assertIn("append(capabilityGuide())", runtime)
        self.assertIn("Поддерживаются пять настоящих инструментов рабочего пространства", local_planner)
        self.assertIn("У Agent Broker есть пять реальных операций", backend)
        self.assertIn("external_app_control", catalog)
        self.assertIn("APK", catalog)
        agent_activity = (ROOT / "android-project/app/src/main/java/com/iipauk/spider/SpiderAgentActivity.kt").read_text(encoding="utf-8")
        self.assertIn("showCapabilityMap()", agent_activity)
        self.assertIn("Карта возможностей ИИ Паука", agent_activity)


if __name__ == "__main__":
    unittest.main(verbosity=2)
