const { spawn } = require('child_process')
const path = require('path')

const SIDECAR = path.join(__dirname, '..', 'sidecar', 'index.js')
const TIMEOUT_MS = 10000

let sidecarA = null
let sidecarB = null

function cleanup () {
  if (sidecarA && !sidecarA.killed) sidecarA.kill()
  if (sidecarB && !sidecarB.killed) sidecarB.kill()
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

function spawnSidecar (name) {
  const child = spawn('node', [SIDECAR], {
    stdio: ['pipe', 'pipe', 'pipe'],
    cwd: path.join(__dirname, '..')
  })
  child.stderr.on('data', (d) => {
    process.stderr.write('[' + name + ' stderr] ' + d)
  })
  return child
}

;(async () => {
  try {
    sidecarA = spawnSidecar('A')
    send(sidecarA, { cmd: 'create', room: 'integration-test', passphrase: 'testpass123' })

    const readyA = await waitForEvent(
      sidecarA,
      (msg) => msg.type === 'ready',
      'A ready'
    )
    const topicKey = readyA.topicKey
    process.stderr.write('A ready, topicKey: ' + topicKey + '\n')

    sidecarB = spawnSidecar('B')
    send(sidecarB, { cmd: 'join', topicKey, passphrase: 'testpass123' })

    await waitForEvent(
      sidecarB,
      (msg) => msg.type === 'ready',
      'B ready'
    )
    process.stderr.write('B ready\n')

    send(sidecarA, { cmd: 'set', key: 'TEST_KEY', value: 'hello_pync' })
    process.stderr.write('Sent set TEST_KEY=hello_pync to A\n')

    await waitForEvent(
      sidecarB,
      (msg) => {
        if (msg.type !== 'update' && msg.type !== 'list') return false
        if (!Array.isArray(msg.secrets)) return false
        return msg.secrets.some(
          (s) => s.key === 'TEST_KEY' && s.value === 'hello_pync'
        )
      },
      'B update with TEST_KEY=hello_pync'
    )

    cleanup()
    console.log('\x1b[32mPASS\x1b[0m')
    process.exit(0)
  } catch (e) {
    cleanup()
    console.log('\x1b[31mFAIL: ' + e.message + '\x1b[0m')
    process.exit(1)
  }
})()
