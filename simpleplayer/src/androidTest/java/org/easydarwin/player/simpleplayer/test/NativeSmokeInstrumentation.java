package org.easydarwin.player.simpleplayer.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import org.easydarwin.audio.AudioCodec;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.video.EasyMuxer2;
import org.easydarwin.video.VideoCodec;
import org.easydarwin.video.EasyPlayerClient;
import org.easydarwin.player.simpleplayer.MainActivity;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** On-device regression checks without a third-party test runner dependency. */
public class NativeSmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private byte[] asset(String name) throws Exception {
        try (InputStream input = getContext().getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int count;
            while ((count = input.read(buf)) > 0) output.write(buf, 0, count);
            return output.toByteArray();
        }
    }
    private void audio() throws Exception {
        for (int codec : new int[]{0x10006, 0x10007, 0x1100b, 0x15002}) {
            long handle = AudioCodec.create(codec, 8000, 1, 16);
            require(handle != 0, "audio create " + codec);
            try {
                byte[] input = codec == 0x15002 ? asset("tone.aac") : new byte[16];
                if (codec == 0x10006) Arrays.fill(input, (byte)0xff);
                if (codec == 0x10007) Arrays.fill(input, (byte)0xd5);
                byte[] pcm = new byte[16000];
                int[] size = {pcm.length};
                require(AudioCodec.decode(handle, input, 0, input.length, pcm, size) == 0, "audio decode " + codec);
                require(size[0] > 0 && size[0] <= pcm.length && size[0] % 2 == 0, "PCM length");
                if (codec == 0x10006) {
                    require(size[0] == 32, "G711U sample count");
                    for (int i = 0; i < size[0]; i++) require(pcm[i] == 0, "G711U silence");
                }
                if (codec == 0x10007) {
                    require(size[0] == 32, "G711A sample count");
                    for (int i = 0; i < size[0]; i += 2) require(pcm[i] == 8 && pcm[i + 1] == 0, "G711A sample");
                }
                if (codec == 0x1100b) require(size[0] == 64, "G726 sample count");
            } finally { AudioCodec.close(handle); }
        }
    }
    private void video() throws Exception {
        for (int codec : new int[]{0, 1}) {
            byte[] input = asset(codec == 0 ? "red.h264" : "red.hevc");
            for (int attempt = 0; attempt < 20; attempt++) {
                VideoCodec decoder = new VideoCodec();
                require(decoder.decoder_create(null, codec) == 0, "video create");
                try {
                    int[] dimensions = new int[2];
                    ByteBuffer frame = decoder.decoder_decodeYUV(input, 0, input.length, dimensions);
                    require(frame != null, "video produced no frame");
                    try {
                        require(dimensions[0] == 64 && dimensions[1] == 48, "video dimensions");
                        require(frame.capacity() == 64 * 48 * 3 / 2, "I420 capacity");
                        require(Math.abs((frame.get(0) & 255) - 81) <= 4, "red luma");
                        require(Math.abs((frame.get(64 * 48) & 255) - 90) <= 4, "red U");
                        require(Math.abs((frame.get(64 * 48 * 5 / 4) & 255) - 240) <= 4, "red V");
                    } finally { decoder.decoder_releaseBuffer(frame); }
                } finally { decoder.decoder_close(); decoder.decoder_close(); }
            }
        }
    }
    private void yuv() {
        byte[] nv12 = new byte[24];
        for (int i = 0; i < 16; i++) nv12[i] = (byte)(16 + i);
        for (int i = 0; i < 4; i++) { nv12[16 + i * 2] = (byte)(40 + i); nv12[17 + i * 2] = (byte)(80 + i); }
        JNIUtil.yuvConvert(nv12, 4, 4, 4);
        for (int i = 0; i < 16; i++) require(nv12[i] == 16 + i, "Y unchanged");
        for (int i = 0; i < 4; i++) { require(nv12[16 + i] == 40 + i, "U plane"); require(nv12[20 + i] == 80 + i, "V plane"); }
        boolean rejected = false;
        try { JNIUtil.yuvConvert(new byte[2], 4, 4, 4); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "undersized YUV buffer accepted");
    }
    private byte[] extradata(byte[] input, int codec) throws Exception {
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        int start = -1;
        for (int i = 0; i <= input.length - 3; i++) {
            if (input[i] == 0 && input[i + 1] == 0 && input[i + 2] == 1) {
                int prefix = i > 0 && input[i - 1] == 0 ? i - 1 : i;
                if (start >= 0) copyParameterSet(extra, input, start, prefix, codec);
                start = prefix;
                i += 2;
            }
        }
        if (start >= 0) copyParameterSet(extra, input, start, input.length, codec);
        return extra.toByteArray();
    }
    private void copyParameterSet(ByteArrayOutputStream out, byte[] bytes, int start, int end, int codec) {
        int header = start + (bytes[start + 2] == 1 ? 3 : 4);
        int type = codec == 0 ? bytes[header] & 31 : (bytes[header] >> 1) & 63;
        if ((codec == 0 && (type == 7 || type == 8)) || (codec == 1 && type >= 32 && type <= 34))
            out.write(bytes, start, end - start);
    }
    private void recording() throws Exception {
        byte[] pcm = new byte[1600]; // 100 ms, mono 8 kHz S16.
        for (int i = 0; i < pcm.length / 2; i++) {
            short sample = (short)(12000 * Math.sin(2 * Math.PI * 440 * i / 8000));
            pcm[i * 2] = (byte)sample;
            pcm[i * 2 + 1] = (byte)(sample >> 8);
        }
        for (int codec : new int[]{0, 1}) {
            byte[] frame = asset(codec == 0 ? "red.h264" : "red.hevc");
            File file = new File(getTargetContext().getFilesDir(), "native-test-" + codec + ".mp4");
            EasyMuxer2 recorder = new EasyMuxer2();
            require(recorder.create(file.getPath(), codec, 64, 48, extradata(frame, codec), 8000, 1) == 0, "record create");
            try {
                for (int i = 0; i < 20; i++) {
                    require(recorder.writeFrame(0, frame, 0, frame.length, 1000 + i * 100) == 0, "record video");
                    require(recorder.writeFrame(1, pcm, 0, pcm.length, 1000 + i * 100) == 0, "record audio");
                }
            } finally { recorder.close(); recorder.close(); }
            require(file.length() > frame.length * 10, "recording too small");
            Log.i("NativeSmoke", "recording=" + file);
        }
    }
    private void livePlayback() throws Exception {
        if (arguments == null || arguments.getString("rtsp_url") == null) return;
        boolean software = !"false".equals(arguments.getString("software"));
        Intent intent = new Intent(getTargetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("rtsp_url", arguments.getString("rtsp_url"));
        intent.putExtra("software", software);
        Activity activity = startActivitySync(intent);
        try {
            Field logField = MainActivity.class.getDeclaredField("eventLog");
            logField.setAccessible(true);
            final TextView log = (TextView)logField.get(activity);
            final String[] text = {""};
            long deadline = SystemClock.elapsedRealtime() + 30000;
            do {
                runOnMainSync(() -> text[0] = log.getText().toString());
                if (text[0].contains("code:907")) break;
                SystemClock.sleep(250);
            } while (SystemClock.elapsedRealtime() < deadline);
            require(text[0].contains("code:907"), "Live playback did not succeed: " + text[0]);
            require(text[0].contains(software ? "解码方式: 软解" : "解码方式: 硬解"), "Unexpected decode mode");
            Field playerField = MainActivity.class.getDeclaredField("rtspPlayer");
            playerField.setAccessible(true);
            EasyPlayerClient player = (EasyPlayerClient)playerField.get(activity);
            File file = new File(getTargetContext().getFilesDir(), software ? "live-test-soft.mp4" : "live-test-hard.mp4");
            require(!file.exists() || file.delete(), "Could not remove previous live recording");
            player.startRecord(file.getPath());
            require(player.isRecording(), "Live recording did not start");
            // Recording starts at the next keyframe, which can arrive several seconds later.
            deadline = SystemClock.elapsedRealtime() + 30000;
            while (file.length() == 0 && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(250);
            require(file.length() > 0, "No keyframe arrived for live recording");
            SystemClock.sleep(10000);
            player.stopRecord();
            require(file.length() > 10000, "Live recording contains no video");
            Log.i("NativeSmoke", "PASS live playback and recording: " + file);
        } finally {
            runOnMainSync(activity::finish);
            waitForIdleSync();
        }
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
            result.putLong("page_size", pageSize);
            if (arguments != null && "true".equals(arguments.getString("require_16k")))
                require(pageSize == 16384, "Expected 16 KB emulator, got " + pageSize);
            yuv(); audio(); video(); recording(); livePlayback();
            result.putString("stream", "PASS: YUV conversion, G711A/U, G726, AAC, H264/H265 decode, create/close stress, MP4 recording"
                + (arguments != null && arguments.getString("rtsp_url") != null ? ", live RTSP playback and recording" : "") + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            Log.e("NativeSmoke", "FAIL", failure);
            result.putString("stream", "FAIL: " + failure + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
