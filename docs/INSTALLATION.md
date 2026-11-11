# Installation and platform verification

## Linux

The portable app archive is `build/StackTraceStudio-linux-x64.tar.gz`. Extract it
and run `StackTraceStudio/bin/StackTraceStudio`. It includes Java; no system JDK
is needed. A graphical desktop and Skia/AWT system libraries are required.
The app stores local data in `~/.stacktrace-studio` unless STACKTRACE_DATA_DIR
is set. Its local retrace operation never needs the backend.

Build a portable image with JDK 21:

```sh
./gradlew :composeApp:createDistributable
```

On Debian/Ubuntu with fakeroot and dpkg tools installed:

```sh
./gradlew :composeApp:packageDeb
sudo apt install ./composeApp/build/compose/binaries/main/deb/*.deb
```

Linux CI verified DEB installation and launched the installed bundled runtime in
[run 42825228608](https://github.com/mahdiy2/TraceWise/actions/runs/42825228608). A system JDK is not needed to run the installed app.
On minimal/headless hosts, desktop menu registration also needs `xdg-utils`,
`desktop-file-utils` and the standard `/etc/xdg/menus`,
`/usr/share/desktop-directories` and `/usr/share/applications` directories.
The CI workflow prepares these explicitly and uses Xvfb for graphical checks.

## Windows

Install JDK 21 and WiX 3.x (including candle.exe and light.exe on PATH), then use
PowerShell from the project root:

```powershell
.\gradlew.bat :composeApp:test :composeApp:createDistributable :composeApp:packageMsi
```

Open the MSI from `composeApp\build\compose\binaries\main\msi`. The installer is
configured for the current user, with Start menu and desktop shortcuts and a
stable upgrade UUID. The installed app includes Java and uses
`$env:USERPROFILE\.stacktrace-studio` by default. STACKTRACE_DATA_DIR overrides it.

For an isolated packaged-runtime test:

```powershell
$env:STACKTRACE_SMOKE_REPORT = "$env:TEMP\studio-smoke.txt"
& '.\composeApp\build\compose\binaries\main\app\StackTraceStudio\StackTraceStudio.exe' --smoke-test
```

A successful run opens/closes a temporary workspace, translates a fixture with
real R8, and writes STACKTRACE_STUDIO_SMOKE_OK to the report. No existing local
workspace data is changed. Windows builds and installer verification must run on
Windows; they cannot be established by a Linux compile.

## CI delivery gates

`.github/workflows/verify.yml` defines Linux tests/browser/DEB verification,
Windows tests/MSI installation and packaged-runtime verification, and a Docker
build/deployment check. It uploads installers and reports. All three jobs passed
in [run 42825228608](https://github.com/mahdiy2/TraceWise/actions/runs/42825228608), covering actual DEB/MSI installation, both installed
launchers, production browser behavior, PHP integration and Docker deployment.
The verified implementation commit is `b8065edf2ba4e91f2657b488aa3e23dae90c5bb4`.

Local delivery artifacts are `build/StackTraceStudio-linux-x64.deb`,
`build/StackTraceStudio-windows-x64.msi` and the portable Linux archive described
above. Each has a `.sha256` sidecar. GitHub Actions retains native installers
and smoke reports under the `linux-delivery` and `windows-delivery` artifacts.

Build prerequisites follow [Oracle's JDK 21 packaging documentation](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html).
The selected [Windows 2022 runner image](https://github.com/actions/runner-images/blob/main/images/windows/Windows2022-Readme.md)
provides WiX 3.14. Platform changes or new packaging errors should be resolved on
the respective runner, with installer smoke reports retained as evidence.
