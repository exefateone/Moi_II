# Meta Muse principles adapted for ИИ Паук

This document separates publicly described product patterns from our own implementation. Spider is not a Meta product and does not require Meta's model or private infrastructure.

## Principles to adapt

| Publicly described Muse pattern | Spider implementation target | Current status |
|---|---|---|
| User states a goal in ordinary language; agent coordinates multi-step work | Spider Agent Core: goal -> plan -> tool -> verify -> report | Local UI has a rules-based planner; full agent loop is future |
| Agent runs in a dedicated working environment | Spider Workspace + future isolated Linux worker | App-private workspace repository exists; PTY/Linux/remote VM are pending |
| Safety layer separated from the agent | Spider Policy Engine / Sentinel | Typed source foundation exists; not an OS sandbox or network firewall yet |
| Agent keeps useful context across a project | Spider Task Repository + project memory | Schema-v1 durable task repository is connected to runtime; visible task history, migration policy and project memory remain future work |
| Long tasks need progress and continuation | Checkpoints, cancellation, bounded retries, resume | Planned; not implemented |
| Agent acts through controlled integrations | Typed `SpiderToolId` and one handler per capability | Enums/contracts exist; execution handlers are future |
| User can inspect actions and control access | Approval screen + audit trail | Audit source exists; approval UI and live operation wiring are future |

## Our differences and constraints

- Local Android execution uses Android's sandbox. A Linux userland via PRoot, if selected, is not a full VM and does not confer root access.
- Long-running work that must survive Android process termination will need Android WorkManager/service behavior or an optional remote worker; it cannot be promised by an Activity alone.
- Browser content and downloaded files are untrusted input. They do not become instructions with authority over system tools.
- Model output never becomes a raw shell command. Tool calls use enum IDs, validated arguments and an explicit policy gate.
- Media playback is a first-class subsystem: Android document-provider files, direct HTTPS media URLs, HLS and MPEG-DASH streams use Media3. A queue and MediaSessionService source are included; actual device playback remains unverified.
- Memory needs explicit view/delete/export controls and should never become an uncontrolled transcript dump.

## Source references

Meta's September 8, 2026 public introduction describes Muse as a personal agent running within a dedicated secure computer (Muse Secure VM), with a separate Sentinel gate approving outbound internet activity and asking the user for permissions when needed. This informed our separation-of-concerns design, but it does not establish that Spider has equivalent isolation or security guarantees.

AndroidX documents Media3 `ExoPlayer`, `MediaSession`, `PlayerView`, and `MediaSessionService`; the latter is recommended for background playback. Media3 1.11.1 is listed as stable with a September 10, 2026 update.

- https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/
- https://developer.android.com/media/media3
- https://developer.android.com/media/media3/session/background-playback
- https://developer.android.com/jetpack/androidx/releases/media3
