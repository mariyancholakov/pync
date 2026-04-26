<div align="center">

# pync

### Peer-to-peer encrypted secrets for developer teams

**No cloud. No server. Secrets never leave your device unencrypted.**

[![Built at HackUPC 2026](https://img.shields.io/badge/Built%20at-HackUPC%202026-00BCD4?style=for-the-badge)](https://hackupc.com)
[![Pear Protocol](https://img.shields.io/badge/Pear-Protocol-00BCD4?style=for-the-badge)](https://holepunch.to)
[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-FF6B35?style=for-the-badge&logo=jetbrains&logoColor=white)](https://www.jetbrains.com)

</div>

## The Problem

Teams share secrets over Slack DMs, cloud vaults, or `.env` files in shared drives. All of these are either plaintext, centralized, or both.

## The Solution

pync encrypts secrets on your device (AES-256-GCM) and syncs them directly between teammates over the Pear/Holepunch P2P protocol. No server ever sees your plaintext.

```
                ┌──────────────────┐
                │  IntelliJ Plugin │
                └────────┬─────────┘
                         │
                ┌────────▼─────────┐
                │     Sidecar      │
                └────────┬─────────┘
                         │
                ┌────────▼─────────┐
                │    PyncCore +    │
                │   AES-256-GCM   │
                └────────┬─────────┘
                         │
                ┌────────▼─────────┐
                │  Hyperswarm P2P  │
                └───┬──────────┬───┘
                    │          │
           ┌────────▼───┐  ┌──▼───────────┐
           │ Teammates  │  │ Relay        │
           │            │  │ pync.nyc     │
           └────────────┘  └──────────────┘
```

## Features

| Feature | Description |
|---------|-------------|
| **E2E Encryption** | AES-256-GCM with HKDF-derived keys |
| **Realtime sync** | Change a secret — teammates see it instantly |
| **Offline support** | Missed changes replay automatically on reconnect |
| **IntelliJ plugin** | Add/edit/delete secrets, reveal/hide, copy, export .env |
| **CLI** | `pync set DB_URL postgres://...` from the terminal |
| **No central server** | The relay is just a peer — remove it and everything still works |
| **Auto .env sync** | Secrets write to your `.env` files as they change |

## Getting Started

### Install the Plugin

1. Download [`pync-plugin-1.0.0.zip`](pync-plugin-1.0.0.zip)
2. IntelliJ → **Settings** → **Plugins** → **Gear icon** → **Install Plugin from Disk...**
3. Select the zip, restart, find **Pync** in the right sidebar

### Usage

1. **Create a workspace** — click **Create Workspace**, enter a room name and passphrase
2. **Share the topic key** — click **Copy Workspace Key** in the toolbar, send it to your team
3. **Teammates join** — they click **Join Workspace**, paste the key, enter the same passphrase
4. **Add secrets** — click **+**, enter key and value (e.g. `DB_URL` = `postgres://prod:5432`)
5. **Edit / Delete** — select a secret, click **Edit** or **Delete** in the toolbar
6. **Copy a value** — click the copy icon on any secret card
7. **Export .env** — click **Export .env** to write all secrets to a `.env` file
8. **Reveal / Hide** — click the eye icon on any secret to toggle visibility

All changes sync instantly to every connected teammate.

## Security

- **AES-256-GCM** encryption via `sodium-native`
- **HKDF** key derivation from passphrase + room name
- **Random 12-byte nonce** per secret
- **Relay has zero knowledge** — only sees encrypted blobs, never has the passphrase

## Tech Stack

[Hyperswarm](https://github.com/holepunchto/hyperswarm) · [Autobase](https://github.com/holepunchto/autobase) · [Hyperbee](https://github.com/holepunchto/hyperbee) · [sodium-native](https://github.com/holepunchto/sodium-native) · [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html) · [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)

## Testing

```bash
node test/integration.js   # 12 end-to-end tests
```

---

<div align="center">

Built at **HackUPC 2026** in Barcelona

</div>
