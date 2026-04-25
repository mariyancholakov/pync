We are at HackUPC 2026 in Barcelona building Pync — a P2P encrypted
environment variable sync tool for developer teams. No cloud, no central
server, secrets never leave devices unencrypted.

We are targeting two sponsor challenges simultaneously:
- PEARS challenge: build a P2P app using the Pear/Holepunch protocol
  Brief: "eliminate the central authority from sensitive data management,
  build using Pear protocol, decentralized multiwriter P2P sync of encrypted
  secrets, DHT-based swarming, no cloud"
- JetBrains challenge: build something that simplifies developer life
  Brief: "create an innovative application or plugin that simplifies the
  lives of developers and improves their coding experience"

The product has 4 components:
1. core/ — PyncCore class, P2P engine using Hyperswarm + Autobase + Hyperbee
2. sidecar/ — Node.js child process, bridges core to plugin via stdin/stdout JSON
3. plugin/ — IntelliJ IDEA plugin in Kotlin, spawns sidecar, shows secrets UI
4. cli/ — commander.js CLI wrapping PyncCore directly

Before starting, identify which team member you are:
- P1: core engine, crypto, CLI, integration tests, Kotlin dialogs
- P2: sidecar (stdin/stdout JSON bridge + state manager)
- P3: IntelliJ plugin UI and sidecar process manager
- P4: README, Devpost, demo script, demo video

Your files based on role:
- P1: core/crypto.js, core/export.js, core/index.js, cli/index.js, cli/bin.js,
  test/integration.js, AddSecretDialog.kt, EditSecretDialog.kt, ExportAction.kt
- P2: sidecar/index.js, sidecar/state.js
- P3: PyncToolWindowFactory.kt, PyncSidecarService.kt, build.gradle.kts,
  settings.gradle.kts, plugin.xml
- P4: README.md, Devpost, demo script, demo video

Only touch your own files. Ask in group chat before touching another person's files.

The JSON protocol is in PROTOCOL.md.
The project is in the pync/ folder.
The repo is on GitHub, P1 merges all PRs into main.

Dependencies to install first:
npm install hyperswarm autobase hyperbee corestore b4a sodium-native commander chalk boxen

Tech stack:
- Node.js (core, sidecar, cli)
- hyperswarm — DHT peer discovery and connections
- autobase — multiwriter support over multiple Hypercores
- hyperbee — append-only B-tree KV store on top of Autobase
- corestore — manages Hypercore collections on disk
- sodium-native — AES-256-GCM encryption
- Kotlin + IntelliJ Platform SDK (plugin)
- kotlinx.serialization (JSON parsing in plugin)
- commander.js + chalk + boxen (CLI)

JSON Protocol (sidecar ↔ plugin) — DO NOT CHANGE:

Commands plugin sends to sidecar via stdin:
{ "cmd": "create", "room": "myteam", "passphrase": "secret123" }
{ "cmd": "join", "topicKey": "<hex>", "passphrase": "secret123" }
{ "cmd": "set", "key": "DB_URL", "value": "postgres://..." }
{ "cmd": "delete", "key": "DB_URL" }
{ "cmd": "list" }

Events sidecar sends to plugin via stdout:
{ "type": "ready", "topicKey": "<hex>", "role": "manager" }
{ "type": "ready", "topicKey": "<hex>", "role": "member" }
{ "type": "list", "secrets": [{ "key": "K", "value": "V" }] }
{ "type": "update", "secrets": [{ "key": "K", "value": "V" }] }
{ "type": "peers", "count": 2 }
{ "type": "error", "message": "..." }

PyncCore API — use EXACT method names, no deviations:
createWorkspace(room, passphrase) → { topicKey: string }
joinWorkspace(topicKey, passphrase) → void
setSecret(key, value) → void — throws if not manager
deleteSecret(key) → void — throws if not manager
listSecrets() → [{ key: string, value: string }]
onChange(callback) → void
getPeerCount() → number
destroy() → void

Encryption details:
- Algorithm: AES-256-GCM via sodium-native
- Topic key: SHA-256 hash of room name
- Encryption key: HKDF(sha256, passphrase, roomName, 32 bytes)
- Per value: random 12-byte nonce, store as JSON { nonce: hex, ciphertext: hex }

Realtime Sync + Offline Caching:
- When manager changes a secret, all ONLINE peers receive the update instantly
  via Autobase's 'update' event firing on their local instance
- When a peer is OFFLINE and comes back online, Hyperswarm automatically
  reconnects them to the swarm and Autobase replays all missed changes
  from the append-only Hypercore log — this is built into the protocol,
  not something we implement manually
- The sidecar must fire onChange whenever Autobase fires 'update' so the
  plugin and CLI always reflect the latest state immediately
- The plugin must show a "syncing..." indicator when first connecting and
  switch to "synced" once the first list or update event arrives
- Offline peers are handled automatically by Hypercore's persistence —
  data is stored on disk in ./data/ and replayed on reconnect
- The sidecar should also emit a "list" event immediately after a peer
  connects (join command) so the plugin populates instantly without
  waiting for a change event

What realtime sync means for each component:
- P1: core/index.js onChange must fire on every Autobase 'update' event
  including the initial one when joining an existing workspace
