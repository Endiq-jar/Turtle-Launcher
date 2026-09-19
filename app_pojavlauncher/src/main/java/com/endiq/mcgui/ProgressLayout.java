package com.endiq.mcgui;


import android.content.Context;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.constraintlayout.widget.ConstraintLayout;

import net.kdt.pojavlaunch.R;
import com.endiq.turtlelauncher.feature.log.Logging;

import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;

import java.util.HashMap;
import java.util.Map;


/** Class staring at specific values and automatically show something if the progress is present
 * Since progress is posted in a specific way, The packing/unpacking is handheld by the class
 * This class relies on ExtraCore for its behavior.
 */
public class ProgressLayout extends ConstraintLayout implements View.OnClickListener, TaskCountListener{
    public static final String UNPACK_RUNTIME = "unpack_runtime";
    public static final String DOWNLOAD_MINECRAFT = "download_minecraft";
    public static final String DOWNLOAD_VERSION_LIST = "download_verlist";
    public static final String LOGIN_ACCOUNT = "login_account";
    public static final String INSTALL_RESOURCE = "install_resource";
    public static final String CHECKING_MODS = "checking_mods";
    public static final String DOWNLOAD_UPDATE = "download_update";

    public ProgressLayout(@NonNull Context context) {
        super(context);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private final Map<String, LayoutProgressListener> mMap = new HashMap<>();
    private LinearLayout mLinearLayout;
    private TextView mTaskNumberDisplayer;
    private ImageView mFlipArrow;
    /** Guards every callback body below - a progress bar is cosmetic and must never be
     *  the reason the launcher dies. See safePost(). */
    private volatile boolean mDestroyed = false;

    /**
     * Start watching a progress key.
     */
    public void observe(String progressKey) {
        LayoutProgressListener previous = mMap.get(progressKey);
        if (previous != null) {
            ProgressKeeper.removeListener(progressKey, previous);
            previous.removeView();
        }
        mMap.put(progressKey, new LayoutProgressListener(progressKey));
    }

    /**
     * Stop watching a progress key.
     */
    public void unObserve(String progressKey) {
        LayoutProgressListener listener = mMap.remove(progressKey);
        if (listener == null) return;
        ProgressKeeper.removeListener(progressKey, listener);
        listener.removeView();
    }

    public void cleanUpObservers() {
        for (Map.Entry<String, LayoutProgressListener> entry : mMap.entrySet()) {
            ProgressKeeper.removeListener(entry.getKey(), entry.getValue());
            entry.getValue().removeView();
        }
        mMap.clear();
    }

    public boolean hasProcesses(){
        return ProgressKeeper.getTaskCount() > 0;
    }


    private void init(){
        inflate(getContext(), R.layout.view_progress, this);
        mLinearLayout = findViewById(R.id.progress_linear_layout);
        mTaskNumberDisplayer = findViewById(R.id.progress_textview);
        mFlipArrow = findViewById(R.id.progress_flip_arrow);
        setOnClickListener(this);
    }

    /** Update the text and progress content */
    public static void setProgress(String progressKey, int progress, @StringRes int resource, Object... message){
        ProgressKeeper.submitProgress(progressKey, progress, resource, message);
    }

    /** Update the text and progress content */
    public static void clearProgress(String progressKey) {
        setProgress(progressKey, -1, -1);
    }

    @Override
    public void onClick(View v) {
        mLinearLayout.setVisibility(mLinearLayout.getVisibility() == GONE ? VISIBLE : GONE);
        mFlipArrow.setRotation(mLinearLayout.getVisibility() == GONE? 0 : 180);
    }

    @Override
    public void onUpdateTaskCount(int tc) {
        post(()->{
            mTaskNumberDisplayer.setText(getContext().getString(R.string.progresslayout_tasks_in_progress, tc));
            setVisibility(GONE);
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        mDestroyed = true;
        if (mLinearLayout != null) mLinearLayout.removeAllViews();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mDestroyed = false;
        // Anything still registered was re-observed before we came back; re-apply it so the
        // bars on screen match ProgressKeeper's real task list instead of an empty layout.
        for (LayoutProgressListener listener : mMap.values()) listener.reApply();
    }

    /** Runs [body] on the UI thread, never letting a cosmetic progress update kill the app. */
    private void safePost(Runnable body) {
        if (mDestroyed) return;
        Runnable guarded = () -> {
            if (mDestroyed || mLinearLayout == null) return;
            try {
                body.run();
            } catch (Throwable t) {
                Logging.e("ProgressLayout", "Failed to apply a progress update", t);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run();
        else post(guarded);
    }

    class LayoutProgressListener implements ProgressListener {
        final String progressKey;
        final TextProgressBar textView;
        final LinearLayout.LayoutParams params;

        private volatile boolean mPendingVisible = false;

        public LayoutProgressListener(String progressKey) {
            this.progressKey = progressKey;
            textView = new TextProgressBar(getContext());
            textView.setTextPadding(getContext().getResources().getDimensionPixelOffset(R.dimen._6sdp));
            params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, getResources().getDimensionPixelOffset(R.dimen._20sdp));
            params.bottomMargin = getResources().getDimensionPixelOffset(R.dimen._6sdp);
            ProgressKeeper.addListener(progressKey, this);
        }

        @Override
        public void onProgressStarted() {
            mPendingVisible = true;
            safePost(this::reconcile);
        }

        @Override
        public void onProgressUpdated(int progress, int resid, Object... va) {
            safePost(() -> {
                textView.setProgress(progress);
                try {
                    if (resid != -1) textView.setText(getContext().getString(resid, va));
                    else if (va.length > 0 && va[0] != null) textView.setText((String) va[0]);
                    else textView.setText("");
                } catch (Throwable ignored) {
                }
            });
        }

        @Override
        public void onProgressEnded() {
            mPendingVisible = false;
            safePost(this::reconcile);
        }

        /** Makes the view hierarchy match [mPendingVisible], whatever order we got here in. */
        private void reconcile() {
            if (mPendingVisible) attachView();
            else detachView();
        }

        private void attachView() {
            Logging.i("ProgressLayout", "onProgressStarted: " + progressKey);
            textView.setProgress(0);
            textView.setText("");

            ViewParent parent = textView.getParent();
            if (parent == mLinearLayout) {
                return;
            }
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(textView);
            }
            mLinearLayout.addView(textView, params);
        }

        private void detachView() {
            ViewParent parent = textView.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(textView);
            }
        }

        /** Drops this listener's bar from the layout. Safe from any thread. */
        void removeView() {
            mPendingVisible = false;
            safePost(this::detachView);
        }

        /** Re-applies whatever ProgressKeeper currently thinks about this key, after a
         *  detach/attach cycle wiped the layout clean. */
        void reApply() {
            mPendingVisible = ProgressKeeper.containsProgress(progressKey);
            safePost(this::reconcile);
        }
    }
}
