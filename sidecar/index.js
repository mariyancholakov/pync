let USE_MOCK = true
try {
  require('../core/index.js')
  USE_MOCK = false
} catch (e) {
  USE_MOCK = true
}

// ============================================================
// MOCK - replace when core is ready
// ============================================================
class PyncCoreMock {
  constructor() {
    this._secrets = new Map()
    this._onChangeCb = null
  }

  async createWorkspace(room, passphrase) {
    return { topicKey: 'mock_' + Buffer.from(room).toString('hex') }
  }

  async joinWorkspace(topicKey, passphrase) {}

  async setSecret(key, value) {
    this._secrets.set(key, value)
    if (this._onChangeCb) {
      this._onChangeCb(await this.listSecrets())
    }
  }

  async deleteSecret(key) {
    this._secrets.delete(key)
    if (this._onChangeCb) {
      this._onChangeCb(await this.listSecrets())
    }
  }

  async listSecrets() {
    return Array.from(this._secrets.entries()).map(
      ([key, value]) => ({ key, value })
    )
  }

  onChange(cb) { this._onChangeCb = cb }
  getPeerCount() { return 2 }
  async destroy() {}
}

const PyncCore = USE_MOCK ? PyncCoreMock : require('../core/index.js')

const readline = require('readline')
const state = require('./state.js')

const core = new PyncCore()

function emit(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n')
}

function debug(msg) {
  process.stderr.write('[sidecar] ' + msg + '\n')
}

async function handleCommand(cmd) {
  try {
    switch (cmd.cmd) {
      case 'create': {
        if (!cmd.room || !cmd.passphrase) {
          emit({ type: 'error', message: 'Missing room or passphrase' })
          return
        }
        const result = await core.createWorkspace(cmd.room, cmd.passphrase)
        state.setReady('manager', result.topicKey)
        emit({ type: 'ready', topicKey: result.topicKey, role: 'manager' })
        break
      }

      case 'join': {
        if (!cmd.topicKey || !cmd.passphrase) {
          emit({ type: 'error', message: 'Missing topicKey or passphrase' })
          return
        }
        await core.joinWorkspace(cmd.topicKey, cmd.passphrase)
        state.setReady('member', cmd.topicKey)
        emit({ type: 'ready', topicKey: cmd.topicKey, role: 'member' })
        const secrets = await core.listSecrets()
        state.setSecrets(secrets)
        emit({ type: 'list', secrets })
        break
      }

      case 'set': {
        if (!cmd.key || !cmd.value) {
          emit({ type: 'error', message: 'Missing key or value' })
          return
        }
        await core.setSecret(cmd.key, cmd.value)
        break
      }

      case 'delete': {
        if (!cmd.key) {
          emit({ type: 'error', message: 'Missing key' })
          return
        }
        await core.deleteSecret(cmd.key)
        break
      }

      case 'list': {
        const secrets = await core.listSecrets()
        state.setSecrets(secrets)
        emit({ type: 'list', secrets })
        break
      }

      default:
        emit({ type: 'error', message: 'Unknown command: ' + cmd.cmd })
    }
  } catch (e) {
    emit({ type: 'error', message: e.message })
  }
}

core.onChange(async (secrets) => {
  state.setSecrets(secrets)
  emit({ type: 'update', secrets })
})

const peerInterval = setInterval(() => {
  const count = core.getPeerCount()
  state.setPeerCount(count)
  emit({ type: 'peers', count })
}, 30000)
peerInterval.unref()

const rl = readline.createInterface({ input: process.stdin })
rl.on('line', async (line) => {
  let cmd
  try {
    cmd = JSON.parse(line)
  } catch (e) {
    emit({ type: 'error', message: 'Invalid JSON: ' + e.message })
    return
  }
  await handleCommand(cmd)
})
rl.on('close', shutdown)

async function shutdown() {
  debug('shutting down')
  await core.destroy()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)

debug(USE_MOCK ? 'running with MOCK core' : 'running with REAL core')
