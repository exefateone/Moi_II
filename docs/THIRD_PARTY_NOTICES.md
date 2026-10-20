# Third-party notices — ИИ Паук v0.38 Stage C1

## Termux TerminalView / TerminalEmulator

- Project: Termux Android app libraries
- Upstream: https://github.com/termux/termux-app
- Version requested by this project: `0.118.0`
- Maven artifact: `com.termux.termux-app:terminal-view:0.118.0` from JitPack
- Components used: `TerminalView`, `TerminalSession`, terminal rendering and the PTY implementation pulled in transitively by `terminal-view`
- License: Apache License 2.0 under the upstream project's library license exception
- Official license/library notes: https://github.com/termux/termux-app/wiki/Termux-Libraries

This source archive declares the dependency in Gradle; it does not vendor the upstream source or AAR. The build system fetches the artifact on a network-enabled Gradle sync/build. Keep the applicable license and attribution notices in distributed APK/source packages. The full Termux application and `termux-shared` are not included by this integration.

The text of Apache License 2.0 is included as `THIRD_PARTY_APACHE_2.0.txt` alongside this notice.

## Qwen2.5-0.5B-Instruct-GGUF smoke-test model

- Model: `Qwen/Qwen2.5-0.5B-Instruct-GGUF`
- File intended for an opt-in smoke test: `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- Upstream: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF
- License stated on model card: Apache-2.0
- SHA-256 pinned in `scripts/download_qwen_smoke_model.sh`: `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`

The model weights are not included in the project archive. The download script fetches them only when the user runs it intentionally and validates the checksum before renaming the file into place. This small model is for engine smoke testing; it is not presented as the final-quality assistant model.
