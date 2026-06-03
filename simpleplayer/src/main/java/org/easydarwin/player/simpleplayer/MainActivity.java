package org.easydarwin.player.simpleplayer;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.Layout;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.widget.EditText;

import org.easydarwin.video.EasyPlayerClient;

import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity implements EasyPlayerClient.SEIDataCallback {

    private EasyPlayerClient client;

    protected static final String TAG = "SimplePlayer";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextureView textureView = findViewById(R.id.texture_view);
        /**
         * 参数说明
         * 第一个参数为Context,
         * 第二个参数为KEY
         * 第三个参数为的textureView,用来显示视频画面
         * 第四个参数为一个ResultReceiver,用来接收SDK层发上来的事件通知;
         * 第五个参数为I420DataCallback,如果不为空,那底层会把YUV数据回调上来.
         */

        ResultReceiver  mResultReceiver = new ResultReceiver(new Handler()) {

            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);

                if (resultCode == EasyPlayerClient.RESULT_VIDEO_DISPLAYED) {
                    if (resultData != null) {
                        int videoDecodeType = resultData.getInt(EasyPlayerClient.KEY_VIDEO_DECODE_TYPE, 0);
                        Log.i(TAG, "视频解码方式:" + (videoDecodeType == 0 ? "软解码" : "硬解码"));

                    }
                } else if (resultCode ==EasyPlayerClient.RESULT_VIDEO_SIZE) {
                    int  w = resultData.getInt(EasyPlayerClient.EXTRA_VIDEO_WIDTH);
                    int h = resultData.getInt(EasyPlayerClient.EXTRA_VIDEO_HEIGHT);
                    Log.i(TAG, "视频尺寸 宽x高:"+w+"x"+h);
                }else if (resultCode == EasyPlayerClient.RESULT_TIMEOUT) {
                    Log.i(TAG, "试播时间到" );
                } else if (resultCode == EasyPlayerClient.RESULT_UNSUPPORTED_VIDEO) {
                    Log.i(TAG, "视频格式不支持" );
                }else if (resultCode == EasyPlayerClient.RESULT_UNSUPPORTED_AUDIO) {
                    Log.i(TAG, "音频格式不支持" );
                }else if (resultCode == EasyPlayerClient.RESULT_EVENT) {
                    int errorCode = resultData.getInt("errorcode");
                    Log.i(TAG, "事件 code"+errorCode );
                }
            }
        };
        client = new EasyPlayerClient(this, textureView, mResultReceiver, null, this);
        client.play("rtsp://admin:xf1234567@192.168.1.120:554/Streaming/Channels/101");



//        final EditText et = new EditText(this);
//        et.setHint("请输入RTSP地址");
//        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
//        et.setText(sp.getString("url", null));
//
//        new AlertDialog.Builder(this).setView(et).setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialog, int which) {
//                String url = et.getText().toString();
//                if (!TextUtils.isEmpty(url)) {
//                    Log.d("simpleplayer",url);
//
//                    sp.edit().putString("url", url).apply();
//                }
//            }
//        }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
//            @Override
//            public void onClick(DialogInterface dialog, int which) {
//                finish();
//            }
//        }).show();

    }


    @Override
    public void onSEIData(byte[] sei) {
    }
}
