# wake

A command-line awake controller for macOS, Linux, and Windows.

## Features

- Interactive picker for starting, inspecting, and stopping sessions on macOS/Linux.
- Indefinite sessions with `wake forever`.
- Timed durations such as `wake 1h`, `wake 30m`, and `wake 1h30m`.
- Clock-based sessions with `wake --until HH:MM`.
- Battery-aware sessions with `wake --until-charge N`.
- Process-bound sessions with `wake --while-pid PID`.
- App-bound sessions with `wake --while-app NAME`.
- Display sleep control with `--no-display`.
- macOS lid-closed sessions with `--even-lid`.
- `wake status` and `wake stop` for active sessions.

## Demo

On macOS/Linux, running `wake` in an interactive terminal opens the picker:

```text
  ☕ wake  — keep your machine awake

  ○ no active session

  ▸ Indefinite   stay awake forever
    For a duration…     1h, 30m, 1h30m, 90s
    Until clock time…   stay awake until HH:MM
    Until battery %…    until charge hits N%
    While app running…  watch a running app/process
    While PID alive…    watch a specific process id
    ─────────────────────────
    Quit

  ↑↓/jk navigate · ↵ select · d display-sleep [off] · q quit
```

On macOS, choosing **Indefinite** asks `Keep awake with the lid closed too? (needs sudo) [y/N]`. The prompt defaults to no; answering `y` starts the indefinite session with `--even-lid` (which prompts for sudo on the normal terminal after the picker exits), while anything else starts a normal indefinite session. On Linux and Windows the prompt does not appear and Indefinite behaves as before.

## Supported Platforms

### macOS

macOS uses `caffeinate` for sleep assertions and `pmset` for battery state.

#### Limitations

`wake` blocks idle sleep on macOS. Closing the lid or choosing Sleep from the Apple menu is forced sleep and still sleeps the Mac unless you use `--even-lid`, which uses the root clamshell setting `pmset -a disablesleep 1`.

`wake --even-lid` is macOS-only and requires sudo. It is intended for long background work where you lock the screen, close the lid, and need agents or other processes to keep running. It preserves the previous `SleepDisabled` value: if clamshell mode was already enabled before `wake`, `wake stop` restores it to enabled; otherwise it restores normal sleep.

Because `SleepDisabled` is a global setting and is not tied to a process, `wake --even-lid` runs a detached supervisor that owns teardown. The supervisor keeps sudo fresh, restores the prior `SleepDisabled` value on exit, and leaves recovery state behind if non-interactive restore cannot be verified. Every foreground `wake start`, `wake status`, and `wake stop` checks for a crashed lid supervisor and prompts with sudo if needed to restore the previous setting.

Caution: running closed-lid on battery, especially without an external display or cooling, can run hot and drain quickly.

This differs from Linux, where systemd can inhibit lid-switch sleep.

### Linux

Linux uses `systemd-inhibit`; requires systemd >= 190. Battery sessions read `/sys/class/power_supply`.

On Linux, display-sleep prevention is limited to idle/sleep inhibitors. Desktop-environment display blanking controls such as X11 `xset` are out of scope.
Lid-switch inhibition is controlled by systemd-logind policy. Polkit grants it only in privileged or local desktop sessions, so `wake` automatically degrades to idle/sleep inhibition when lid-switch locks are unavailable and prints a note in the start confirmation.

### Windows

Windows uses Windows PowerShell to call `SetThreadExecutionState`, preventing system and display sleep while the child PowerShell process is alive.

On Windows, `--no-display` omits `ES_DISPLAY_REQUIRED`, so the system stays awake while the display may sleep. The interactive picker is not available on Windows yet; running `wake` starts an indefinite session.

## Install

Prerequisites:

- macOS, Linux with systemd >= 190, or Windows with Windows PowerShell.
- GraalVM JDK 21 or newer. The current native build uses GraalVM JDK 25.
- Maven.

Build from source:

```sh
git clone git@github.com:AbhinavGupta-de/wake-cli.git
cd wake-cli
```

macOS:

```sh
JAVA_HOME=<graalvm-home> bash bin/build.sh
```

Linux:

```sh
export JAVA_HOME=<graalvm-home>
bash bin/build.sh
```

Windows:

```powershell
$env:JAVA_HOME = "<graalvm-home>"
mvn -q -DskipTests package
& "$env:JAVA_HOME\bin\native-image" --no-fallback -O2 -jar target\wake.jar target\wake.exe
```

`bin/build.sh` is for macOS/Linux. Windows users can build with the commands above or download a prebuilt release binary.

The native build writes `target/wake` on macOS/Linux or `target\wake.exe` on Windows.

Add it to your shell:

```sh
alias wake="$(pwd)/target/wake"
```

Or link it into your PATH:

```sh
mkdir -p ~/.local/bin
ln -s "$(pwd)/target/wake" ~/.local/bin/wake
```

## Usage

```sh
wake
wake 1h
wake forever
wake --until 23:00
wake --until-charge 80
wake --while-app "Final Cut Pro"
wake --even-lid
wake --even-lid 2h
wake status
wake stop
wake help
```

Example output from the current binary:

```text
$ wake --version
wake 0.2.0

$ wake status
wake: no active session
```

## Requirements

`wake` uses platform tools instead of a background daemon:

- macOS: `caffeinate`, `pmset`, `stty`, and `pgrep`.
- Linux: `systemd-inhibit`, GNU coreutils `sleep` and `tail` (not BusyBox), `stty`, `pgrep`, and sysfs power-supply files for battery triggers.
- Windows: Windows PowerShell, `tasklist`, and CIM `Win32_Battery` for battery triggers.

Session state is stored under `~/.local/state/wake/session.properties` on every platform, including Windows.
Set `WAKE_STATE_DIR` to override the state directory; `wake` writes `session.properties` inside that directory.

## License

MIT — see [LICENSE](LICENSE)
