# Together Link

**English** | [简体中文](README.md)

Together Link (mod ID: `bbs_together`) is a multiplayer collaboration extension for [BBS FS](https://modrinth.com/mod/bbs-mod). The primary release supports Minecraft 1.20.1–1.20.4 on Fabric, with an experimental Minecraft 1.21.11 compatibility build. While retaining BBS's original editing experience, it adds real-time multiplayer collaboration, film version history, server-side permissions and asset locks, asset synchronization, and server auditing.

> Minecraft 1.20.1–1.20.4 share one universal build; Minecraft 1.21.11 uses a separate build. Do not mix them.

## Version and requirements

| Build | Minecraft | Java | Fabric Loader | BBS FS | Status |
|---|---|---|---|---|---|
| Universal | 1.20.1 – 1.20.4 | 17+ | >= 0.15.6 | >= 2.6 (2.7 recommended) | Primary |
| 1.21.11 compatibility | 1.21.11 | 21+ | >= 0.19.3 | 2.7-1.21.11 | Experimental |

Current version: **2.1**. Fabric API is required; licensed under MIT.

## Feature overview

### Real-time collaboration

| Feature | Description |
|---|---|
| Player colors | Each player gets a distinct color that highlights their selection and cursor |
| Shared cursors | Other players' cursor positions are shown in real time |
| Selection sync | Form, keyframe, and replay clip selections are broadcast to online players |
| Form lock | When the same replay form is in use, it is displayed as locked to prevent concurrent edits |
| Replay swap | Players can swap replay order with each other |

### Cooperative recording

| Feature | Description |
|---|---|
| Invite and claim | Invite players from the record replay button; after accepting, each player joins the picking room and claims one replay |
| Room list | The replay list scrolls, and every row previews the model of its replay's form (following BBS' list model preview setting) |
| Hidden re-recorded actors | While recording, the old bodies of the claimed replays are hidden, so the previous cast does not stand next to the real players |
| Automatic merge | Once everyone hands in, the server merges the replay data and broadcasts the result |

### Films, commits, and branches

| Feature | Description |
|---|---|
| Film sync | Film selection, screen state, and keyframe/track edits are pushed in real time |
| List sync | Every client refreshes after a film is added, renamed, or deleted |
| Commits | Each film keeps a commit graph, snapshots, and diffs, up to 300 entries by default |
| Save as commit | Saving appends the current edits as a commit on the player's current branch; periodic saves only refresh the working copy |
| Branch isolation | Each player can work on a separate branch without overwriting others |
| Reset / squash | Reset that either keeps or deletes later commits, plus squashing consecutive commits |
| Commit details | Shows the commit message, author, time, clip/keyframe stats, and the diff |
| Commit graph | One column per film, ordered by time, with connections clipped outside the viewport |
| Commit colors | Stored and broadcast by the server, so every player sees the same colors |

### Permissions and asset locks

| Feature | Description |
|---|---|
| Three permission levels | 0 read-only / 1 edit / 2 manage, broken down by feature domain |
| Feature domains | Morphing, films, recording, model blocks, particles, model editor, textures, audio, upload |
| Asset locks | Models, films, and model blocks can be restricted with a whitelist/blacklist |
| Lock display | Players without permission see a closed lock; the creator/admin sees a management badge |
| Lock enforcement | The server re-checks uploads, saves, right-clicks, and breaking; locked blocks cannot be broken or opened by unauthorized players |
| Film write guard | Film saves, renames, deletions, undos, and folder operations all go through `FilmWriteGuard` |

### Asset synchronization

| Feature | Description |
|---|---|
| Manifest sync | The server publishes the SHA-1, size, and mtime of assets |
| Auto download/upload | Clients sync missing or changed files automatically based on manifest diffs |
| Conflict arbitration | Uploads carry the server mtime as a CAS token to avoid overwriting someone else's changes |
| Atomic writes | The server verifies SHA-1 locally and replaces files atomically via temp files |
| Chunk isolation | Multi-chunk temp files are isolated per player, with their cumulative size verified |
| Size ceiling | Server and client share `asset_sync_max_mb` |
| CDN (optional) | Read-only download fallback: CDN first, falling back to the server when verification fails |
| Anti-resurrection | Model deletion tombstones are persisted, so offline clients cannot re-upload deleted models |
| Restore | `/bbstogether restore <model>` clears the tombstone and allows re-uploading |

