const fs = require('fs')
const path = require('path')

function writeEnvFile (secrets, filePath) {
  const lines = secrets.map(s => s.key + '=' + s.value)
  fs.writeFileSync(filePath, lines.join('\n') + '\n')
}

module.exports = { writeEnvFile }
