# Terminal PTY and local smoke-model dependencies — Stage C1 (17 October 2026)

## Terminal PTY dependency

The Android app now declares `com.termux.termux-app:terminal-view:0.118.0` from JitPack. That library transitively includes `terminal-emulator`, which provides the Android terminal renderer and PTY session. The project already lists JitPack in `android-project/settings.gradle.kts`.

This integration uses **only the TerminalView and TerminalSession APIs**. It does not include the Termux app, `termux-shared`, its bootstrap archive, package repositories, or a Linux distribution. The shell command is `/system/bin/sh`, runs as the normal `com.iipauk.spider` app UID, and starts in the app-private `filesDir`; it does not grant root. Access to files outside the app sandbox remains governed by Android's UID and platform permissions.

The upstream project documents terminal libraries as published on JitPack for versions >= 0.116 and specifies the `com.termux.termux-app:terminal-view:0.118.0` coordinate. The terminal-view and terminal-emulator code are covered by the Apache-2.0 exception in the upstream license notes; the full Termux app itself is GPLv3-only, and its GPL-only `termux-shared` library is not included. Keep `docs/THIRD_PARTY_APACHE_2.0.txt` with redistributions of the Apache-licensed libraries and retain attribution/notices required by the license.

The dependency is **declared, not vendored**. Gradle will fetch it from JitPack when the project is synced/built on a network-enabled machine. The current execution environment could not retrieve AAR binary files, and lacks Android SDK/Gradle/NDK/emulator, so dependency resolution and the app compilation are not verified here.

## Optional model download

`scripts/download_qwen_smoke_model.sh` fetches the official Qwen/Qwen2.5-0.5B-Instruct-GGUF Q4_K_M model, approximately 491 MB, then verifies SHA-256:

`74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`

The model card labels the model Apache-2.0. This 0.5B model is meant only for a first local inference smoke test; it is a compact, quality-limited model, not the desired final assistant. The download is **opt-in** and not part of app startup, Gradle, or the source archive, because it is large and devices vary in available RAM/storage. Run `./scripts/download_qwen_smoke_model.sh` on a machine with curl, a SHA-256 utility, internet access, and sufficient disk space. Afterwards copy the file to the Android device and import it inside `ИИ Паук → Local LLM → Импортировать GGUF`; Android may block direct filesystem access to `Android/data`.

The binary model could not be physically downloaded from this environment (network download failed). The script is included and checksum-pinned; no `.gguf` model file is claimed to be present in this archive.

## Acceptance required

1. Sync the Gradle project and confirm the JitPack dependency resolves.
2. Build `:app:assembleDebug`; resolve any API/compiler errors against the exact library artifact.
3. On a device/emulator, open Terminal from both the app UI and the floating widget; check `pwd`, `id`, `echo hello`, arrow keys, Ctrl-C, copy/paste, background/resume, close/reopen and crash-free PTY teardown.
4. Confirm `id` is the app's unprivileged UID and that the process stays in the Android app sandbox. Do not route agent-generated plans directly to this interactive shell.
5. For local inference, import the checksum-verified GGUF and test model load, a short Russian prompt, unload/reload, memory use and low-memory failure.

Until those checks pass, diagnostics mark `terminalPtyVerified=false` and `linuxRuntime=false` deliberately.

## Command handling boundary

The old HTML helper console continues to serve local, allowlisted app actions and does **not** execute operating-system shell commands. The Android-native PTY is opened explicitly through `Android.openSpiderTerminal()`; users type OS commands there themselves. Chat text or agent plans are not passed directly into the shell, which avoids turning untrusted/generated text into implicit command execution. The helper response was updated to say this clearly instead of the stale “PTY not connected” status.