### Server audit

| Feature | Description |
|---|---|
| Behavior panel | Records edits, downloads, uploads, delete requests, and similar actions |
| Deduplication | Consecutive identical actions are merged; no record is written when nothing actually changed |
| Source tagging | Client-reported entries are marked "reported by client (not verified by server)" |
| Permission protection | Non-OP players are shown anonymously; deleting logs requires OP or BBS level 2 |
| Persistence | `<world>/bbs/behaviors/`, UTF-8 + atomic writes |
| Rate limiting | Behavior reports and manifest queries are rate-limited per player |

## Permission levels

| Level | Name | Capabilities |
|---|---|---|
| 0 | Read-only | Browse and download; cannot edit, upload, or manage |
| 1 | Editor | Can edit all domains by default; admins can restrict domains individually |
| 2 | Admin | Edit everything; manage player levels, feature domains, and asset locks |

- The integrated server in single-player/LAN worlds automatically grants the host level 2.
- Dedicated servers no longer automatically make the first player to join an admin; on the first start, run `/bbsop <player> 2` from the console.
- Only BBS level 2 can always bypass asset locks; a lock's creator is subject to the lock as well after being demoted.

## Server commands

| Command | Description | Permission |
|---|---|---|
| `/bbstogether record <player>` | Toggle the target player's recording state | OP |
| `/bbstogether restore <model>` | Clear the model deletion tombstone, allowing re-upload | OP or BBS 2 |
| `/bbstogether diag` | Print asset sync/queue diagnostics | OP |
| `/bbsop <player> [0-2]` | Query or set a BBS permission level | OP/console/BBS 2 |
| `/bbsop <player> grant <domain> <allow\|deny>` | Allow/deny a domain for level 1 | OP/console/BBS 2 |
| `/bbsop <player> grant reset` | Restore level 1 to unrestricted | OP/console/BBS 2 |

Feature domains: `morph`, `film`, `record`, `model_blocks`, `particles`, `model_editor`, `textures`, `audio`, `upload`.

## Installation and deployment

| Step | Action |
|---|---|
| 1 | Install Minecraft 1.20.1 or 1.20.4, Fabric Loader, Fabric API, and the matching BBS FS version |
| 2 | Put `together-link-2.1-1.20.1-1.20.4.jar` into the `mods/` folder on both client and server |
| 3 | Start the server, then run `/bbsop <player> 2` from the console or as OP to set up an admin |
| 4 | Connect all clients to the same server; client and server must run the same version |
| 5 | Open the BBS dashboard to start collaborating |

> Updating only the client has no effect: asset paths, permissions, CAS, tombstones, co-op recording locks, and other server-side checks all run on the server.
>
> The 1.21.11 compatibility build is installed the same way, but requires `together-link-2.1-1.21.11.jar` and BBS `2.7-1.21.11`. The 1.20.x and 1.21.11 builds are not interchangeable.

## Configuration

Config file: `config/bbs/settings/bbs_together.json`.

| Setting | Default | Description |
|---|---|---|
| `cdn.url` / `cdn.token` | empty | Optional read-only CDN download source; falls back to the server on failure |
| `cdn.download` | on | Download missing assets automatically |
| `cdn.upload` | on | Upload local changes automatically |
| `cdn.receive` | on | Receive asset pushes from the server |
| `cdn.progress_bar` | on | Show the sync status widget in the bottom-left corner |
| `cdn.progress_bar_always` | off | Show a small status dot while idle |
| `server.auto_sync` | off | Watch for asset changes automatically |
| `server.asset_sync_max_mb` | 256 | Per-file size ceiling; 0 = unlimited |
| `server.asset_upload_kbps` | 0 | Client upload rate limit; 0 = unlimited |
| `server.asset_egress_kbps` | 0 | Server total egress limit; 0 = unlimited |
| `server.asset_ingress_kbps` | 0 | Server total ingress limit; 0 = unlimited |
| `behaviors.behavior_log_limit` | 1000 | Maximum number of behavior log entries |
| `coop.coop_invite_seconds` | 60 | Co-op recording invite lifetime (seconds) |

## Compatibility

