# Download Class Control

Windows 64-bit setup files are available on the [GitHub Releases page](https://github.com/Cognix-0/Class-Control/releases/tag/v1.0.0):

- [Instructor setup](https://github.com/Cognix-0/Class-Control/releases/download/v1.0.0/Class.Control-Setup-1.0.0-x64.exe)
- [Student setup](https://github.com/Cognix-0/Class-Control/releases/download/v1.0.0/Class.Control.Student-Setup-1.0.0-x64.exe)

Run the appropriate setup to install and launch automatically. Java is included. Windows may show an unknown-publisher prompt because these installers are unsigned.

The installers were built from commit `584d6b0`. Mac installers are not included in this release. See [INSTALL.md](INSTALL.md) for setup, rebuild instructions, and platform limitations.

The setup files are also tracked on `main` using Git LFS:

- [Instructor setup in main](installers/windows/Class-Control-Instructor-Setup.exe)
- [Student setup in main](installers/windows/Class-Control-Student-Setup.exe)

Use **Download raw file** on the file's GitHub page to download the actual installer. When cloning, install Git LFS and run `git lfs pull` to retrieve the binaries. Each installer exceeds GitHub's 100 MB regular Git file limit, so Git LFS stores its binary contents separately. Generated dependencies and other build output remain ignored.
