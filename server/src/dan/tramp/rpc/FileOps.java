// SPDX-License-Identifier: GPL-3.0-or-later
package dan.tramp.rpc;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import static dan.tramp.rpc.Rpc.*;

final class FileOps {
    static final int CHUNK = 16 * 1024 * 1024;
    static final LinkOption[] NOFOLLOW = { LinkOption.NOFOLLOW_LINKS };

    // OpenJDK's UnixPath preserves arbitrary Unix filename bytes. The launcher
    // opens only this package; normal UTF-8 paths also work without that flag.
    static Path rawPath(byte[] b) throws Exception {
        if (Arrays.equals(b, new String(b, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8)))
            return Paths.get(new String(b, StandardCharsets.UTF_8));
        Class<?> fs = Class.forName("sun.nio.fs.UnixFileSystem");
        Constructor<?> c = Class.forName("sun.nio.fs.UnixPath").getDeclaredConstructor(fs, byte[].class);
        c.setAccessible(true);
        return (Path) c.newInstance(FileSystems.getDefault(), b);
    }

    static byte[] pathBytes(Path p) throws Exception {
        try {
            Method m = p.getClass().getDeclaredMethod("asByteArray");
            m.setAccessible(true);
            return (byte[]) m.invoke(p);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return p.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    static String expand(String s) throws Exception {
        if (!s.startsWith("~"))
            return s;
        int slash = s.indexOf('/');
        String user = s.substring(1, slash < 0 ? s.length() : slash);
        String home = System.getProperty("user.home");
        if (!user.isEmpty()) {
            Process p = new ProcessBuilder("getent", "passwd", user).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return s;
            }
            String[] fields = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim().split(":");
            if (fields.length < 6)
                return s;
            home = fields[5];
        }
        return home + (slash < 0 ? "" : s.substring(slash));
    }

    static Path path(Object v) throws Exception {
        byte[] b = binary(v);
        if (b.length > 0 && b[0] == '~') {
            int n = 0;
            while (n < b.length && b[n] != '/')
                n++;
            byte[] prefix = expand(new String(b, 0, n, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            byte[] all = Arrays.copyOf(prefix, prefix.length + b.length - n);
            System.arraycopy(b, n, all, prefix.length, b.length - n);
            b = all;
        }
        for (byte c : b)
            if (c == 0)
                throw new Rpc(-32602, "NUL in path");
        return rawPath(b);
    }

    static Path path(Map<String, Object> p, String key) throws Exception {
        return path(p.get(key));
    }

    static String type(int mode) {
        switch (mode & 0170000) {
            case 0100000:
                return "file";
            case 0040000:
                return "directory";
            case 0120000:
                return "symlink";
            case 0020000:
                return "chardevice";
            case 0060000:
                return "blockdevice";
            case 0010000:
                return "fifo";
            case 0140000:
                return "socket";
            default:
                return "unknown";
        }
    }

    static Map<String, Object> stat(Path p, boolean nofollow) throws Exception {
        Map<String, Object> a = Files.readAttributes(p, "unix:*", nofollow ? NOFOLLOW : new LinkOption[0]);
        int mode = (Integer) a.get("mode");
        Map<String, Object> r = map("type", type(mode), "mode", mode, "nlinks", a.get("nlink"), "uid", a.get("uid"),
                "gid", a.get("gid"), "size", a.get("size"), "inode", a.get("ino"), "dev", a.get("dev"), "uname",
                ((UserPrincipal) a.get("owner")).getName(), "gname", ((GroupPrincipal) a.get("group")).getName());
        r.put("atime", ((FileTime) a.get("lastAccessTime")).to(TimeUnit.SECONDS));
        r.put("mtime", ((FileTime) a.get("lastModifiedTime")).to(TimeUnit.SECONDS));
        r.put("ctime", ((FileTime) a.get("ctime")).to(TimeUnit.SECONDS));
        if (type(mode).equals("symlink"))
            r.put("link_target", pathBytes(Files.readSymbolicLink(p)));
        return r;
    }

    static Object handle(String method, Map<String, Object> p) throws Exception {
        switch (method) {
            case "file.stat":
                try {
                    return stat(path(p, "path"), bool(p, "lstat", false));
                } catch (NoSuchFileException e) {
                    return null;
                }
            case "file.truename":
                return pathBytes(path(p, "path").toRealPath());
            case "dir.list": {
                Path dir = path(p, "path");
                List<Object> entries = new ArrayList<>();
                boolean attrs = bool(p, "include_attrs", false), hidden = bool(p, "include_hidden", true);
                if (hidden) {
                    entries.add(entry(dir.resolve("."), new byte[] { '.' }, attrs));
                    entries.add(entry(dir.resolve(".."), new byte[] { '.', '.' }, attrs));
                }
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path f : ds) {
                        byte[] n = pathBytes(f.getFileName());
                        if (!hidden && n.length > 0 && n[0] == '.')
                            continue;
                        entries.add(entry(f, n, attrs));
                    }
                }
                return entries;
            }
            case "dir.create": {
                Path f = path(p, "path");
                // An existing directory is a successful no-op for mkdir -p.
                // The client uses false to preserve Emacs's return value.
                if (bool(p, "parents", false) && Files.isDirectory(f))
                    return false;
                if (bool(p, "parents", false))
                    Files.createDirectories(f);
                else
                    Files.createDirectory(f);
                if (p.containsKey("mode"))
                    Files.setAttribute(f, "unix:mode", (int) num(p, "mode", 0755));
                return true;
            }
            case "dir.remove": {
                Path f = path(p, "path");
                if (bool(p, "recursive", false))
                    remove(f);
                else
                    Files.delete(f);
                return true;
            }
            case "file.read": {
                long offset = num(p, "offset", 0), length = num(p, "length", CHUNK);
                if (offset < 0 || length < 0 || length > CHUNK)
                    throw new Rpc(-32602, "Invalid read range");
                byte[] data;
                long size;
                try (SeekableByteChannel f = Files.newByteChannel(path(p, "path"))) {
                    size = f.size();
                    if (!p.containsKey("length") && size - offset > CHUNK)
                        throw new Rpc(-32602, "File exceeds maximum read chunk");
                    f.position(offset);
                    ByteBuffer b = ByteBuffer.allocate((int) Math.min(length, Math.max(0, size - offset)));
                    while (b.hasRemaining() && f.read(b) >= 0) {
                    }
                    data = Arrays.copyOf(b.array(), b.position());
                }
                int read = data.length;
                boolean compress = bool(p, "compress", false);
                if (compress) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    try (DeflaterOutputStream z = new DeflaterOutputStream(b)) {
                        z.write(data);
                    }
                    data = b.toByteArray();
                }
                return map("content", data, "size", read, "file_size", size, "compressed", compress, "compression",
                        compress ? "zlib" : null);
            }
            case "file.write": {
                Path f = path(p, "path");
                byte[] b = binary(p.get("content"));
                boolean append = bool(p, "append", false);
                long offset = num(p, "offset", 0);
                if (offset < 0)
                    throw new Rpc(-32602, "Negative offset");
                Set<OpenOption> opts = new HashSet<>(
                        Arrays.asList(StandardOpenOption.CREATE, StandardOpenOption.WRITE));
                if (append)
                    opts.add(StandardOpenOption.APPEND);
                else if (!p.containsKey("offset"))
                    opts.add(StandardOpenOption.TRUNCATE_EXISTING);
                try (SeekableByteChannel c = Files.newByteChannel(f, opts)) {
                    if (!append)
                        c.position(offset);
                    ByteBuffer buf = ByteBuffer.wrap(b);
                    while (buf.hasRemaining())
                        c.write(buf);
                }
                if (p.containsKey("mode"))
                    Files.setAttribute(f, "unix:mode", (int) num(p, "mode", 0644));
                return map("written", b.length);
            }
            case "file.delete": {
                Path f = path(p, "path");
                if (Files.isDirectory(f, NOFOLLOW))
                    throw new FileSystemException(f.toString(), null, "Is a directory");
                if (bool(p, "force", false))
                    return Files.deleteIfExists(f);
                Files.delete(f);
                return true;
            }
            case "file.rename": {
                Path src = path(p, "src"), dest = path(p, "dest");
                if (bool(p, "overwrite", false))
                    Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
                else {
                    if (Files.exists(dest, NOFOLLOW))
                        throw new FileAlreadyExistsException(dest.toString());
                    Files.move(src, dest);
                }
                return true;
            }
            case "file.copy": {
                Path src = path(p, "src"), dest = path(p, "dest");
                if (!bool(p, "exact_dest", false) && Files.isDirectory(dest))
                    dest = dest.resolve(src.getFileName());
                if (Files.exists(dest) && Files.isSameFile(src, dest))
                    throw new FileSystemException(src.toString(), dest.toString(), "Same file");
                if (Files.isDirectory(src, NOFOLLOW) && realAncestor(dest).startsWith(src.toRealPath()))
                    throw new Rpc(-32602, "Cannot copy directory into itself");
                return map("copied", copy(src, dest, p, true));
            }
            case "file.set_modes":
                Files.setAttribute(path(p, "path"), "unix:mode", (int) num(p, "mode", 0));
                return true;
            case "file.set_times": {
                long m = num(p, "mtime", 0);
                Files.getFileAttributeView(path(p, "path"), BasicFileAttributeView.class,
                        bool(p, "nofollow", false) ? NOFOLLOW : new LinkOption[0])
                        .setTimes(FileTime.from(m, TimeUnit.SECONDS),
                                FileTime.from(num(p, "atime", m), TimeUnit.SECONDS), null);
                return true;
            }
            case "file.make_symlink": {
                Path link = path(p, "link_path");
                if (Files.isDirectory(link, NOFOLLOW))
                    throw new FileAlreadyExistsException(link.toString());
                Files.deleteIfExists(link);
                Files.createSymbolicLink(link, path(p, "target"));
                return true;
            }
            case "file.make_hardlink":
                Files.createLink(path(p, "dest"), path(p, "src"));
                return true;
            case "file.chown": {
                Path f = path(p, "path");
                for (String key : Arrays.asList("uid", "gid")) {
                    long id = num(p, key, -1);
                    if (id < -1 || id > Integer.MAX_VALUE)
                        throw new Rpc(-32602, "Invalid " + key);
                    if (id >= 0)
                        Files.setAttribute(f, "unix:" + key, (int) id);
                }
                return true;
            }
            default:
                throw new Rpc(-32601, "Unknown method: " + method);
        }
    }

