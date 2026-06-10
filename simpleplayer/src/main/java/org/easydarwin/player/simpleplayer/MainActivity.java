package org.easydarwin.player.simpleplayer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.TextureView;
import android.widget.TextView;

import org.easydarwin.video.EasyPlayerClient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EasyPlayerClient client;
    private TextView eventLog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    protected static final String TAG = "SimplePlayer";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextureView textureView = findViewById(R.id.texture_view);
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
                    String line = String.format("code:%d  msg: %s",mCode,msg);
                    appendEvent(line);
                }
            }
        };

        client = new EasyPlayerClient(this, textureView, mResultReceiver, null, null);
        client.play("rtsp://admin:xf1234567@192.168.1.120:554/Streaming/Channels/101");
        appendEvent("开始播放...");

    }

    private void appendEvent(final String line) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String entry = timeFormat.format(new Date()) + "  " + line + "\n";
                eventLog.append(entry);
            }
        });
    }
}
