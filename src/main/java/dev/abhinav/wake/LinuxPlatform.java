package dev.abhinav.wake;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;

final class LinuxPlatform implements Platform {
    private static final Path POWER_SUPPLY = Path.of("/sys/class/power_supply");
    private static final Set<String> EXPECTED_COMMANDS = Set.of("systemd-inhibit", "sleep", "tail", "wake");

    @Override
    public List<String> keepAwakeCommand(boolean noDisplay, Long timeoutSec, Long waitPid) {
        String systemdInhibit = Platform.resolveOnPath("systemd-inhibit",
                "systemd-inhibit not found on PATH; wake requires systemd on Linux");
        String what = noDisplay ? "sleep:handle-lid-switch" : "idle:sleep:handle-lid-switch";
        List<String> cmd = new ArrayList<>(List.of(
                systemdInhibit,
                "--what=" + what,
                "--who=wake",
                "--why=wake CLI"));
        if (waitPid != null) {
            cmd.add("tail");
            cmd.add("--pid=" + waitPid);
            cmd.add("-f");
            cmd.add("/dev/null");
        } else {
            cmd.add("sleep");
            cmd.add(timeoutSec == null ? "infinity" : String.valueOf(timeoutSec));
        }
        return cmd;
    }

    @Override
    public Supervisor.BatteryStatus readBattery() throws IOException {
        if (!Files.isDirectory(POWER_SUPPLY)) throw new IOException("no usable battery found");

        List<Battery> batteries = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(POWER_SUPPLY)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                Battery battery = readBattery(dir);
                if (battery != null) batteries.add(battery);
            }
        }
        if (batteries.isEmpty()) throw new IOException("no usable battery found");

        boolean charging = false;
        boolean anyDischarging = false;
        long nowSum = 0;
        long fullSum = 0;
        int capacitySum = 0;
        for (Battery battery : batteries) {
            String status = battery.status.toLowerCase(Locale.ROOT);
            if ("charging".equals(status)) charging = true;
            if ("discharging".equals(status)) anyDischarging = true;
            if (battery.measurement != null) {
                nowSum += battery.measurement.now;
                fullSum += battery.measurement.full;
            }
            if (battery.capacity != null) capacitySum += battery.capacity;
        }
        boolean discharging = !charging && anyDischarging;
        int percent = fullSum > 0
                ? Math.round((float) (100.0 * nowSum / fullSum))
                : Math.round((float) capacitySum / batteries.size());
        percent = clampPercent(percent);
        String neutralState = (!charging && !discharging) ? "not charging or discharging" : null;
        return new Supervisor.BatteryStatus(percent, charging, discharging, neutralState);
    }

    @Override
    public Long findAppPid(String name) throws IOException, InterruptedException {
        String pattern = caseInsensitiveEre(name);
        Long exact = Platform.firstAllowedPid(Platform.pgrep(List.of("pgrep", "-x", pattern)));
        if (exact != null) return exact;
        if (name.length() > 15) {
            exact = Platform.firstAllowedPid(Platform.pgrep(List.of("pgrep", "-x", caseInsensitiveEre(name.substring(0, 15)))));
            if (exact != null) return exact;
        }
        return Platform.firstAllowedPid(Platform.pgrep(List.of("pgrep", "-f", pattern)));
    }

    @Override
    public Set<String> expectedCommandBasenames() {
        return EXPECTED_COMMANDS;
    }

    private static String readTrimmed(Path path) throws IOException {
        return Files.readString(path).trim();
    }

    private static Battery readBattery(Path dir) {
        try {
            String type = readTrimmed(dir.resolve("type"));
            if (!"Battery".equals(type)) return null;
            String status = readTrimmed(dir.resolve("status"));
            Measurement measurement = readMeasurement(dir, "energy_now", "energy_full");
            if (measurement == null) measurement = readMeasurement(dir, "charge_now", "charge_full");
            OptionalLong capacityValue = readCapacity(dir);
            Integer capacity = capacityValue.isPresent() ? (int) capacityValue.getAsLong() : null;
            if (measurement == null && capacity == null) return null;
            return new Battery(capacity, measurement, status);
        } catch (IOException e) {
            return null;
        }
    }

    private static OptionalLong readCapacity(Path dir) {
        try {
            return OptionalLong.of(parseCapacity(readTrimmed(dir.resolve("capacity")), dir));
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    private static Measurement readMeasurement(Path dir, String nowName, String fullName) {
        try {
            long now = parsePositiveLong(readTrimmed(dir.resolve(nowName)), dir.resolve(nowName));
            long full = parsePositiveLong(readTrimmed(dir.resolve(fullName)), dir.resolve(fullName));
            if (full <= 0 || now < 0) return null;
            return new Measurement(now, full);
        } catch (IOException e) {
            return null;
        }
    }

    private static int parseCapacity(String raw, Path dir) throws IOException {
        try {
            return clampPercent(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            throw new IOException("cannot parse battery capacity from " + dir.resolve("capacity"));
        }
    }

    private static long parsePositiveLong(String raw, Path path) throws IOException {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IOException("cannot parse battery value from " + path);
        }
    }

    static String caseInsensitiveEre(String name) {
        StringBuilder pattern = new StringBuilder(name.length() * 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z') {
                pattern.append('[').append(c).append((char) (c - 32)).append(']');
            } else if (c >= 'A' && c <= 'Z') {
                pattern.append('[').append((char) (c + 32)).append(c).append(']');
            } else if (isEreMetacharacter(c)) {
                pattern.append('\\').append(c);
            } else {
                pattern.append(c);
            }
        }
        return pattern.toString();
    }

    private static boolean isEreMetacharacter(char c) {
        return switch (c) {
            case '.', '[', ']', '\\', '^', '$', '*', '+', '?', '{', '}', '|', '(', ')' -> true;
            default -> false;
        };
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private record Battery(Integer capacity, Measurement measurement, String status) {}
    private record Measurement(long now, long full) {}
}
