package dev.abhinav.wake;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class Interactive {

    private static final String ESC = "\u001B";
    private static final boolean STYLE_ENABLED = styleEnabled();
    private static final String ALT_ON      = ESC + "[?1049h";
    private static final String ALT_OFF     = ESC + "[?1049l";
    private static final String CLEAR       = ESC + "[2J";
    private static final String HOME        = ESC + "[H";
    private static final String HIDE_CURSOR = ESC + "[?25l";
    private static final String SHOW_CURSOR = ESC + "[?25h";
    private static final String RESET       = style("[0m");
    private static final String BOLD        = style("[1m");
    private static final String DIM         = style("[2m");
    private static final String FG_CYAN     = style("[38;5;87m");
    private static final String FG_YELLOW   = style("[38;5;220m");
    private static final String FG_GREY     = style("[38;5;245m");
    private static final String FG_PINK     = style("[38;5;213m");

    private static final int KEY_UP   = -1001;
    private static final int KEY_DOWN = -1002;
    private static final int KEY_ESC  = -1005;
    private static final int KEY_IGNORE = 0;

    private boolean noDisplay = false;
    private int selected = 0;
    private boolean rawMode = false;
    private String savedTty;

    static void run() throws Exception {
        new Interactive().loop();
    }

    private void loop() throws Exception {
        if (!isInteractive()) {
            Wake.start(new String[0]);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
        enterRawMode();
        System.out.print(ALT_ON + HIDE_CURSOR);
        System.out.flush();

        Session existing = Session.readIfAlive();
        List<MenuItem> items = buildMenu(existing);
        selected = firstSelectable(items);

        while (true) {
            render(items, existing);
            int key = readKey();
            if (key == KEY_UP || key == 'k') selected = prev(items, selected);
            else if (key == KEY_DOWN || key == 'j') selected = next(items, selected);
            else if ((key == 'd' || key == 'D') && existing == null) noDisplay = !noDisplay;
            else if (key == 'q' || key == 'Q' || key == KEY_ESC || key == 3) {
                cleanup();
                return;
            } else if (key == '\r' || key == '\n') {
                MenuItem it = items.get(selected);
                if (it.separator) continue;
                cleanup();
                it.action.accept(this);
                return;
            }
        }
    }

    private void cleanup() {
        try {
            System.out.print(SHOW_CURSOR + ALT_OFF + RESET);
            System.out.flush();
        } catch (Exception ignored) {}
        if (rawMode) {
            try { leaveRawMode(); } catch (Exception ignored) {}
            rawMode = false;
        }
    }

    private static final class MenuItem {
        final String label;
        final String hint;
        final Consumer<Interactive> action;
        final boolean separator;
        MenuItem(String label, String hint, Consumer<Interactive> action) {
            this.label = label; this.hint = hint; this.action = action; this.separator = false;
        }
        private MenuItem() {
            this.label = ""; this.hint = ""; this.action = i -> {}; this.separator = true;
        }
        static MenuItem sep() { return new MenuItem(); }
    }

    private List<MenuItem> buildMenu(Session existing) {
        List<MenuItem> items = new ArrayList<>();
        if (existing != null) {
            items.add(new MenuItem("Show status",  "view session details",
                    i -> i.runAction(() -> Wake.status())));
            items.add(new MenuItem("Stop session", "end the active session",
                    i -> i.runAction(() -> Wake.stop())));
        } else {
            items.add(new MenuItem("Indefinite",          "stay awake forever",
                    i -> i.startWith()));
            items.add(new MenuItem("For a duration…",     "1h, 30m, 1h30m, 90s",
                    i -> i.askAndStart("Duration (e.g. 1h30m, 90s)", null)));
            items.add(new MenuItem("Until clock time…",   "stay awake until HH:MM",
                    i -> i.askAndStart("Until clock time (HH:MM)", "--until")));
            items.add(new MenuItem("Until battery %…",    "until charge hits N%",
                    i -> i.askAndStart("Target battery percent (1-100)", "--until-charge")));
            items.add(new MenuItem("While app running…",  "watch a running app/process",
                    i -> i.askAndStart("App/process name", "--while-app")));
            items.add(new MenuItem("While PID alive…",    "watch a specific process id",
                    i -> i.askAndStart("PID to watch", "--while-pid")));
        }
        items.add(MenuItem.sep());
        items.add(new MenuItem("Quit", "exit without changes", i -> {}));
        return items;
    }

    private int firstSelectable(List<MenuItem> items) {
        for (int i = 0; i < items.size(); i++) if (!items.get(i).separator) return i;
        return 0;
    }
    private int next(List<MenuItem> items, int cur) {
        int n = items.size();
        for (int step = 1; step <= n; step++) {
            int idx = (cur + step) % n;
            if (!items.get(idx).separator) return idx;
        }
        return cur;
    }
    private int prev(List<MenuItem> items, int cur) {
        int n = items.size();
        for (int step = 1; step <= n; step++) {
            int idx = ((cur - step) % n + n) % n;
            if (!items.get(idx).separator) return idx;
        }
        return cur;
    }

    private void render(List<MenuItem> items, Session existing) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(CLEAR).append(HOME).append('\n');
        sb.append("  ").append(BOLD).append(FG_CYAN).append("☕ wake").append(RESET);
        sb.append("  ").append(DIM).append("— keep your machine awake").append(RESET).append("\n\n");

        if (existing != null) {
            sb.append("  ").append(FG_YELLOW).append("● active").append(RESET)
              .append("   ").append(existing.trigger).append(' ').append(existing.detail);
            if (existing.endsAt != null) {
                long rem = Math.max(0, java.time.Duration.between(Instant.now(), existing.endsAt).getSeconds());
                sb.append("   ").append(DIM).append('(').append(Wake.prettyDuration(rem)).append(" left)").append(RESET);
            }
            sb.append("\n\n");
        } else {
            sb.append("  ").append(FG_GREY).append("○ no active session").append(RESET).append("\n\n");
        }

        for (int i = 0; i < items.size(); i++) {
            MenuItem it = items.get(i);
            if (it.separator) {
                sb.append("    ").append(DIM).append("─────────────────────────").append(RESET).append('\n');
                continue;
            }
            if (i == selected) {
                sb.append("  ").append(FG_PINK).append("▸ ").append(BOLD).append(it.label).append(RESET);
                if (it.hint != null && !it.hint.isEmpty()) {
                    sb.append("   ").append(DIM).append(it.hint).append(RESET);
                }
            } else {
                sb.append("    ").append(it.label);
            }
            sb.append('\n');
        }

        sb.append('\n').append("  ").append(DIM).append("↑↓/jk navigate · ↵ select");
        if (existing == null) {
            sb.append(" · d display-sleep [").append(noDisplay ? "ON" : "off").append(']');
        }
        sb.append(" · q quit").append(RESET).append('\n');

        System.out.print(sb);
        System.out.flush();
    }

    private void startWith(String... extra) {
        runAction(() -> Wake.start(buildArgs(extra)));
    }

    private void askAndStart(String prompt, String flag) {
        String v = readLine(prompt);
        if (v == null || v.isBlank()) {
            System.out.println("wake: cancelled");
            return;
        }
        v = v.trim();
        if (flag == null) {
            String value = v;
            runAction(() -> Wake.start(buildArgs(value)));
        } else {
            String value = v;
            runAction(() -> Wake.start(buildArgs(flag, value)));
        }
    }

    private String[] buildArgs(String... parts) {
        List<String> out = new ArrayList<>();
        if (noDisplay) out.add("--no-display");
        for (String p : parts) out.add(p);
        return out.toArray(new String[0]);
    }

    private String readLine(String prompt) {
        System.out.print("\n  " + FG_CYAN + prompt + ":" + RESET + " ");
        System.out.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        try {
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private void runAction(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Wake.UsageError e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t.getMessage(), t);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    private boolean isInteractive() {
        return System.console() != null;
    }

    private void enterRawMode() throws IOException, InterruptedException {
        Process snap = new ProcessBuilder("/bin/sh", "-c", "stty -g < /dev/tty").start();
        savedTty = new String(snap.getInputStream().readAllBytes()).trim();
        snap.waitFor();
        new ProcessBuilder("/bin/sh", "-c", "stty -icanon -echo susp undef min 1 < /dev/tty")
                .inheritIO().start().waitFor();
        rawMode = true;
    }

    private void leaveRawMode() throws IOException, InterruptedException {
        if (savedTty != null && !savedTty.isEmpty()) {
            new ProcessBuilder("/bin/sh", "-c", "stty " + savedTty + " < /dev/tty")
                    .inheritIO().start().waitFor();
        } else {
            new ProcessBuilder("/bin/sh", "-c", "stty sane < /dev/tty")
                    .inheritIO().start().waitFor();
        }
    }

    private int readKey() throws IOException {
        int b = System.in.read();
        if (b == -1) return KEY_ESC;
        if (b != 27) return b;
        if (System.in.available() == 0) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        if (System.in.available() == 0) return KEY_ESC;
        int b2 = System.in.read();
        if (b2 == '[' || b2 == 'O') {
            int last = -1;
            while (true) {
                int next = System.in.read();
                if (next == -1) break;
                if (next >= 0x40 && next <= 0x7E) {
                    last = next;
                    break;
                }
            }
            switch (last) {
                case 'A': return KEY_UP;
                case 'B': return KEY_DOWN;
                default: return KEY_IGNORE;
            }
        }
        return KEY_ESC;
    }

    private static String style(String code) {
        return STYLE_ENABLED ? ESC + code : "";
    }

    private static boolean styleEnabled() {
        if (System.getenv("NO_COLOR") != null) return false;
        String term = System.getenv("TERM");
        return term != null && !term.isBlank() && !term.equalsIgnoreCase("dumb");
    }
}