| Scenario | Behavior |
|---|---|
| BBS-only/vanilla server | Together permissions and sync stay disabled; BBS editing keeps working |
| Older Together server | Recognized via the first permission sync packet; stays compatible |
| BBS-Cubed | The context menu is filtered while categories are built, keeping addon additions |
| Film branches | Saves on non-default branches go to branch storage instead of overwriting the main film |
| CDN | Read-only download fallback; no CDN upload or CDN manifest |
| Conflict token | A CAS based on server mtime, not a strictly monotonic revision |
| Behavior log | Source tags, type/length validation, and rate limiting; no server signature/audit chain |

## Building

| Command | Description |
|---|---|
| `.\gradlew.bat build` | Build the universal artifact (1.20.1 / 1.20.4) |
| `build/libs/together-link-2.1-1.20.1-1.20.4.jar` | Primary release artifact |
| `.\gradlew.bat build "-Pmcver=1.20.4"` | Compile/run verification for 1.20.4 only; the artifact has a `-verify` suffix and is not released |
| `.\gradlew.bat build12111` | Build the experimental 1.21.11 compatibility artifact (separate release) |
| `.\gradlew.bat runClient12111` | Start the 1.21.11 dev client (`run-1.21.11-client/`) |
| `.\gradlew.bat runClient12111B` | Start a second 1.21.11 dev client (`run-1.21.11-client-b/`) |
| `.\gradlew.bat runServer12111` | Start the 1.21.11 dev server (`run-1.21.11-server/`) |
| `dist/` | Release copies |

Build tooling pins Loom to `1.15.5` to avoid build differences caused by snapshot versions.

`runClient12111` and `runClient12111B` can join a server on launch with `-PjoinServer=127.0.0.1:25565`:

```powershell
.\gradlew.bat runServer12111 --console=plain
.\gradlew.bat runClient12111  '-PjoinServer=127.0.0.1:25565'
.\gradlew.bat runClient12111B '-PjoinServer=127.0.0.1:25565'
```

> **1.21.11 compatibility build (experimental):** This is a separate artifact from the 1.20.x primary release. It reuses the common 1.20 sources and adapts networking, rendering, and Mixin APIs through the thin `src/main/java-1.21.11` and `src/client/java-1.21.11` overlays. Compilation, dedicated-server join, two-client join, a 9 MB asset transfer, and ingress/egress limiting have been verified; 30-minute sessions, sustained synchronization, and lock contention have not been stress-tested, and world saves are not guaranteed to interoperate with 1.20.x. Before building, publish BBS `2.7-1.21.11` from `lib/bbs-fs-1.21.11` to mavenLocal. Test record: [1.21.11 test record](docs/12111-p5-checkpoint.md).

## Project structure

| Directory | Contents |
|---|---|
| `src/main/java/.../locks` | Asset locks, deletion tombstones, persistence |
| `src/main/java/.../network` | Server networking, asset sync, film write policy, crusher |
| `src/main/java/.../commits` | Commit graph, branch working copies, diffs, atomic writes |
| `src/main/java/.../bbsop` | Permission levels and feature domains |
| `src/main/java/.../behaviors` | Behavior log |
| `src/client/java/.../sync` | Bottom-left sync status widget |
| `src/client/java/.../mixin` | Client UI and sync hooks |
| `src/main/resources/bbs-together.mixins.json` | Common mixin config |
| `src/client/resources/bbs-together.client.mixins.json` | Client mixin config |

## Changelog

### v2.1

#### Added

- The cooperative recording room's replay list now scrolls: when the film has more replays than the panel is tall, the list scrolls with the mouse wheel or the scrollbar.
- Every replay row now previews the model of that replay's form, following BBS' list model preview setting. The preview only reads the local film copy and never writes a received film to disk, so opening the room cannot overwrite unsaved edits.
- Saving or committing a film with zero replays now logs a diagnostic line, so it is clear whether the empty data came from the client or from the server working copy.

#### Fixed

