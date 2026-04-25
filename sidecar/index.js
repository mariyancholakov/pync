const PyncCore = require('../core/index.js')

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
        if (!cmd.key || cmd.value == null) {
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

core.onChange(async () => {
  const secrets = await core.listSecrets()
  state.setSecrets(secrets)
  emit({ type: 'update', secrets })
})

const peerInterval = setInterval(() => {
  const count = core.getPeerCount()
  state.setPeerCount(count)
  emit({ type: 'peers', count })
}, 30000)
peerInterval.unref()

let queue = Promise.resolve()

const rl = readline.createInterface({ input: process.stdin })
rl.on('line', (line) => {
  queue = queue.then(async () => {
    let cmd
    try {
      cmd = JSON.parse(line)
    } catch (e) {
      emit({ type: 'error', message: 'Invalid JSON: ' + e.message })
      return
    }
    await handleCommand(cmd)
  })
})
rl.on('close', shutdown)

async function shutdown() {
  await queue
  debug('shutting down')
  await core.destroy()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)

debug('sidecar started')
