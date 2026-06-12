package dev.abhinav.wake;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Durations {
    private static final long MAX_SECONDS = 30L * 24L * 60L * 60L;
    private static final Pattern P = Pattern.compile("^(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$");

    static long parse(String raw) {
        if (raw == null || raw.isBlank()) throw new Wake.UsageError("empty duration");
        String s = raw.trim().toLowerCase();
        if (s.matches("\\d+")) {
            long v = parseUnit(s, raw);
            if (v <= 0) throw new Wake.UsageError("duration must be positive");
            return cap(v, raw);
        }
        Matcher m = P.matcher(s);
        if (!m.matches()) throw new Wake.UsageError("invalid duration: '" + raw + "' (try 1h30m, 90s, etc.)");
        long d = m.group(1) == null ? 0 : parseUnit(m.group(1), raw);
        long h = m.group(2) == null ? 0 : parseUnit(m.group(2), raw);
        long mi = m.group(3) == null ? 0 : parseUnit(m.group(3), raw);
        long se = m.group(4) == null ? 0 : parseUnit(m.group(4), raw);
        long total;
        try {
            total = Math.addExact(
                    Math.addExact(Math.multiplyExact(d, 86_400L), Math.multiplyExact(h, 3_600L)),
                    Math.addExact(Math.multiplyExact(mi, 60L), se));
        } catch (ArithmeticException e) {
            throw tooLarge(raw);
        }
        if (total <= 0) throw new Wake.UsageError("invalid duration: '" + raw + "'");
        return cap(total, raw);
    }

    private static long parseUnit(String s, String raw) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw tooLarge(raw);
        }
    }

    private static long cap(long seconds, String raw) {
        if (seconds > MAX_SECONDS) throw tooLarge(raw);
        return seconds;
    }

    private static Wake.UsageError tooLarge(String raw) {
        return new Wake.UsageError("duration exceeds maximum of 30d: '" + raw + "'");
    }
}
