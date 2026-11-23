package com.tlinkx.companion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RootShell {
    private RootShell() {}

    public static final class Result {
        public final int code;
        public final String out;

        public Result(int code, String out) {
            this.code = code;
            this.out = out;
        }
    }

    public static boolean hasRoot() {
        Result r = runRoot("id", 3000);
        return r.code == 0 && r.out.contains("uid=0");
    }

    public static Result runRoot(String cmd, long timeoutMs) {
        return run(new String[]{"su", "-c", cmd}, timeoutMs);
    }

    public static Result run(String[] cmd, long timeoutMs) {
        StringBuilder sb = new StringBuilder();
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();

            final Process p = proc;
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                }
            });
            reader.start();

            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                reader.join(500);
                return new Result(-1, sb + "\n[TIMEOUT]");
            }
            reader.join(1000);
            return new Result(proc.exitValue(), sb.toString());
        } catch (Exception e) {
            if (proc != null) proc.destroyForcibly();
            return new Result(-1, "ERROR: " + e.getMessage());
        }
    }
}
