package org.easydarwin.player.simpleplayer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import org.easydarwin.video.EasyPlayerClient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String RTSP_URL =
            "rtsp://admin:admin123@192.168.1.190:554/cam/realmonitor?channel=0&subtype=0&unicast=true&proto=Onvif";

    private EasyPlayerClient rtspPlayer;
    private TextureView textureView;
    private Button btnPlayToggle;
    private ScrollView eventScroll;
    private TextView eventLog;
    private ResultReceiver resultReceiver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    protected static final String TAG = "SimplePlayer";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.texture_view);
        // TextureView 不支持 background，黑底由布局 video_container 提供；opaque 避免未出画面前透底
        textureView.setOpaque(true);
        btnPlayToggle = findViewById(R.id.btn_play_toggle);
        eventScroll = findViewById(R.id.event_scroll);
        eventLog = findViewById(R.id.event_log);

        /**
         * ResultReceiver 回调 code 说明：
         * 1  连接中
         * 3  连接成功
         * 4  连接失败
         * 5  切换分辨率
         * 6  流中断
         * 7  重连中
         * 8  无数据
         * 9  超时
         * 10   连接退出
         * 900  视频分辨率
         * 901  解码方式
         * 902  首帧时间（data=毫秒）
         * 903  解码失败
         * 904  不支持该视频编码
         * 905  不支持该音频格式
         * 906  重连耗时（data=毫秒，count=第几次重连）
         * 907  播放成功率（仅成功时回调，data=成功率%，count=成功次数，total=总尝试次数）
         */
        resultReceiver = new ResultReceiver(new Handler()) {
            @Override
            protected void onReceiveResult(int code, Bundle data) {
                super.onReceiveResult(code, data);
                if (data != null) {
                    Log.i(TAG, "onReceiveResult: " + data.toString());
                    int mCode = data.getInt("code");
                    String msg = data.getString("msg");
                    appendEvent(String.format("code:%d  msg: %s", mCode, msg));
                }
            }
        };

        btnPlayToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rtspPlayer != null) {
                    stopPlayer("手动停止播放");
                } else {
                    startPlayer();
                }
            }
        });

        startPlayer();
    }

    private void startPlayer() {
        if (rtspPlayer != null) {
            return;
        }
        /**
         * @param Context        上下文
         * @param TextureView    画布
         * @param software       true=软解，false=硬解
         * @param ResultReceiver 消息回调
         */
        rtspPlayer = new EasyPlayerClient(this, textureView, false, resultReceiver);
        rtspPlayer.play(RTSP_URL);
        updatePlayButton();
        appendEvent("开始播放...");
    }

    private void stopPlayer(String reason) {
        if (rtspPlayer == null) {
            return;
        }
        rtspPlayer.stop();
        rtspPlayer = null;
        updatePlayButton();
        Log.i(TAG, reason);
        appendEvent(reason);
    }

    /** 播放中显示「停止播放」，停止后显示「重新播放」 */
    private void updatePlayButton() {
        btnPlayToggle.setText(rtspPlayer != null ? "停止播放" : "重新播放");
    }

    @Override
    protected void onDestroy() {
        if (rtspPlayer != null) {
            rtspPlayer.stop();
            rtspPlayer = null;
        }
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
