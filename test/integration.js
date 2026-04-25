const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')
const os = require('os')
const createTestnet = require('hyperdht/testnet')

const SIDECAR = path.join(__dirname, '..', 'sidecar', 'index.js')
const TIMEOUT_MS = 15000

const tmpBase = path.join(os.tmpdir(), 'pync-test-' + Date.now())
const tmpA = path.join(tmpBase, 'a')
const tmpB = path.join(tmpBase, 'b')
fs.mkdirSync(tmpA, { recursive: true })
fs.mkdirSync(tmpB, { recursive: true })

let sidecarA = null
let sidecarB = null
let testnet = null

function cleanup () {
  if (sidecarA && !sidecarA.killed) sidecarA.kill()
  if (sidecarB && !sidecarB.killed) sidecarB.kill()
  try { fs.rmSync(tmpBase, { recursive: true, force: true }) } catch (_) {}
}

process.on('exit', cleanup)
process.on('SIGINT', () => { cleanup(); process.exit(1) })
process.on('SIGTERM', () => { cleanup(); process.exit(1) })

function send (proc, obj) {
  proc.stdin.write(JSON.stringify(obj) + '\n')
}

function waitForEvent (proc, predicate, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error('Timeout waiting for ' + label))
    }, TIMEOUT_MS)

    let buf = ''
    function onData (chunk) {
      buf += chunk.toString()
      const lines = buf.split('\n')
      buf = lines.pop()
      for (const line of lines) {
        if (!line.trim()) continue
        try {
          const msg = JSON.parse(line)
          if (predicate(msg)) {
            clearTimeout(timer)
            proc.stdout.removeListener('data', onData)
            resolve(msg)
            return
          }
        } catch (_) {}
      }
    }
    proc.stdout.on('data', onData)

    proc.on('exit', (code) => {
      clearTimeout(timer)
      reject(new Error(label + ': sidecar exited with code ' + code))
    })
  })
}

function spawnSidecar (name, dataDir, bootstrap) {
  const child = spawn('node', [SIDECAR], {
    stdio: ['pipe', 'pipe', 'pipe'],
    env: {
      ...process.env,
      PYNC_DATA_DIR: dataDir,
      PYNC_BOOTSTRAP: JSON.stringify(bootstrap)
    }
  })
  child.stderr.on('data', (d) => {
    process.stderr.write('[' + name + ' stderr] ' + d)
  })
  return child
}

function assert (condition, msg) {
  if (!condition) throw new Error('ASSERT: ' + msg)
}

