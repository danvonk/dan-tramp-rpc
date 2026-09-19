// SPDX-License-Identifier: GPL-3.0-or-later
package dan.tramp.rpc;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dan.tramp.rpc.Rpc.*;

final class Queries {
    final Server server;

    Queries(Server server) {
        this.server = server;
    }

    Object handle(String method, Map<String, Object> p) throws Exception {
        switch (method) {
            case "system.info": {
                Map<String, Object> a = FileOps.stat(Paths.get("/proc/self"), false);
                return map("version", "0.13.1-java11", "os", "linux", "arch", System.getProperty("os.arch"), "watcher",
                        "java-watchservice", "watcher_available", server.watches != null, "max_read_chunk_bytes",
                        FileOps.CHUNK, "hostname", Files.readString(Paths.get("/proc/sys/kernel/hostname")).trim(),
                        "uid", a.get("uid"), "gid", a.get("gid"), "home", System.getProperty("user.home"), "user",
                        System.getProperty("user.name"), "shell", System.getenv().getOrDefault("SHELL", "/bin/sh"));
            }
            case "system.getenv":
                return System.getenv(str(p, "name"));
            case "system.expand_path":
                return FileOps.expand(str(p, "path"));
            case "system.statvfs": {
                FileStore s = Files.getFileStore(FileOps.path(p, "path"));
                return map("total", s.getTotalSpace(), "free", s.getUnallocatedSpace(), "available", s.getUsableSpace(),
                        "block_size", s.getBlockSize());
            }
            case "system.groups": {
                Map<String, Object> result = object(
                        server.processes.run(map("cmd", "id", "args", Arrays.asList("-G"))));
                String[] ids = text(result.get("stdout")).trim().split("\\s+");
                Map<String, Object> names = object(
                        server.processes.run(map("cmd", "id", "args", Arrays.asList("-Gn"))));
                String[] ns = text(names.get("stdout")).trim().split("\\s+");
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < ids.length; i++)
                    out.add(map("gid", Long.parseLong(ids[i]), "name", i < ns.length ? ns[i] : null));
                return out;
            }
            case "commands.run_parallel": {
                List<?> commands = list(p, "commands");
                if (commands.size() > 256)
                    throw new Rpc(-32602, "Too many commands");
                List<Future<Object>> futures = new ArrayList<>();
                List<String> keys = new ArrayList<>();
                long timeout = Math.max(1, Math.min(120000, num(p, "timeout_ms", 25000)));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
                for (Object obj : commands) {
                    Map<String, Object> entry = new LinkedHashMap<>(object(obj));
                    keys.add(str(entry, "key"));
                    entry.put("env", p.get("env"));
                    entry.put("clear_env", p.get("clear_env"));
                    futures.add(server.commands.submit(() -> {
                        try {
                            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                            if (left <= 0)
                                throw new Rpc(-32004, "Command timed out");
                            entry.put("timeout_ms", left);
                            return server.processes.run(entry);
                        } catch (Exception e) {
                            return map("exit_code", -1, "stdout", new byte[0], "stderr",
                                    String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8), "timed_out",
                                    String.valueOf(e.getMessage()).contains("timed out"));
                        }
                    }));
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (int i = 0; i < futures.size(); i++)
                    out.put(keys.get(i), futures.get(i).get());
                return out;
            }
            case "ancestors.scan": {
                Path dir = FileOps.path(p, "directory");
                if (!Files.exists(dir))
                    throw new NoSuchFileException(dir.toString());
                Map<String, Object> out = new LinkedHashMap<>();
                for (Object marker : list(p, "markers"))
                    out.put(text(marker), null);
                long max = num(p, "max_depth", 10);
                for (int depth = 0; dir != null && depth < max; depth++, dir = dir.getParent())
                    for (String marker : out.keySet())
                        if (out.get(marker) == null && Files.exists(dir.resolve(marker)))
                            out.put(marker, pathValue(dir));
                return out;
            }
            case "highlevel.test_files_in_dir": {
                Path dir = FileOps.path(p, "directory");
                try {
                    dir = dir.toRealPath();
                } catch (Exception ignored) {
                }
                List<Object> out = new ArrayList<>();
                for (Object name : list(p, "names")) {
                    Path f = dir.resolve(text(name));
                    if (Files.isRegularFile(f) && Files.isReadable(f))
                        out.add(f.toString());
                }
                return out;
            }
            case "highlevel.locate_dominating_file_multi": {
                Path dir = dominating(FileOps.path(p, "file"), list(p, "names"));
                List<Object> out = new ArrayList<>();
                if (dir != null)
                    for (Object n : list(p, "names"))
                        if (Files.exists(dir.resolve(text(n))))
                            out.add(dir.resolve(text(n)).toString());
                return out;
            }
            case "highlevel.dir_locals_find_file_cache_update": {
                Path file = FileOps.path(p, "file"), locals = dominating(file, list(p, "names")), cache = null;
                for (Object d : list(p, "cache_dirs")) {
                    Path path = FileOps.path(d);
                    if (Files.isDirectory(path) && file.startsWith(path)
                            && (cache == null || path.getNameCount() > cache.getNameCount()))
                        cache = path;
                }
                return map("file", file.toString(), "locals", locals(locals, list(p, "names")), "cache",
                        locals(cache, list(p, "names")));
            }
            default:
                throw new Rpc(-32601, "Unknown method: " + method);
        }
    }

    static Object pathValue(Path p) throws Exception {
        byte[] b = FileOps.pathBytes(p);
        String s = new String(b, StandardCharsets.UTF_8);
        return Arrays.equals(b, s.getBytes(StandardCharsets.UTF_8)) ? s : b;
    }

    static Path dominating(Path file, List<?> names) throws Exception {
        Path dir = file;
        while (dir != null && !Files.exists(dir))
            dir = dir.getParent();
        if (dir != null && !Files.isDirectory(dir))
            dir = dir.getParent();
        for (int depth = 0; dir != null; depth++, dir = dir.getParent()) {
            if (depth > 100)
                throw new Rpc(-32602, "Maximum ancestor depth exceeded");
            for (Object n : names)
                if (Files.exists(dir.resolve(text(n))))
                    return dir;
        }
        return null;
    }

    static Object locals(Path dir, List<?> names) throws Exception {
        if (dir == null)
            return null;
        List<Object> files = new ArrayList<>();
        for (Object n : names) {
            Path f = dir.resolve(text(n));
            if (Files.isRegularFile(f))
                files.add(map("name", n, "mtime", Files.getLastModifiedTime(f).to(TimeUnit.SECONDS)));
        }
        return map("dir", dir.toString(), "files", files);
    }
}
