const path = require('node:path');
const fs = require('node:fs');

function javaExecutable() {
  const bundled = path.join(process.resourcesPath || '', 'java', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  return fs.existsSync(bundled) ? bundled : 'java';
}

module.exports = { javaExecutable };
