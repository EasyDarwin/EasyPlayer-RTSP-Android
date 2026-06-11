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
            "rtsp://admin:xf1234567@192.168.1.120:554/Streaming/Channels/101";

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
        textureView.setOpaque(true);
        btnPlayToggle = findViewById(R.id.btn_play_toggle);
        eventScroll = findViewById(R.id.event_scroll);
        eventLog = findViewById(R.id.event_log);

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
        rtspPlayer = new EasyPlayerClient(this, textureView, true, resultReceiver);
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
