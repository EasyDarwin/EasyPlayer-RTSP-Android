package org.easydarwin.easyplayer.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Writes encoded snapshot bytes and only reports success for a non-empty file. */
public final class SnapshotFileWriter {
    public interface Encoder {
        boolean encode(OutputStream output) throws IOException;
    }

    private SnapshotFileWriter() {
    }

    public static boolean write(File destination, Encoder encoder) throws IOException {
        if (destination == null || encoder == null) return false;
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            return false;
        }

        try (FileOutputStream output = new FileOutputStream(destination)) {
            if (!encoder.encode(output)) return false;
            output.flush();
        }
        return destination.isFile() && destination.length() > 0;
    }
}
