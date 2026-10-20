import com.iipauk.spider.*
import java.io.File
import java.nio.file.Files

/** Exercised from SpiderPersistenceSmoke so all Agent Core checks share one JVM harness. */
fun runWorkspaceTextToolsSmoke(base: File) {
    val workspaceId = "12345678-1234-1234-1234-123456789012"
    val root = File(base, "real-workspace-tools")
    checkTrue(root.mkdirs(), "workspace root created")
    File(root, ".spider-workspace.json").writeText("internal metadata must remain inaccessible")
    File(root, "README.md").writeText(
        "Project notes\nneedle: first hit\napi_key=supersecretvalue\nneedle: second hit\n", Charsets.UTF_8
    )
    File(root, "private.env").writeText("needle private should not be scanned")
    val outside = File(base, "outside.md").apply { writeText("outside should never be reachable") }
    val symlinkCreated = runCatching { Files.createSymbolicLink(File(root, "jump.md").toPath(), outside.toPath()) }.isSuccess
    val redactedSamples = WorkspaceToolSafety.sanitizeOutput(
        "token=plain-secret ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890 github_pat_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890 xoxb-1234567890-abcdef"
    )
    checkTrue(!redactedSamples.contains("plain-secret") && !redactedSamples.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890") &&
        !redactedSamples.contains("github_pat_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890") && !redactedSamples.contains("xoxb-1234567890-abcdef"),
        "common API and source-control token formats are redacted")
    val quotedSecretSamples = WorkspaceToolSafety.sanitizeOutput(
        """{"token": "secret value with spaces", "password": 'another private phrase'} Bearer "bearer secret with spaces"""
    )
    checkTrue(!quotedSecretSamples.contains("secret value with spaces") && !quotedSecretSamples.contains("another private phrase") && !quotedSecretSamples.contains("bearer secret with spaces") &&
        quotedSecretSamples.count { it == '[' } >= 3,
        "quoted credential values containing spaces are fully redacted")

    fun directoryFor(id: String): File? = if (id == workspaceId) root else null
    val auditSummaries = mutableListOf<String>()
    val broker = SpiderToolBroker(
        handlers = listOf(
            WorkspaceFileListHandler(::directoryFor),
            WorkspaceContentSearchHandler(::directoryFor),
            WorkspaceFileReadHandler(::directoryFor),
            WorkspaceFileWriteHandler(::directoryFor)
        ),
        auditSink = SpiderAuditSink { _, _, _, _, code, summary ->
            auditSummaries += "$code $summary"
            true
        }
    )
    val verifier = WorkspaceTextToolVerifier(::directoryFor)

    // A read requires its own explicit one-shot approval and redacts obvious credentials.
    val read = SpiderToolRequest(
        requestId = "workspace-read-approval-1", taskId = "workspace-read-task", toolId = SpiderToolId.READ_WORKSPACE_FILE,
        summary = "Read a selected text file after confirmation", resourceReference = "README.md", workspaceId = workspaceId
    )
    checkTrue(broker.execute(read).status == SpiderToolCallStatus.WAITING_FOR_APPROVAL, "file read waits for explicit approval")
    val readResult = broker.execute(read, SpiderPolicyContext(approvedRequestIds = setOf(read.requestId)))
    checkTrue(readResult.status == SpiderToolCallStatus.SUCCEEDED, "approved text read executes")
    checkTrue(readResult.outputPayload?.contains("needle: first hit") == true, "read returns actual file text")
    checkTrue(readResult.outputPayload?.contains("supersecretvalue") != true && readResult.outputPayload?.contains("[REDACTED]") == true, "read masks obvious credentials")
    checkTrue(verifier.verify(read, readResult).verified, "read gets independent file-and-digest verification")

    // Search runs against real files, skips sensitive/metadata names and repeats the scan to verify it.
    val search = SpiderToolRequest(
        requestId = "workspace-search-approval-1", taskId = "workspace-search-task", toolId = SpiderToolId.SEARCH_WORKSPACE_CONTENT,
        summary = "Search selected project text after confirmation", workspaceId = workspaceId, inputText = "needle"
    )
    checkTrue(broker.execute(search).status == SpiderToolCallStatus.WAITING_FOR_APPROVAL, "workspace search requires approval")
    val searchResult = broker.execute(search, SpiderPolicyContext(approvedRequestIds = setOf(search.requestId)))
    checkTrue(searchResult.status == SpiderToolCallStatus.SUCCEEDED, "approved search executes")
    checkTrue(searchResult.outputPayload?.contains("README.md:2") == true && searchResult.outputPayload?.contains("README.md:4") == true, "search returns correct line hits")
    checkTrue(verifier.verify(search, searchResult).verified, "search result matches an independent second pass")
    checkTrue(searchResult.outputPayload?.contains("private should not be scanned") != true, "known sensitive suffix is excluded from search")
    checkTrue(readResult.outputPayload?.contains("supersecretvalue") != true, "read output does not expose the sample credential")

    // A write uses user-confirmed text, will not overwrite an existing file by default, and is re-read for proof.
    val write = SpiderToolRequest(
        requestId = "workspace-write-approval-1", taskId = "workspace-write-task", toolId = SpiderToolId.WRITE_WORKSPACE_FILE,
        summary = "Write explicit user-provided text after confirmation", resourceReference = "docs/new.md",
        workspaceId = workspaceId, inputText = "Generated by the user-approved write action.\napi_key=secret-do-not-log"
    )
    checkTrue(broker.execute(write).status == SpiderToolCallStatus.WAITING_FOR_APPROVAL, "write waits for explicit approval")
    val writeResult = broker.execute(write, SpiderPolicyContext(approvedRequestIds = setOf(write.requestId)))
    checkTrue(writeResult.status == SpiderToolCallStatus.SUCCEEDED, "approved write creates a new file")
    checkTrue(File(root, "docs/new.md").readText(Charsets.UTF_8) == write.inputText, "write persists the exact UTF-8 text")
    checkTrue(verifier.verify(write, writeResult).verified, "write is independently verified by re-read and SHA-256")
    checkTrue(auditSummaries.none { it.contains("secret-do-not-log") || it.contains("supersecretvalue") }, "audit summaries never contain file contents or discovered secrets")

    val overwriteWithoutConsent = write.copy(
        requestId = "workspace-write-existing-no-consent", taskId = "workspace-write-existing-task", inputText = "not allowed silently",
        resourceReference = "README.md", overwriteExisting = false
    )
    val overwriteBlocked = broker.execute(overwriteWithoutConsent, SpiderPolicyContext(approvedRequestIds = setOf(overwriteWithoutConsent.requestId)))
    checkTrue(overwriteBlocked.status == SpiderToolCallStatus.FAILED && overwriteBlocked.resultCode == "overwrite_not_confirmed", "existing-file replacement fails closed without separate overwrite consent")
    val overwriteAllowed = overwriteWithoutConsent.copy(
        requestId = "workspace-write-existing-with-consent", overwriteExisting = true, inputText = "approved replacement"
    )
    val overwriteResult = broker.execute(overwriteAllowed, SpiderPolicyContext(approvedRequestIds = setOf(overwriteAllowed.requestId)))
    checkTrue(overwriteResult.status == SpiderToolCallStatus.SUCCEEDED && verifier.verify(overwriteAllowed, overwriteResult).verified, "separately approved overwrite executes and verifies")
    checkTrue(File(root, "README.md").readText(Charsets.UTF_8) == "approved replacement", "explicit overwrite changed the intended file")

    // AI-proposed edits are bound to the SHA-256 of the exact source reviewed by the user.
    val guardedFile = File(root, "guarded.md").apply { writeText("reviewed original", Charsets.UTF_8) }
    val reviewedHash = WorkspaceToolSafety.sha256(guardedFile.readBytes())
    guardedFile.writeText("changed after preview", Charsets.UTF_8)
    val staleProposal = SpiderToolRequest(
        requestId = "workspace-edit-stale-1", taskId = "workspace-edit-stale-task", toolId = SpiderToolId.WRITE_WORKSPACE_FILE,
        summary = "Apply reviewed AI edit with a source hash", resourceReference = "guarded.md", workspaceId = workspaceId,
        inputText = "model proposed replacement", overwriteExisting = true, expectedPreimageSha256 = reviewedHash
    )
    val staleResult = broker.execute(staleProposal, SpiderPolicyContext(approvedRequestIds = setOf(staleProposal.requestId)))
    checkTrue(staleResult.status == SpiderToolCallStatus.FAILED && staleResult.resultCode == "preimage_changed", "AI edit is rejected if the source changes after preview")
    checkTrue(guardedFile.readText(Charsets.UTF_8) == "changed after preview", "stale proposal does not overwrite newer user edits")

    val freshHash = WorkspaceToolSafety.sha256(guardedFile.readBytes())
    val freshProposal = staleProposal.copy(
        requestId = "workspace-edit-fresh-1", taskId = "workspace-edit-fresh-task",
        inputText = "approved model replacement", expectedPreimageSha256 = freshHash
    )
    val freshResult = broker.execute(freshProposal, SpiderPolicyContext(approvedRequestIds = setOf(freshProposal.requestId)))
    checkTrue(freshResult.status == SpiderToolCallStatus.SUCCEEDED && verifier.verify(freshProposal, freshResult).verified, "fresh AI proposal is written and independently verified")
    checkTrue(guardedFile.readText(Charsets.UTF_8) == "approved model replacement", "approved fresh proposal is applied to intended file")

    val newFileProposal = staleProposal.copy(
        requestId = "workspace-edit-create-1", taskId = "workspace-edit-create-task", resourceReference = "new-ai.md",
        inputText = "created from approved proposal", overwriteExisting = false, expectedPreimageSha256 = "MISSING"
    )
    val newFileResult = broker.execute(newFileProposal, SpiderPolicyContext(approvedRequestIds = setOf(newFileProposal.requestId)))
    checkTrue(newFileResult.status == SpiderToolCallStatus.SUCCEEDED && verifier.verify(newFileProposal, newFileResult).verified, "AI proposal can create a new file only while its path remains missing")
    val collisionProposal = newFileProposal.copy(
        requestId = "workspace-edit-create-collision-1", taskId = "workspace-edit-create-collision-task", inputText = "must not overwrite"
    )
    val collisionResult = broker.execute(collisionProposal, SpiderPolicyContext(approvedRequestIds = setOf(collisionProposal.requestId)))
    checkTrue(collisionResult.status == SpiderToolCallStatus.FAILED && collisionResult.resultCode == "preimage_changed", "a file created after preview blocks an AI-create collision")

    // Path traversal, credential files, workspace identity spoofing and symlinks fail closed.
    checkTrue(runCatching { WorkspaceToolSafety.resolveTarget(root, "../outside.md", allowMissingLeaf = false) }.isFailure, "parent traversal is blocked")
    checkTrue(runCatching { WorkspaceToolSafety.resolveTarget(root, "private.env", allowMissingLeaf = false) }.isFailure, "secret-looking files are blocked")
    checkTrue(runCatching { WorkspaceToolSafety.resolveTarget(root, ".spider-workspace.json", allowMissingLeaf = false) }.isFailure, "internal workspace marker is blocked")
    if (symlinkCreated) checkTrue(runCatching { WorkspaceToolSafety.resolveTarget(root, "jump.md", allowMissingLeaf = false) }.isFailure, "symlink escape is blocked")
    val wrongWorkspace = read.copy(requestId = "wrong-workspace-id", workspaceId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    val wrongResult = broker.execute(wrongWorkspace, SpiderPolicyContext(approvedRequestIds = setOf(wrongWorkspace.requestId)))
    checkTrue(wrongResult.status == SpiderToolCallStatus.FAILED, "unknown workspace ID cannot be accessed")

    // Prove that the registered executor contains only these five typed, bounded workspace tools.
    val dangerousRequest = SpiderToolRequest(
        requestId = "unsupported-delete-tool", taskId = "unsupported-delete-task", toolId = SpiderToolId.DELETE_WORKSPACE_FILE,
        summary = "Try unsupported delete tool", resourceReference = "README.md", workspaceId = workspaceId
    )
    val dangerousResult = broker.execute(dangerousRequest, SpiderPolicyContext(approvedRequestIds = setOf(dangerousRequest.requestId)))
    checkTrue(dangerousResult.status == SpiderToolCallStatus.FAILED && dangerousResult.resultCode == "tool_handler_missing", "no delete/shell/external-action executor is silently enabled")

    println("PASS real workspace tools: guarded read/search/write, stale-source prevention for AI edits, overwrite consent, path safety and independent verification")
}

/** Read-only bounded project checker verifies syntax evidence without executing project code. */
fun runWorkspaceProjectCheckSmoke(base: File) {
    val workspaceId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    val root = File(base, "static-project-check")
    checkTrue(root.mkdirs(), "static-check workspace created")
    File(root, "package.json").writeText("{\"name\":\"spider-demo\",\"version\":\"1.0.0\"}", Charsets.UTF_8)
    File(root, "broken.json").writeText("{\"name\":,}", Charsets.UTF_8)
    File(root, "layout.xml").writeText("<root><child/></root>", Charsets.UTF_8)
    File(root, "broken.svg").writeText("<svg><path></svg>", Charsets.UTF_8)
    File(root, "script.sh").writeText("#!/bin/sh\ntouch ${File(root, "MUST_NOT_RUN").absolutePath}\n", Charsets.UTF_8)
    File(root, ".env").writeText("api_key=must-not-read", Charsets.UTF_8)
    val validHandler = WorkspaceProjectCheckHandler { id -> if (id == workspaceId) root else null }
    val audit = mutableListOf<String>()
    val broker = SpiderToolBroker(
        handlers = listOf(validHandler),
        auditSink = SpiderAuditSink { _, _, _, _, code, summary -> audit += "$code $summary"; true }
    )
    val verifier = WorkspaceProjectCheckVerifier { id -> if (id == workspaceId) root else null }
    val request = SpiderToolRequest(
        requestId = "static-check-once-1", taskId = "static-check-task-1",
        toolId = SpiderToolId.CHECK_WORKSPACE_PROJECT,
        summary = "Run bounded static project check after user confirmation",
        workspaceId = workspaceId
    )
    checkTrue(broker.execute(request).status == SpiderToolCallStatus.WAITING_FOR_APPROVAL,
        "static project check requires one-shot user approval")
    val result = broker.execute(request, SpiderPolicyContext(approvedRequestIds = setOf(request.requestId)))
    checkTrue(result.status == SpiderToolCallStatus.SUCCEEDED, "approved static project check runs")
    checkTrue(result.outputPayload?.contains("json_syntax_invalid") == true, "invalid JSON is reported")
    checkTrue(result.outputPayload?.contains("xml_syntax_invalid") == true, "invalid XML/SVG is reported")
    checkTrue(result.outputPayload?.contains("не запускались") == true, "report makes execution boundary explicit")
    checkTrue(!File(root, "MUST_NOT_RUN").exists(), "shell script was not executed")
    checkTrue(!result.outputPayload.orEmpty().contains("must-not-read"), "secret-looking files are not disclosed")
    checkTrue(verifier.verify(workspaceId, result).verified, "static report is repeated and independently evidence-checked")
    File(root, "package.json").writeText("{\"name\":\"changed-after-check\"}", Charsets.UTF_8)
    checkTrue(!verifier.verify(workspaceId, result).verified, "result is rejected if files change after the check")
    checkTrue(audit.none { it.contains("must-not-read") || it.contains("changed-after-check") }, "audit never stores project file content")

    val unsupportedRequest = request.copy(requestId = "static-check-other-workspace", workspaceId = "ffffffff-ffff-ffff-ffff-ffffffffffff")
    val unknown = broker.execute(unsupportedRequest, SpiderPolicyContext(approvedRequestIds = setOf(unsupportedRequest.requestId)))
    checkTrue(unknown.status == SpiderToolCallStatus.FAILED, "unknown workspace cannot be statically inspected")
    println("PASS bounded static project check: approval, JSON/XML syntax findings, no code execution, protected paths, repeat verification and stale-result rejection")
}
