const { Command } = require('commander')
const chalk = require('chalk')
const boxen = require('boxen')
const fs = require('fs')
const path = require('path')
const { spawn } = require('child_process')
const PyncCore = require('../core/index')
const { writeEnvFile } = require('../core/export')

const CONFIG_DIR = path.join(require('os').homedir(), '.pync')
const CONFIG_PATH = path.join(CONFIG_DIR, 'config.json')

function saveConfig (config) {
  fs.mkdirSync(CONFIG_DIR, { recursive: true })
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2) + '\n')
}

function loadConfig () {
  if (!fs.existsSync(CONFIG_PATH)) {
    console.error(chalk.red('No workspace configured. Run pync create or pync join first.'))
    process.exit(1)
  }
  return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf-8'))
}

async function coreFromConfig () {
  const config = loadConfig()
  const core = new PyncCore()
  if (config.role === 'manager') {
    await core.createWorkspace(config.topicKey, config.passphrase)
  } else {
    await core.joinWorkspace(config.topicKey, config.passphrase)
  }
  return core
}

async function runCommand () {
  const runIdx = process.argv.indexOf('run')
  let cmdArgs = process.argv.slice(runIdx + 1)
  if (cmdArgs[0] === '--') cmdArgs = cmdArgs.slice(1)

  if (cmdArgs.length === 0) {
    console.error(chalk.red('Usage: pync run -- <command> [args...]'))
    process.exit(1)
  }

  const core = await coreFromConfig()
  const secrets = await core.listSecrets()
  const env = { ...process.env }
  for (const s of secrets) env[s.key] = s.value
  await core.destroy()

  const child = spawn(cmdArgs[0], cmdArgs.slice(1), { env, stdio: 'inherit' })
  child.on('exit', (code) => process.exit(code || 0))
}

if (process.argv.includes('run')) {
  runCommand().catch(e => {
    console.error(chalk.red(e.message))
    process.exit(1)
  })
} else {
  const program = new Command()
  program.name('pync').description('P2P encrypted environment variable sync').version('1.0.0')

  program
    .command('create <room> <passphrase>')
    .description('Create a new workspace')
    .action(async (room, passphrase) => {
      try {
        const core = new PyncCore()
        const { topicKey } = await core.createWorkspace(room, passphrase)

        saveConfig({ topicKey, passphrase, role: 'manager' })

        const banner = boxen(
          chalk.bold.green('Workspace created!') + '\n\n' +
          chalk.dim('Room: ') + chalk.white(room) + '\n' +
          chalk.dim('Role: ') + chalk.yellow('manager') + '\n\n' +
          chalk.dim('Topic Key (share with team):') + '\n' +
          chalk.cyan(topicKey),
          { padding: 1, borderColor: 'green', borderStyle: 'round' }
        )
        console.log(banner)
        console.log(chalk.dim('\nListening for peers... (Ctrl+C to exit)'))

        core.onChange(async () => {
          const secrets = await core.listSecrets()
          console.log(chalk.yellow('\n--- update ---'))
          for (const s of secrets) console.log(chalk.white(s.key + '=' + s.value))
        })
      } catch (e) {
        console.error(chalk.red(e.message))
        process.exit(1)
      }
    })

  program
    .command('join <topicKey> <passphrase>')
    .description('Join an existing workspace')
    .action(async (topicKey, passphrase) => {
      try {
        const core = new PyncCore()
        await core.joinWorkspace(topicKey, passphrase)

        saveConfig({ topicKey, passphrase, role: 'member' })

        console.log(chalk.green('Joined workspace as member'))
        console.log(chalk.dim('Topic Key: ') + chalk.cyan(topicKey))

        const secrets = await core.listSecrets()
        if (secrets.length > 0) {
          console.log(chalk.dim('\nCurrent secrets:'))
          for (const s of secrets) console.log(chalk.white(s.key + '=' + s.value))
        } else {
          console.log(chalk.dim('\nNo secrets yet.'))
        }

        console.log(chalk.dim('\nWatching for updates... (Ctrl+C to exit)'))
        core.onChange(async () => {
          const updated = await core.listSecrets()
          console.log(chalk.yellow('\n--- update ---'))
          for (const s of updated) console.log(chalk.white(s.key + '=' + s.value))
        })
      } catch (e) {
        console.error(chalk.red(e.message))
        process.exit(1)
      }
    })

  program
    .command('set <key> <value>')
    .description('Set a secret (manager only)')
    .action(async (key, value) => {
      try {
        const core = await coreFromConfig()
        await core.setSecret(key, value)
        console.log(chalk.green('Set ') + chalk.white(key))
        await core.destroy()
        process.exit(0)
      } catch (e) {
        console.error(chalk.red(e.message))
        process.exit(1)
      }
    })

  program
    .command('delete <key>')
    .description('Delete a secret (manager only)')
    .action(async (key) => {
      try {
        const core = await coreFromConfig()
        await core.deleteSecret(key)
        console.log(chalk.green('Deleted ') + chalk.white(key))
        await core.destroy()
        process.exit(0)
      } catch (e) {
        console.error(chalk.red(e.message))
        process.exit(1)
      }
    })

  program
    .command('list')
    .description('List all secrets')
    .action(async () => {
      try {
        const core = await coreFromConfig()
        const secrets = await core.listSecrets()
        for (const s of secrets) console.log(s.key + '=' + s.value)
        await core.destroy()
        process.exit(0)
      } catch (e) {
        console.error(chalk.red(e.message))
        process.exit(1)
      }
    })

  program
    .command('export')
    .description('Write secrets to .env file in current directory')
    .action(async () => {
      try {
        const core = await coreFromConfig()
        const secrets = await core.listSecrets()
        const envPath = path.join(process.cwd(), '.env')
        writeEnvFile(secrets, envPath)
        console.log(chalk.green('Wrote ' + secrets.length + ' secrets to ') + chalk.white(envPath))
        await core.destroy()
        process.exit(0)
      } catch (e) {
        console.error(chalk.red(e.message))
        process.exit(1)
      }
    })

  program.parse()
}
