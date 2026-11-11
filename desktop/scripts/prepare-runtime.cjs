const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const output = path.resolve(__dirname, '../build/java');
const suffix = process.platform === 'win32' ? '.exe' : '';
const marker = path.join(output, '.class-control-platform');
const platform = `${process.platform}-${process.arch}`;

if (fs.existsSync(path.join(output, `bin/java${suffix}`)) &&
    fs.existsSync(marker) && fs.readFileSync(marker, 'utf8') === platform) process.exit(0);

// Only remove the generated runtime in this project's build directory.
fs.rmSync(output, { recursive: true, force: true });
fs.mkdirSync(path.dirname(output), { recursive: true });
const jlink = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, `bin/jlink${suffix}`) : 'jlink';
const result = spawnSync(jlink, [
  '--add-modules', 'java.se,jdk.compiler,jdk.zipfs,jdk.unsupported',
  '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2',
  '--output', output,
], { stdio: 'inherit' });
if (result.error) console.error('Install a native JDK 17+ with jlink:', result.error.message);
if (result.status === 0) fs.writeFileSync(marker, platform);
process.exit(result.status ?? 1);
