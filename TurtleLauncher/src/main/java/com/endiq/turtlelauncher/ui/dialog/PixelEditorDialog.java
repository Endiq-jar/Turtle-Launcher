package com.endiq.turtlelauncher.ui.dialog;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.databinding.DialogPixelEditorBinding;

import net.kdt.pojavlaunch.customcontrols.handleview.PixelCanvasView;

/**
 * TurtleLauncher: a small pixel-art editor for drawing a custom control-button icon in-app,
 * as a complement to (not a replacement for) picking an existing image file - see
 * EditControlPopup's "Draw Image" entry point next to "Custom Button Image". Follows
 * KeyboardDialog's pattern (also opened from EditControlPopup) for consistency: a
 * FullScreenDialog wrapping a view-binding-inflated layout.
 */
public class PixelEditorDialog extends FullScreenDialog implements View.OnClickListener {
    private final DialogPixelEditorBinding binding = DialogPixelEditorBinding.inflate(getLayoutInflater());

    // A small, deliberately limited palette - this is for quick button-icon sprites, not a
    // full art tool, so a curated set beats a giant grid the user has to scroll through.
    private static final int[] PALETTE = {
            Color.BLACK, Color.WHITE, 0xFF808080, 0xFFC0C0C0,
            0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
            0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFF6D4C41,
            0xFFFFB6C1, 0xFF00BCD4
    };

    private OnSaveListener mOnSaveListener;
    private TextView mSelectedToolView;
    private View mSelectedPaletteView;
    private boolean mUpdatingRgbSliders = false;

    public interface OnSaveListener {
        void onSave(Bitmap bitmap);
    }

    public PixelEditorDialog(@NonNull Context context) {
        super(context);
    }

    public PixelEditorDialog setOnSaveListener(OnSaveListener listener) {
        mOnSaveListener = listener;
        return this;
    }

    /** Pre-loads an existing custom image (if any) so re-opening the editor continues from it. */
    public PixelEditorDialog withInitialBitmap(Bitmap bitmap) {
        binding.pixelEditorCanvas.post(() -> binding.pixelEditorCanvas.loadBitmap(bitmap));
        return this;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(binding.getRoot());
        init();
    }

    private void init() {
        mSelectedToolView = binding.pixelEditorToolPencil;
        // Pencil is the default tool - highlighted here in code rather than via
        // android:selected="true" in the layout (that attribute tripped up resource linking
        // in CI's build environment), functionally identical either way.
        mSelectedToolView.setSelected(true);

        binding.pixelEditorClose.setOnClickListener(this);
        binding.pixelEditorCancel.setOnClickListener(this);
        binding.pixelEditorSave.setOnClickListener(this);
        binding.pixelEditorToolPencil.setOnClickListener(this);
        binding.pixelEditorToolEraser.setOnClickListener(this);
        binding.pixelEditorToolFill.setOnClickListener(this);
        binding.pixelEditorUndo.setOnClickListener(this);
        binding.pixelEditorClear.setOnClickListener(this);

        buildPalette();
        setupRgbSliders();
        setCurrentColor(PALETTE[0], null);
    }

    private void buildPalette() {
        int swatchSize = getContext().getResources().getDimensionPixelSize(R.dimen._28sdp);
        int margin = getContext().getResources().getDimensionPixelSize(R.dimen.padding_medium);
        for (int color : PALETTE) {
            View swatch = new View(getContext());
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(swatchSize, swatchSize);
            params.setMarginEnd(margin);
            swatch.setLayoutParams(params);
            swatch.setBackgroundColor(color);
            swatch.setTag(color);
            swatch.setOnClickListener(v -> setCurrentColor((int) v.getTag(), v));
            binding.pixelEditorPalette.addView(swatch);
            if (color == PALETTE[0]) mSelectedPaletteView = swatch;
        }
    }

    private void setupRgbSliders() {
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mUpdatingRgbSliders || !fromUser) return;
                int color = Color.rgb(
                        binding.pixelEditorRedSeekbar.getProgress(),
                        binding.pixelEditorGreenSeekbar.getProgress(),
                        binding.pixelEditorBlueSeekbar.getProgress());
                setCurrentColor(color, null);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        binding.pixelEditorRedSeekbar.setOnSeekBarChangeListener(listener);
        binding.pixelEditorGreenSeekbar.setOnSeekBarChangeListener(listener);
        binding.pixelEditorBlueSeekbar.setOnSeekBarChangeListener(listener);
    }

    /**
     * @param sourceSwatch the palette swatch that was tapped, if any - null when the color
     *                     came from the RGB sliders instead, in which case no preset swatch
     *                     should be highlighted as "selected".
     */
    private void setCurrentColor(int color, View sourceSwatch) {
        binding.pixelEditorCanvas.setCurrentColor(color);
        binding.pixelEditorCustomColorPreview.setBackgroundColor(color);

        if (mSelectedPaletteView != null) mSelectedPaletteView.setSelected(false);
        mSelectedPaletteView = sourceSwatch;
        if (mSelectedPaletteView != null) mSelectedPaletteView.setSelected(true);

        mUpdatingRgbSliders = true;
        binding.pixelEditorRedSeekbar.setProgress(Color.red(color));
        binding.pixelEditorGreenSeekbar.setProgress(Color.green(color));
        binding.pixelEditorBlueSeekbar.setProgress(Color.blue(color));
        mUpdatingRgbSliders = false;
    }

    private void selectTool(TextView toolView, PixelCanvasView.Tool tool) {
        mSelectedToolView.setSelected(false);
        mSelectedToolView = toolView;
        mSelectedToolView.setSelected(true);
        binding.pixelEditorCanvas.setCurrentTool(tool);
    }

    @Override
    public void onClick(View v) {
        if (v == binding.pixelEditorClose || v == binding.pixelEditorCancel) {
            dismiss();
        } else if (v == binding.pixelEditorSave) {
            if (mOnSaveListener != null) {
                mOnSaveListener.onSave(binding.pixelEditorCanvas.exportBitmap());
            }
            dismiss();
        } else if (v == binding.pixelEditorToolPencil) {
            selectTool(binding.pixelEditorToolPencil, PixelCanvasView.Tool.PENCIL);
        } else if (v == binding.pixelEditorToolEraser) {
            selectTool(binding.pixelEditorToolEraser, PixelCanvasView.Tool.ERASER);
        } else if (v == binding.pixelEditorToolFill) {
            selectTool(binding.pixelEditorToolFill, PixelCanvasView.Tool.FILL);
        } else if (v == binding.pixelEditorUndo) {
            binding.pixelEditorCanvas.undo();
        } else if (v == binding.pixelEditorClear) {
            binding.pixelEditorCanvas.clear();
        }
    }
}
