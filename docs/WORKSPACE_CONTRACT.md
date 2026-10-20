# Workspace contract

## Data model

```json
{
  "id": "UUID",
  "name": "SONARA",
  "directory": "<app-private-files>/workspaces/<UUID>",
  "createdAt": "epoch-ms",
  "schemaVersion": 1
}
```

## Rules
- Workspace identity is UUID; never use its display name directly as a filesystem path.
- Workspace directory must be a canonical direct child of `filesDir/workspaces/`.
- User-visible rename does not move or change the UUID-backed directory.
- Delete requires explicit confirmation in UI. Active terminal session must be closed or moved before deleting a workspace.
- In v0.36, the native repository source creates directories and metadata in app-private storage, but the HTML workspace list is not yet connected to it; real PTY cwd binding is not implemented.
- `cwd` binding belongs to the future PTY session launch: the session must receive the canonical workspace directory, not construct a shell command using the name.
