package dev.abhinav.wake;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

final class Supervisor {
    private static final long POLL_INTERVAL_MS = 30_000;

    static void runCharge(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalStateException("supervisor: bad args");
        int target = Integer.parseInt(args[1]);
        boolean noDisplay = Boolean.parseBoolean(args[2]);
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

        AtomicReference<Process> keepAwakeProcRef = new AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = keepAwakeProcRef.get();
            try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
            try { Files.deleteIfExists(Wake.STATE_FILE); } catch (Throwable ignored) {}
        }));

        ProcessBuilder keepAwake = new ProcessBuilder(Wake.PLATFORM.keepAwakeCommand(noDisplay, null, null));
        File devnull = new File("/dev/null");
        keepAwake.redirectInput(ProcessBuilder.Redirect.from(devnull));
        keepAwake.redirectOutput(ProcessBuilder.Redirect.to(devnull));
        keepAwake.redirectError(ProcessBuilder.Redirect.to(devnull));
        Process keepAwakeProc = keepAwake.start();
        keepAwakeProcRef.set(keepAwakeProc);

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
            Wake.destroyProcess(keepAwakeProc);
            throw t;
        }

        while (true) {
            Thread.sleep(POLL_INTERVAL_MS);
            if (!keepAwakeProc.isAlive()) break;
            BatteryStatus status;
            try {
                status = readBatteryStatus();
            } catch (Exception e) {
                continue;
            }
            if (chargingUp ? status.percent >= target : status.percent <= target) break;
        }
        keepAwakeProc.destroy();
        keepAwakeProc.waitFor();
        try { Files.deleteIfExists(Wake.STATE_FILE); } catch (Throwable ignored) {}
    }

    static int readBatteryPct() throws IOException, InterruptedException {
        return readBatteryStatus().percent;
    }

    static BatteryStatus readBatteryStatus() throws IOException, InterruptedException {
        return Wake.PLATFORM.readBattery();
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
        if (status.neutralState != null) {
            throw new Wake.UsageError("--until-charge " + target + " is unreachable while battery is "
                    + status.neutralState + " at " + status.percent + "%");
        }
        throw new Wake.UsageError("cannot determine battery charging direction");
    }

    static final class BatteryStatus {
        final int percent;
        final boolean charging;
        final boolean discharging;
        final String neutralState;

        BatteryStatus(int percent, boolean charging, boolean discharging) {
            this(percent, charging, discharging, null);
        }

        BatteryStatus(int percent, boolean charging, boolean discharging, String neutralState) {
            this.percent = percent;
            this.charging = charging;
            this.discharging = discharging;
            this.neutralState = neutralState;
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
