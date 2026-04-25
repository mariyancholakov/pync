const Corestore = require('corestore')
const Autobase = require('autobase')
const Hyperbee = require('hyperbee')
const Hyperswarm = require('hyperswarm')
const b4a = require('b4a')
const crypto = require('./crypto')

class PyncCore {
  constructor (opts = {}) {
    this.base = null
    this.swarm = null
    this.store = null
    this.encryptionKey = null
    this.role = null
    this._onChange = null
    this._dataDir = opts.dataDir || process.env.PYNC_DATA_DIR || './data/'
    this._bootstrap = opts.bootstrap || (process.env.PYNC_BOOTSTRAP ? JSON.parse(process.env.PYNC_BOOTSTRAP) : undefined)
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

  async createWorkspace (room, passphrase) {
    this.role = 'manager'
    this.store = new Corestore(this._dataDir)

    this.base = new Autobase(this.store, null, this._makeHandlers())
    await this.base.ready()

    const topicKey = b4a.toString(this.base.key, 'hex')
    this.encryptionKey = crypto.deriveKey(passphrase, topicKey)

    this._setupSwarm()
    this._setupUpdateListener()

    return { topicKey }
  }

  async joinWorkspace (topicKey, passphrase) {
    this.role = 'member'
    this.store = new Corestore(this._dataDir)

    const bootstrap = b4a.from(topicKey, 'hex')
    this.base = new Autobase(this.store, bootstrap, this._makeHandlers())
    await this.base.ready()

    this.encryptionKey = crypto.deriveKey(passphrase, topicKey)

    this._setupSwarm()
    this._setupUpdateListener()
  }

  _setupSwarm () {
    const swarmOpts = this._bootstrap ? { bootstrap: this._bootstrap } : {}
    this.swarm = new Hyperswarm(swarmOpts)
    this.swarm.join(this.base.discoveryKey)
    this.swarm.on('connection', (conn) => {
      this.store.replicate(conn)
    })
  }

  _setupUpdateListener () {
    this.base.on('update', () => {
      if (this._onChange) this._onChange()
    })
  }

  async setSecret (key, value) {
    if (this.role !== 'manager') throw new Error('Only manager can set secrets')
    const encrypted = crypto.encrypt(value, this.encryptionKey)
    await this.base.append(JSON.stringify({ type: 'put', key, value: encrypted }))
  }

  async deleteSecret (key) {
    if (this.role !== 'manager') throw new Error('Only manager can delete secrets')
    await this.base.append(JSON.stringify({ type: 'put', key, value: null }))
  }

  async listSecrets () {
    await this.base.update()
    const secrets = []
    for await (const entry of this.base.view.createReadStream()) {
      if (!entry.value || entry.value === 'null') continue
      try {
        const decrypted = crypto.decrypt(entry.value, this.encryptionKey)
        secrets.push({ key: entry.key, value: decrypted })
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
    if (this.swarm) await this.swarm.destroy()
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
      const { topicKey } = await core.createWorkspace('testroom', 'testpass')

      if (!topicKey || topicKey.length !== 64) throw new Error('bad topicKey')
      if (core.role !== 'manager') throw new Error('role should be manager')

      await core.setSecret('DB_URL', 'postgres://localhost/mydb')
      await core.setSecret('API_KEY', 'sk-abc123')

      const secrets = await core.listSecrets()
      if (secrets.length !== 2) throw new Error('expected 2 secrets, got ' + secrets.length)

      const db = secrets.find(s => s.key === 'DB_URL')
      if (!db || db.value !== 'postgres://localhost/mydb') throw new Error('DB_URL mismatch')

      const api = secrets.find(s => s.key === 'API_KEY')
      if (!api || api.value !== 'sk-abc123') throw new Error('API_KEY mismatch')

      await core.deleteSecret('API_KEY')
      const after = await core.listSecrets()
      if (after.length !== 1) throw new Error('expected 1 secret after delete, got ' + after.length)
      if (after[0].key !== 'DB_URL') throw new Error('wrong key after delete')

      let threw = false
      const core2 = new PyncCore({ dataDir: testDir + '-member' })
      await core2.joinWorkspace(topicKey, 'testpass')
      try { await core2.setSecret('X', 'Y') } catch { threw = true }
      if (!threw) throw new Error('member should not be able to set secrets')

      await core2.destroy()
      await core.destroy()

      fs.rmSync(testDir, { recursive: true, force: true })
      fs.rmSync(testDir + '-member', { recursive: true, force: true })

      console.log('PASS')
      process.exit(0)
    } catch (e) {
      console.log('FAIL: ' + e.message)
      process.stderr.write(e.stack + '\n')
      try {
        fs.rmSync(testDir, { recursive: true, force: true })
        fs.rmSync(testDir + '-member', { recursive: true, force: true })
      } catch (_) {}
      process.exit(1)
    }
  })()
}