;(async () => {
  try {
    testnet = await createTestnet(3)
    const bootstrap = testnet.bootstrap
    process.stderr.write('Local DHT testnet ready at ' + JSON.stringify(bootstrap) + '\n')

    // test 1: create workspace
    process.stderr.write('\n=== TEST 1: create workspace ===\n')
    sidecarA = spawnSidecar('A', tmpA, bootstrap)
    send(sidecarA, { cmd: 'create', workspace: 'integration-test', passphrase: 'testpass123' })

    const readyA = await waitForEvent(
      sidecarA,
      (msg) => msg.type === 'ready',
      'A ready'
    )
    assert(readyA.role === 'manager', 'A should be manager, got ' + readyA.role)
    assert(readyA.topicKey && readyA.topicKey.length === 64, 'A topicKey should be 64 hex chars')
    const topicKey = readyA.topicKey
    process.stderr.write('PASS: create returned ready with role=manager, topicKey=' + topicKey.slice(0, 16) + '...\n')

    // test 2: set secrets on manager
    process.stderr.write('\n=== TEST 2: set secrets on manager ===\n')
    send(sidecarA, { cmd: 'set', key: 'DB_URL', value: 'postgres://localhost/mydb' })
    send(sidecarA, { cmd: 'set', key: 'API_KEY', value: 'sk-abc123' })
    send(sidecarA, { cmd: 'set', key: 'REDIS_URL', value: 'redis://localhost:6379' })

    // test 3: list secrets on manager
    process.stderr.write('\n=== TEST 3: list secrets on manager ===\n')
    send(sidecarA, { cmd: 'list' })
    const listA = await waitForEvent(
      sidecarA,
      (msg) => msg.type === 'list' && Array.isArray(msg.secrets),
      'A list'
    )
    assert(listA.secrets.length === 3, 'expected 3 secrets, got ' + listA.secrets.length)

    const db = listA.secrets.find(s => s.key === 'DB_URL')
    assert(db && db.value === 'postgres://localhost/mydb', 'DB_URL mismatch')
    const api = listA.secrets.find(s => s.key === 'API_KEY')
    assert(api && api.value === 'sk-abc123', 'API_KEY mismatch')
    const redis = listA.secrets.find(s => s.key === 'REDIS_URL')
    assert(redis && redis.value === 'redis://localhost:6379', 'REDIS_URL mismatch')
    process.stderr.write('PASS: list returned 3 correct secrets\n')

    // test 4: delete secret on manager
    process.stderr.write('\n=== TEST 4: delete secret on manager ===\n')
    send(sidecarA, { cmd: 'delete', key: 'REDIS_URL' })
    send(sidecarA, { cmd: 'list' })
    const listAfterDel = await waitForEvent(
      sidecarA,
      (msg) => msg.type === 'list' && Array.isArray(msg.secrets),
      'A list after delete'
    )
    assert(listAfterDel.secrets.length === 2, 'expected 2 secrets after delete, got ' + listAfterDel.secrets.length)
    assert(!listAfterDel.secrets.find(s => s.key === 'REDIS_URL'), 'REDIS_URL should be deleted')
    process.stderr.write('PASS: delete removed REDIS_URL, 2 secrets remain\n')

    // test 5: join workspace
    process.stderr.write('\n=== TEST 5: join workspace ===\n')
    sidecarB = spawnSidecar('B', tmpB, bootstrap)
    send(sidecarB, { cmd: 'join', topicKey, passphrase: 'testpass123' })

    const readyB = await waitForEvent(
      sidecarB,
      (msg) => msg.type === 'ready',
      'B ready'
    )
    assert(readyB.role === 'member', 'B should be member, got ' + readyB.role)
    assert(readyB.topicKey === topicKey, 'B topicKey should match A')
    process.stderr.write('PASS: join returned ready with role=member\n')

    // test 6: member gets initial list after join
    process.stderr.write('\n=== TEST 6: member gets initial list after join ===\n')
    const listB = await waitForEvent(
      sidecarB,
      (msg) => msg.type === 'list' && Array.isArray(msg.secrets),
      'B initial list'
    )
    process.stderr.write('PASS: B received initial list with ' + listB.secrets.length + ' secrets\n')

    // test 7: member can set secrets (equal permissions)
    process.stderr.write('\n=== TEST 7: member can set secrets ===\n')
    process.stderr.write('Waiting for member to become writable...\n')
    await new Promise(r => setTimeout(r, 3000))
    send(sidecarB, { cmd: 'set', key: 'MEMBER_SECRET', value: 'from_member' })
    send(sidecarB, { cmd: 'list' })
    const listAfterMemberSet = await waitForEvent(
      sidecarB,
      (msg) => msg.type === 'list' && Array.isArray(msg.secrets) && msg.secrets.some(s => s.key === 'MEMBER_SECRET'),
      'B list after member set'
    )
    const ms = listAfterMemberSet.secrets.find(s => s.key === 'MEMBER_SECRET')
    assert(ms && ms.value === 'from_member', 'member set value mismatch')
    process.stderr.write('PASS: member set MEMBER_SECRET=from_member\n')

    // test 8: member can delete secrets (equal permissions)
    process.stderr.write('\n=== TEST 8: member can delete secrets ===\n')
    send(sidecarB, { cmd: 'delete', key: 'MEMBER_SECRET' })
    send(sidecarB, { cmd: 'list' })
    const listAfterMemberDel = await waitForEvent(
      sidecarB,
      (msg) => msg.type === 'list' && Array.isArray(msg.secrets) && !msg.secrets.some(s => s.key === 'MEMBER_SECRET'),
      'B list after member delete'
    )
    process.stderr.write('PASS: member deleted MEMBER_SECRET\n')

    // test 9: member can list secrets
    process.stderr.write('\n=== TEST 9: member can list secrets ===\n')
    send(sidecarB, { cmd: 'list' })
    const listBExplicit = await waitForEvent(
      sidecarB,
      (msg) => msg.type === 'list' && Array.isArray(msg.secrets),
      'B explicit list'
    )
    process.stderr.write('PASS: member list returned ' + listBExplicit.secrets.length + ' secrets\n')

    // test 10: realtime sync
    process.stderr.write('\n=== TEST 10: realtime sync (set propagates to member) ===\n')
    send(sidecarA, { cmd: 'set', key: 'LIVE_KEY', value: 'realtime_value' })
    process.stderr.write('Sent set LIVE_KEY=realtime_value to A, waiting for B update...\n')

    const updateB = await waitForEvent(
      sidecarB,
      (msg) => {
        if (msg.type !== 'update' && msg.type !== 'list') return false
        if (!Array.isArray(msg.secrets)) return false
        return msg.secrets.some(s => s.key === 'LIVE_KEY' && s.value === 'realtime_value')
      },
      'B update with LIVE_KEY=realtime_value'
    )
    process.stderr.write('PASS: member received LIVE_KEY=realtime_value via realtime sync\n')

    // test 11: error on invalid command
    process.stderr.write('\n=== TEST 11: error on invalid command ===\n')
    send(sidecarA, { cmd: 'bogus' })
    const errBogus = await waitForEvent(
      sidecarA,
      (msg) => msg.type === 'error',
      'A error on bogus'
    )
    process.stderr.write('PASS: invalid command returned error: ' + errBogus.message + '\n')

    // test 12: error on missing fields
    process.stderr.write('\n=== TEST 12: error on missing fields ===\n')
    send(sidecarA, { cmd: 'set' })
    const errMissing = await waitForEvent(
      sidecarA,
      (msg) => msg.type === 'error',
      'A error on missing fields'
    )
    process.stderr.write('PASS: missing fields returned error: ' + errMissing.message + '\n')

    cleanup()
    await testnet.destroy()
    console.log('\n\x1b[32m=== ALL 12 TESTS PASS ===\x1b[0m')
    process.exit(0)
  } catch (e) {
    cleanup()
    if (testnet) await testnet.destroy()
    console.log('\n\x1b[31mFAIL: ' + e.message + '\x1b[0m')
    process.stderr.write(e.stack + '\n')
    process.exit(1)
  }
})()
