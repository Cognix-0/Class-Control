# Windows installation

Requires Windows 10/11, 64-bit. Java and Electron are included; Node.js and Java do not need to be installed on destination PCs.

1. Copy `desktop/release/Class Control-Setup-1.0.0-x64.exe` to the instructor PC and run it. Setup automatically installs to the default location, creates shortcuts, and launches the app.
2. Copy `desktop/release-student/Class Control Student-Setup-1.0.0-x64.exe` to each student PC and run it. Setup automatically installs and launches the student app. Starting the student app requests administrator access for computer management features.
3. Connect the PCs to the same local network. Start a class in the instructor app, then open the student apps and sign in. Settings allows an explicit instructor IP and class password if automatic discovery is unavailable.
4. Allow the applications on the private network when Windows Firewall prompts. The network must permit TCP 5757 and UDP 5758 between lab PCs.

These installers are unsigned, so Windows may show an unknown publisher / SmartScreen prompt. Only run installers you obtained from a trusted source. Uninstall using Windows Settings > Apps.

## Rebuild

On Windows x64, install Node.js and a JDK 17+ containing `jlink`, then run from `desktop`:

```powershell
npm.cmd ci
npm.cmd run dist
npm.cmd run dist:student
```

`prepare:runtime` creates `desktop/build/java` from the build machine's JDK. Remove that generated directory before rebuilding with a newer JDK. Its runtime license notices are included in the installed `resources/java/legal` directory.

The student defaults can be set in `desktop/electron/studentDefaults.json` before building, or configured individually in the installed app.

## Mac computers

Mac installers use `.dmg`, rather than `.exe`. The Mac packages have not yet been built or tested on a Mac.

Build on a Mac with Node.js and a native JDK 17+ installed (use an Apple Silicon JDK on Apple Silicon and an Intel JDK on Intel):

```sh
cd desktop
npm ci
npm run dist:mac
npm run dist:student:mac
```

The build includes the Mac's native Java runtime. Output files appear in `desktop/release` and `desktop/release-student`, with `arm64` for Apple Silicon or `x64` for Intel in their names. Build separately on each architecture; copying a Windows Java runtime or choosing another Electron architecture will not produce a working bundle.

Alternatively, after pushing this project to GitHub, run **Actions > Build Mac installers > Run workflow**. Download the two architecture artifacts from the completed run. The workflow is manual and does not publish releases.

Open the appropriate DMG and drag the app to Applications. These builds are not Developer ID signed or notarized; public distribution requires configuring Apple signing credentials. macOS privacy permissions for screen capture and control must be granted on the target Mac. Validate those features on real Macs before classroom deployment.

### Current Mac feature limits

The instructor interface and Java networking backend are cross-platform. Student login, messaging, whiteboard and screen features use cross-platform code but still need real Mac testing and privacy permissions. Website-blocking enforcement (system proxy/firewall) and keyboard/gesture restrictions during screen lock currently use Windows-only code. They are not implemented for macOS; the visual lock overlay alone does not prevent switching apps. Packaging does not add these capabilities.
