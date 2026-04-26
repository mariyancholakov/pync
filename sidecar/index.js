const PyncCore = require('../core/index.js')
const http = require('http')
const https = require('https')

const readline = require('readline')
const state = require('./state.js')

const RELAY_URL = process.env.RELAY_URL || 'https://pync.nyc'
const RELAY_SECRET = process.env.RELAY_SECRET || 'hackupc'

const core = new PyncCore({ relayURL: RELAY_URL })

function registerWithRelay (topicKey) {
  if (!RELAY_SECRET) return
  const url = new URL('/relay', RELAY_URL)
  const body = JSON.stringify({ topicKey })
  const mod = url.protocol === 'https:' ? https : http
  const req = mod.request(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + RELAY_SECRET,
      'Content-Length': Buffer.byteLength(body)
    }
  }, (res) => {
    let data = ''
    res.on('data', (c) => { data += c })
    res.on('end', () => {
      debug('relay register: ' + res.statusCode + ' ' + data)
    })
  })
  req.on('error', (e) => debug('relay register failed: ' + e.message))
  req.write(body)
  req.end()
}

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
        if (!cmd.workspace || !cmd.passphrase) {
          emit({ type: 'error', message: 'Missing workspace or passphrase' })
          return
        }
        const result = await core.createWorkspace(cmd.workspace, cmd.passphrase)
        state.setReady('manager', result.topicKey)
        emit({ type: 'ready', topicKey: result.topicKey, role: 'manager' })
        registerWithRelay(result.topicKey)
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
        registerWithRelay(cmd.topicKey)
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