- Fixed leftover actors from the previous take: starting a new cooperative recording on a film that already had one left the previous cast standing on set next to the real players. The bodies of the replays the session claimed (including members who already handed in but are not merged yet) are hidden for the duration of the recording; replays nobody claimed keep their bodies as blocking reference.
- Fixed other players not syncing, and the committed replays being emptied, after changing a film's replay count and committing. The structure sync called `ValueStableList.sync()` after rebuilding the replay list, and that method always throws `UnsupportedOperationException` in BBS. The exception also happened inside the server's `execute` scheduling, where it is dropped silently, so both the save and the relay were skipped and the new replays were never filled in. Replays are now built from the payload ids before the list is replaced, and the call is gone.
- Added payload validation to the structure sync: a payload is applied only when it is a list whose size matches the metadata. A mismatched payload used to clear the whole replay list first, which is how a film ended up with zero replays; a failed apply now logs instead of silently aborting.
- Fixed cooperative recording end notices showing raw translation keys (such as `bbs_together.coop.notify.merged`): those keys were only written to the vanilla language table, while BBS' own table is what resolves them.

#### Verified

- All three targets (1.20.1, 1.20.4, 1.21.11) build successfully.

#### Compatibility

- The network protocol remains version 3 with no new fields; the 1.20.1–1.20.4 universal build and the 1.21.11 compatibility build are updated together.
- The old bodies are hidden on the server before the actor list is broadcast, so every client receives the same cast with no extra configuration.

### v2.0.4

#### Fixed

- Fixed a client crash when picking "request deletion" or "delete local copy" from a model category's context menu. If BBS reloaded its assets and rebuilt the model list while the menu was open, the category that built the menu left the UI tree and its `getContext()` returned `null`, which the menu action dereferenced on click (`crash-2026-11-17_11.38.31-client.txt`). The UI context is now resolved once, while the menu is built, instead of being requested from the element on click.
- Fixed the same pattern in the player behavior list menus (level, feature domain, and clear logs), the model and film lock menus, and the keyframe shape and interpolation pickers: each one now keeps the context resolved at build time and skips the action when it is missing.

#### Notes

- Client-side only; no server update is required. Both the 1.20.1–1.20.4 universal build and the 1.21.11 compatibility build carry the fix, and the network protocol remains version 3.
- All three targets build successfully.

### v2.0.3

#### Added

- Added an experimental Minecraft 1.21.11 compatibility build: separate artifact, Java 21, Fabric Loader >= 0.19.3, and BBS 2.7-1.21.11. The 1.20.x universal build is unchanged.
- Added a dedicated server and a second 1.21.11 dev client entry (`runClient12111B` / `run-1.21.11-client-b/`); both clients support auto-join with `-PjoinServer`.
- Adapted 1.21.11 CustomPayload networking, `Identifier.of`, render states, and Mixin overlays; restored the model-block lock badge, player hiding, keyframe highlighting, and player face rendering.
- Added startup payload pre-registration and a 64 MB total chunked-transfer cap to reduce first-packet loss and excessive chunk accumulation.
- Added structured transfer logs and metrics (egress/ingress bytes, completed/failed counts, average throughput, rate-limit waits) and a `server.asset_ingress_kbps` server-wide ingress limit.

#### Fixed

- Fixed the missing `mixins` declaration in the 1.21.11 `fabric.mod.json`, which prevented every 1.21.11 Mixin from loading and left UI features such as the lock panel unavailable.
- Fixed the model-block lock section not refreshing when permissions or asset locks changed while the panel was already open.
- Fixed 1.21.11 compatibility issues in networking, rendering, player hiding, keyframe highlighting, and skin faces.
- Corrected version numbers, build commands, and the 1.21.11 support scope in the README.

#### Verification

- All three targets build successfully: 1.20.1, 1.20.4, and 1.21.11.
- 1.21.11 was verified with a dedicated-server join, two simultaneous clients, a 9 MB asset transfer, a 1024 KiB/s ingress limit, and the 1.20.4 join/reconnect regression.

#### Compatibility

- Minecraft 1.20.1–1.20.4 continue to use one universal build; 1.21.11 uses a separate build, and client/server must run the same Minecraft version and Together build.
- The network protocol remains version 3; 1.20.x and 1.21.11 world saves are not guaranteed to interoperate.

#### Known limitations

- The 1.21.11 build still needs 30-minute sessions, sustained asset synchronization, and lock-contention stress tests; some visual items need re-validation.
- The 1.21.11 build requires BBS 2.7-1.21.11, which must be published from `lib/bbs-fs-1.21.11` to mavenLocal before building.

### v2.0.2

#### Added

