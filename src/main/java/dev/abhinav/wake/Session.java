package dev.abhinav.wake;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;

final class Session {
    long pid;
    String mode;
    String trigger;
    String detail;
    Instant startedAt;
    Instant endsAt;
    long processStartMs;
    String processCommand;
    String processCommandLine;

    static Session readIfAlive() throws IOException {
        return readIfAlive(false);
    }

    static Session readIfAlive(boolean mayDeleteMalformed) throws IOException {
        if (!Files.exists(Wake.STATE_FILE)) return null;
        Properties props = new Properties();
        try (var in = Files.newBufferedReader(Wake.STATE_FILE)) {
            props.load(in);
        } catch (IOException e) {
            if (mayDeleteMalformed) try { Files.deleteIfExists(Wake.STATE_FILE); } catch (IOException ignored) {}
            return null;
        }
        Session s = new Session();
        try {
            s.pid = Long.parseLong(props.getProperty("pid", "0"));
            s.mode = props.getProperty("mode", "");
            s.trigger = props.getProperty("trigger", "");
            s.detail = props.getProperty("detail", "");
            s.startedAt = Instant.parse(props.getProperty("startedAt"));
            String e = props.getProperty("endsAt", "");
            s.endsAt = e.isEmpty() ? null : Instant.parse(e);
            s.processStartMs = Long.parseLong(props.getProperty("processStartMs"));
            s.processCommand = props.getProperty("processCommand", "");
            s.processCommandLine = props.getProperty("processCommandLine", "");
        } catch (Exception ex) {
            if (mayDeleteMalformed) try { Files.deleteIfExists(Wake.STATE_FILE); } catch (IOException ignored) {}
            return null;
        }
        if (!s.matchesLiveProcess()) {
            try { Files.deleteIfExists(Wake.STATE_FILE); } catch (IOException ignored) {}
            return null;
        }
        return s;
    }

    static FileLock acquireLock() throws IOException {
        Files.createDirectories(Wake.STATE_DIR);
        Path lockFile = Wake.STATE_DIR.resolve("wake.lock");
        FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = ch.tryLock();
            if (lock == null) {
                ch.close();
                throw new Wake.UsageError("another wake invocation is in progress; try again");
            }
            return lock;
        } catch (OverlappingFileLockException e) {
            closeQuietly(ch);
            throw new Wake.UsageError("another wake invocation is in progress; try again");
        } catch (IOException | RuntimeException e) {
            closeQuietly(ch);
            throw e;
        }
    }

    static void releaseLock(FileLock lock) {
        if (lock == null) return;
        FileChannel ch = lock.channel();
        try { lock.release(); } catch (IOException ignored) {}
        closeQuietly(ch);
    }

    void captureProcessIdentity() throws IOException {
        ProcessHandle handle = ProcessHandle.of(pid)
                .orElseThrow(() -> new IOException("process " + pid + " is not running"));
        ProcessHandle.Info info = handle.info();
        processStartMs = info.startInstant()
                .map(Instant::toEpochMilli)
                .orElseThrow(() -> new IOException("cannot read process start time for pid " + pid));
        processCommand = info.command()
                .orElseThrow(() -> new IOException("cannot read process command for pid " + pid));
        processCommandLine = info.commandLine().orElse(processCommand);
    }

    static void write(Session s) throws IOException {
        Files.createDirectories(Wake.STATE_DIR);
        Properties props = new Properties();
        props.setProperty("pid", String.valueOf(s.pid));
        props.setProperty("mode", s.mode);
        props.setProperty("trigger", s.trigger);
        props.setProperty("detail", s.detail);
        props.setProperty("startedAt", s.startedAt.toString());
        props.setProperty("endsAt", s.endsAt == null ? "" : s.endsAt.toString());
        props.setProperty("processStartMs", String.valueOf(s.processStartMs));
        props.setProperty("processCommand", s.processCommand == null ? "" : s.processCommand);
        props.setProperty("processCommandLine", s.processCommandLine == null ? "" : s.processCommandLine);
        Path tmp = Wake.STATE_DIR.resolve("session.properties.tmp");
        try (var out = Files.newBufferedWriter(tmp)) {
            props.store(out, "wake session");
        }
        try {
            Files.move(tmp, Wake.STATE_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    private boolean matchesLiveProcess() {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null || !handle.isAlive()) return false;
        ProcessHandle.Info info = handle.info();
        long liveStartMs = info.startInstant().map(Instant::toEpochMilli).orElse(-1L);
        String liveCommand = info.command().orElse("");
        String liveCommandLine = info.commandLine().orElse(liveCommand);
        return processStartMs == liveStartMs
                && processCommand.equals(liveCommand)
                && isExpectedCommand(liveCommand, liveCommandLine);
    }

    private static boolean isExpectedCommand(String command, String commandLine) {
        Path commandPath = Path.of(command == null ? "" : command);
        Path fileName = commandPath.getFileName();
        String base = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        String line = commandLine == null ? "" : commandLine.toLowerCase(Locale.ROOT);
        return base.equals("caffeinate")
                || base.equals("wake")
                || line.contains("wake.jar")
                || line.contains("dev.abhinav.wake.wake");
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch == null) return;
        try { ch.close(); } catch (IOException ignored) {}
    }
}
