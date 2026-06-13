package dev.abhinav.wake;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

interface Platform {
    /** Command argv to keep the machine awake. timeoutSec/waitPid nullable. */
    List<String> keepAwakeCommand(boolean noDisplay, Long timeoutSec, Long waitPid);

    /** Battery percent + charging/discharging state. Throws if no battery. */
    Supervisor.BatteryStatus readBattery() throws IOException, InterruptedException;

    /** Find PID of a running app/process by name, or null. Must exclude self + parent. */
    Long findAppPid(String name) throws IOException, InterruptedException;

    /** Basenames considered ours for the Session identity check. */
    Set<String> expectedCommandBasenames();

    /** Whether no-args console launch can use the interactive picker. */
    default boolean supportsInteractive() {
        return true;
    }

    /** Whether this platform can keep running through macOS lid-close sleep. */
    default boolean supportsEvenLid() {
        return false;
    }

    /** Current macOS SleepDisabled value. Missing/unset is reported as 0. */
    default int readDisableSleep() throws IOException, InterruptedException {
        throw new UnsupportedOperationException("--even-lid is not supported on this platform");
    }

    /** Foreground sudo validation for commands that may prompt the user. */
    default boolean authenticateSudo() throws IOException, InterruptedException {
        throw new UnsupportedOperationException("--even-lid is not supported on this platform");
    }

    /** Foreground SleepDisabled setter. May prompt via sudo. */
    default void setDisableSleepForeground(int value) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("--even-lid is not supported on this platform");
    }

    /** Non-interactive SleepDisabled setter for detached teardown. */
    default boolean setDisableSleepNonInteractive(int value) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("--even-lid is not supported on this platform");
    }

    /** Non-interactive sudo timestamp refresh for long detached sessions. */
    default boolean refreshSudoNonInteractive() throws IOException, InterruptedException {
        throw new UnsupportedOperationException("--even-lid is not supported on this platform");
    }

    /** Note to print after the start confirmation for this invocation, if any. */
    default Optional<String> startNote() {
        return Optional.empty();
    }

    /** Platform null input device for detached children. */
    default File devNull() {
        return new File("/dev/null");
    }

    static Platform detect() {
        String osName = System.getProperty("os.name", "unknown");
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac") || lower.contains("darwin")) return new MacPlatform();
        if (lower.contains("linux")) return new LinuxPlatform();
        if (lower.contains("windows")) return new WindowsPlatform();
        System.err.println("wake: unsupported platform: " + osName);
        System.exit(1);
        throw new IllegalStateException("unsupported platform: " + osName);
    }

    static List<Long> pgrep(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        List<Long> pids = new ArrayList<>();
        if (out.isEmpty()) return pids;
        for (String line : out.split("\n")) {
            try {
                if (!line.isBlank()) pids.add(Long.parseLong(line.trim()));
            } catch (NumberFormatException ignored) {}
        }
        return pids;
    }

    static Long firstAllowedPid(List<Long> pids) {
        for (long pid : pids) {
            if (isAllowedAppPid(pid)) return pid;
        }
        return null;
    }

    static boolean isAllowedAppPid(long pid) {
        if (selfAndParentPids().contains(pid)) return false;
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null || !handle.isAlive()) return false;
        ProcessHandle.Info info = handle.info();
        String command = info.command().orElse("");
        String commandLine = info.commandLine().orElse(command);
        String haystack = (command + " " + commandLine).toLowerCase(Locale.ROOT);
        return !haystack.contains("wake") && !haystack.contains("java");
    }

    static String resolveOnPath(String executable, String missingMessage) {
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir == null || dir.isBlank()) continue;
                try {
                    Path candidate = Path.of(dir, executable);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toString();
                    }
                } catch (InvalidPathException ignored) {}
            }
        }
        throw new IllegalStateException(missingMessage);
    }

    private static Set<Long> selfAndParentPids() {
        ProcessHandle handle = ProcessHandle.current();
        var parent = handle.parent();
        if (parent.isEmpty()) return Set.of(handle.pid());
        Set<Long> pids = Set.of(handle.pid(), parent.get().pid());
        return pids;
    }
}
