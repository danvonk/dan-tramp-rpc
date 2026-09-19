// SPDX-License-Identifier: GPL-3.0-or-later
package dan.tramp.rpc;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static dan.tramp.rpc.Rpc.*;

/** One SSH stdio connection. stdout is reserved exclusively for framed RPC. */
public final class Server implements AutoCloseable {
    final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(System.out));
    final Processes processes = new Processes();
    final ExecutorService requests = pool(16, 128), control = pool(4, 64), writes = pool(4, 32),
            commands = pool(16, 256);
    final java.util.concurrent.atomic.AtomicLong retainedBytes = new java.util.concurrent.atomic.AtomicLong();
    final Queries queries = new Queries(this);
    Watches watches;

    Server() {
        try {
            watches = new Watches(this);
        } catch (IOException ignored) {
        }
    }

    static ExecutorService pool(int n, int queue) {
        return new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue), r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized void send(Object response) throws IOException {
        byte[] b = MessagePack.encode(response);
        if (b.length > MessagePack.MAX) {
            Object id = ((Map<?, ?>) response).get("id");
            b = MessagePack.encode(map("version", "2.0", "id", id, "error",
                    map("code", -32603, "message", "Response exceeds frame limit")));
        }
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }

    Object route(String method, Map<String, Object> params) throws Exception {
        if (method.startsWith("file.") || method.startsWith("dir."))
            return FileOps.handle(method, params);
        if (method.startsWith("process."))
            return processes.handle(method, params);
        if (method.startsWith("watch.")) {
            if (watches == null)
                throw new Rpc(-32603, "Watcher unavailable");
            return watches.handle(method, params);
        }
        return queries.handle(method, params);
    }

    Object dispatch(String method, Map<String, Object> params) throws Exception {
        if (!method.equals("batch"))
            return route(method, params);
        List<?> reqs = list(params, "requests");
        if (reqs.size() > 64)
            throw new Rpc(-32602, "Batch exceeds 64 requests");
        List<Object> results = new ArrayList<>();
        for (Object req : reqs) {
            try {
                Map<String, Object> r = object(req);
                results.add(map("result", route(str(r, "method"), object(r.get("params")))));
            } catch (Exception e) {
                results.add(map("error", error(e)));
            }
        }
        return map("results", results);
    }

    void accept(byte[] frame) throws IOException {
        Map<String, Object> request;
        Object id = null;
        try {
            Object decoded;
            try {
                decoded = MessagePack.decode(frame);
            } catch (IOException e) {
                throw new Rpc(-32700, e.getMessage());
            }
            if (!(decoded instanceof Map))
                throw new Rpc(-32600, "Request must be a map");
            request = object(decoded);
            id = request.get("id");
            if (!(id instanceof String) && !(id instanceof Long))
                throw new Rpc(-32600, "Request id must be integer or string");
            if (!"2.0".equals(request.get("version")) || !(request.get("method") instanceof String))
                throw new Rpc(-32600, "Invalid RPC request");
        } catch (Exception e) {
            send(map("version", "2.0", "id", id, "error", error(e)));
            return;
        }
        final Object requestId = id;
        String method = (String) request.get("method");
        ExecutorService pool = method.matches("process\\.(kill(_pty)?|signal|close_stdin|close_pty)") ? control
                : method.matches("process\\.write(_pty)?") ? writes : requests;
        if (retainedBytes.addAndGet(frame.length) > 128L * 1024 * 1024) {
            retainedBytes.addAndGet(-frame.length);
            send(map("version", "2.0", "id", id, "error",
                    map("code", -32603, "message", "Server parameter budget exhausted")));
            return;
        }
        try {
            pool.execute(() -> {
                Map<String, Object> response = map("version", "2.0", "id", requestId);
                try {
                    response.put("result", dispatch(method, object(request.get("params"))));
                } catch (Exception e) {
                    response.put("error", error(e));
                }
                try {
                    send(response);
                } catch (IOException ignored) {
                } finally {
                    retainedBytes.addAndGet(-frame.length);
                }
            });
        } catch (RejectedExecutionException e) {
            retainedBytes.addAndGet(-frame.length);
            send(map("version", "2.0", "id", id, "error",
                    map("code", -32603, "message", "Server request queue is full")));
        }
    }

    void run() throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in));
        while (true) {
            int n;
            try {
                n = in.readInt();
            } catch (EOFException e) {
                return;
            }
            if (n <= 0 || n > MessagePack.MAX) {
                send(map("version", "2.0", "error", map("code", -32600, "message", "Invalid frame size")));
                return;
            }
            byte[] b = new byte[n];
            in.readFully(b);
            accept(b);
        }
    }

    @Override
    public void close() {
        requests.shutdownNow();
        control.shutdownNow();
        writes.shutdownNow();
        commands.shutdownNow();
        processes.close();
        if (watches != null)
            try {
                watches.close();
            } catch (IOException ignored) {
            }
    }

    public static void main(String[] args) {
        try (Server server = new Server()) {
            server.run();
        } catch (Exception ignored) {
            System.exit(1);
        }
    }
}
