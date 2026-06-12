package dev.abhinav.wake;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Wake {

    static final Path STATE_DIR = Path.of(System.getProperty("user.home"), ".local/state/wake");
    static final Path STATE_FILE = STATE_DIR.resolve("session.properties");
    static final String VERSION = "0.2.0";
    static final Platform PLATFORM = Platform.detect();

    public static void main(String[] args) {
        try {
            dispatch(args);
        } catch (UsageError e) {
            System.err.println("wake: " + e.getMessage());
            System.err.println("try 'wake --help'");
            System.exit(2);
        } catch (Throwable e) {
            System.err.println("wake: " + e.getMessage());
            System.exit(1);
        }
    }

    static void dispatch(String[] args) throws Exception {
        if (args.length > 0) {
            switch (args[0]) {
                case "-h", "--help", "help" -> { printHelp(); return; }
                case "-v", "--version", "version" -> { System.out.println("wake " + VERSION); return; }
                case "status" -> { status(); return; }
                case "stop" -> { stop(); return; }
                case "forever", "indefinite" -> { startForever(args); return; }
                case "__supervise_charge__" -> { Supervisor.runCharge(args); return; }
            }
            start(args);
            return;
        }
        if (System.console() != null) {
            Interactive.run();
        } else {
            start(new String[0]);
        }
    }

    static void start(String[] args) throws Exception {
        Long timeoutSec = null;
        Integer chargeTarget = null;
        Long waitPid = null;
        String triggerDetail = "indefinite";
        String trigger = "indefinite";
        String triggerFlag = null;
        boolean noDisplay = false;

        int i = 0;
        while (i < args.length) {
            String a = args[i];
            switch (a) {
                case "--no-display" -> { noDisplay = true; i++; }
                case "-t", "--for" -> {
                    if (i + 1 >= args.length) throw new UsageError("missing value for " + a);
                    triggerFlag = claimTrigger(triggerFlag, a);
                    timeoutSec = Durations.parse(args[++i]);
                    triggerDetail = args[i];
                    trigger = "timed";
                    i++;
                }
                case "--until" -> {
                    if (i + 1 >= args.length) throw new UsageError("missing value for --until");
                    triggerFlag = claimTrigger(triggerFlag, a);
                    String hhmm = args[++i];
                    timeoutSec = secondsUntil(hhmm);
                    triggerDetail = "until " + hhmm;
                    trigger = "until-time";
                    i++;
                }
                case "--until-charge" -> {
                    if (i + 1 >= args.length) throw new UsageError("missing value for --until-charge");
                    triggerFlag = claimTrigger(triggerFlag, a);
                    chargeTarget = parseInt(args[++i], "--until-charge");
                    if (chargeTarget < 1 || chargeTarget > 100) throw new UsageError("--until-charge must be 1-100");
                    triggerDetail = chargeTarget + "%";
                    trigger = "until-charge";
                    i++;
                }
                case "--while-pid" -> {
                    if (i + 1 >= args.length) throw new UsageError("missing value for --while-pid");
                    triggerFlag = claimTrigger(triggerFlag, a);
                    waitPid = Long.parseLong(args[++i]);
                    if (!isAlive(waitPid)) throw new UsageError("pid " + waitPid + " is not running");
                    triggerDetail = "pid " + waitPid;
                    trigger = "while-pid";
                    i++;
                }
                case "--while-app" -> {
                    if (i + 1 >= args.length) throw new UsageError("missing value for --while-app");
                    triggerFlag = claimTrigger(triggerFlag, a);
                    String appName = args[++i];
                    waitPid = PLATFORM.findAppPid(appName);
                    if (waitPid == null) throw new UsageError("no running process matching '" + appName + "'");
                    triggerDetail = "app '" + appName + "' (pid " + waitPid + ")";
                    trigger = "while-app";
                    i++;
                }
                default -> {
                    if (a.startsWith("-")) throw new UsageError("unknown flag: " + a);
                    triggerFlag = claimTrigger(triggerFlag, "duration");
                    timeoutSec = Durations.parse(a);
                    triggerDetail = a;
                    trigger = "timed";
                    i++;
                }
            }
        }

        FileLock lock = Session.acquireLock();
        try {
            Session existing = Session.readIfAlive(true);
            if (existing != null) {
                System.err.println("wake: session already active (pid " + existing.pid + ", " + existing.trigger + " " + existing.detail + ")");
                System.err.println("run 'wake stop' first");
                System.exit(1);
            }

            String mode = noDisplay ? "system-only" : "display+system";

            if (chargeTarget != null) {
                startSupervisor(chargeTarget, mode, noDisplay);
                return;
            }

            List<String> cmd = PLATFORM.keepAwakeCommand(noDisplay, timeoutSec, waitPid);

            Session s = new Session();
            s.mode = mode;
            s.trigger = trigger;
            s.detail = triggerDetail;
            s.startedAt = Instant.now();
            s.endsAt = timeoutSec == null ? null : s.startedAt.plusSeconds(timeoutSec);

            Process cafProc = null;
            try {
                cafProc = spawnDetachedProcess(cmd);
                s.pid = cafProc.pid();
                s.captureProcessIdentity();
                Session.write(s);
            } catch (Exception t) {
                if (cafProc != null) destroyProcess(cafProc);
                throw t;
            }
            printStartConfirmation(s);
        } finally {
            Session.releaseLock(lock);
        }
    }

    static void startSupervisor(int chargeTarget, String mode, boolean noDisplay) throws Exception {
        Supervisor.BatteryStatus status = Supervisor.readBatteryStatus();
        Supervisor.ChargePlan plan = Supervisor.planCharge(chargeTarget, status);
        if (plan.alreadyMet) {
            System.out.printf("wake: battery already at %d%%; target %d%% reached%n", status.percent, chargeTarget);
            return;
        }

        List<String> cmd = new ArrayList<>(selfCommand());
        cmd.add("__supervise_charge__");
        cmd.add(String.valueOf(chargeTarget));
        cmd.add(String.valueOf(noDisplay));
        cmd.add(mode);
        spawnDetached(cmd);

        for (int i = 0; i < 30; i++) {
            if (Files.exists(STATE_FILE)) break;
            Thread.sleep(100);
        }
        Session s = Session.readIfAlive();
        if (s == null) {
            System.err.println("wake: supervisor failed to start");
            System.exit(1);
        }
        printStartConfirmation(s);
    }

    static void status() throws Exception {
        Session s = Session.readIfAlive();
        if (s == null) {
            System.out.println("wake: no active session");
            return;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        long elapsedSec = java.time.Duration.between(s.startedAt, Instant.now()).getSeconds();
        String remaining = s.endsAt == null
                ? "—"
                : prettyDuration(Math.max(0, java.time.Duration.between(Instant.now(), s.endsAt).getSeconds()));
        System.out.printf("wake: session active (pid %d)%n", s.pid);
        System.out.printf("  mode      : %s%n", s.mode);
        System.out.printf("  trigger   : %s (%s)%n", s.trigger, s.detail);
        System.out.printf("  started   : %s (%s ago)%n", fmt.format(s.startedAt), prettyDuration(elapsedSec));
        System.out.printf("  remaining : %s%n", remaining);
    }

    static void stop() throws Exception {
        Session s = Session.readIfAlive();
        if (s == null) {
            System.out.println("wake: no active session");
            try { Files.deleteIfExists(STATE_FILE); } catch (IOException ignored) {}
            return;
        }
        try {
            new ProcessBuilder("/bin/kill", "-TERM", String.valueOf(s.pid))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
        } catch (Exception ignored) {}
        Thread.sleep(200);
        Files.deleteIfExists(STATE_FILE);
        System.out.printf("wake: stopped (pid %d, %s)%n", s.pid, s.trigger);
    }

    static void printStartConfirmation(Session s) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        String startsAt = fmt.format(s.startedAt);
        String endsAt = s.endsAt == null ? "—" : fmt.format(s.endsAt);
        System.out.printf("wake: session active (pid %d)%n", s.pid);
        System.out.printf("  mode    : %s%n", s.mode);
        System.out.printf("  trigger : %s (%s)%n", s.trigger, s.detail);
        System.out.printf("  started : %s%n", startsAt);
        System.out.printf("  ends    : %s%n", endsAt);
    }

    static long spawnDetached(List<String> cmd) throws IOException {
        return spawnDetachedProcess(cmd).pid();
    }

    static Process spawnDetachedProcess(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        File devnull = new File("/dev/null");
        pb.redirectInput(ProcessBuilder.Redirect.from(devnull));
        pb.redirectOutput(ProcessBuilder.Redirect.to(devnull));
        pb.redirectError(ProcessBuilder.Redirect.to(devnull));
        return pb.start();
    }

    static List<String> selfCommand() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String cmd = info.command().orElse(null);
        if (cmd != null && !cmd.endsWith("/java") && !cmd.equals("java")) {
            return new ArrayList<>(List.of(cmd));
        }
        String jarPath;
        try {
            jarPath = new File(Wake.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("can't determine jar path", e);
        }
        return new ArrayList<>(List.of(cmd != null ? cmd : "java", "-jar", jarPath));
    }

    static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static long secondsUntil(String hhmm) {
        String[] parts = hhmm.split(":");
        if (parts.length != 2) throw new UsageError("--until expects HH:MM, got '" + hhmm + "'");
        int h = parseInt(parts[0], "--until hour");
        int m = parseInt(parts[1], "--until minute");
        if (h < 0 || h > 23 || m < 0 || m > 59) throw new UsageError("--until: invalid time '" + hhmm + "'");
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate date = now.toLocalDate();
        LocalTime time = LocalTime.of(h, m);
        ZonedDateTime target = ZonedDateTime.of(date, time, zone);
        if (!target.isAfter(now)) target = target.plusDays(1);
        return java.time.Duration.between(now, target).getSeconds();
    }

    static void startForever(String[] args) throws Exception {
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        for (String arg : rest) {
            if (!"--no-display".equals(arg)) throw new UsageError("forever only accepts --no-display");
        }
        start(rest);
    }

    static String claimTrigger(String current, String next) {
        if (current != null) throw new UsageError("conflicting triggers: " + current + " and " + next);
        return next;
    }

    static void destroyProcess(Process p) {
        p.destroy();
        try {
            if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    static int parseInt(String s, String name) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new UsageError(name + ": not an integer: '" + s + "'");
        }
    }

    static String prettyDuration(long sec) {
        if (sec < 0) sec = 0;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format("%dh%02dm", h, m);
        if (m > 0) return String.format("%dm%02ds", m, s);
        return s + "s";
    }

    static void printHelp() {
        System.out.println("""
                wake — keep your machine awake from the CLI

                platforms:
                  macOS uses caffeinate; Linux uses systemd-inhibit and requires systemd

                interactive:
                  wake                       open the picker (when run in a terminal)

                direct:
                  wake forever               stay awake indefinitely (no menu)
                  wake <duration>            e.g. wake 1h, wake 30m, wake 1h30m, wake 90s
                  wake -t <duration>         same as above with explicit flag
                  wake --until HH:MM         stay awake until clock time
                  wake --until-charge N      stay awake until battery hits N% (1-100)
                  wake --while-pid PID       stay awake while PID is running
                  wake --while-app NAME      stay awake while named app/process is running
                  wake --no-display          prevent system sleep only, allow display sleep
                  wake status                show current session
                  wake stop                  end current session
                  wake version               print version
                  wake help                  this message

                duration syntax:
                  90s, 5m, 1h, 1h30m, 2h45m30s, 1d, or plain seconds (3600)
                  maximum: 30d

                state file:
                  ~/.local/state/wake/session.properties
                """);
    }

    static final class UsageError extends RuntimeException {
        UsageError(String m) { super(m); }
    }
}