- P2: sidecar must emit { type: "list", secrets } immediately after
  joinWorkspace resolves, before waiting for any update events
- P3: plugin must handle the transition:
    Disconnected → Syncing (spinner) → Synced (green dot)
  show "Syncing..." as soon as join/create is clicked
  switch to "Synced" when first list or update event arrives
- The ./data/ directory is the offline cache — Corestore persists
  everything to disk automatically, no extra work needed

Data storage:
- Corestore at ./data/ directory
- Autobase with open and apply handlers
- Hyperbee view on top of Autobase for KV storage
- Deleted keys stored as null encryptedValue, filtered in listSecrets()

CLI commands:
- pync create <room> <passphrase> — creates workspace, prints banner with topicKey
- pync join <topicKey> <passphrase> — joins workspace, lists secrets, watches changes
- pync set <key> <value> — sets a secret
- pync delete <key> — deletes a secret
- pync list — lists all secrets as KEY=VALUE
- pync export — writes .env file
- pync run -- <command> — runs command with secrets as env vars
- workspace state stored in ~/.pync/config.json: { topicKey, passphrase, role }

Integration test flow (test/integration.js):
1. Spawn sidecar A, send create command, get topicKey
2. Spawn sidecar B, send join with topicKey
3. Send set TEST_KEY=hello_pync to A
4. Wait max 10 seconds for B to emit update with TEST_KEY=hello_pync
5. Print PASS green exit 0 or FAIL red exit 1

Build order — strict, do not skip:
1. core/crypto.js — run manual test before continuing
2. core/export.js — run manual test before continuing
3. core/index.js — run self-test before continuing, must print PASS
4. cli/index.js + cli/bin.js
5. test/integration.js — run it, must print PASS
6. AddSecretDialog.kt
7. EditSecretDialog.kt
8. ExportAction.kt

Kotlin dialog specs:
- AddSecretDialog: DialogWrapper subclass, two JBTextField fields Key and Value,
  validate key not empty, returns Pair<String,String> or null on cancel
- EditSecretDialog: DialogWrapper subclass, key field readonly pre-filled,
  value field editable pre-filled, returns new value String or null on cancel
- ExportAction: class with fun export(secrets: List<Pair<String,String>>, projectPath: String)
  writes KEY=VALUE lines to projectPath/.env, shows IntelliJ notification balloon

File structure:
pync/
  core/
    index.js         ← PyncCore class, the P2P engine
    crypto.js        ← encryption utilities (P1)
    export.js        ← .env file writer (P1)
  sidecar/
    index.js         ← stdin/stdout JSON bridge wrapping PyncCore
    state.js         ← state manager singleton
  cli/
    index.js         ← commander.js CLI wrapping PyncCore
    bin.js           ← #!/usr/bin/env node entrypoint
  plugin/
    build.gradle.kts
    settings.gradle.kts
    src/main/kotlin/com/pync/
      PyncToolWindowFactory.kt   ← UI panel (P3)
      PyncSidecarService.kt      ← sidecar process manager (P3)
      AddSecretDialog.kt         ← add secret dialog (P1)
      EditSecretDialog.kt        ← edit secret dialog (P1)
      ExportAction.kt            ← export to .env action (P1)
    src/main/resources/META-INF/
      plugin.xml
  test/
    integration.js
  CLAUDE.md
  PROTOCOL.md
  README.md
  package.json
  .gitignore

Team ownership:
- P1: core/crypto.js, core/export.js, core/index.js, cli/index.js, cli/bin.js,
  test/integration.js, AddSecretDialog.kt, EditSecretDialog.kt, ExportAction.kt,
  CLAUDE.md, PROTOCOL.md
- P2: sidecar/state.js, sidecar/index.js
- P3: build.gradle.kts, settings.gradle.kts, plugin.xml,
  PyncSidecarService.kt, PyncToolWindowFactory.kt
- P4: README.md, Devpost, demo script, demo video, judges

Coordination rules:
- Ping group chat every 30 minutes with status: done / blocked / next
- P1 merges all PRs into main
- Branches: p1-core, p2-sidecar, p3-plugin
- When P1 finishes core announce in group chat — P2 swaps mock immediately
- When P2 connects real core announce in group chat — P3 connects real sidecar
- Nobody changes PROTOCOL.md without group chat approval
- Nobody touches another person's files without asking

Rules for Claude Code:
- Never touch files owned by another team member (see ownership above)
- Never change the JSON protocol
- Always use exact class name PyncCore
- Always use exact method names
- Store data in ./data/
- Never console.log in core — use process.stderr for debug
- Report PASS or FAIL after each test before moving to next step
- Do not stop until all steps done and both tests pass

Demo plan (P4 executes):
1. Open IntelliJ, show Pync panel — Disconnected
2. Click Create, type room + passphrase, panel shows topicKey
3. On second laptop: paste topicKey, click Join — shows Syncing then Synced
4. Manager adds DB_URL=postgres://prod — member IDE updates instantly
5. Manager changes API_KEY — member sees row flash yellow and update
6. Disconnect member laptop from wifi, manager changes a value,
   reconnect laptop — value syncs automatically from offline cache
7. Say: No cloud. No server. Direct device to device. Encrypted. Built on Pear.

Dependencies:
npm install hyperswarm autobase hyperbee corestore b4a sodium-native commander chalk boxen