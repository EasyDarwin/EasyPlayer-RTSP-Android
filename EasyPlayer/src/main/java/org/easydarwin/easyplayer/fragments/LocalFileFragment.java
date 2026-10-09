package org.easydarwin.easyplayer.fragments;

import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;

import org.easydarwin.easyplayer.R;
import org.easydarwin.easyplayer.activity.LocalPlaybackActivity;
import org.easydarwin.easyplayer.util.MediaFileActions;
import org.easydarwin.easyplayer.databinding.FragmentMediaFileBinding;
import org.easydarwin.easyplayer.databinding.ImagePickerItemBinding;
import org.easydarwin.easyplayer.util.FileUtil;

import java.io.File;
import java.io.FilenameFilter;

public class LocalFileFragment extends Fragment implements CompoundButton.OnCheckedChangeListener, View.OnClickListener {
    public static final String KEY_IS_RECORD = "key_last_selection";
    public static final String KEY_URL = "KEY_URL";

    private boolean mShowMp4File;
    private FragmentMediaFileBinding mBinding;

    SparseArray<Boolean> mImageChecked;

    private String mSuffix;
    File mRoot = null;
    File[] mSubFiles;
    int mImgHeight;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);

        mImageChecked = new SparseArray<>();

        String url = getArguments().getString(KEY_URL);
        mShowMp4File = getArguments().getBoolean(KEY_IS_RECORD);
        mSuffix = mShowMp4File ? ".mp4" : ".jpg";

        if (mShowMp4File) {
            mRoot = new File(FileUtil.getMoviePath(getContext(), url));
        } else {
            mRoot = new File(FileUtil.getPicturePath(getContext(), url));
        }

        File[] subFiles = mRoot.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String filename) {
                return filename.endsWith(mSuffix);
            }
        });

        if (subFiles == null)
            subFiles = new File[0];

        mSubFiles = subFiles;
        mImgHeight = (int) (getResources().getDisplayMetrics().density * 100 + 0.5f);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mBinding = DataBindingUtil.inflate(inflater, R.layout.fragment_media_file, container, false);
        return mBinding.getRoot();
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        GridLayoutManager layoutManager = new GridLayoutManager(getContext(), 3);
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);

        mBinding.recycler.setLayoutManager(layoutManager);

        mBinding.recycler.setAdapter(new RecyclerView.Adapter() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                ImagePickerItemBinding binding = DataBindingUtil.inflate(LayoutInflater.from(getContext()), R.layout.image_picker_item, parent, false);
                return new ImageItemHolder(binding);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder viewHolder, int position) {
                ImageItemHolder holder = (ImageItemHolder) viewHolder;
                holder.mCheckBox.setOnCheckedChangeListener(null);
                holder.mCheckBox.setChecked(mImageChecked.get(position, false));
                holder.mCheckBox.setOnCheckedChangeListener(LocalFileFragment.this);
                holder.mCheckBox.setTag(R.id.click_tag, holder);
                holder.mImage.setTag(R.id.click_tag, holder);

                if (mShowMp4File) {
                    holder.mPlayImage.setVisibility(View.VISIBLE);
                } else {
                    holder.mPlayImage.setVisibility(View.GONE);
                }

                Glide.with(getContext()).load(mSubFiles[position]).into(holder.mImage);
            }

            @Override
            public int getItemCount() {
                return mSubFiles.length;
            }
        });
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
//        ImageItemHolder holder = (ImageItemHolder) buttonView.getTag(R.id.click_tag);
//        int position = holder.getAdapterPosition();
    }

    @Override
    public void onClick(View v) {
        ImageItemHolder holder = (ImageItemHolder) v.getTag(R.id.click_tag);
        if (holder.getAdapterPosition() == RecyclerView.NO_POSITION) {
            return;
        }

        final String path = mSubFiles[holder.getAdapterPosition()].getPath();
        if (TextUtils.isEmpty(path)) {
            Toast.makeText(getContext(), "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        File f = new File(path);
        if (!f.isFile() || f.length() == 0) {
            Toast.makeText(getContext(), "文件不存在或为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mShowMp4File) {
            startActivity(LocalPlaybackActivity.intent(getContext(), f));
        } else {
            getFragmentManager().beginTransaction()
                    .add(android.R.id.content, ImageFragment.newGalleryInstance(mRoot, f))
                    .addToBackStack(null).commit();
        }
    }

    private void showFileActions(View anchor, File file) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "分享");
        menu.getMenu().add(0, 2, 1, "用其他应用打开");
        menu.setOnMenuItemClickListener(item -> {
            MediaFileActions.launch(getContext(), file, item.getItemId() == 1);
            return true;
        });
        menu.show();
    }

    class ImageItemHolder extends RecyclerView.ViewHolder {
        public final CheckBox mCheckBox;
        public final ImageView mImage;
        public final ImageView mPlayImage;

        public ImageItemHolder(ImagePickerItemBinding binding) {
            super(binding.getRoot());

            mCheckBox = binding.imageCheckbox;
            mImage = binding.imageIcon;
            mPlayImage = binding.imagePlay;
            mImage.setOnClickListener(LocalFileFragment.this);
            mImage.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    showFileActions(v, mSubFiles[position]);
                    return true;
                }
                return false;
            });
        }
    }
}
