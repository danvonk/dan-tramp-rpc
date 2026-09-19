// SPDX-License-Identifier: GPL-3.0-or-later
package dan.tramp.rpc;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static dan.tramp.rpc.Rpc.*;

final class Watches implements AutoCloseable {
    final WatchService service;
    final Map<Path, Boolean> roots = new LinkedHashMap<>();
    final Map<Path, Path> targets = new HashMap<>();
    final Map<WatchKey, Path> keys = new HashMap<>();
    final Server server;

    Watches(Server server) throws IOException {
        this.server = server;
        service = FileSystems.getDefault().newWatchService();
        Processes.daemon(this::loop);
    }

    synchronized void register(Path p) throws IOException {
        if (keys.containsValue(p))
            return;
        keys.put(p.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY), p);
    }

    void tree(Path p) throws IOException {
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                register(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    synchronized Object handle(String method, Map<String, Object> p) throws Exception {
        if (method.equals("watch.list")) {
            List<Object> out = new ArrayList<>();
            for (Map.Entry<Path, Boolean> e : roots.entrySet())
                out.add(map("path", FileOps.pathBytes(e.getKey()), "recursive", e.getValue()));
            return out;
        }
        Path raw = FileOps.path(p, "path").toAbsolutePath().normalize();
        Path path;
        try {
            path = raw.toRealPath(bool(p, "nofollow", false) ? FileOps.NOFOLLOW : new LinkOption[0]);
        } catch (NoSuchFileException e) {
            if (method.equals("watch.remove"))
                path = raw;
            else
                throw e;
        }
        if (method.equals("watch.remove")) {
            roots.remove(path);
            targets.remove(path);
            Iterator<Map.Entry<WatchKey, Path>> it = keys.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<WatchKey, Path> e = it.next();
                boolean needed = false;
                for (Map.Entry<Path, Boolean> r : roots.entrySet())
                    if (e.getValue().equals(targets.get(r.getKey()))
                            || (r.getValue() && e.getValue().startsWith(r.getKey())))
                        needed = true;
                if (!needed) {
                    e.getKey().cancel();
                    it.remove();
                }
            }
            return true;
        }
        if (!method.equals("watch.add"))
            throw new Rpc(-32601, "Unknown method: " + method);
        boolean recursive = bool(p, "recursive", true) || roots.getOrDefault(path, false);
        Path target = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? path : path.getParent();
        if (recursive && target.equals(path))
            tree(path);
        else
            register(target);
        roots.put(path, recursive);
        targets.put(path, target);
        return map("path", FileOps.pathBytes(path), "recursive", recursive, "nofollow", bool(p, "nofollow", false));
    }

    void loop() {
        try {
            while (true) {
                WatchKey key = service.take();
                List<Object> events = new ArrayList<>();
                synchronized (this) {
                    Path parent = keys.get(key);
                    if (parent != null)
                        for (WatchEvent<?> e : key.pollEvents()) {
                            if (e.kind() == StandardWatchEventKinds.OVERFLOW) {
                                events.add(map("action", "rescan"));
                                continue;
                            }
                            Path path = parent.resolve((Path) e.context());
                            String action = e.kind() == StandardWatchEventKinds.ENTRY_CREATE ? "created"
                                    : e.kind() == StandardWatchEventKinds.ENTRY_DELETE ? "deleted" : "changed";
                            events.add(map("action", action, "path", FileOps.pathBytes(path)));
                            if (action.equals("changed"))
                                events.add(map("action", "attribute-changed", "path", FileOps.pathBytes(path)));
                            if (action.equals("created") && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                                for (Map.Entry<Path, Boolean> root : roots.entrySet())
                                    if (root.getValue() && path.startsWith(root.getKey())) {
                                        try {
                                            tree(path);
                                            events.add(map("action", "rescan"));
                                        } catch (IOException ex) {
                                            events.add(map("action", "rescan"));
                                        }
                                        break;
                                    }
                            }
                        }
                    if (!key.reset()) {
                        keys.remove(key);
                        if (parent != null) {
                            roots.remove(parent);
                            events.add(map("action", "deleted", "path", FileOps.pathBytes(parent)));
                        }
                    }
                }
                if (!events.isEmpty())
                    server.send(map("version", "2.0", "method", "fs.events", "params", map("events", events)));
            }
        } catch (ClosedWatchServiceException | InterruptedException ignored) {
        } catch (Exception ignored) {
            try {
                server.send(map("version", "2.0", "method", "fs.events", "params",
                        map("events", Collections.singletonList(map("action", "rescan")))));
            } catch (IOException ignoredAgain) {
            }
        }
    }

    @Override
    public void close() throws IOException {
        service.close();
    }
}
