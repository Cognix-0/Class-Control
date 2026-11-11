module.exports = {
  ...require('./package.json').build,
  appId: 'lk.classcontrol.student',
  productName: 'Class Control Student',
  extraMetadata: { main: 'electron/student.js' },
  directories: { output: 'release-student' },
  extraResources: [
    { from: 'build/java', to: 'java' },
    { from: '../Agent.java', to: 'Agent.java' },
  ],
  win: { target: 'nsis', requestedExecutionLevel: 'requireAdministrator' },
};