- The save button appends the current edits as a commit on the player's current branch, so saved content is no longer reported as uncommitted. Periodic saves, film duplication, and post-recording saves still refresh the working copy only.
- Saving identical content does not create an empty commit (`time_spent_active` is ignored in the comparison).

#### Fixed

- Shutdown no longer stalls when players have joined: branch working copies are dirtiness-tracked, only changed copies are written on shutdown, and a `Flushed film saves` timing line is logged.
- Undo now reaches other players: the server dropped every undo packet because its empty crusher payload arrives as null, and the packet carried the wrong value for nested paths. Local undo of nested values is fixed as well.
- Camera clip keyframe edits are no longer silently dropped for other players (the first path segment was skipped).
- Rolling back a keyframe no longer reverts another player's newer edit: every replay path is validated, and unresolvable paths are still relayed to the other editors.
- Saving no longer pulls the editor's orbit camera back to the previous shot; rebinding a replay after a remote update keeps the pivot in place.
- Fixed a buffer mix-up in the BBS delta sync entry point that made native syncs fail to parse.

#### Compatibility

- Protocol version is now 3; clients and servers must both update to 2.0.2.

### v2.0.1

#### Added

- Added Minecraft 1.20.4 support. Releases are consolidated into a single build compatible with 1.20.1–1.20.4.
- Verified 1.20.4 dedicated server and client startup and join paths.

#### Fixed

- Fixed the model block panel rendering blank on first open in 1.20.4 while its buttons remained clickable.
- Fixed the 1.20.4 binary incompatibility in the model block NBT size check (`PacketByteBuf.writeNbt`).
- Fixed locked model block pick-block handling in 1.20.4 by intercepting `MinecraftClient.doItemPick`.
- Fixed player skins and the F3 debug HUD state being unreadable on a released client: lookups now accept both development and production method names.
- Fixed a client crash while the film and replay lists drew collaborator faces (a missing skin is no longer requested from the texture manager as a null texture).
- Fixed invited players being told the film was missing locally, which stopped them from recording replays; the session's branch film is now sent before recording starts.
- Removed the legacy refmap file; Loom now remaps Mixin annotations directly during `remapJar`.

### v2.0

#### Security

- The Fix28 snapshot channel now uniformly validates the FILM/RECORD domains, asset locks, deletion tombstones, co-op recording locks, and sender identity.
- Asset uploads, downloads, manifests, client writes, and local deletions are all confined to the assets root; absolute paths, drive letters, UNC paths, `..`, and symlinks are rejected.
- Crusher adds chunk-header validation, capacity limits, expiry cleanup, and a gzip decompression ceiling; each player gets a separate crusher on the Together channel.
- Model, texture, audio, and particle uploads map to their feature domain by path and are re-checked by the server.
- Added the `s_hello/c_hello` handshake; BBS-only servers stay editable, and older Together servers are recognized via the permission sync packet as a fallback.
- Model lock keys are normalized case-insensitively on Windows/macOS.
- The first admin on a dedicated server is now granted explicitly with `/bbsop <player> 2` from the console.

#### Sync and data

- Uploads now use server-side local SHA-1 and atomic temp-file replacement; per-player chunk temp files are isolated and their cumulative size verified.
- Uploads carry the server mtime as a CAS token; if someone else has already modified the file, the overwrite is refused and a resync is triggered.
- Model deletion tombstones are persisted; added `/bbstogether restore <model>`.
- The CDN URL/token now backs read-only downloads, falling back to the server whenever SHA-1/size verification fails.
- The server publishes `asset_sync_max_mb` / `coop_invite_seconds`; the client uses the server value as its download ceiling.
- The asset HUD is now a minimal bottom-left widget: an icon + 2px progress line + percentage while active, a checkmark pulse on completion, and a status dot while idle; it draws above all hotbars and menus without intercepting clicks.
- Fixed upload progress occasionally never finishing: a new scan cleans up old jobs, an empty job list finishes automatically, active/queued jobs time out and are reclaimed, and state transitions can wake it up again.
- Model block NBT larger than 4 MB now skips `BlockEntityUpdateS2CPacket` to stay under vanilla's 8 MB limit and avoid a disconnect; oversized forms require a chunk reload.

#### Films and UI

