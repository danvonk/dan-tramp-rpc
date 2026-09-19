// SPDX-License-Identifier: GPL-3.0-or-later
package dan.tramp.rpc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dan.tramp.rpc.Rpc.*;

final class Processes implements AutoCloseable {
    final ConcurrentMap<Long, Child> children = new ConcurrentHashMap<>();
    volatile boolean closed;
    static final int OUTPUT_LIMIT = 32 * 1024 * 1024;

    static Thread daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
        return t;
    }

    static final class Buffer {
        final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        volatile int size;
        int head;
        volatile boolean eof, overflow;

        synchronized void add(byte[] b, int n) {
            if (size + n > OUTPUT_LIMIT) {
                overflow = true;
                notifyAll();
                return;
            }
            chunks.add(Arrays.copyOf(b, n));
            size += n;
            notifyAll();
        }

        synchronized byte[] take(int max) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (size > 0 && out.size() < max) {
                byte[] b = chunks.peek();
                int n = Math.min(b.length - head, max - out.size());
                out.write(b, head, n);
                head += n;
                size -= n;
                if (head == b.length) {
                    chunks.remove();
                    head = 0;
                }
            }
            return out.toByteArray();
        }

        synchronized boolean done() {
            return eof && size == 0;
        }

        synchronized void finish() {
            eof = true;
            notifyAll();
        }
    }

    static final class Child {
        final Process process;
        final Buffer out = new Buffer(), err = new Buffer();
        final String cmd;
        final boolean pty;
        String tty;
        long osPid;

        Child(Process process, String cmd, boolean pty) {
            this.process = process;
            this.cmd = cmd;
            this.pty = pty;
            osPid = process.pid();
            pump(process.getInputStream(), out);
            pump(process.getErrorStream(), err);
        }

        void pump(InputStream in, Buffer buffer) {
            daemon(() -> {
                try (InputStream stream = in) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = stream.read(b)) >= 0) {
                        buffer.add(b, n);
                        if (buffer.overflow) {
                            terminate(this);
                            break;
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    buffer.finish();
                }
            });
        }

        boolean exited() {
            return !process.isAlive() && out.done() && err.done();
        }

        Object exitCode() {
            return process.isAlive() ? null : process.exitValue();
        }
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    Child start(Map<String, Object> p, boolean pty) throws Exception {
        if (closed)
            throw new Rpc(-32004, "Connection closed");
        String cmd = str(p, "cmd");
        List<String> command = new ArrayList<>();
        command.add(cmd);
        for (Object arg : list(p, "args"))
            command.add(text(arg));
        Path cwd = p.get("cwd") == null ? Paths.get(System.getProperty("user.dir")) : FileOps.path(p, "cwd");
        ProcessBuilder pb = new ProcessBuilder();
        if (bool(p, "clear_env", false))
            pb.environment().clear();
        for (Map.Entry<String, Object> e : object(p.get("env")).entrySet())
            pb.environment().put(e.getKey(), text(e.getValue()));
        // ProcessBuilder uses the JVM's PATH for bare names; resolve using the
        // requested environment.
        if (!cmd.contains("/")) {
            String search = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
            String found = null;
            for (String dir : search.split(":", -1)) {
                Path f = Paths.get(dir.isEmpty() ? "." : dir).resolve(cmd);
                if (!f.isAbsolute())
                    f = cwd.resolve(f);
                if (Files.isExecutable(f) && !Files.isDirectory(f)) {
                    found = f.toAbsolutePath().toString();
                    break;
                }
            }
            if (found == null)
                throw new Rpc(-32004, "Executable not found: " + cmd, map("spawn_not_found", true));
            command.set(0, found);
        }
        Path handshake = null;
        if (pty) {
            handshake = Files.createTempFile("dan-tramp-tty-", ".txt");
            StringBuilder shell = new StringBuilder("tty > ").append(quote(handshake.toString()))
                    .append("; echo $$ >> ").append(quote(handshake.toString())).append("; stty rows ")
                    .append(dimension(p, "rows", 24)).append(" cols ").append(dimension(p, "cols", 80))
                    .append("; exec");
            for (String arg : command)
                shell.append(' ').append(quote(arg));
            command = new ArrayList<>(Arrays.asList("/usr/bin/script", "-qefc", shell.toString(), "/dev/null"));
            pb.environment().putIfAbsent("TERM", "xterm-256color");
        } else if (Files.isExecutable(Paths.get("/usr/bin/setsid")))
            command.add(0, "/usr/bin/setsid");
        pb.command(command).directory(cwd.toFile()).redirectErrorStream(pty || bool(p, "merge_stderr", false));
        Child c;
        try {
            c = new Child(pb.start(), cmd, pty);
        } catch (IOException e) {
            if (handshake != null)
                Files.deleteIfExists(handshake);
            throw new Rpc(-32004, e.getMessage());
        }
        children.put(c.process.pid(), c);
        if (closed) {
            terminate(c);
            throw new Rpc(-32004, "Connection closed");
        }
        if (pty) {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    List<String> lines = Files.readAllLines(handshake);
                    if (lines.size() >= 2) {
                        c.tty = lines.get(0);
                        c.osPid = Long.parseLong(lines.get(1));
                        break;
                    }
                    if (!c.process.isAlive())
                        break;
                    Thread.sleep(10);
                }
                if (c.tty == null)
                    throw new Rpc(-32004, "PTY startup failed (util-linux script required)");
            } catch (Exception e) {
                terminate(c);
                children.remove(c.process.pid());
                throw e;
            } finally {
                Files.deleteIfExists(handshake);
            }
        }
        return c;
    }

    static int dimension(Map<String, Object> p, String key, int d) throws Rpc {
        long n = num(p, key, d);
        if (n < 1 || n > 65535)
            throw new Rpc(-32602, "Invalid terminal " + key);
        return (int) n;
    }

    Object run(Map<String, Object> p) throws Exception {
        Child c = start(p, false);
        long timeout = num(p, "timeout_ms", 0);
        long deadline = timeout > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) : Long.MAX_VALUE;
        Thread input = daemon(() -> {
            try (OutputStream out = c.process.getOutputStream()) {
                if (p.get("stdin") != null)
                    out.write(binary(p.get("stdin")));
            } catch (Exception ignored) {
            }
        });
        try {
            while (c.process.isAlive() || !c.out.eof || !c.err.eof) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                    throw new Rpc(-32004, "Command timed out", map("process_error", "timed_out"));
                if (c.out.overflow || c.err.overflow)
                    throw new Rpc(-32004, "Process output limit exceeded");
                Thread.sleep(5);
            }
            if (c.out.overflow || c.err.overflow)
                throw new Rpc(-32004, "Process output limit exceeded");
            return map("exit_code", c.process.exitValue(), "stdout", c.out.take(OUTPUT_LIMIT), "stderr",
                    c.err.take(OUTPUT_LIMIT));
        } finally {
            terminate(c);
            children.remove(c.process.pid());
            input.interrupt();
        }
    }

    Object handle(String method, Map<String, Object> p) throws Exception {
        if (method.equals("process.run"))
            return run(p);
        if (method.equals("process.start") || method.equals("process.start_pty")) {
            Child c = start(p, method.endsWith("_pty"));
            return c.pty ? map("pid", c.process.pid(), "os_pid", c.osPid, "tty_name", c.tty)
                    : map("pid", c.process.pid());
        }
        if (method.equals("process.list") || method.equals("process.list_pty")) {
            List<Object> out = new ArrayList<>();
            for (Child c : children.values())
                if (c.pty == method.endsWith("_pty"))
                    out.add(map("pid", c.process.pid(), "os_pid", c.osPid, "cmd", c.cmd, "exited", !c.process.isAlive(),
                            "exit_code", c.exitCode()));
            return out;
        }
        long pid = num(p, "pid", 0);
        if (pid <= 0)
            throw new Rpc(-32602, "PID must be positive");
        if (method.equals("process.signal")) {
            signal(pid, p.getOrDefault("signal", 15L), false);
            return true;
        }
        Child c = children.get(pid);
        if (c == null) {
            if (method.equals("process.close_pty"))
                return true;
            throw new Rpc(-32004, "Process not found: " + pid, map("process_error", "not_found"));
        }
        switch (method) {
            case "process.write":
            case "process.write_pty": {
                byte[] b = binary(p.get("data"));
                synchronized (c.process.getOutputStream()) {
                    c.process.getOutputStream().write(b);
                    c.process.getOutputStream().flush();
                }
                return map("written", b.length);
            }
            case "process.close_stdin":
                c.process.getOutputStream().close();
                return true;
            case "process.status":
                return map("exited", !c.process.isAlive(), "exit_code", c.exitCode());
            case "process.read":
            case "process.read_pty": {
                int max = (int) num(p, "max_bytes", 65536);
                if (max <= 0 || max > FileOps.CHUNK)
                    throw new Rpc(-32602, "Invalid max_bytes");
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(Math.min(120000, num(p, "timeout_ms", 0)));
                while (c.out.size == 0 && c.err.size == 0 && !c.exited() && System.nanoTime() < deadline)
                    Thread.sleep(5);
                if (c.out.overflow || c.err.overflow)
                    throw new Rpc(-32004, "Process output limit exceeded");
                byte[] out = c.out.take(max), err = c.err.take(max);
                boolean exited = c.exited();
                Object code = exited ? c.exitCode() : null;
                if (exited)
                    children.remove(pid, c);
                return c.pty ? map("output", out, "exited", exited, "exit_code", code)
                        : map("stdout", out, "stderr", err, "exited", exited, "exit_code", code);
            }
            case "process.resize_pty": {
                if (c.tty == null)
                    throw new Rpc(-32004, "Not a PTY");
                Process resize = new ProcessBuilder("stty", "-F", c.tty, "rows",
                        String.valueOf(dimension(p, "rows", 24)), "cols", String.valueOf(dimension(p, "cols", 80)))
                        .start();
                if (resize.waitFor() != 0)
                    throw new Rpc(-32004, "Terminal resize failed");
                return true;
            }
            case "process.kill_pty":
                signal(c.osPid, p.getOrDefault("signal", 15L), true);
                return true;
            case "process.kill":
                signal(c.osPid, p.getOrDefault("signal", 15L), true);
                if (!c.process.waitFor(500, TimeUnit.MILLISECONDS))
                    terminate(c);
                return true;
            case "process.close_pty":
                terminate(c);
                children.remove(pid, c);
                return true;
            default:
                throw new Rpc(-32601, "Unknown method: " + method);
        }
    }

    static void signal(long pid, Object signal, boolean group) throws Exception {
        String s = signal instanceof Number ? signal.toString()
                : text(signal).toUpperCase(Locale.ROOT).replaceFirst("^SIG", "");
        if (!s.matches("[A-Z0-9]+"))
            throw new Rpc(-32602, "Invalid signal");
        Process p = new ProcessBuilder("/bin/kill", "-" + s, "--", (group ? "-" : "") + pid).start();
        if (p.waitFor() != 0)
            throw new Rpc(-32004, "Unable to signal process " + pid);
    }

    static void terminate(Child c) {
        try {
            signal(c.osPid, 9, true);
        } catch (Exception ignored) {
        }
        if (c.process.isAlive()) {
            List<ProcessHandle> descendants = new ArrayList<>();
            c.process.descendants().forEach(descendants::add);
            for (ProcessHandle d : descendants)
                d.destroyForcibly();
            c.process.destroyForcibly();
            try {
                c.process.waitFor(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            c.process.getOutputStream().close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Child c : children.values())
            terminate(c);
        children.clear();
    }
}
