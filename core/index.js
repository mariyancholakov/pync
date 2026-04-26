const Corestore = require('corestore')
const Autobase = require('autobase')
const Hyperbee = require('hyperbee')
const Hyperswarm = require('hyperswarm')
const Protomux = require('protomux')
const b4a = require('b4a')
const crypto = require('./crypto')
const WebSocket = require('ws')
const RelayDHT = require('@hyperswarm/dht-relay')
const DhtRelayWS = require('@hyperswarm/dht-relay/ws')

class PyncCore {
  constructor (opts = {}) {
    this.base = null
    this.swarm = null
    this.store = null
    this.encryptionKey = null
    this.role = null
    this._onChange = null
    this._dht = null
    this._destroyed = false
    this._dataDir = opts.dataDir || process.env.PYNC_DATA_DIR || './data/'
    this._bootstrap = opts.bootstrap || (process.env.PYNC_BOOTSTRAP ? JSON.parse(process.env.PYNC_BOOTSTRAP) : undefined)
    this._relayURL = opts.relayURL || process.env.RELAY_URL || null
  }

  _makeHandlers () {
    return {
      open (store) {
        return new Hyperbee(store.get('view'), {
          keyEncoding: 'utf-8',
          valueEncoding: 'utf-8'
        })
      },
      async apply (nodes, view, host) {
        for (const node of nodes) {
          const val = typeof node.value === 'string' ? node.value : b4a.toString(node.value)
          const op = JSON.parse(val)
          if (op.type === 'addWriter') {
            await host.addWriter(b4a.from(op.key, 'hex'), { indexer: true })
            continue
          }
          await view.put(op.key, op.value)
        }
      }
    }
  }

  async createWorkspace (workspace, passphrase) {
    this.role = 'creator'
    const dir = require('path').join(this._dataDir, 'ws-' + Date.now().toString(36))
    require('fs').mkdirSync(dir, { recursive: true })
    this.store = new Corestore(dir)

    this.base = new Autobase(this.store, null, this._makeHandlers())
    await this.base.ready()

    const topicKey = b4a.toString(this.base.key, 'hex')
    this.encryptionKey = crypto.deriveKey(passphrase, topicKey)

    await this._setupSwarm()
    this._setupUpdateListener()

    return { topicKey }
  }

  async joinWorkspace (topicKey, passphrase) {
    this.role = 'member'
    const dir = require('path').join(this._dataDir, topicKey.slice(0, 16))
    require('fs').mkdirSync(dir, { recursive: true })
    this.store = new Corestore(dir)

    const bootstrap = b4a.from(topicKey, 'hex')
    this.base = new Autobase(this.store, bootstrap, this._makeHandlers())
    await this.base.ready()

    this.encryptionKey = crypto.deriveKey(passphrase, topicKey)

    await this._setupSwarm()
    this._setupUpdateListener()
  }

  getWriterKey () {
    return b4a.toString(this.base.local.key, 'hex')
  }

  async addWriter (writerKey) {
    await this.base.append(JSON.stringify({ type: 'addWriter', key: writerKey }))
  }

  async _connectRelay () {
    if (!this._relayURL || this._bootstrap) return null
    const wsURL = this._relayURL.replace('https://', 'wss://').replace('http://', 'ws://')
    process.stderr.write('[pync] connecting to DHT relay at ' + wsURL + '\n')
    const ws = new WebSocket(wsURL)
    await new Promise((resolve, reject) => {
      ws.on('open', resolve)
      ws.on('error', reject)
    })
    ws.on('close', () => {
      if (this._destroyed) return
      process.stderr.write('[pync] relay WebSocket closed, reconnecting in 3s...\n')
      setTimeout(() => this._reconnect(), 3000)
    })
    ws.on('error', () => {})
    return ws
  }

  async _reconnect () {
    if (this._destroyed) return
    try {
      if (this.swarm) { await this.swarm.destroy().catch(() => {}); this.swarm = null }
      if (this._dht) { await this._dht.destroy().catch(() => {}); this._dht = null }
      await this._setupSwarm()
      process.stderr.write('[pync] reconnected to relay\n')
      if (this._onChange) this._onChange()
    } catch (e) {
      process.stderr.write('[pync] reconnect failed: ' + e.message + ', retrying in 5s...\n')
      setTimeout(() => this._reconnect(), 5000)
    }
  }

