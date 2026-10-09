package org.easydarwin.easyplayer.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MediaFileActionsTest {
    @Test public void privateSnapshotUsesReadableContentUriAndReadGrant() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        File file = FileUtil.getPictureName(context, "share-regression");
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output));
        }
        bitmap.recycle();
        try {
            Intent view = MediaFileActions.createIntent(context, file, false);
            assertEquals(Intent.ACTION_VIEW, view.getAction());
            assertEquals("image/jpeg", view.getType());
            assertEquals("content", view.getData().getScheme());
            assertTrue((view.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            try (InputStream input = context.getContentResolver().openInputStream(view.getData())) {
                assertNotNull(input);
                assertEquals(255, input.read()); // JPEG SOI
            }
            Intent share = MediaFileActions.createIntent(context, file, true);
            assertEquals(Intent.ACTION_SEND, share.getAction());
            Uri uri = share.getParcelableExtra(Intent.EXTRA_STREAM);
            assertEquals(view.getData(), uri);
            assertEquals(uri, share.getClipData().getItemAt(0).getUri());
        } finally {
            file.delete();
        }
    }
}
