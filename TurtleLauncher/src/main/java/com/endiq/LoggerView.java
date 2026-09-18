package com.endiq;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.endiq.anim.animations.Animations;
import com.endiq.turtlelauncher.databinding.ViewLoggerBinding;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.utils.anim.ViewAnimUtils;

import net.endiq.launcher.Logger;

/**
 * A class able to display logs to the user.
 * It has support for the Logger class
 */
public class LoggerView extends ConstraintLayout {
    private Logger.eventLogListener mLogListener;
    private ViewLoggerBinding binding;
    private boolean isShowing = false;

    // TurtleLauncher v10: raw, unfiltered log lines kept separately so the regex/text
    // filter can be applied and cleared without losing history that arrived before it.
    private final StringBuilder rawLogBuffer = new StringBuilder();
    private java.util.regex.Pattern activeFilter = null;

    public LoggerView(@NonNull Context context) {
        this(context, null);
    }

    public LoggerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // Triggers the log view shown state by default when viewing it
        binding.toggleLog.setChecked(visibility == VISIBLE);
    }

    public void toggleViewWithAnim() {
        setVisibilityWithAnim(!isShowing);
    }

    public void setVisibilityWithAnim(boolean visibility) {
        if (isShowing == visibility) return;
        isShowing = visibility;

        ViewAnimUtils.setViewAnim(this,
                visibility ? Animations.BounceInUp : Animations.SlideOutDown,
                (long) (AllSettings.getAnimationSpeed().getValue() * 0.7),
                () -> setVisibility(VISIBLE),
                () -> setVisibility(visibility ? VISIBLE : GONE));
    }

    /**
     * Force-show the log; closing it through the button triggers the callback.
     */
    public void forceShow(OnCloseClickListener listener) {
        setVisibilityWithAnim(true);
        binding.cancel.setOnClickListener(v -> listener.onClick());
    }

    /**
     * Inflate the layout, and add component behaviors
     */
    private void init() {
        binding = ViewLoggerBinding.inflate(LayoutInflater.from(getContext()), this, true);

        binding.logView.setTypeface(Typeface.MONOSPACE);
        //TODO clamp the max text so it doesn't go oob
        binding.logView.setMaxLines(Integer.MAX_VALUE);
        binding.logView.setEllipsize(null);
        binding.logView.setVisibility(GONE);

        // Toggle log visibility
        binding.toggleLog.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    binding.logView.setVisibility(isChecked ? VISIBLE : GONE);
                    if (isChecked) {
                        Logger.setLogListener(mLogListener);
                    } else {
                        binding.logView.setText("");
                        rawLogBuffer.setLength(0);
                        Logger.setLogListener(null); // Makes the JNI code be able to skip expensive logger callbacks
                        // NOTE: was tested by rapidly smashing the log on/off button, no sync issues found :)
                    }
                });
        binding.toggleLog.setChecked(false);

        // TurtleLauncher v10: share the current log content via the Android share sheet.
        binding.shareLog.setOnClickListener(v -> {
            String text = rawLogBuffer.toString();
            if (text.isEmpty()) text = binding.logView.getText().toString();
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, text);
            android.content.Intent chooser = android.content.Intent.createChooser(shareIntent, "Share log");
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);
        });

        // TurtleLauncher v10: regex/plain-text log filter. Empty = show everything.
        // Accepts a plain substring, or a /regex/ wrapped pattern for full regex filtering.
        binding.logFilter.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String query = s.toString();
                if (query.isEmpty()) {
                    activeFilter = null;
                } else if (query.length() > 2 && query.startsWith("/") && query.endsWith("/")) {
                    try {
                        activeFilter = java.util.regex.Pattern.compile(
                            query.substring(1, query.length() - 1), java.util.regex.Pattern.CASE_INSENSITIVE);
                    } catch (Exception e) {
                        activeFilter = null; // invalid regex mid-typing — just show everything until it's valid
                    }
                } else {
                    activeFilter = java.util.regex.Pattern.compile(
                        java.util.regex.Pattern.quote(query), java.util.regex.Pattern.CASE_INSENSITIVE);
                }
                rerenderFilteredLog();
            }
        });

        binding.cancel.setOnClickListener(view -> setVisibilityWithAnim(false));

        // Set the scroll view
        binding.scroll.setKeepFocusing(true);

        //Set up the autoscroll switch
        binding.toggleAutoscroll.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    if (isChecked) binding.scroll.fullScroll(View.FOCUS_DOWN);
                    binding.scroll.setKeepFocusing(isChecked);
                }
        );
        binding.toggleAutoscroll.setChecked(true);

        // Listen to logs
        mLogListener = text -> {
            if (binding.logView.getVisibility() != VISIBLE) return;
            post(() -> {
                rawLogBuffer.append(text).append('\n');
                // Cap the raw buffer so a very long session doesn't grow unbounded.
                if (rawLogBuffer.length() > 2_000_000) {
                    rawLogBuffer.delete(0, rawLogBuffer.length() - 1_500_000);
                }
                if (activeFilter == null || activeFilter.matcher(text).find()) {
                    binding.logView.append(text + '\n');
                    if (binding.scroll.isKeepFocusing())
                        binding.scroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        };
    }

    /** Rebuilds the visible log text from the raw buffer against the current [activeFilter]. */
    private void rerenderFilteredLog() {
        String[] lines = rawLogBuffer.toString().split("\n", -1);
        StringBuilder rebuilt = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) continue;
            if (activeFilter == null || activeFilter.matcher(line).find()) {
                rebuilt.append(line).append('\n');
            }
        }
        binding.logView.setText(rebuilt.toString());
        if (binding.scroll.isKeepFocusing())
            binding.scroll.fullScroll(View.FOCUS_DOWN);
    }

    public ViewLoggerBinding getBinding() {
        return binding;
    }

    public interface OnCloseClickListener {
        void onClick();
    }
}
