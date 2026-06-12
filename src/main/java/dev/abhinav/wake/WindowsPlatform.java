package dev.abhinav.wake;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class WindowsPlatform implements Platform {
    private static final String POWERSHELL_MISSING =
            "powershell not found on PATH; wake requires Windows PowerShell on Windows";
    private static final Set<String> EXPECTED_COMMANDS =
            Set.of("powershell.exe", "powershell", "wake.exe", "wake");

    @Override
    public List<String> keepAwakeCommand(boolean noDisplay, Long timeoutSec, Long waitPid) {
        if (timeoutSec != null && waitPid != null) {
            throw new IllegalArgumentException("timeoutSec and waitPid are mutually exclusive");
        }

        String powershell = resolvePowerShell();
        String flags = noDisplay ? "0x80000001" : "0x80000003";
        String typeDefinition = """
                using System;
                using System.Runtime.InteropServices;
                namespace Wake {
                    public static class Native {
                        [DllImport("kernel32.dll")]
                        public static extern uint SetThreadExecutionState(uint esFlags);
                    }
                }
                """;

        String block;
        if (waitPid != null) {
            block = "Wait-Process -Id " + digits(waitPid, "waitPid") + " -ErrorAction SilentlyContinue";
        } else if (timeoutSec != null) {
            block = "Start-Sleep -Seconds " + digits(timeoutSec, "timeoutSec");
        } else {
            block = "while ($true) { Start-Sleep -Seconds 3600 }";
        }

        String script = """
                Add-Type -TypeDefinition @'
                %s
                '@
                $r = [Wake.Native]::SetThreadExecutionState([uint32]%s)
                if ($r -eq 0) { exit 1 }
                %s
                """.formatted(typeDefinition, flags, block);
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        return List.of(powershell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
    }

    @Override
    public Supervisor.BatteryStatus readBattery() throws IOException, InterruptedException {
        String powershell = resolvePowerShell();
        String script = "Get-CimInstance Win32_Battery "
                + "| Select-Object -Property EstimatedChargeRemaining,BatteryStatus "
                + "| ConvertTo-Csv -NoTypeInformation";
        String out = runCapture(List.of(powershell, "-NoProfile", "-NonInteractive", "-Command", script));

        List<List<String>> rows = parseCsv(out);
        int count = 0;
        int percentSum = 0;
        boolean anyCharging = false;
        boolean anyDischarging = false;

        for (List<String> row : rows) {
            if (row.size() < 2 || "EstimatedChargeRemaining".equalsIgnoreCase(row.get(0))) continue;
            Integer percent = parseIntOrNull(row.get(0));
            Integer batteryStatus = parseIntOrNull(row.get(1));
            if (percent == null || batteryStatus == null) continue;

            // Win32_Battery.BatteryStatus values:
            // 1=Discharging, 2=On AC/not charging, 3=Fully Charged,
            // 4=Low(discharge), 5=Critical(discharge), 6/7/8/9=Charging variants,
            // 10=Undefined, 11=Partially Charged.
            boolean rowCharging = batteryStatus >= 6 && batteryStatus <= 9;
            boolean rowDischarging = batteryStatus == 1 || batteryStatus == 4 || batteryStatus == 5;

            // Win32_Battery exposes no energy capacity, so multiple batteries are averaged equally.
            percentSum += clampPercent(percent);
            count++;
            if (rowCharging) anyCharging = true;
            if (rowDischarging) anyDischarging = true;
        }

        if (count == 0) throw new IOException("no usable battery found");
        boolean charging = anyCharging;
        boolean discharging = !charging && anyDischarging;
        String neutralState = (!charging && !discharging) ? "not charging or discharging" : null;
        int percent = Math.round((float) percentSum / count);
        return new Supervisor.BatteryStatus(percent, charging, discharging, neutralState);
    }

    @Override
    public Long findAppPid(String name) throws IOException, InterruptedException {
        if (name.contains("\"")) throw new Wake.UsageError("app/process name cannot contain double quotes");

        for (String imageName : imageNameCandidates(name)) {
            String out = runCapture(List.of(
                    "tasklist",
                    "/FO", "CSV",
                    "/NH",
                    "/FI", "IMAGENAME eq " + imageName));
            List<Long> pids = new ArrayList<>();
            for (List<String> row : parseCsv(out)) {
                if (row.size() < 2) continue;
                try {
                    pids.add(Long.parseLong(row.get(1).trim()));
                } catch (NumberFormatException ignored) {}
            }
            Long allowed = Platform.firstAllowedPid(pids);
            if (allowed != null) return allowed;
        }
        return null;
    }

    @Override
    public Set<String> expectedCommandBasenames() {
        return EXPECTED_COMMANDS;
    }

    @Override
    public boolean supportsInteractive() {
        return false;
    }

    @Override
    public File devNull() {
        return new File("NUL");
    }

    private static String resolvePowerShell() {
        try {
            return Platform.resolveOnPath("powershell.exe", POWERSHELL_MISSING);
        } catch (IllegalStateException e) {
            return Platform.resolveOnPath("powershell", POWERSHELL_MISSING);
        }
    }

    private static List<String> imageNameCandidates(String name) {
        String trimmed = name.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".exe")) return List.of(trimmed);
        return List.of(trimmed + ".exe", trimmed);
    }

    private static String runCapture(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) throw new IOException(cmd.get(0) + " exited with status " + code);
        return out;
    }

    private static String digits(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
        String formatted = Long.toString(value);
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException(name + " must be digits only");
        }
        return formatted;
    }

    private static Integer parseIntOrNull(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static List<List<String>> parseCsv(String input) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean sawAny = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            sawAny = true;
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                if (!isBlankRow(row)) rows.add(row);
                row = new ArrayList<>();
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
            } else {
                field.append(c);
            }
        }

        if (inQuotes) throw new IOException("unterminated CSV quote");
        if (sawAny || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (!isBlankRow(row)) rows.add(row);
        }
        return rows;
    }

    private static boolean isBlankRow(List<String> row) {
        for (String field : row) {
            if (!field.isBlank()) return false;
        }
        return true;
    }
}