- Film save, rename, delete, undo, and folder operations all go through `FilmWriteGuard`.
- Folder rename/delete now migrates or cleans up branches, commits, player selections, editing state, cursors, form locks, and pending save jobs.
- Saves from the BBS manager on non-default branches go to branch storage and no longer overwrite the main film.
- Film rename/delete now notify only after success, so the server no longer changes state ahead of a BBS rejection.
- The replay structure keeps only the dedicated identity protocol.
- BBS-Cubed context menu compatibility; a single permission owner for the record button; `MorphRenderer` is hidden only in the film UI.
- The lock menu always shows whitelist/blacklist entries.

#### Runtime and engineering

- Server manifest/request/inventory handling moved to a bounded I/O thread pool; upload chunks are written in order on a single thread.
- Hard caps on the client SHA-1 cache and tombstone count.
- The commit graph now clips edges outside the viewport.
- File persistence moved to UTF-8 + atomic writes.
- The behavior log adds source tags, type/length validation, and per-player rate limiting.
- Removed duplicate l10n registration; Loom pinned to 1.15.5.

#### Verification

- The 2.0 build passes (`BUILD SUCCESSFUL`).
- The 1.7.9 baseline completed client startup, a single-client server, and a two-client connection smoke test; 2.0 gameplay and two-client interaction are pending re-testing against the acceptance checklist.
- The full two-player UI acceptance checklist is in the [acceptance checklist](docs/验收清单-TogetherLink-2.0.md).

#### Known limitations

- CDN supports only read-only download fallback.
- mtime CAS is not a strictly monotonic revision.
- The behavior log has no server signature/audit chain.
- `client-bbs-together-refmap.json` is a legacy file; the production jar has been verified to start, but the file was not regenerated.
- The commit graph clips off-screen edges; full rebuild/layout could still be optimized.
- Model block NBT larger than 4 MB skips its BlockEntityUpdate packet to stay under vanilla's 8 MB limit and avoid a disconnect; clients may need to reload the chunk to see the block's latest form.

### v1.7.9

- Added BBS level-2 asset locks: morph models, films, and model blocks can each be set to forbid all editing, allow only a whitelist, or forbid only a blacklist
- The model block panel gains "Lock editing" plus whitelist and blacklist columns; the lock menu always shows both list entries and supports selecting multiple players
- Lock display: a grey overlay on morph models, a padlock in the film list, and a per-player lock icon in the model block F3 outline
- Lock badges differ per player: those who cannot edit see a closed-lock badge; the lock's creator sees a small padlock in the bottom-right of the model cell when they can edit, plus an open padlock in the film list; other editors see no lock badge, and level-2 admins keep their management badge
- Locked models and locked films are read-only; locked model blocks cannot be broken or have their properties copied; model uploads, film writes, and model block writes are re-checked by the server
- Fixed locked model blocks vanishing or flickering after a denied break: the client no longer predicts the break, and the server resends the block and block entity data when cancelling; players who cannot edit a locked model block no longer open its panel with a right-click
- Locks are stored in `<world>/bbs/asset_locks.json` and migrated or cleaned up when a film or model is renamed or deleted
- Security hardening: the Fix28 snapshot channel uniformly validates the film domain/record domain/asset locks/deletion tombstones/co-op recording locks; asset upload paths are confined to the assets root; Crusher chunk headers and gzip decompression are capped, with a separate crusher per player
- Asset uploads now compute SHA-1 locally on the server and replace files atomically via temp files; multi-chunk temp files are isolated per player and their cumulative size verified; model deletion tombstones persist to `<world>/bbs/asset_tombstones.json`
- Added the `s_hello/c_hello` handshake: collaboration permissions and sync are enabled only on servers that actually run Together; BBS-only/vanilla servers stay editable instead of being misdetected as level-0 read-only
- The first admin on a dedicated server is now granted explicitly with `/bbsop <player> 2` from the console; model lock keys are normalized case-insensitively on Windows/macOS
- Film manager save/rename/delete, folder rename/delete, and undo sync all go through the unified `FilmWriteGuard`; during co-op recording, films can no longer be overwritten through legacy snapshot or undo channels
- The behavior log now writes temp files atomically; deleting logs requires OP or BBS level 2; downloads are recorded only for paths that actually pass verification

### v1.7.8.1

