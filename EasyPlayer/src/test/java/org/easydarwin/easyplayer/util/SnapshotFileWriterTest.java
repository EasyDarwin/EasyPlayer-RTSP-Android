package org.easydarwin.easyplayer.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SnapshotFileWriterTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesEncodedSnapshotAndConfirmsNonEmptyFile() throws Exception {
        File destination = new File(temporaryFolder.getRoot(), "nested/snapshot.jpg");

        boolean saved = SnapshotFileWriter.write(destination, new SnapshotFileWriter.Encoder() {
            @Override
            public boolean encode(OutputStream output) throws IOException {
                output.write(new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9});
                return true;
            }
        });

        assertTrue(saved);
        assertTrue(destination.isFile());
        assertEquals(6, destination.length());
    }

    @Test
    public void reportsFailureWhenEncoderDeclinesSnapshot() throws Exception {
        File destination = new File(temporaryFolder.getRoot(), "snapshot.jpg");

        boolean saved = SnapshotFileWriter.write(destination, new SnapshotFileWriter.Encoder() {
            @Override
            public boolean encode(OutputStream output) {
                return false;
            }
        });

        assertFalse(saved);
    }

    @Test
    public void propagatesWriteFailure() throws Exception {
        File destination = new File(temporaryFolder.getRoot(), "snapshot.jpg");

        try {
            SnapshotFileWriter.write(destination, new SnapshotFileWriter.Encoder() {
                @Override
                public boolean encode(OutputStream output) throws IOException {
                    throw new IOException("simulated disk error");
                }
            });
        } catch (IOException expected) {
            assertEquals("simulated disk error", expected.getMessage());
            return;
        }
        throw new AssertionError("write failure should be propagated");
    }
}
