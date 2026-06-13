package dev.abhinav.wake;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MacPlatform implements Platform {
    private static final String CAFFEINATE = "/usr/bin/caffeinate";
    private static final String LID_CLOSE_NOTE =
            "note: closing the lid still sleeps the mac unless you use --even-lid";
    private static final String PMSET = "/usr/bin/pmset";
    private static final String SUDO = "/usr/bin/sudo";
    private static final Pattern PCT = Pattern.compile("(\\d+)%");
    private static final Pattern SLEEP_DISABLED =
            Pattern.compile("(?im)^\\s*SleepDisabled\\s+(\\d+)\\s*$");
    private static final Set<String> EXPECTED_COMMANDS = Set.of("caffeinate", "wake");

    @Override
    public List<String> keepAwakeCommand(boolean noDisplay, Long timeoutSec, Long waitPid) {
        char caffeinateMode = noDisplay ? 'i' : 'd';
        List<String> cmd = new ArrayList<>(List.of(CAFFEINATE, "-" + caffeinateMode));
        if (timeoutSec != null) {
            cmd.add("-t");
            cmd.add(String.valueOf(timeoutSec));
        }
        if (waitPid != null) {
            cmd.add("-w");
            cmd.add(String.valueOf(waitPid));
        }
        return cmd;
    }

    @Override
    public Supervisor.BatteryStatus readBattery() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("/usr/bin/pmset", "-g", "batt");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        Matcher m = PCT.matcher(out);
        if (!m.find()) throw new IOException("cannot parse battery percentage from pmset");
        int percent = Integer.parseInt(m.group(1));
        String lower = out.toLowerCase(Locale.ROOT);
        boolean discharging = lower.contains("discharging") || lower.contains("battery power");
        boolean charging = lower.contains("; charging;") || lower.contains("ac power");
        return new Supervisor.BatteryStatus(percent, charging, discharging);
    }

    @Override
    public Long findAppPid(String name) throws IOException, InterruptedException {
        Long exact = Platform.firstAllowedPid(Platform.pgrep(List.of("/usr/bin/pgrep", "-i", "-x", name)));
        if (exact != null) return exact;
        return Platform.firstAllowedPid(Platform.pgrep(List.of("/usr/bin/pgrep", "-i", "-f", name)));
    }

    @Override
    public Set<String> expectedCommandBasenames() {
        return EXPECTED_COMMANDS;
    }

    @Override
    public boolean supportsEvenLid() {
        return true;
    }

    @Override
    public int readDisableSleep() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(PMSET, "-g");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) throw new IOException("pmset -g exited with status " + code);
        Matcher m = SLEEP_DISABLED.matcher(out);
        if (!m.find()) return 0;
        return parseDisableSleep(m.group(1));
    }

    @Override
    public boolean authenticateSudo() throws IOException, InterruptedException {
        return runForeground(List.of(SUDO, "-v")) == 0;
    }

    @Override
    public void setDisableSleepForeground(int value) throws IOException, InterruptedException {
        int code = runForeground(List.of(SUDO, PMSET, "-a", "disablesleep", disableSleepValue(value)));
        if (code != 0) throw new IOException("sudo pmset -a disablesleep exited with status " + code);
    }

    @Override
    public boolean setDisableSleepNonInteractive(int value) throws IOException, InterruptedException {
        return runQuiet(List.of(SUDO, "-n", PMSET, "-a", "disablesleep", disableSleepValue(value))) == 0;
    }

    @Override
    public boolean refreshSudoNonInteractive() throws IOException, InterruptedException {
        return runQuiet(List.of(SUDO, "-n", "-v")) == 0;
    }

    @Override
    public Optional<String> startNote() {
        return Optional.of(LID_CLOSE_NOTE);
    }

    private static int runForeground(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        return p.waitFor();
    }

    private static int runQuiet(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        return p.waitFor();
    }

    private static String disableSleepValue(int value) {
        if (value != 0 && value != 1) throw new IllegalArgumentException("disablesleep value must be 0 or 1");
        return String.valueOf(value);
    }

    private static int parseDisableSleep(String raw) throws IOException {
        try {
            int value = Integer.parseInt(raw);
            if (value == 0 || value == 1) return value;
        } catch (NumberFormatException ignored) {}
        throw new IOException("cannot parse SleepDisabled value from pmset");
    }
}