- Only one tab is kept per film; reopening a film from the film list switches to the existing tab, and duplicate tabs are cleaned up
- Switching back to a film from a blank "new tab" closes the temporary tab automatically
- The "Film nodes" tab stays pinned to the far left; opening a film no longer replaces it
- Fixed closed tabs reusing a closed document id: closing "Film nodes" no longer closes a film tab, and closing a film tab neither reopens it nor swallows the neighbouring tab
- The "Commits" dock page still merges into the properties stack by default on first load; after unlocking the layout it can be dragged, resized, or moved to another slot, and layout refreshes no longer force it back
- The commit details bar can be dragged by its entire top edge, the handle is a centered short line, and double-clicking restores the default height
- Every node in the commit graph gains a "Rename commit" right-click entry
- The commit graph can list legacy `_commits` history that has not been migrated yet, and migrates it on read

### v1.7.8

- When a film is opened, the server manager's LOAD returns the player's current branch directly, avoiding a duplicate transfer of the default branch and branch state
- Film selection and join requests only refresh the branch name and commit graph during the short window right after a whole film arrives
- The client caches the most recently opened films; in multiplayer they appear immediately on reopening and are reconciled with server data
- Branch and commit data moved into the film's own `_<film name>/_together/` directory, so it follows the film directory and stays out of the vanilla film list
- Legacy `<world>/bbs/films/_commits/<film>/` data migrates automatically on first access
- The server manager's SAVE writes to the player's branch, so non-default branches no longer overwrite the main film file
- Commits reuse the branch head snapshot cache; when the working copy is unchanged, repeated disk writes and BBS backups are skipped

### v1.7.7

- Commit reset is now a same-branch operation: "Revert to this commit" appears only when the commit's branch matches the player's current branch; cross-branch commits no longer offer a reset entry, and the server adds matching validation
- The reset dialog is reduced to two options: "Delete and reset" removes the commits after it, and "Keep and reset" keeps them in history; both use the selected commit as the reset point
- Added "Squash into this commit": right-click a commit that is on the same branch, is not the branch head, and is an ancestor of the current branch to squash the history between that commit and the current branch head into a single node, keeping the branch head's film content; the operation is refused with a message when the range is referenced by another branch
- Commit graph layout: the default branch is pinned to the center line and other branches alternate left/right, reducing crossing lines when several branches pile up on one side; commit text starts to the right of the lane band
- The co-op recording invite panel now uses vanilla's online player list, fixing missing rows and heads for some players (especially those who just joined and have no behavior records yet)
- Auto-commit before co-op recording invites: when "Send invite" is clicked and the current film has uncommitted changes (including structural changes such as newly added replays), the client commits to the current branch first, so the server no longer validates the replay count against a stale working copy
- Both BBS level 1 (editor) and level 2 (admin) can send co-op recording invites; domain whitelists now only restrict editable content and no longer affect the invite entry
- Fixed commit details rebuilding the whole film diff every second while the editor page is idle, which made large films stutter: it now rebuilds per edit version, at most once per second, and the per-second activity tick no longer triggers sync or diff computation
- Added the `s_commit_squash` packet; client and server must run the same version

### v1.7.6

- Multiplayer branch isolation: each player can switch between and edit different branches of the same film; branch working copies and each player's branch choice are persisted by the server; players on the same branch keep collaborating in real time, and commits land on the initiator's current branch
- Each player's commit graph computes `current` / `head` independently, so headers, current-commit highlighting, uncommitted changes, and commit targets no longer mix in other players' branches
- Branch switching uses the authoritative `c_film_branch_state` packet and is no longer subject to the 5-second pending timeout; the commit graph right-click menu gains "Check out this commit's branch", with a clear message on failure
- The selected commit in the graph survives data refreshes, keyed by film + commit id, instead of jumping back to the latest commit; other players' selected heads and each player's branch-head avatars are always shown
- Avatars match the replay list style: a 10px face, a 1px opaque player-color outline, and a 1px gap; your own avatar comes first
- Commits without an explicit color (including new branches) default to the UI theme color; manual colors and explicit grey still follow the color stored by the server
- Fixed all players sharing a global current branch, which made edits/commits be dropped by the server after switching branches
- Fixed asset manifest requests getting no response when the server's `config/bbs/assets` directory did not exist, which made the F6 "Upload all models" button appear dead (the server now recreates the directory and replies with an empty manifest)
- Added network transfer compression: large payloads such as film workspaces are GZIP-compressed before chunked sending, while small packets stay as they are
- Co-op recording supports cross-branch invites: after accepting, the invited player automatically commits their branch and switches to the initiator's branch; if the switch fails they do not join the session and get a message
- Fixed a network flood from resending player color packets every tick after joining a world
- Server film file reads/writes are locked to avoid conflicts with BBS saves; opening a film that has not been uploaded to the server yet no longer spams errors into the log

