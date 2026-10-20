# Spider Sentinel — tool policy and approvals

## Rule of authority

Tool permissions are assigned by trusted application code, not by the model. Web content, prompt text, files and metadata cannot grant themselves authority.

## Policy baseline

| Tool category | Default risk | Default behavior |
|---|---|---|
| List/read workspace files | Low | May be allowed inside the active workspace |
| Public search / open browser | Low, networked | Requires the user's network setting or approval for this specific request |
| Play media / read media metadata | Low | Limited to a user-selected URI or approved workspace resource |
| Write project file | Guarded | Fresh user approval required in current baseline |
| Run project tests | High | Explicit approval: projects can contain executable code |
| Download remote file / install package | Guarded/high | Explicit approval and destination/size checks required |
| Delete workspace file | Critical | Fresh, specific approval plus path validation required |
| Send/publish externally | Critical | Fresh confirmation showing destination and content required |

The source-level policy engine currently uses typed tool IDs and returns `ALLOW`, `REQUIRE_APPROVAL` or `DENY`. Approval is scoped to one unique request ID and must not be reused for a different action.

## Fail-closed rules

- Empty/oversized request IDs, missing summaries and malformed requests are denied.
- Unknown tool capabilities are denied.
- Network access is not implicitly granted by the model asking for it.
- High and critical actions cannot auto-approve themselves.
- A policy decision does not execute a tool; the executor must independently validate parameters and report a result.
- The audit record stores only a bounded summary, decision, IDs and result code—not raw data payloads or file contents.
- Redaction patterns reduce accidental leakage but are not a substitute for never sending secrets to the logger.

## Required next work

Connect policy evaluation to a native confirmation UI; persist user preferences; bind approvals to one immutable request hash; implement per-tool parameter validation, timeouts, cancellation, post-action verification and audit logging. Until then, this module is not a working network firewall or process sandbox.
