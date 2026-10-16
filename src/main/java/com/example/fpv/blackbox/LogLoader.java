package com.example.fpv.blackbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LogLoader {

    public static List<FlightData> loadDirectory(Path dir) throws IOException {
        List<FlightData> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".bbl") || n.endsWith(".bfl");
            }).sorted().toList();
            int fileIndex = 0;
            for (Path file : files) {
                out.addAll(loadFile(file, fileIndex));
                fileIndex++;
            }
        }
        return out;
    }

    public static List<FlightData> loadFile(Path file, int fileIndex) {
        List<FlightData> out = new ArrayList<>();
        try {
            byte[] data = Files.readAllBytes(file);
            List<int[]> bounds = HeaderParser.splitSessions(data);
            for (int s = 0; s < bounds.size(); s++) {
                int[] b = bounds.get(s);
                byte[] slice = new byte[b[1] - b[0]];
                System.arraycopy(data, b[0], slice, 0, slice.length);
                try {
                    FlightData fd = decodeSession(slice, file.getFileName().toString(), fileIndex, s);
                    out.add(fd);
                } catch (BlackboxException e) {
                    System.err.println("  [!] " + file.getFileName() + " session " + (s + 1) + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("  [!] δεν μπόρεσε να διαβαστεί το " + file + ": " + e.getMessage());
        }
        return out;
    }

    public static FlightData decodeSession(byte[] sessionBytes, String sourceName, int fileIndex, int sessionIndex) {
        Header header = HeaderParser.parse(sessionBytes);
        FlightData fd = new FlightData(header, sourceName, fileIndex, sessionIndex, new DecodeStats());
        new Decoder(fd, sessionBytes).run();
        return fd;
    }
}