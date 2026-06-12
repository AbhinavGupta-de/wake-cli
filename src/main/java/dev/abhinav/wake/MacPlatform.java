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
            "note: closing the lid still sleeps the mac — macOS forced sleep can't be blocked by user apps";
    private static final Pattern PCT = Pattern.compile("(\\d+)%");
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
    public Optional<String> startNote() {
        return Optional.of(LID_CLOSE_NOTE);
    }
}
