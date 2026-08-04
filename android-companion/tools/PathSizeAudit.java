package com.thorium.preview.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** Reports exact apparent size and missing entries for an absolute-path list. */
public final class PathSizeAudit {
    private PathSizeAudit() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            System.err.println("usage: PathSizeAudit PATH_LIST");
            System.exit(2);
        }
        int listed = 0;
        int present = 0;
        int missing = 0;
        long bytes = 0L;
        try (BufferedReader reader = new BufferedReader(new FileReader(arguments[0]))) {
            String path;
            while ((path = reader.readLine()) != null) {
                path = path.trim();
                if (path.isEmpty()) continue;
                listed++;
                File file = new File(path);
                if (file.isFile()) {
                    present++;
                    bytes += file.length();
                } else {
                    missing++;
                    System.out.println("MISSING\t" + path);
                }
            }
        }
        System.out.println("SUMMARY\tlisted=" + listed + "\tpresent=" + present
                + "\tmissing=" + missing + "\tbytes=" + bytes);
    }
}
