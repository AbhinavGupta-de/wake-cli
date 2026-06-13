package dev.abhinav.wake;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class Supervisor {
    private static final long POLL_INTERVAL_MS = 30_000;
    private static final long LID_POLL_INTERVAL_MS = 1_000;
    private static final long SUDO_HEARTBEAT_MS = 180_000;

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

        var keepAwakeCmd = Wake.PLATFORM.keepAwakeCommand(noDisplay, null, null);
        ProcessBuilder keepAwake = new ProcessBuilder(keepAwakeCmd);
        keepAwake.redirectInput(ProcessBuilder.Redirect.from(Wake.PLATFORM.devNull()));
        keepAwake.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        keepAwake.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process keepAwakeProc = keepAwake.start();
        keepAwakeProcRef.set(keepAwakeProc);
        Wake.requireChildAlive(keepAwakeProc.pid(), keepAwakeCmd);

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

    static void runLid(String[] args) throws Exception {
        if (args.length < 7) throw new IllegalStateException("lid supervisor: bad args");
        if (!Wake.PLATFORM.supportsEvenLid()) return;

        String modeChar = args[1];
        if (!"d".equals(modeChar) && !"i".equals(modeChar)) {
            throw new IllegalStateException("lid supervisor: bad caffeinate mode");
        }
        boolean noDisplay = "i".equals(modeChar);
        Long timeoutSec = optionalLong(args[2]);
        Long waitPid = optionalLong(args[3]);
        int priorDisableSleep = parseDisableSleep(args[4]);
        String trigger = args[5];
        String detail = args[6];
        Integer chargeTarget = args.length > 7 && !args[7].isBlank()
                ? Integer.parseInt(args[7])
                : null;

        Boolean chargingUpValue = null;
        if (chargeTarget != null) {
            BatteryStatus initial = readBatteryStatus();
            ChargePlan plan = planCharge(chargeTarget, initial);
            if (plan.alreadyMet) return;
            chargingUpValue = plan.chargingUp;
        }
        final Boolean chargingUp = chargingUpValue;

        AtomicReference<Process> keepAwakeProcRef = new AtomicReference<>();
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            boolean restored = false;
            try {
                int current = Wake.PLATFORM.readDisableSleep();
                if (current != priorDisableSleep) {
                    Wake.PLATFORM.setDisableSleepNonInteractive(priorDisableSleep);
                }
                restored = Wake.PLATFORM.readDisableSleep() == priorDisableSleep;
            } catch (Throwable ignored) {}

            Process p = keepAwakeProcRef.get();
            try { if (p != null) Wake.destroyProcess(p); } catch (Throwable ignored) {}

            if (restored) {
                try { Files.deleteIfExists(Wake.STATE_FILE); } catch (Throwable ignored) {}
            } else {
                Wake.printSleepRestoreRescue(priorDisableSleep);
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(cleanup, "wake-lid-teardown"));

        var keepAwakeCmd = Wake.PLATFORM.keepAwakeCommand(noDisplay, timeoutSec, waitPid);
        ProcessBuilder keepAwake = new ProcessBuilder(keepAwakeCmd);
        keepAwake.redirectInput(ProcessBuilder.Redirect.from(Wake.PLATFORM.devNull()));
        keepAwake.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        keepAwake.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process keepAwakeProc = null;
        try {
            keepAwakeProc = keepAwake.start();
            keepAwakeProcRef.set(keepAwakeProc);
            Wake.requireChildAlive(keepAwakeProc.pid(), keepAwakeCmd);

            Session s = new Session();
            s.pid = ProcessHandle.current().pid();
            s.mode = noDisplay ? "system-only" : "display+system";
            s.trigger = trigger;
            s.detail = detail;
            s.startedAt = Instant.now();
            s.endsAt = timeoutSec == null ? null : s.startedAt.plusSeconds(timeoutSec);
            s.evenLid = true;
            s.priorDisableSleep = priorDisableSleep;
            s.phase = Wake.PHASE_ACTIVE;
            s.captureProcessIdentity();
            Session.write(s);

            long startMs = System.currentTimeMillis();
            long nextSudoHeartbeatMs = startMs + SUDO_HEARTBEAT_MS;
            while (true) {
                Thread.sleep(LID_POLL_INTERVAL_MS);
                long nowMs = System.currentTimeMillis();
                if (!keepAwakeProc.isAlive()) break;
                if (timeoutSec != null && nowMs - startMs >= timeoutSec * 1_000L) break;
                if (waitPid != null && !Wake.isAlive(waitPid)) break;
                if (chargeTarget != null && chargeReached(chargeTarget, chargingUp)) break;
                if (nowMs >= nextSudoHeartbeatMs) {
                    try { Wake.PLATFORM.refreshSudoNonInteractive(); } catch (Exception ignored) {}
                    nextSudoHeartbeatMs = nowMs + SUDO_HEARTBEAT_MS;
                }
            }
        } finally {
            cleanup.run();
        }
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

    private static boolean chargeReached(int target, Boolean chargingUp) {
        if (chargingUp == null) return false;
        try {
            BatteryStatus status = readBatteryStatus();
            return chargingUp ? status.percent >= target : status.percent <= target;
        } catch (Exception e) {
            return false;
        }
    }

    private static Long optionalLong(String raw) {
        return raw == null || raw.isBlank() ? null : Long.parseLong(raw);
    }

    private static int parseDisableSleep(String raw) {
        int value = Integer.parseInt(raw);
        if (value != 0 && value != 1) throw new IllegalArgumentException("priorDisableSleep must be 0 or 1");
        return value;
    }
}