### v1.7.5

- Added film commits (version history): the "Film nodes" tab shows a commit graph per film, supporting commit, revert, reset, branch creation, and branch switching; commits can be colored individually and the color is synced by the server to every player
- The film editor's "Properties" group gains a "Commits" sub-tab, so the commit graph and commit info can be viewed in place without leaving the editor
- The server owner gets BBS level 2 by default on their first join

### v1.7.4

- Added co-op recording: right-click the "Record replay" button to invite players; after accepting through the top-right pinned popup (the hotkey can be changed in the "Film controller" category of BBS settings), they enter the replay selection room; once everyone has claimed a replay the initiator starts recording, members can stop midway, and the server merges and broadcasts automatically after everyone submits
- Added model deletion requests: right-click a model → "Request server deletion", and admins approve it in the behavior list; after deletion a persistent tombstone stops auto-sync from bringing the model back; a "Delete locally only" option is also available
- Added recording sub-permissions: a "Record" subdomain under the film domain controls replay recording and the "Record video/screenshot" buttons
- Added undo protection: undos are validated by the server, and content already modified by others can no longer be rolled back with one click
- Improved asset sync: the manifest carries modification times and conflicts are arbitrated automatically; uploads use the server mtime as a CAS token, so the two sides no longer exchange the same file back and forth
- CDN settings feed the read-only download path: assets are fetched from the configured CDN address first, verified by SHA-1/size, then written to disk, falling back to the server on failure; uploads and permission checks remain the server's responsibility
- The server sends `asset_sync_max_mb` / `coop_invite_seconds` to clients in the hello packet, so the download ceiling no longer depends on each side's own configuration
- Server manifest/request/inventory handling moved to a bounded shared I/O thread pool, and upload chunks are written in order on a single thread (no longer occupying the server tick thread); the client SHA-1 cache and deletion tombstones are capped to prevent unbounded growth over long sessions or from malicious packet floods
- l10n keeps only the addon event registration, removing the duplicate registerOne and the repeated language-file loading at startup
- Folder rename/delete now migrates or cleans up branch/commit data, plus players' commit selections, editing/screen state, cursors, form locks, and pending save jobs
- Added `/bbstogether restore <model>`: clears a persistent deletion tombstone and allows a deleted model to be uploaded again
- Added per-player rate limiting for manifest resync and behavior reports, so a level-1 client cannot flood the behavior log
- Fixed a crash when clicking a level group header to filter the behavior list
- Note: client and server must run the same version; in whitelist mode, level-1 players are denied recording by default after upgrading, and an admin must run `grant record allow`

### v1.7.3

- Added the BBS permission level system: level 0 read-only, level 1 editor, level 2 admin, with eight feature domains that can be toggled individually
- Added the `/bbsop` command: query or set permission levels and allow/deny per domain
- The behavior panel's player list is grouped by level, with drag-and-drop level changes, right-click management, and group filtering

### v1.7.2

- Added the server behavior panel: records edit/download/upload audit logs, entries can be clicked to jump, with OP management and anonymity protection
- Adapted to BBS FS 2.7
- Fixed crashes when a film file was locked during writing, and an out-of-bounds crash in sync

### v1.7.1

- The F6 panel gains "Download all models" and "Upload all models" buttons that sync only differing files
- Added a per-file size ceiling (16 MB–2 GB); oversized files are skipped and flagged
- Uploads are sent with an interval per file to avoid dropping the connection on large files

### v1.7

- Uploaded assets are stored on the server as the authoritative copy for all players
- Asset manifest and transfer improvements; large files are skipped automatically, and sync runs in the background without stutter

### v1.6.16 (legacy)

- Initial version based on BBS FS 2.4, already adapted to the 2.6 addon API; multiplayer cursors, selection, film sync, and recording coordination

## License

MIT
