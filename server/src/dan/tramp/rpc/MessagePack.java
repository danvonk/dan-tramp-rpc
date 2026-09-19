// SPDX-License-Identifier: GPL-3.0-or-later
package dan.tramp.rpc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Small, bounded MessagePack codec for the RPC protocol (no external
 * dependencies).
 */
final class MessagePack {
    static final int MAX = 100 * 1024 * 1024;

    static Object decode(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        Object value = read(in, 0);
        if (in.available() != 0)
            throw new IOException("Trailing MessagePack data");
        return value;
    }

    static int length(DataInputStream in, int n) throws IOException {
        if (n < 0 || n > MAX || n > in.available())
            throw new IOException("Invalid MessagePack length");
        return n;
    }

    static byte[] bytes(DataInputStream in, int n) throws IOException {
        byte[] b = new byte[length(in, n)];
        in.readFully(b);
        return b;
    }

    static Object sequence(DataInputStream in, int n, boolean map, int depth) throws IOException {
        length(in, n);
        if (map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                Object key = read(in, depth + 1);
                if (!(key instanceof String))
                    throw new IOException("Map key must be a string");
                result.put((String) key, read(in, depth + 1));
            }
            return result;
        }
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < n; i++)
            result.add(read(in, depth + 1));
        return result;
    }

    static Object read(DataInputStream in, int depth) throws IOException {
        if (depth > 64)
            throw new IOException("MessagePack nesting limit");
        int c = in.readUnsignedByte();
        if (c < 128)
            return (long) c;
        if (c >= 224)
            return (long) (byte) c;
        if ((c & 224) == 160)
            return new String(bytes(in, c & 31), StandardCharsets.UTF_8);
        if ((c & 240) == 144)
            return sequence(in, c & 15, false, depth);
        if ((c & 240) == 128)
            return sequence(in, c & 15, true, depth);
        switch (c) {
            case 192:
                return null;
            case 194:
                return false;
            case 195:
                return true;
            case 196:
                return bytes(in, in.readUnsignedByte());
            case 197:
                return bytes(in, in.readUnsignedShort());
            case 198:
                return bytes(in, in.readInt());
            case 202:
                return (double) in.readFloat();
            case 203:
                return in.readDouble();
            case 204:
                return (long) in.readUnsignedByte();
            case 205:
                return (long) in.readUnsignedShort();
            case 206:
                return Integer.toUnsignedLong(in.readInt());
            case 207:
                return in.readLong();
            case 208:
                return (long) in.readByte();
            case 209:
                return (long) in.readShort();
            case 210:
                return (long) in.readInt();
            case 211:
                return in.readLong();
            case 217:
                return new String(bytes(in, in.readUnsignedByte()), StandardCharsets.UTF_8);
            case 218:
                return new String(bytes(in, in.readUnsignedShort()), StandardCharsets.UTF_8);
            case 219:
                return new String(bytes(in, in.readInt()), StandardCharsets.UTF_8);
            case 220:
                return sequence(in, in.readUnsignedShort(), false, depth);
            case 221:
                return sequence(in, in.readInt(), false, depth);
            case 222:
                return sequence(in, in.readUnsignedShort(), true, depth);
            case 223:
                return sequence(in, in.readInt(), true, depth);
            default:
                throw new IOException("Unsupported MessagePack tag " + c);
        }
    }

    static byte[] encode(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        write(new DataOutputStream(bytes), value);
        return bytes.toByteArray();
    }

    static void write(DataOutputStream out, Object v) throws IOException {
        if (v == null) {
            out.writeByte(192);
            return;
        }
        if (v instanceof Boolean) {
            out.writeByte((Boolean) v ? 195 : 194);
            return;
        }
        if (v instanceof Float || v instanceof Double) {
            out.writeByte(203);
            out.writeDouble(((Number) v).doubleValue());
            return;
        }
        if (v instanceof Number) {
            out.writeByte(211);
            out.writeLong(((Number) v).longValue());
            return;
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            out.writeByte(198);
            out.writeInt(b.length);
            out.write(b);
            return;
        }
        if (v instanceof String) {
            byte[] b = ((String) v).getBytes(StandardCharsets.UTF_8);
            out.writeByte(219);
            out.writeInt(b.length);
            out.write(b);
            return;
        }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            out.writeByte(223);
            out.writeInt(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                write(out, e.getKey());
                write(out, e.getValue());
            }
            return;
        }
        if (v instanceof Collection) {
            Collection<?> a = (Collection<?>) v;
            out.writeByte(221);
            out.writeInt(a.size());
            for (Object e : a)
                write(out, e);
            return;
        }
        throw new IOException("Cannot encode " + v.getClass());
    }
}
