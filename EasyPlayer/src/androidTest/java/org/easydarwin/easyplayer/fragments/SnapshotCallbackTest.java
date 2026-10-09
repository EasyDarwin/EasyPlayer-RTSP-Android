package org.easydarwin.easyplayer.fragments;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.ParcelFileDescriptor;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.view.Surface;

import org.easydarwin.easyplayer.activity.MediaFilesActivity;
import org.easydarwin.easyplayer.util.FileUtil;
import org.easydarwin.video.Client;
import org.easydarwin.video.EasyPlayerClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.Assert.*;

/** Exercises the real producer callback, Fragment receiver, TextureView and JPEG writer. */
@RunWith(AndroidJUnit4.class)
public class SnapshotCallbackTest {
    @Rule public ActivityTestRule<MediaFilesActivity> activityRule =
            new ActivityTestRule<>(MediaFilesActivity.class, true, false);

    public static class TestFragment extends PlayFragment {
        volatile boolean frameUpdated;
        @Override protected void startRending(SurfaceTexture texture) { /* No network in this test. */ }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { frameUpdated = true; }
        public ResultReceiver receiver() { return mResultReceiver; }
        public int videoWidth() { return mWidth; }
        public int videoHeight() { return mHeight; }
        public boolean available() { return mSurfaceView != null && mSurfaceView.isAvailable(); }
        public void drawFrame() {
            Surface surface = new Surface(mSurfaceView.getSurfaceTexture());
            Canvas canvas = surface.lockCanvas(null);
            canvas.drawColor(Color.CYAN);
            surface.unlockCanvasAndPost(canvas);
            surface.release();
        }
    }

    private TestFragment launchFragment() throws Exception {
        // MIUI blocks ActivityTestRule when the instrumented process is in the background.
        // Bring the app's launcher to the foreground through UiAutomation's shell identity.
        ParcelFileDescriptor launch = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("am start -W -n org.easydarwin.easyplayer/.activity.PlayListActivity");
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(launch)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) { /* Wait for the launcher to become visible. */ }
        }
        MediaFilesActivity activity = activityRule.launchActivity(new Intent()
                .putExtra("play_url", "snapshot-regression"));
        TestFragment fragment = new TestFragment();
        Bundle args = new Bundle();
        args.putString(PlayFragment.ARG_PARAM1, "snapshot-regression");
        fragment.setArguments(args);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activity.getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment).commit();
            activity.getSupportFragmentManager().executePendingTransactions();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        return fragment;
    }

    private static Client.FrameInfo frame(int width, int height) {
        Client.FrameInfo frame = new Client.FrameInfo();
        frame.width = (short) width;
        frame.height = (short) height;
        frame.codec = EasyPlayerClient.EASY_SDK_VIDEO_CODEC_H264;
        frame.buffer = new byte[512];
        frame.length = frame.buffer.length;
        return frame;
    }

    @Test public void newResolutionCallbacksEnableActualJpegCapture() throws Exception {
        TestFragment fragment = launchFragment();
        EasyPlayerClient producer = new EasyPlayerClient(activityRule.getActivity().getApplicationContext(),
                (Surface) null, fragment.receiver());
        Field waiting = EasyPlayerClient.class.getDeclaredField("mWaitingKeyFrame");
        waiting.setAccessible(true);
        waiting.setBoolean(producer, true);
        producer.onRTSPSourceCallBack1(0, 0, Client.EASY_SDK_VIDEO_FRAME_FLAG, frame(1920, 1080));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1920, fragment.videoWidth());
        assertEquals(1080, fragment.videoHeight());

        waiting.setBoolean(producer, false);
        producer.onRTSPSourceCallBack1(0, 0, Client.EASY_SDK_VIDEO_FRAME_FLAG, frame(640, 360));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(640, fragment.videoWidth());
        assertEquals(360, fragment.videoHeight());

        for (int i = 0; i < 100 && !fragment.available(); i++) Thread.sleep(50);
        assertTrue("TextureView must be available", fragment.available());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(fragment::drawFrame);
        for (int i = 0; i < 100 && !fragment.frameUpdated; i++) Thread.sleep(50);
        assertTrue("TextureView must receive the rendered frame", fragment.frameUpdated);
        File picture = FileUtil.getPictureName(activityRule.getActivity(), "snapshot-regression");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertTrue(fragment.takePicture(picture.getPath())));
        Bitmap decoded = BitmapFactory.decodeFile(picture.getPath());
        assertNotNull("Saved JPEG must be decodable", decoded);
        assertEquals(640, decoded.getWidth());
        assertEquals(360, decoded.getHeight());
        int pixel = decoded.getPixel(320, 180);
        assertTrue("Snapshot must contain the drawn frame", Color.green(pixel) > 200 && Color.blue(pixel) > 200);
        decoded.recycle();
        picture.delete();
    }

    @Test public void legacyResolutionAndInvalidUpdatesAreHandled() throws Exception {
        TestFragment fragment = launchFragment();
        Bundle size = new Bundle();
        size.putInt(EasyPlayerClient.EXTRA_VIDEO_WIDTH, 800);
        size.putInt(EasyPlayerClient.EXTRA_VIDEO_HEIGHT, 600);
        fragment.receiver().send(EasyPlayerClient.RESULT_VIDEO_SIZE, size);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(800, fragment.videoWidth());
        assertEquals(600, fragment.videoHeight());
        Bundle invalid = new Bundle();
        invalid.putInt("code", 9003);
        fragment.receiver().send(0, invalid);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(800, fragment.videoWidth());
        assertEquals(600, fragment.videoHeight());
    }
}
