const http = require('http')
const fs = require('fs')
const path = require('path')
const Corestore = require('corestore')
const Autobase = require('autobase')
const Hyperbee = require('hyperbee')
const Hyperswarm = require('hyperswarm')
const Protomux = require('protomux')
const b4a = require('b4a')
const DHT = require('hyperdht')
const { relay } = require('@hyperswarm/dht-relay')
const DhtRelayWS = require('@hyperswarm/dht-relay/ws')
const { WebSocketServer } = require('ws')

const PORT = process.env.RELAY_PORT || 3001
const SECRET = process.env.RELAY_SECRET
const DATA_ROOT = process.env.RELAY_DATA || path.join(__dirname, '..', 'relay-data')

if (!SECRET) {
  process.stderr.write('RELAY_SECRET env var is required\n')
  process.exit(1)
}

const workspaces = new Map()

async function joinWorkspace (topicKey) {
  if (workspaces.has(topicKey)) return

  const dataDir = path.join(DATA_ROOT, topicKey.slice(0, 16))
  fs.mkdirSync(dataDir, { recursive: true })

  const store = new Corestore(dataDir)
  const base = new Autobase(store, b4a.from(topicKey, 'hex'), {
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
  })

  await base.ready()

  const swarm = new Hyperswarm()
  swarm.join(base.discoveryKey)
  swarm.on('connection', (conn) => {
    store.replicate(conn)
    exchangeWriterKeys(conn, base)
  })

  base.on('update', () => {
    process.stderr.write('workspace ' + topicKey.slice(0, 16) + '... updated\n')
  })

  const updateInterval = setInterval(() => {
    base.update().catch(() => {})
  }, 2000)
  updateInterval.unref()

  workspaces.set(topicKey, { store, base, swarm, updateInterval })
  process.stderr.write('joined workspace ' + topicKey.slice(0, 16) + '...\n')
}

function exchangeWriterKeys (conn, base) {
  const mux = Protomux.from(conn)
  const channel = mux.createChannel({ protocol: 'pync/writer-exchange' })
  const msg = channel.addMessage({
    encoding: {
      preencode (state, m) { state.end += m.length },
      encode (state, m) { state.buffer.set(m, state.start); state.start += m.length },
      decode (state) { return state.buffer.subarray(state.start, state.end) }
    },
    async onmessage (remoteKey) {
      if (base.writable) {
        const keyHex = b4a.toString(remoteKey, 'hex')
        await base.append(JSON.stringify({ type: 'addWriter', key: keyHex })).catch(() => {})
        process.stderr.write('added writer ' + keyHex.slice(0, 16) + '...\n')
      }
    }
  })
  channel.open()
  msg.send(base.local.key)
}

async function leaveWorkspace (topicKey) {
  const ws = workspaces.get(topicKey)
  if (!ws) return false
  clearInterval(ws.updateInterval)
  await ws.swarm.destroy()
  await ws.base.close()
  await ws.store.close()
  workspaces.delete(topicKey)
  process.stderr.write('left workspace ' + topicKey.slice(0, 16) + '...\n')
  return true
}

function auth (req) {
  const header = req.headers.authorization || ''
  return header === 'Bearer ' + SECRET
}

function readBody (req) {
  return new Promise((resolve) => {
    let data = ''
    req.on('data', (c) => { data += c })
    req.on('end', () => {
      try { resolve(JSON.parse(data)) } catch { resolve(null) }
    })
  })
}

function send (res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

const server = http.createServer(async (req, res) => {
  if (!auth(req)) return send(res, 401, { error: 'unauthorized' })

  if (req.method === 'GET' && req.url === '/relay') {
    return send(res, 200, { workspaces: [...workspaces.keys()] })
  }

  if (req.method === 'POST' && req.url === '/relay') {
    const body = await readBody(req)
    if (!body || !body.topicKey || body.topicKey.length !== 64) {
      return send(res, 400, { error: 'missing or invalid topicKey' })
    }
    await joinWorkspace(body.topicKey)
    return send(res, 200, { ok: true, topicKey: body.topicKey })
  }

  if (req.method === 'DELETE' && req.url === '/relay') {
    const body = await readBody(req)
    if (!body || !body.topicKey) return send(res, 400, { error: 'missing topicKey' })
    const removed = await leaveWorkspace(body.topicKey)
    return send(res, 200, { ok: removed })
  }

  send(res, 404, { error: 'not found' })
})

const dht = new DHT()
const wss = new WebSocketServer({ server })
wss.on('connection', (socket) => {
  process.stderr.write('dht-relay: new WebSocket client\n')
  relay(dht, new DhtRelayWS(false, socket))
})

server.listen(PORT, () => {
  process.stderr.write('relay server listening on port ' + PORT + ' (HTTP + WebSocket DHT relay)\n')
})

async function shutdown () {
  process.stderr.write('shutting down...\n')
  for (const [key] of workspaces) await leaveWorkspace(key)
  wss.close()
  await dht.destroy()
  server.close()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
