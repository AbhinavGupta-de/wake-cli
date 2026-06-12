package dev.abhinav.wake;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Supervisor {
    private static final Pattern PCT = Pattern.compile("(\\d+)%");
    private static final long POLL_INTERVAL_MS = 30_000;

    static void runCharge(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalStateException("supervisor: bad args");
        int target = Integer.parseInt(args[1]);
        char modeChar = args[2].charAt(0);
        String modeName = args[3];

        BatteryStatus initial;
        ChargePlan plan;
        try {
            initial = readBatteryStatus();
            plan = planCharge(target, initial);
        } catch (Exception e) {
            System.err.println("wake supervisor: " + e.getMessage());
            return;
        }
        if (plan.alreadyMet) return;
        boolean chargingUp = plan.chargingUp;

        AtomicReference<Process> cafProcRef = new AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = cafProcRef.get();
            try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
            try { Files.deleteIfExists(Wake.STATE_FILE); } catch (Throwable ignored) {}
        }));

        ProcessBuilder caf = new ProcessBuilder(Wake.CAFFEINATE, "-" + modeChar);
        File devnull = new File("/dev/null");
        caf.redirectInput(ProcessBuilder.Redirect.from(devnull));
        caf.redirectOutput(ProcessBuilder.Redirect.to(devnull));
        caf.redirectError(ProcessBuilder.Redirect.to(devnull));
        Process cafProc = caf.start();
        cafProcRef.set(cafProc);

        Session s = new Session();
        s.pid = ProcessHandle.current().pid();
        s.mode = modeName;
        s.trigger = "until-charge";
        s.detail = target + "% (was " + initial.percent + "%, " + (chargingUp ? "charging up" : "discharging down") + ")";
        s.startedAt = Instant.now();
        s.endsAt = null;
        try {
            s.captureProcessIdentity();
            Session.write(s);
        } catch (Exception t) {
            Wake.destroyProcess(cafProc);
            throw t;
        }

        while (true) {
            Thread.sleep(POLL_INTERVAL_MS);
            if (!cafProc.isAlive()) break;
            BatteryStatus status;
            try {
                status = readBatteryStatus();
            } catch (Exception e) {
                continue;
            }
            if (chargingUp ? status.percent >= target : status.percent <= target) break;
        }
        cafProc.destroy();
        cafProc.waitFor();
        try { Files.deleteIfExists(Wake.STATE_FILE); } catch (Throwable ignored) {}
    }

    static int readBatteryPct() throws IOException, InterruptedException {
        return readBatteryStatus().percent;
    }

    static BatteryStatus readBatteryStatus() throws IOException, InterruptedException {
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
        return new BatteryStatus(percent, charging, discharging);
    }

    static ChargePlan planCharge(int target, BatteryStatus status) {
        if (status.discharging) {
            if (status.percent == target) return ChargePlan.alreadyMet();
            if (status.percent < target) {
                throw new Wake.UsageError("--until-charge " + target + " is unreachable while battery is discharging at "
                        + status.percent + "%; connect power or choose a target at or below the current charge");
            }
            return ChargePlan.waiting(false);
        }
        if (status.charging) {
            if (status.percent >= target) return ChargePlan.alreadyMet();
            return ChargePlan.waiting(true);
        }
        if (status.percent == target) return ChargePlan.alreadyMet();
        throw new Wake.UsageError("cannot determine battery charging direction from pmset output");
    }

    static final class BatteryStatus {
        final int percent;
        final boolean charging;
        final boolean discharging;

        BatteryStatus(int percent, boolean charging, boolean discharging) {
            this.percent = percent;
            this.charging = charging;
            this.discharging = discharging;
        }
    }

    static final class ChargePlan {
        final boolean alreadyMet;
        final boolean chargingUp;

        private ChargePlan(boolean alreadyMet, boolean chargingUp) {
            this.alreadyMet = alreadyMet;
            this.chargingUp = chargingUp;
        }

        static ChargePlan alreadyMet() {
            return new ChargePlan(true, false);
        }

        static ChargePlan waiting(boolean chargingUp) {
            return new ChargePlan(false, chargingUp);
        }
    }
}
