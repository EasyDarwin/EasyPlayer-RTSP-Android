package org.easydarwin.player.simpleplayer;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.graphics.Typeface;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SwitchCompat;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.easydarwin.video.EasyPlayerClient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EasyPlayerClient rtspPlayer;
    private TextureView textureView;
    private Button btnPlayToggle;
    private SwitchCompat mSoftwareSwitch;
    private TextView tvDecodeHard;
    private TextView tvDecodeSoft;
    private ProgressBar loadingBar;
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
        mSoftwareSwitch = findViewById(R.id.switch_software_decode);
        tvDecodeHard = findViewById(R.id.tv_decode_hard);
        tvDecodeSoft = findViewById(R.id.tv_decode_soft);
        loadingBar = findViewById(R.id.loading);
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
         * 907  播放成功率（仅成功时回调，data=成功率%，count=成功次数，total=总尝试次数；收到后隐藏加载动画）
         */
        resultReceiver = new ResultReceiver(new Handler()) {
            @Override
            protected void onReceiveResult(int code, Bundle data) {
                super.onReceiveResult(code, data);
                if (data != null) {
                    Log.i(TAG, "onReceiveResult: " + data.toString());
                    int mCode = data.getInt("code");
                    String msg = data.getString("msg");
                    if (mCode == 907)  hideLoading();
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

        updateDecodeSwitchUi(mSoftwareSwitch.isChecked());
        mSoftwareSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                updateDecodeSwitchUi(isChecked);
                appendEvent(isChecked ? "解码方式: 软解" : "解码方式: 硬解");
                if (rtspPlayer != null) {
                    stopPlayer("切换解码方式，重新播放");
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
        boolean software = mSoftwareSwitch.isChecked();
        rtspPlayer = new EasyPlayerClient(this, textureView, software, resultReceiver);
        rtspPlayer.play(getString(R.string.rtsp_url));
        updatePlayButton();
        showLoading();
        appendEvent("开始播放(" + (software ? "软解" : "硬解") + ")...");
    }

    private void stopPlayer(String reason) {
        if (rtspPlayer == null) {
            return;
        }
        rtspPlayer.stop();
        rtspPlayer = null;
        hideLoading();
        updatePlayButton();
        Log.i(TAG, reason);
        appendEvent(reason);
    }

    /** 播放中显示「停止播放」，停止后显示「重新播放」 */
    private void updatePlayButton() {
        btnPlayToggle.setText(rtspPlayer != null ? "停止播放" : "重新播放");
    }

    /** 高亮当前选中的解码方式 */
    private void updateDecodeSwitchUi(boolean isSoft) {
        int active = ContextCompat.getColor(this, R.color.text_decode_active);
        int inactive = ContextCompat.getColor(this, R.color.text_decode_inactive);
        tvDecodeHard.setTextColor(isSoft ? inactive : active);
        tvDecodeSoft.setTextColor(isSoft ? active : inactive);
        tvDecodeHard.setTypeface(null, isSoft ? Typeface.NORMAL : Typeface.BOLD);
        tvDecodeSoft.setTypeface(null, isSoft ? Typeface.BOLD : Typeface.NORMAL);
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

    private void showLoading() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                loadingBar.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideLoading() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                loadingBar.setVisibility(View.GONE);
            }
        });
    }
}