  async _setupSwarm () {
    const swarmOpts = this._bootstrap ? { bootstrap: this._bootstrap } : {}

    if (this._relayURL && !this._bootstrap) {
      try {
        const ws = await this._connectRelay()
        if (ws) {
          this._dht = new RelayDHT(new DhtRelayWS(true, ws))
          swarmOpts.dht = this._dht
        }
      } catch (e) {
        process.stderr.write('[pync] relay connection failed, using direct DHT: ' + e.message + '\n')
      }
    }

    this.swarm = new Hyperswarm(swarmOpts)
    this.swarm.join(this.base.discoveryKey)
    this.swarm.on('connection', (conn) => {
      this.store.replicate(conn)
      this._exchangeWriterKeys(conn)
    })
  }

  _exchangeWriterKeys (conn) {
    const mux = Protomux.from(conn)
    const channel = mux.createChannel({ protocol: 'pync/writer-exchange' })
    const self = this
    const msg = channel.addMessage({
      encoding: {
        preencode (state, m) { state.end += m.length },
        encode (state, m) { state.buffer.set(m, state.start); state.start += m.length },
        decode (state) { return state.buffer.subarray(state.start, state.end) }
      },
      async onmessage (remoteKey) {
        if (self.base.writable) {
          self.addWriter(b4a.toString(remoteKey, 'hex')).catch(() => {})
        }
      }
    })
    channel.open()
    msg.send(self.base.local.key)
  }

  _setupUpdateListener () {
    this.base.on('update', () => {
      if (this._onChange) this._onChange()
    })
  }

  async setSecret (key, value) {
    const encrypted = crypto.encrypt(value, this.encryptionKey)
    const envelope = JSON.stringify({ v: encrypted, w: this.getWriterKey(), t: Date.now() })
    await this.base.append(JSON.stringify({ type: 'put', key, value: envelope }))
  }

  async deleteSecret (key) {
    const envelope = JSON.stringify({ v: null, w: this.getWriterKey(), t: Date.now() })
    await this.base.append(JSON.stringify({ type: 'put', key, value: envelope }))
  }

  async listSecrets () {
    try { await this.base.update() } catch (_) {}
    const secrets = []
    for await (const entry of this.base.view.createReadStream()) {
      if (!entry.value || entry.value === 'null') continue
      try {
        const envelope = JSON.parse(entry.value)
        if (!envelope.v || envelope.v === null) continue
        const decrypted = crypto.decrypt(envelope.v, this.encryptionKey)
        secrets.push({
          key: entry.key,
          value: decrypted,
          writer: envelope.w || null,
          timestamp: envelope.t || null
        })
      } catch (e) {
        process.stderr.write('decrypt error for key ' + entry.key + ': ' + e.message + '\n')
      }
    }
    return secrets
  }

  onChange (callback) {
    this._onChange = callback
  }

  getPeerCount () {
    if (!this.swarm) return 0
    return this.swarm.connections.size
  }

  async destroy () {
    this._destroyed = true
    if (this.swarm) await this.swarm.destroy()
    if (this._dht) await this._dht.destroy()
    if (this.base) await this.base.close()
    if (this.store) await this.store.close()
  }
}

module.exports = PyncCore

if (require.main === module) {
  (async () => {
    const fs = require('fs')
    const testDir = './data-selftest-' + Date.now()

    try {
      const core = new PyncCore({ dataDir: testDir })
      const { topicKey } = await core.createWorkspace('testworkspace', 'testpass')

      if (!topicKey || topicKey.length !== 64) throw new Error('bad topicKey')
      if (core.role !== 'creator') throw new Error('role should be creator')

      await core.setSecret('DB_URL', 'postgres://localhost/mydb')
      await core.setSecret('API_KEY', 'sk-abc123')

      const secrets = await core.listSecrets()
      if (secrets.length !== 2) throw new Error('expected 2 secrets, got ' + secrets.length)

      const db = secrets.find(s => s.key === 'DB_URL')
      if (!db || db.value !== 'postgres://localhost/mydb') throw new Error('DB_URL mismatch')
      if (!db.writer || db.writer.length !== 64) throw new Error('missing writer key')
      if (!db.timestamp || typeof db.timestamp !== 'number') throw new Error('missing timestamp')

      const api = secrets.find(s => s.key === 'API_KEY')
      if (!api || api.value !== 'sk-abc123') throw new Error('API_KEY mismatch')

      await core.deleteSecret('API_KEY')
      const after = await core.listSecrets()
      if (after.length !== 1) throw new Error('expected 1 secret after delete, got ' + after.length)
      if (after[0].key !== 'DB_URL') throw new Error('wrong key after delete')

      await core.destroy()

      fs.rmSync(testDir, { recursive: true, force: true })

      console.log('PASS')
      process.exit(0)
    } catch (e) {
      console.log('FAIL: ' + e.message)
      process.stderr.write(e.stack + '\n')
      try {
        fs.rmSync(testDir, { recursive: true, force: true })
      } catch (_) {}
      process.exit(1)
    }
  })()
}