    static Object entry(Path f, byte[] name, boolean attrs) throws Exception {
        Map<String, Object> a = stat(f, true);
        String type = (String) a.get("type");
        if (!attrs && Files.isDirectory(f))
            type = "directory";
        Map<String, Object> r = map("name", name, "type", type);
        if (attrs)
            r.put("attrs", a);
        return r;
    }

    static void remove(Path p) throws IOException {
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null)
                    throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static Path realAncestor(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute))
            return absolute.toRealPath();
        Path parent = absolute.getParent();
        if (parent == null)
            return absolute;
        return realAncestor(parent).resolve(absolute.getFileName()).normalize();
    }

    static long copy(Path src, Path dest, Map<String, Object> p, boolean top) throws Exception {
        boolean dir = Files.isDirectory(src, NOFOLLOW);
        long size = 0;
        if (dir) {
            if (Files.exists(dest, NOFOLLOW)) {
                if ((!top && !bool(p, "merge_existing_directories", true)) || !Files.isDirectory(dest))
                    throw new FileAlreadyExistsException(dest.toString());
            } else
                Files.createDirectories(dest);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
                for (Path child : ds)
                    size += copy(child, dest.resolve(child.getFileName()), p, false);
            }
        } else {
            List<CopyOption> opts = new ArrayList<>();
            opts.add(LinkOption.NOFOLLOW_LINKS);
            if (bool(p, "overwrite", false))
                opts.add(StandardCopyOption.REPLACE_EXISTING);
            Files.copy(src, dest, opts.toArray(new CopyOption[0]));
            size = Files.isSymbolicLink(src) ? 0 : Files.size(src);
        }
        if (!Files.isSymbolicLink(src)) {
            if (bool(p, "preserve", false) || bool(p, "preserve_permissions", false))
                Files.setAttribute(dest, "unix:mode", Files.getAttribute(src, "unix:mode"));
            if (bool(p, "preserve", false) || bool(p, "preserve_times", false)) {
                BasicFileAttributes a = Files.readAttributes(src, BasicFileAttributes.class);
                Files.getFileAttributeView(dest, BasicFileAttributeView.class).setTimes(a.lastModifiedTime(),
                        a.lastAccessTime(), null);
            }
        }
        return size;
    }
}
