package org.easydarwin.easyplayer.fragments;

import android.databinding.DataBindingUtil;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.bumptech.glide.Glide;

import org.easydarwin.easyplayer.R;
import org.easydarwin.easyplayer.databinding.FragmentImageBinding;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import uk.co.senab.photoview.PhotoView;

/** Full-screen image viewer with PhotoView zooming and ViewPager navigation. */
public class ImageFragment extends Fragment {

    private static final String ARG_URI = "image_uri";
    private static final String ARG_DIRECTORY = "image_directory";
    private static final String ARG_SELECTED_PATH = "selected_image_path";

    private FragmentImageBinding binding;
    private ArrayList<Uri> images = new ArrayList<>();
    private int initialPosition;

    public ImageFragment() {
    }

    /** Keeps the existing single-image entry point for the playback overlay. */
    public static ImageFragment newInstance(Uri uri) {
        Bundle args = new Bundle();
        args.putParcelable(ARG_URI, uri);
        ImageFragment fragment = new ImageFragment();
        fragment.setArguments(args);
        return fragment;
    }

    /** Opens all JPEG snapshots in a folder, starting at the selected image. */
    public static ImageFragment newGalleryInstance(File directory, File selected) {
        Bundle args = new Bundle();
        args.putString(ARG_DIRECTORY, directory.getAbsolutePath());
        args.putString(ARG_SELECTED_PATH, selected.getAbsolutePath());
        ImageFragment fragment = new ImageFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args == null) return;

        String directoryPath = args.getString(ARG_DIRECTORY);
        if (directoryPath != null) {
            File directory = new File(directoryPath);
            File[] files = directory.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase(java.util.Locale.ROOT).endsWith(".jpg");
                }
            });
            if (files != null) {
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File left, File right) {
                        int byTime = Long.compare(right.lastModified(), left.lastModified());
                        return byTime != 0 ? byTime : right.getName().compareTo(left.getName());
                    }
                });
                String selectedPath = args.getString(ARG_SELECTED_PATH);
                for (int i = 0; i < files.length; i++) {
                    images.add(Uri.fromFile(files[i]));
                    if (files[i].getAbsolutePath().equals(selectedPath)) initialPosition = i;
                }
            }
        } else {
            Uri uri = args.getParcelable(ARG_URI);
            if (uri != null) images.add(uri);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_image, container, false);
        binding.imageClose.setOnClickListener(v -> closeViewer());
        binding.imagePager.setAdapter(new ImagePagerAdapter());
        binding.imagePager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updateCounter(position);
            }
        });
        binding.imagePager.setCurrentItem(initialPosition, false);
        updateCounter(initialPosition);
        return binding.getRoot();
    }

    private void updateCounter(int position) {
        if (binding == null) return;
        int count = images.size();
        binding.imageCounter.setText(count == 0 ? "0 / 0" : (position + 1) + " / " + count);
        binding.imageCounter.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
    }

    private void closeViewer() {
        if (getFragmentManager() != null) getFragmentManager().popBackStack();
    }

    private class ImagePagerAdapter extends PagerAdapter {
        @Override
        public int getCount() {
            return images.size();
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            PhotoView photoView = new PhotoView(container.getContext());
            photoView.setBackgroundColor(0xff000000);
            photoView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            // At the image edge, let horizontal gestures page to the previous/next photo.
            photoView.setAllowParentInterceptOnEdge(true);
            photoView.setOnViewTapListener((view, x, y) -> {
                if (binding != null) {
                    int visibility = binding.imageToolbar.getVisibility() == View.VISIBLE
                            ? View.GONE : View.VISIBLE;
                    binding.imageToolbar.setVisibility(visibility);
                }
            });
            Glide.with(ImageFragment.this).load(images.get(position)).into(photoView);
            container.addView(photoView, new ViewPager.LayoutParams());
            return photoView;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
            Glide.clear((View) object);
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public int getItemPosition(Object object) {
            return POSITION_NONE;
        }
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            binding.imagePager.setAdapter(null);
            binding = null;
        }
        super.onDestroyView();
    }
}
