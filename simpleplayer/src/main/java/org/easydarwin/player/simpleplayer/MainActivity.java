package org.easydarwin.player.simpleplayer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import org.easydarwin.video.EasyPlayerClient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    /** 播放开始后多久自动销毁 client（毫秒） */
    private static final long DESTROY_DELAY_MS = 10*60_000;

    private EasyPlayerClient client;
    private ScrollView eventScroll;
    private TextView eventLog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private final Runnable destroyClientRunnable = new Runnable() {
        @Override
        public void run() {
            destroyClient("定时到达，自动停止播放");
        }
    };

    protected static final String TAG = "SimplePlayer";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextureView textureView = findViewById(R.id.texture_view);
        textureView.setOpaque(true);
        eventScroll = findViewById(R.id.event_scroll);
        eventLog = findViewById(R.id.event_log);

        /**
         * 1 连接中
         * 3 连接成功
         * 4  连接失败
         * 5  切换分辨率
         * 6  流中断
         * 7  重连中
         * 8  无数据
         * 9  超时
         * 10 连接退出
         *   900 视频分辨率
         *   901 解码方式
         *   902 首帧时间
         *   903 解码失败
         *   904 不支持该编码
         *   905 不支持该音频格式
         *   906 重连耗时（data=毫秒，count=第几次重连）
         *   907 播放成功率（仅成功时回调，data=成功率%，count=成功次数，total=总尝试次数）
         *
         */

        ResultReceiver mResultReceiver = new ResultReceiver(new Handler()) {
            @Override
            protected void onReceiveResult(int code, Bundle data) {
                super.onReceiveResult(code, data);
                if (data != null) {
                    Log.i(TAG, "onReceiveResult: " + data.toString());
                    int mCode = data.getInt("code");
                    String msg = data.getString("msg");
                    String line;
                    line = String.format("code:%d  msg: %s", mCode, msg);
                    appendEvent(line);
                }
            }
        };

        client = new EasyPlayerClient(this, textureView, mResultReceiver, null, null);
        client.play("rtsp://admin:xf1234567@192.168.1.120:554/Streaming/Channels/101");
        appendEvent("开始播放...");
        scheduleDestroyClient();
    }

    private void scheduleDestroyClient() {
        mainHandler.removeCallbacks(destroyClientRunnable);
        mainHandler.postDelayed(destroyClientRunnable, DESTROY_DELAY_MS);
        appendEvent("将在 " + (DESTROY_DELAY_MS / 1000) + " 秒后自动停止播放");
    }

    private void destroyClient(String reason) {
        mainHandler.removeCallbacks(destroyClientRunnable);
        if (client == null) {
            return;
        }
        client.stop();
        client = null;
        Log.i(TAG, reason);
        appendEvent(reason);
    }

    @Override
    protected void onDestroy() {
        destroyClient("Activity 销毁，停止播放");
        super.onDestroy();
    }

    private void appendEvent(final String line) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String entry = timeFormat.format(new Date()) + "  " + line + "\n";
                eventLog.append(entry);
                scrollEventLogToBottom();
            }
        });
    }

    private void scrollEventLogToBottom() {
        eventScroll.post(new Runnable() {
            @Override
            public void run() {
                eventScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
