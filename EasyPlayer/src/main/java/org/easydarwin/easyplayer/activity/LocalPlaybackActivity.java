package org.easydarwin.easyplayer.activity;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.PopupMenu;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import org.easydarwin.easyplayer.R;
import org.easydarwin.easyplayer.util.MediaFileActions;

import java.io.File;

/** Plays app-private recordings without requiring another installed player. */
public class LocalPlaybackActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "local_media_path";
    private static final String TAG = "LocalPlayback";
    private VideoView video;
    private File file;
    private int position;
    private boolean resumePlayback = true;
    private boolean prepared;

    public static Intent intent(android.content.Context context, File file) {
        return new Intent(context, LocalPlaybackActivity.class).putExtra(EXTRA_PATH, file.getPath());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_local_playback);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        TextView status = findViewById(R.id.local_playback_status);
        if (path == null || !(file = new File(path)).isFile()) {
            status.setText("录像文件不存在");
            findViewById(R.id.local_playback_more).setVisibility(View.GONE);
            findViewById(R.id.local_playback_back).setOnClickListener(v -> finish());
            return;
        }
        if (state != null) {
            position = state.getInt("position");
            resumePlayback = state.getBoolean("playing", true);
        }
        ((TextView) findViewById(R.id.local_playback_title)).setText(file.getName());
        findViewById(R.id.local_playback_back).setOnClickListener(v -> finish());
        findViewById(R.id.local_playback_more).setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, v);
            menu.getMenu().add(0, 1, 0, "分享");
            menu.getMenu().add(0, 2, 1, "用其他应用打开");
            menu.setOnMenuItemClickListener(item -> {
                MediaFileActions.launch(this, file, item.getItemId() == 1);
                return true;
            });
            menu.show();
        });
        video = findViewById(R.id.local_playback_video);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setOnPreparedListener(player -> {
            prepared = true;
            status.setVisibility(View.GONE);
            Log.i(TAG, "prepared: " + file + ", duration=" + player.getDuration()
                    + ", size=" + player.getVideoWidth() + "x" + player.getVideoHeight());
            if (position > 0) video.seekTo(position);
            if (resumePlayback) video.start();
            controller.show(3000);
        });
        video.setOnCompletionListener(player -> {
            resumePlayback = false;
            position = 0;
            controller.show(0);
            Log.i(TAG, "completed: " + file);
        });
        video.setOnErrorListener((player, what, extra) -> {
            status.setVisibility(View.VISIBLE);
            status.setText("播放失败，可从右上角选择其他播放器");
            Log.e(TAG, "playback failed: " + file + ", what=" + what + ", extra=" + extra);
            return true;
        });
        try {
            // The system media service cannot open app-private file paths directly.
            // Give it a provider URI so ContentResolver can pass an authorized file descriptor.
            video.setVideoURI(MediaFileActions.getContentUri(this, file));
        } catch (IllegalArgumentException e) {
            status.setVisibility(View.VISIBLE);
            status.setText("无法读取录像文件");
            Log.e(TAG, "cannot create media content URI: " + file, e);
        }
    }

    @Override
    protected void onPause() {
        if (video != null && prepared) {
            resumePlayback = video.isPlaying();
            position = video.getCurrentPosition();
            video.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (video != null && prepared && resumePlayback) video.start();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putInt("position", video != null && prepared ? video.getCurrentPosition() : position);
        state.putBoolean("playing", video != null && prepared ? video.isPlaying() : resumePlayback);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
