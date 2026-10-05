package com.langqi.fakegps;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** File operations run on the ViewModel's worker, never on the UI thread. */
final class RouteRepository {
    private final File directory;

    RouteRepository(File directory) throws IOException {
        this.directory = directory.getCanonicalFile();
        if (!this.directory.isDirectory() && !this.directory.mkdirs()) {
            throw new IOException("无法创建路线目录");
        }
    }

    List<File> files() throws IOException {
        File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".kml"));
        if (files == null) throw new IOException("无法读取路线目录");
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return Arrays.asList(files);
    }

    File save(InputStream input, String displayName, File replacement) throws Exception {
        File target = replacement == null ? uniqueFile(displayName) : replacement.getCanonicalFile();
        if (!directory.equals(target.getParentFile())) throw new IOException("无效的路线存储位置");
        if (replacement != null && !target.isFile()) throw new IOException("待替换的路线已不存在");
        // Same filesystem permits atomic rename; .tmp is never enumerated as a route.
        File temporary = File.createTempFile("route_", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > KmlParser.MAX_BYTES) throw new IOException("KML 文件不能超过 5 MB");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            try (InputStream validation = new FileInputStream(temporary)) {
                KmlParser.read(validation);
            }
            // If atomic replacement is unavailable, leave the previous file intact and report failure.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    void delete(File file) throws IOException {
        File target = file.getCanonicalFile();
        if (!directory.equals(target.getParentFile())) throw new IOException("无效的路线存储位置");
        Files.delete(target.toPath());
    }

    private File uniqueFile(String displayName) {
        String name = displayName == null ? "route" : displayName.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.toLowerCase(Locale.ROOT).endsWith(".kml")) name = name.substring(0, name.length() - 4);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "route";
        if (name.length() > 80) name = name.substring(0, 80);
        File target = new File(directory, name + ".kml");
        for (int suffix = 2; target.exists(); suffix++) {
            target = new File(directory, name + " (" + suffix + ").kml");
        }
        return target;
    }
}
