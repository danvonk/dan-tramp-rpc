// SPDX-License-Identifier: GPL-3.0-or-later
package dan.tramp.rpc;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class Rpc extends Exception {
    private static final long serialVersionUID = 1L;
    final int code;
    final Object data;

    Rpc(int code, String message) {
        this(code, message, null);
    }

    Rpc(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    static Map<String, Object> map(Object... values) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2)
            m.put((String) values[i], values[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object v) throws Rpc {
        if (v == null)
            return new LinkedHashMap<>();
        if (!(v instanceof Map))
            throw new Rpc(-32602, "Expected map");
        return (Map<String, Object>) v;
    }

    static List<?> list(Map<String, Object> p, String k) throws Rpc {
        Object v = p.get(k);
        if (v == null)
            return Collections.emptyList();
        if (!(v instanceof List))
            throw new Rpc(-32602, "Expected array: " + k);
        return (List<?>) v;
    }

    static String text(Object v) throws Rpc {
        if (v instanceof String)
            return (String) v;
        if (v instanceof byte[])
            return new String((byte[]) v, StandardCharsets.UTF_8);
        throw new Rpc(-32602, "Expected string or bytes");
    }

    static String str(Map<String, Object> p, String k) throws Rpc {
        return text(p.get(k));
    }

    static long num(Map<String, Object> p, String k, long d) throws Rpc {
        Object v = p.get(k);
        if (v == null)
            return d;
        if (!(v instanceof Number))
            throw new Rpc(-32602, "Expected integer: " + k);
        return ((Number) v).longValue();
    }

    static boolean bool(Map<String, Object> p, String k, boolean d) throws Rpc {
        Object v = p.get(k);
        if (v == null)
            return d;
        if (!(v instanceof Boolean))
            throw new Rpc(-32602, "Expected boolean: " + k);
        return (Boolean) v;
    }

    static byte[] binary(Object v) throws Rpc {
        return v instanceof byte[] ? (byte[]) v : text(v).getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, Object> error(Exception e) {
        if (e instanceof Rpc) {
            Rpc r = (Rpc) e;
            Map<String, Object> m = map("code", r.code, "message", r.getMessage());
            if (r.data != null)
                m.put("data", r.data);
            return m;
        }
        int code = -32003, errno = 5;
        if (e instanceof NoSuchFileException) {
            code = -32001;
            errno = 2;
        } else if (e instanceof AccessDeniedException) {
            code = -32002;
            errno = 13;
        } else if (e instanceof FileAlreadyExistsException)
            errno = 17;
        else if (e instanceof NotDirectoryException)
            errno = 20;
        else if (e instanceof DirectoryNotEmptyException)
            errno = 39;
        else if (e instanceof FileSystemLoopException)
            errno = 40;
        else if (e instanceof FileSystemException) {
            String reason = ((FileSystemException) e).getReason();
            if ("Not a directory".equals(reason))
                errno = 20;
            else if ("Is a directory".equals(reason))
                errno = 21;
            else if ("Too many levels of symbolic links".equals(reason))
                errno = 40;
        } else if (e instanceof IllegalArgumentException) {
            code = -32602;
            errno = 22;
        }
        return map("code", code, "message", String.valueOf(e.getMessage()), "data", map("os_errno", errno));
    }
}
