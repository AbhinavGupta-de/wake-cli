# wake

A command-line awake controller for macOS and Linux.

## Features

- Interactive picker for starting, inspecting, and stopping sessions.
- Indefinite sessions with `wake forever`.
- Timed durations such as `wake 1h`, `wake 30m`, and `wake 1h30m`.
- Clock-based sessions with `wake --until HH:MM`.
- Battery-aware sessions with `wake --until-charge N`.
- Process-bound sessions with `wake --while-pid PID`.
- App-bound sessions with `wake --while-app NAME`.
- Display sleep control with `--no-display`.
- `wake status` and `wake stop` for active sessions.

## Demo

Running `wake` in an interactive terminal opens the picker:

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

## Supported Platforms

- macOS: uses `caffeinate` for sleep assertions and `pmset` for battery state.
- Linux: uses `systemd-inhibit`; requires systemd >= 190. Battery sessions read `/sys/class/power_supply`.

On Linux, display-sleep prevention is limited to idle/sleep inhibitors. Desktop-environment display blanking controls such as X11 `xset` are out of scope.
Linux sessions inhibit lid-switch sleep, including `--no-display` sessions.

## Install

Prerequisites:

- macOS, or Linux with systemd >= 190.
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

The build writes the native binary to `target/wake`.

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
wake status
wake stop
wake help
```

Example output from the current binary:

```text
$ wake --version
wake 0.1.0

$ wake status
wake: no active session
```

## Requirements

`wake` uses platform tools instead of a background daemon:

- macOS: `caffeinate`, `pmset`, `stty`, and `pgrep`.
- Linux: `systemd-inhibit`, GNU coreutils `sleep` and `tail` (not BusyBox), `stty`, `pgrep`, and sysfs power-supply files for battery triggers.

## License

MIT — see [LICENSE](LICENSE)
