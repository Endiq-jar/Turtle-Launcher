package com.endiq.turtlelauncher.feature.download;

import static com.endiq.turtlelauncher.feature.download.InfoAdapter.createCategoryView;
import static com.endiq.turtlelauncher.feature.download.InfoAdapter.getTagTextView;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.flexbox.FlexboxLayout;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.databinding.ItemModDependenciesBinding;
import com.endiq.turtlelauncher.event.value.AddFragmentEvent;
import com.endiq.turtlelauncher.feature.download.enums.Category;
import com.endiq.turtlelauncher.feature.download.enums.ModLoader;
import com.endiq.turtlelauncher.feature.download.enums.Platform;
import com.endiq.turtlelauncher.feature.download.item.DependenciesInfoItem;
import com.endiq.turtlelauncher.feature.download.item.InfoItem;
import com.endiq.turtlelauncher.feature.download.utils.DependencyUtils;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.ui.fragment.DownloadModFragment;
import com.endiq.turtlelauncher.utils.NumberWithUnits;
import com.endiq.turtlelauncher.utils.ZHTools;
import net.endiq.launcher.Tools;
import org.greenrobot.eventbus.EventBus;
import org.jackhuang.hmcl.ui.versions.ModTranslations;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.Future;








public class ModDependenciesAdapter extends RecyclerView.Adapter<ModDependenciesAdapter.InnerHolder> {
    private final InfoItem mInfoItem;
    private final List<DependenciesInfoItem> mData;
    private SetOnClickListener onClickListener;

    public ModDependenciesAdapter(InfoItem item, List<DependenciesInfoItem> mData) {
        this.mInfoItem = item;
        this.mData = mData;
    }

    @NonNull
    @Override
    public InnerHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new InnerHolder(ItemModDependenciesBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull InnerHolder holder, int position) {
        holder.setData(mData.get(position));
    }

    @Override
    public int getItemCount() {
        return mData != null ? mData.size() : 0;
    }

    public void setOnItemCLickListener(SetOnClickListener listener) {
        this.onClickListener = listener;
    }

    public interface SetOnClickListener {
        void onItemClick();
    }

    public class InnerHolder extends RecyclerView.ViewHolder {
        private final Context context;
        private final ItemModDependenciesBinding binding;
        private Future<?> mExtensionFuture;

        public InnerHolder(@NonNull ItemModDependenciesBinding binding) {
            super(binding.getRoot());
            context = binding.getRoot().getContext();
            this.binding = binding;
        }

        @SuppressLint("CheckResult")
        public void setData(DependenciesInfoItem infoItem) {
            ModTranslations.Mod mod = ModTranslations.getTranslationsByRepositoryType(infoItem.getClassify())
                    .getModByCurseForgeId(infoItem.getSlug());

            if (mExtensionFuture != null) {
                mExtensionFuture.cancel(true);
                mExtensionFuture = null;
            }

            binding.getRoot().getBackground().setTint(infoItem.getDependencyType().getColor());

            binding.sourceImageview.setImageDrawable(getPlatformIcon(infoItem.getPlatform()));
            binding.sourceTextview.setText(infoItem.getPlatform().getPName());

            String title;
            if (ZHTools.areaChecks("zh")) {
                if (mod != null) title = mod.getDisplayName();
                else title = infoItem.getTitle();
            } else title = infoItem.getTitle();
            binding.titleTextview.setText(title);

            binding.categoriesLayout.removeAllViews();
            binding.tagsLayout.removeAllViews();
            for (Category category : infoItem.getCategory()) {
                addCategoryView(binding.categoriesLayout, context.getString(category.getResNameID()));
            }

            binding.bodyTextview.setText(infoItem.getDescription());

            binding.tagsLayout.addView(
                    getTagTextView(
                            context,
                            R.string.download_info_dependencies,
                            DependencyUtils.Companion.getTextFromType(context, infoItem.getDependencyType())
                    )
            );

            binding.tagsLayout.addView(
                    getTagTextView(
                            context,
                            R.string.download_info_downloads,
                            NumberWithUnits.formatNumberWithUnit(
                                    infoItem.getDownloadCount(),
                                    // Check whether the current system language is English.
                                    ZHTools.isEnglish(context)
                            )
                    )
            );

            StringJoiner modloaderSJ = new StringJoiner(", ");
            for (ModLoader modloader : infoItem.getModloaders()) {
                modloaderSJ.add(modloader.getLoaderName());
            }
            String modloaderText;
            if (modloaderSJ.length() > 0) modloaderText = modloaderSJ.toString();
            else modloaderText = context.getString(R.string.generic_unknown);
            binding.tagsLayout.addView(
                    getTagTextView(context, R.string.download_info_modloader, modloaderText)
            );

            binding.thumbnailImageview.setImageDrawable(null);
            RequestBuilder<Drawable> builder = Glide.with(context).load(infoItem.getIconUrl());
            if (!AllSettings.getResourceImageCache().getValue()) builder.diskCacheStrategy(DiskCacheStrategy.NONE);
            builder.into(binding.thumbnailImageview);

            itemView.setOnClickListener(v -> {
                EventBus.getDefault().post(new AddFragmentEvent(
                        DownloadModFragment.class, DownloadModFragment.TAG, null,
                        fragmentActivity -> {
                            InfoViewModel viewModel = new ViewModelProvider(fragmentActivity).get(InfoViewModel.class);
                            viewModel.setInfoItem(infoItem);
                            viewModel.setPlatformHelper(mInfoItem.getPlatform().getHelper());
                        }
                ));

                if (onClickListener != null) onClickListener.onItemClick();
            });
        }

        private Drawable getPlatformIcon(Platform platform) {
            if (platform == Platform.MODRINTH) return ContextCompat.getDrawable(context, R.drawable.ic_modrinth);
            return null;
        }

        private void addCategoryView(FlexboxLayout layout, String text) {
            TextView textView = createCategoryView(context);
            textView.setText(text);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, Tools.dpToPx(9F));
            layout.addView(textView);
        }
    }
}
