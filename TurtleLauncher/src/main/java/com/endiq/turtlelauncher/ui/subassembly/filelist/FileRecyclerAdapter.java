package com.endiq.turtlelauncher.ui.subassembly.filelist;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.DrawableImageViewTarget;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.databinding.ItemFileListViewBinding;
import com.endiq.turtlelauncher.feature.turtle.heatmap.PerfEstimate;
import com.endiq.turtlelauncher.feature.turtle.heatmap.PerfTier;
import com.endiq.turtlelauncher.ui.dialog.TipDialog;
import com.endiq.turtlelauncher.utils.file.FileTools;
import com.endiq.turtlelauncher.utils.image.ImageUtils;
import com.endiq.turtlelauncher.utils.stringutils.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class FileRecyclerAdapter extends RecyclerView.Adapter<FileRecyclerAdapter.InnerHolder> {
    private final List<FileItemBean> mData = new ArrayList<>();
    private final List<FileItemBean> selectedFiles = new ArrayList<>();
    private boolean isMultiSelectMode = false;
    private OnItemClickListener mOnItemClickListener;
    private OnItemLongClickListener mOnItemLongClickListener;
    private OnMultiSelectListener mOnMultiSelectListener;

    @NonNull
    @Override
    public InnerHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new InnerHolder(ItemFileListViewBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull InnerHolder holder, int position) {
        holder.setData(mData.get(position), position);
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateItems(List<FileItemBean> items) {
        this.mData.clear();
        this.mData.addAll(items);
        notifyDataSetChanged();
    }

    public List<FileItemBean> getData() {
        return mData;
    }

    public boolean isNoFile() {
        return (mData.size() == 1 && !mData.get(0).isCanCheck) || mData.isEmpty();
    }

    private void toggleSelection(FileItemBean itemBean, CheckBox checkBox) {
        if (itemBean.isCanCheck) {
            if (selectedFiles.contains(itemBean)) {
                selectedFiles.remove(itemBean);
                checkBox.setChecked(false);
            } else {
                selectedFiles.add(itemBean);
                checkBox.setChecked(true);
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setMultiSelectMode(boolean multiSelectMode) {
        isMultiSelectMode = multiSelectMode;
        if (!multiSelectMode) {
            selectedFiles.clear(); // reset the selection when leaving multi-select mode
        }
        notifyDataSetChanged();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void selectAllFiles(boolean selectAll) {
        selectedFiles.clear();
        if (selectAll) { // select-all walks every item and sets the state
            for (FileItemBean item : mData) {
                if (item.isCanCheck) {
                    selectedFiles.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    public List<FileItemBean> getSelectedFiles() {
        return selectedFiles;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.mOnItemClickListener = listener;
    }

    public void setOnMultiSelectListener(OnMultiSelectListener listener) {
        this.mOnMultiSelectListener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.mOnItemLongClickListener = listener;
    }

    public interface OnItemClickListener {
        void onItemClick(int position, FileItemBean itemBean);
    }

    public interface OnMultiSelectListener {
        void onMultiSelect(List<FileItemBean> itemBeans);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(int position, FileItemBean itemBean);
    }

    public class InnerHolder extends RecyclerView.ViewHolder {
        private final Context context;
        private final ItemFileListViewBinding binding;
        private int mPosition;
        private FileItemBean mFileItemBean;

        public InnerHolder(@NonNull ItemFileListViewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            context = itemView.getContext();

            binding.check.setOnClickListener(v -> {
                if (isMultiSelectMode) {
                    toggleSelection(mFileItemBean, binding.check);
                }
            });
            if (mOnItemClickListener != null) {
                itemView.setOnClickListener(v -> {
                    if (isMultiSelectMode) {
                        toggleSelection(mFileItemBean, binding.check);
                    } else {
                        mOnItemClickListener.onItemClick(mPosition, mFileItemBean);
                    }
                });
            }
            itemView.setOnLongClickListener(v -> {
                if (isMultiSelectMode) {
                    if (mOnMultiSelectListener != null)
                        mOnMultiSelectListener.onMultiSelect(getSelectedFiles());
                } else {
                    if (mOnItemLongClickListener != null)
                        mOnItemLongClickListener.onItemLongClick(mPosition, mFileItemBean);
                }
                return true;
            });

            binding.perfBadge.setOnClickListener(v -> {
                PerfEstimate estimate = mFileItemBean != null ? mFileItemBean.perfEstimate : null;
                if (estimate == null) return;
                String name = mFileItemBean.name;
                String message = estimate.getTier().getEmoji() + " " + estimate.getTier().getLabel() + "\n\n" +
                        "RAM: " + estimate.formatRam() + "\n" +
                        "VRAM: " + estimate.formatVram() + "\n" +
                        "CPU signal: " + estimate.getCpuSignal() + " " + estimate.getCpuSignalLabel() + "\n\n" +
                        estimate.getReasoning() + "\n\n" +
                        "Static estimate only — not a measured benchmark.";
                new TipDialog.Builder(context)
                        .setTitle(name)
                        .setMessage(message)
                        .setConfirm(android.R.string.ok)
                        .setShowCancel(false)
                        .showDialog();
            });
        }

        public void setData(FileItemBean fileItemBean, int position) {
            mPosition = position;
            mFileItemBean = fileItemBean;
            File file = fileItemBean.file;

            binding.name.setText(fileItemBean.name);

            int infoLayoutVisible = View.GONE;
            if (fileItemBean.date != null) {
                String date = StringUtils.formatDate(fileItemBean.date, Locale.getDefault(), TimeZone.getDefault());
                binding.time.setText(date);
                binding.time.setVisibility(View.VISIBLE);
                infoLayoutVisible = View.VISIBLE;
            } else binding.time.setVisibility(View.GONE);

            if (fileItemBean.size != null) {
                String size = FileTools.formatFileSize(fileItemBean.size);
                binding.size.setText(size);
                binding.size.setVisibility(View.VISIBLE);
                infoLayoutVisible = View.VISIBLE;
            } else binding.size.setVisibility(View.GONE);

            binding.infoLayout.setVisibility(infoLayoutVisible);

            if (fileItemBean.isHighlighted) {
                binding.name.setTextColor(Color.rgb(69, 179, 162)); // highlight
            } else {
                binding.name.setTextColor(binding.name.getResources().getColor(R.color.black_or_white, binding.name.getContext().getTheme()));
            }

            binding.updateBadge.setVisibility(fileItemBean.hasUpdate ? View.VISIBLE : View.GONE);

            PerfEstimate perfEstimate = fileItemBean.perfEstimate;
            if (perfEstimate != null) {
                int tintColor;
                if (perfEstimate.getTier() == PerfTier.HEAVY) {
                    tintColor = Color.rgb(224, 67, 60);
                } else if (perfEstimate.getTier() == PerfTier.MODERATE) {
                    tintColor = Color.rgb(230, 184, 0);
                } else {
                    tintColor = Color.rgb(76, 175, 80);
                }
                binding.perfBadge.setBackgroundTintList(ColorStateList.valueOf(tintColor));
                binding.perfBadge.setVisibility(View.VISIBLE);
            } else {
                binding.perfBadge.setVisibility(View.GONE);
            }

            if (fileItemBean.isCanCheck) {
                binding.check.setVisibility(isMultiSelectMode ? View.VISIBLE : View.GONE);
                binding.check.setChecked(selectedFiles.contains(fileItemBean));
            } else {
                binding.check.setVisibility(View.GONE);
            }

            if (file != null && file.isFile() &&
                (file.getName().endsWith(com.endiq.turtlelauncher.feature.mod.ModUtils.JAR_FILE_SUFFIX) ||
                 file.getName().endsWith(com.endiq.turtlelauncher.feature.mod.ModUtils.DISABLE_JAR_FILE_SUFFIX))) {
                boolean enabled = file.getName().endsWith(com.endiq.turtlelauncher.feature.mod.ModUtils.JAR_FILE_SUFFIX);
                binding.modToggle.setVisibility(isMultiSelectMode ? View.GONE : View.VISIBLE);
                // remove old listener before setting checked state to avoid triggering rename
                binding.modToggle.setOnCheckedChangeListener(null);
                binding.modToggle.setChecked(enabled);
                binding.modToggle.setOnCheckedChangeListener((btn, isChecked) -> {
                    if (isChecked) {
                        com.endiq.turtlelauncher.feature.mod.ModUtils.enableMod(file);
                    } else {
                        com.endiq.turtlelauncher.feature.mod.ModUtils.disableMod(file);
                    }
                    // reload icon + name immediately
                    if (mOnItemClickListener != null) {
                        mOnItemClickListener.onItemClick(mPosition, mFileItemBean);
                    }
                });
            } else {
                binding.modToggle.setVisibility(View.GONE);
            }

            if (file != null && file.isFile() && ImageUtils.isImage(file)) {
                Glide.with(context).load(file)
                        .override(binding.image.getWidth(), binding.image.getHeight())
                        .centerCrop()
                        .into(new DrawableImageViewTarget(binding.image));
            } else {
                binding.image.setImageDrawable(fileItemBean.image);
            }
        }
    }
}
