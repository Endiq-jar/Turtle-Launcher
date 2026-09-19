package net.kdt.pojavlaunch.customcontrols.handleview;


import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.endiq.DefocusableScrollView;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.listener.SimpleTextWatcher;
import com.endiq.turtlelauncher.ui.dialog.KeyboardDialog;
import com.endiq.turtlelauncher.ui.dialog.PixelEditorDialog;
import com.endiq.turtlelauncher.utils.path.PathManager;

import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode;
import net.kdt.pojavlaunch.colorselector.ColorSelector;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlDrawer;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Class providing a sort of popup on top of a Layout, allowing to edit a given ControlButton
 */
public class EditControlPopup {
    private final Context context;
    protected final Spinner[] mKeycodeSpinners = new Spinner[4];
    private KeyboardDialog keyboardDialog;
    private final DefocusableScrollView mScrollView;
    private final ColorSelector mColorSelector;

    private final ObjectAnimator mEditPopupAnimator;
    private final ObjectAnimator mColorEditorAnimator;
    private final int mMargin;
    public boolean internalChanges = false; // True when we programmatically change stuff.
    private final View.OnLayoutChangeListener mLayoutChangedListener = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (internalChanges) return;

            internalChanges = true;
            int width = (int) (safeParseFloat(mWidthEditText.getText().toString()));

            if (width >= 0 && Math.abs(right - width) > 1) {
                mWidthEditText.setText(String.valueOf(right - left));
                mWidthSeekbar.setProgress(clampToSizeSeekbar(right - left));
            }
            int height = (int) (safeParseFloat(mHeightEditText.getText().toString()));
            if (height >= 0 && Math.abs(bottom - height) > 1) {
                mHeightEditText.setText(String.valueOf(bottom - top));
                mHeightSeekbar.setProgress(clampToSizeSeekbar(bottom - top));
            }

            internalChanges = false;
        }
    };
    protected EditText mNameEditText, mWidthEditText, mHeightEditText;
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    protected Switch mToggleSwitch, mPassthroughSwitch, mSwipeableSwitch, mForwardLockSwitch, mAbsoluteTrackingSwitch;
    protected Spinner mOrientationSpinner;
    protected TextView[] mKeycodeTextviews = new TextView[4];
    protected SeekBar mStrokeWidthSeekbar, mCornerRadiusSeekbar, mAlphaSeekbar;
    protected SeekBar mWidthSeekbar, mHeightSeekbar;
    private static final int SIZE_SEEKBAR_MAX_DP = 400;
    protected TextView mStrokePercentTextView, mCornerRadiusPercentTextView, mAlphaPercentTextView;
    protected TextView mSelectBackgroundColor, mSelectStrokeColor;
    protected TextView mButtonImageTextView, mButtonImageSelectTextView, mButtonImageDrawTextView, mButtonImageClearTextView;
    private ActivityResultLauncher<String> mImagePickerLauncher;
    protected ArrayAdapter<String> mAdapter;
    protected List<String> mSpecialArray;
    protected CheckBox mDisplayInGameCheckbox, mDisplayInMenuCheckbox;
    private ConstraintLayout mRootView;
    private boolean mDisplaying = false;
    private boolean mDisplayingColor = false;
    private ControlInterface mCurrentlyEditedButton;
    // Decorative textviews
    private TextView mOrientationTextView, mMappingTextView, mNameTextView,
            mCornerRadiusTextView, mVisibilityTextView, mSizeTextview, mSizeXTextView;


    public EditControlPopup(Context context, ViewGroup parent) {
        this.context = context;

        mScrollView = (DefocusableScrollView) LayoutInflater.from(context).inflate(R.layout.dialog_control_button_setting, parent, false);
        parent.addView(mScrollView);

        mMargin = context.getResources().getDimensionPixelOffset(R.dimen._20sdp);

        mColorSelector = new ColorSelector(context, parent, null);
        mColorSelector.getRootView().setElevation(11);
        mColorSelector.getRootView().setTranslationZ(11);
        mColorSelector.getRootView().setX(-context.getResources().getDimensionPixelOffset(R.dimen._280sdp));

        mEditPopupAnimator = ObjectAnimator.ofFloat(mScrollView, "x", 0).setDuration(600);
        mColorEditorAnimator = ObjectAnimator.ofFloat(mColorSelector.getRootView(), "x", 0).setDuration(600);
        Interpolator decelerate = new AccelerateDecelerateInterpolator();
        mEditPopupAnimator.setInterpolator(decelerate);
        mColorEditorAnimator.setInterpolator(decelerate);

        mScrollView.setElevation(10);
        mScrollView.setTranslationZ(10);
        mScrollView.setX(-context.getResources().getDimensionPixelOffset(R.dimen._280sdp));

        bindLayout();
        loadAdapter();

        setupRealTimeListeners();
    }

    public static void setPercentageText(TextView textView, int progress) {
        textView.setText(textView.getContext().getString(R.string.percent_format, progress));
    }

    private static int clampToSizeSeekbar(float dpValue) {
        return (int) Math.max(0, Math.min(SIZE_SEEKBAR_MAX_DP, Math.round(dpValue)));
    }

    public void setImagePickerLauncher(ActivityResultLauncher<String> launcher) {
        mImagePickerLauncher = launcher;
    }

    private void updateButtonImageLabel(String path) {
        boolean hasImage = path != null && !path.isEmpty();
        mButtonImageTextView.setText(hasImage ? R.string.customctrl_button_image_selected : R.string.customctrl_button_image);
        mButtonImageClearTextView.setVisibility(hasImage ? VISIBLE : GONE);
    }

    public void onCustomImagePicked(Uri uri) {
        if (mCurrentlyEditedButton == null) return;
        File dest = newCustomImageFile();
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("Could not open the picked image");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Logging.e("EditControlPopup", "Failed to copy the picked custom button image", e);
            return;
        }
        applyNewCustomImage(dest);
    }

    public void onPixelImageDrawn(Bitmap bitmap) {
        if (mCurrentlyEditedButton == null || bitmap == null) return;
        File dest = newCustomImageFile();
        try (FileOutputStream out = new FileOutputStream(dest)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Logging.e("EditControlPopup", "Failed to save the drawn custom button image", e);
            return;
        }
        applyNewCustomImage(dest);
    }

    private void openPixelEditor() {
        if (mCurrentlyEditedButton == null) return;
        PixelEditorDialog dialog = new PixelEditorDialog(context)
                .setOnSaveListener(this::onPixelImageDrawn);
        String existingPath = mCurrentlyEditedButton.getProperties().customImagePath;
        if (existingPath != null && !existingPath.isEmpty()) {
            // The editor works on a 32x32 grid, but decodeFile() used to allocate
            // the FULL bitmap first - a gallery photo (4000x3000+) as a control
            // image meant a ~48MB transient allocation and an OOM crash on low-RAM
            // devices. Sample down to 256px max dimension instead, which is still
            // 8x the grid resolution.
            Bitmap existing = decodeSampled(existingPath, 256);
            if (existing != null) dialog.withInitialBitmap(existing);
        }
        dialog.show();
    }

    private static Bitmap decodeSampled(String path, int maxDimension) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sampleSize = 1;
            while ((bounds.outWidth / sampleSize) > maxDimension
                    || (bounds.outHeight / sampleSize) > maxDimension) {
                sampleSize *= 2;
            }
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            return android.graphics.BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            android.util.Log.e("EditControlPopup", "Failed to decode sampled bitmap: " + path, t);
            return null;
        }
    }

    private File newCustomImageFile() {
        File dir = new File(PathManager.DIR_FILE, "custom_control_images");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, UUID.randomUUID() + ".img");
    }

    private void applyNewCustomImage(File dest) {
        // Clean up the previous image for this button, if any, now that the new one has been
        // written successfully - avoids leaving orphaned files behind on repeated re-picks/
        // re-draws (they're small, but they're also pointless once unreferenced).
        String oldPath = mCurrentlyEditedButton.getProperties().customImagePath;
        if (oldPath != null && !oldPath.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            new File(oldPath).delete();
        }

        mCurrentlyEditedButton.getProperties().customImagePath = dest.getAbsolutePath();
        mCurrentlyEditedButton.updateProperties();
        updateButtonImageLabel(dest.getAbsolutePath());
    }

    /**
     * Slide the layout into the visible screen area
     */
    public void appear(boolean fromRight) {
        disappearColor(); // When someone jumps from a button to another

        if (fromRight) {
            if (!mDisplaying || !isAtRight()) {
                mEditPopupAnimator.setFloatValues(currentDisplayMetrics.widthPixels, currentDisplayMetrics.widthPixels - mScrollView.getWidth() - mMargin);
                mEditPopupAnimator.start();
            }
        } else {
            if (!mDisplaying || isAtRight()) {
                mEditPopupAnimator.setFloatValues(-mScrollView.getWidth(), mMargin);
                mEditPopupAnimator.start();
            }
        }

        mDisplaying = true;
    }

    /**
     * Slide out the layout
     */
    public void disappear() {
        if (!mDisplaying) return;

        mDisplaying = false;
        if (isAtRight())
            mEditPopupAnimator.setFloatValues(currentDisplayMetrics.widthPixels - mScrollView.getWidth() - mMargin, currentDisplayMetrics.widthPixels);
        else
            mEditPopupAnimator.setFloatValues(mMargin, -mScrollView.getWidth());

        mEditPopupAnimator.start();
    }

    /**
     * Slide the layout into the visible screen area
     */
    public void appearColor(boolean fromRight, int color) {
        if (fromRight) {
            if (!mDisplayingColor || !isAtRight()) {
                mColorEditorAnimator.setFloatValues(currentDisplayMetrics.widthPixels, currentDisplayMetrics.widthPixels - mScrollView.getWidth() - mMargin);
                mColorEditorAnimator.start();
            }
        } else {
            if (!mDisplayingColor || isAtRight()) {
                mColorEditorAnimator.setFloatValues(-mScrollView.getWidth(), mMargin);
                mColorEditorAnimator.start();
            }
        }

        // Adjust the color selector to have the same size as the control settings
        ViewGroup.LayoutParams params = mColorSelector.getRootView().getLayoutParams();
        params.height = mScrollView.getHeight();
        mColorSelector.getRootView().setLayoutParams(params);

        mDisplayingColor = true;
        mColorSelector.show(color == -1 ? Color.WHITE : color);
    }

    /**
     * Slide out the layout
     */
    public void disappearColor() {
        if (!mDisplayingColor) return;

        mDisplayingColor = false;
        if (isAtRight())
            mColorEditorAnimator.setFloatValues(currentDisplayMetrics.widthPixels - mScrollView.getWidth() - mMargin, currentDisplayMetrics.widthPixels);
        else
            mColorEditorAnimator.setFloatValues(mMargin, -mScrollView.getWidth());

        mColorEditorAnimator.start();
    }

    /**
     * Slide out the first visible layer.
     *
     * @return True if the last layer is disappearing
     */
    public boolean disappearLayer() {
        if (mDisplayingColor) {
            disappearColor();
            return false;
        } else {
            disappear();
            return true;
        }
    }

    /**
     * Switch the panels position if needed
     */
    public void adaptPanelPosition() {
        if (mDisplaying) {
            boolean isAtRight = mCurrentlyEditedButton.getControlView().getX() + mCurrentlyEditedButton.getControlView().getWidth() / 2f < currentDisplayMetrics.widthPixels / 2f;
            appear(isAtRight);
        }
    }

    public void destroy() {
        ((ViewGroup) mScrollView.getParent()).removeView(mColorSelector.getRootView());
        ((ViewGroup) mScrollView.getParent()).removeView(mScrollView);
    }

    private void loadAdapter() {
        //Initialize adapter for keycodes
        mAdapter = new ArrayAdapter<>(context, R.layout.item_centered_textview);
        mSpecialArray = ControlData.buildSpecialButtonArray(context);

        mAdapter.addAll(mSpecialArray);
        mAdapter.addAll(EfficientAndroidLWJGLKeycode.generateKeyName());
        mAdapter.setDropDownViewResource(android.R.layout.simple_list_item_single_choice);

        for (Spinner spinner : mKeycodeSpinners) {
            spinner.setAdapter(mAdapter);
        }

        // Orientation spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item);
        adapter.addAll(ControlDrawerData.getOrientations(context));
        adapter.setDropDownViewResource(android.R.layout.simple_list_item_single_choice);

        mOrientationSpinner.setAdapter(adapter);
    }

    private void setDefaultVisibilitySetting() {
        for (int i = 0; i < mRootView.getChildCount(); ++i) {
            mRootView.getChildAt(i).setVisibility(VISIBLE);
        }
        for(Spinner s : mKeycodeSpinners) {
            s.setVisibility(View.INVISIBLE);
        }
    }

    private boolean isAtRight() {
        return mScrollView.getX() > currentDisplayMetrics.widthPixels / 2f;
    }

    /* LOADING VALUES */

    /**
     * Load values for basic control data
     */
    public void loadValues(ControlData data) {
        setDefaultVisibilitySetting();
        mOrientationTextView.setVisibility(GONE);
        mOrientationSpinner.setVisibility(GONE);
        mForwardLockSwitch.setVisibility(GONE);
        mAbsoluteTrackingSwitch.setVisibility(GONE);

        mNameEditText.setText(data.name);
        mWidthEditText.setText(String.valueOf(data.getWidth()));
        mHeightEditText.setText(String.valueOf(data.getHeight()));
        mWidthSeekbar.setProgress(clampToSizeSeekbar(data.getWidth()));
        mHeightSeekbar.setProgress(clampToSizeSeekbar(data.getHeight()));
        updateButtonImageLabel(data.customImagePath);

        mAlphaSeekbar.setProgress((int) (data.opacity * 100));
        mStrokeWidthSeekbar.setProgress((int) data.strokeWidth * 10);
        mCornerRadiusSeekbar.setProgress((int) data.cornerRadius);

        setPercentageText(mAlphaPercentTextView, (int) (data.opacity * 100));
        setPercentageText(mStrokePercentTextView, (int) data.strokeWidth * 10);
        setPercentageText(mCornerRadiusPercentTextView, (int) data.cornerRadius);

        mToggleSwitch.setChecked(data.isToggle);
        mPassthroughSwitch.setChecked(data.passThruEnabled);
        mSwipeableSwitch.setChecked(data.isSwipeable);

        mDisplayInGameCheckbox.setChecked(data.displayInGame);
        mDisplayInMenuCheckbox.setChecked(data.displayInMenu);

        int keycodeCount = Math.min(data.keycodes.length, mKeycodeSpinners.length);
        for (int i = 0; i < keycodeCount; i++) {
            if (data.keycodes[i] < 0) {
                mKeycodeSpinners[i].setSelection(data.keycodes[i] + mSpecialArray.size());
            } else {
                mKeycodeSpinners[i].setSelection(EfficientAndroidLWJGLKeycode.getIndexByValue(data.keycodes[i]) + mSpecialArray.size());
            }
        }
    }

    /**
     * Load values for extended control data
     */
    public void loadValues(ControlDrawerData data) {
        loadValues(data.properties);

        mOrientationSpinner.setSelection(
                ControlDrawerData.orientationToInt(data.orientation));

        mMappingTextView.setVisibility(GONE);
        for (int i = 0; i < mKeycodeSpinners.length; i++) {
            mKeycodeSpinners[i].setVisibility(GONE);
            mKeycodeTextviews[i].setVisibility(GONE);
        }

        mOrientationTextView.setVisibility(VISIBLE);
        mOrientationSpinner.setVisibility(VISIBLE);

        mSwipeableSwitch.setVisibility(View.GONE);
        mPassthroughSwitch.setVisibility(View.GONE);
        mToggleSwitch.setVisibility(View.GONE);
    }

    /**
     * Load values for the joystick
     */
    public void loadJoystickValues(ControlJoystickData data) {
        loadValues(data);

        mMappingTextView.setVisibility(GONE);
        for (int i = 0; i < mKeycodeSpinners.length; i++) {
            mKeycodeSpinners[i].setVisibility(GONE);
            mKeycodeTextviews[i].setVisibility(GONE);
        }

        mNameTextView.setVisibility(GONE);
        mNameEditText.setVisibility(GONE);

        mCornerRadiusTextView.setVisibility(GONE);
        mCornerRadiusSeekbar.setVisibility(GONE);
        mCornerRadiusPercentTextView.setVisibility(GONE);

        mSwipeableSwitch.setVisibility(View.GONE);
        mPassthroughSwitch.setVisibility(View.GONE);
        mToggleSwitch.setVisibility(View.GONE);

        mForwardLockSwitch.setVisibility(VISIBLE);
        mForwardLockSwitch.setChecked(data.forwardLock);

        mAbsoluteTrackingSwitch.setVisibility(VISIBLE);
        mAbsoluteTrackingSwitch.setChecked(data.absolute);
    }

    /**
     * Load values for sub buttons
     */
    public void loadSubButtonValues(ControlData data, ControlDrawerData.Orientation drawerOrientation) {
        loadValues(data);

        // Size linked to the parent drawer depending on the drawer settings
        if(drawerOrientation != ControlDrawerData.Orientation.FREE){
            mSizeTextview.setVisibility(GONE);
            mSizeXTextView.setVisibility(GONE);
            mWidthEditText.setVisibility(GONE);
            mHeightEditText.setVisibility(GONE);
        }

        // No conditional, already depends on the parent drawer visibility
        mVisibilityTextView.setVisibility(GONE);
        mDisplayInMenuCheckbox.setVisibility(GONE);
        mDisplayInGameCheckbox.setVisibility(GONE);
    }


    private void bindLayout() {
        mRootView = mScrollView.findViewById(R.id.edit_layout);
        mNameEditText = mScrollView.findViewById(R.id.editName_editText);
        mWidthEditText = mScrollView.findViewById(R.id.editSize_editTextX);
        mHeightEditText = mScrollView.findViewById(R.id.editSize_editTextY);
        mToggleSwitch = mScrollView.findViewById(R.id.checkboxToggle);
        mPassthroughSwitch = mScrollView.findViewById(R.id.checkboxPassThrough);
        mSwipeableSwitch = mScrollView.findViewById(R.id.checkboxSwipeable);
        mForwardLockSwitch = mScrollView.findViewById(R.id.checkboxForwardLock);
        mAbsoluteTrackingSwitch = mScrollView.findViewById(R.id.checkboxAbsoluteFingerTracking);
        mKeycodeSpinners[0] = mScrollView.findViewById(R.id.editMapping_spinner_1);
        mKeycodeSpinners[1] = mScrollView.findViewById(R.id.editMapping_spinner_2);
        mKeycodeSpinners[2] = mScrollView.findViewById(R.id.editMapping_spinner_3);
        mKeycodeSpinners[3] = mScrollView.findViewById(R.id.editMapping_spinner_4);
        mKeycodeTextviews[0] = mScrollView.findViewById(R.id.mapping_1_textview);
        mKeycodeTextviews[1] = mScrollView.findViewById(R.id.mapping_2_textview);
        mKeycodeTextviews[2] = mScrollView.findViewById(R.id.mapping_3_textview);
        mKeycodeTextviews[3] = mScrollView.findViewById(R.id.mapping_4_textview);
        mOrientationSpinner = mScrollView.findViewById(R.id.editOrientation_spinner);
        mStrokeWidthSeekbar = mScrollView.findViewById(R.id.editStrokeWidth_seekbar);
        mCornerRadiusSeekbar = mScrollView.findViewById(R.id.editCornerRadius_seekbar);
        mAlphaSeekbar = mScrollView.findViewById(R.id.editButtonOpacity_seekbar);
        mWidthSeekbar = mScrollView.findViewById(R.id.editSize_seekbarX);
        mHeightSeekbar = mScrollView.findViewById(R.id.editSize_seekbarY);
        mWidthSeekbar.setMax(SIZE_SEEKBAR_MAX_DP);
        mHeightSeekbar.setMax(SIZE_SEEKBAR_MAX_DP);
        mButtonImageTextView = mScrollView.findViewById(R.id.editButtonImage_textView);
        mButtonImageSelectTextView = mScrollView.findViewById(R.id.editButtonImage_select_textView);
        mButtonImageDrawTextView = mScrollView.findViewById(R.id.editButtonImage_draw_textView);
        mButtonImageClearTextView = mScrollView.findViewById(R.id.editButtonImage_clear_textView);
        mSelectBackgroundColor = mScrollView.findViewById(R.id.editBackgroundColor_textView);
        mSelectStrokeColor = mScrollView.findViewById(R.id.editStrokeColor_textView);
        mStrokePercentTextView = mScrollView.findViewById(R.id.editStrokeWidth_textView_percent);
        mAlphaPercentTextView = mScrollView.findViewById(R.id.editButtonOpacity_textView_percent);
        mCornerRadiusPercentTextView = mScrollView.findViewById(R.id.editCornerRadius_textView_percent);
        mDisplayInGameCheckbox = mScrollView.findViewById(R.id.visibility_game_checkbox);
        mDisplayInMenuCheckbox = mScrollView.findViewById(R.id.visibility_menu_checkbox);

        //Decorative stuff
        mMappingTextView = mScrollView.findViewById(R.id.editMapping_textView);
        mOrientationTextView = mScrollView.findViewById(R.id.editOrientation_textView);
        mNameTextView = mScrollView.findViewById(R.id.editName_textView);
        mCornerRadiusTextView = mScrollView.findViewById(R.id.editCornerRadius_textView);
        mVisibilityTextView = mScrollView.findViewById(R.id.visibility_textview);
        mSizeTextview = mScrollView.findViewById(R.id.editSize_textView);
        mSizeXTextView = mScrollView.findViewById(R.id.editSize_x_textView);

        keyboardDialog = new KeyboardDialog(this.context);
    }

    /**
     * A long function linking all the displayed data on the popup and,
     * the currently edited mCurrentlyEditedButton
     * @noinspection SuspiciousNameCombination
     */
    public void setupRealTimeListeners() {
        mNameEditText.addTextChangedListener((SimpleTextWatcher) s -> {
            if (internalChanges) return;

            mCurrentlyEditedButton.getProperties().name = s.toString();

            // Cheap and unoptimized, doesn't break the abstraction layer
            mCurrentlyEditedButton.setProperties(mCurrentlyEditedButton.getProperties(), false);
        });

        mWidthEditText.addTextChangedListener((SimpleTextWatcher) s -> {
            if (internalChanges) return;

            float width = safeParseFloat(s.toString());
            if (width >= 0) {
                mCurrentlyEditedButton.getProperties().setWidth(width);
                if (mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData) {
                    // Joysticks are square
                    mCurrentlyEditedButton.getProperties().setHeight(width);
                    mHeightSeekbar.setProgress(clampToSizeSeekbar(width));
                }
                mCurrentlyEditedButton.updateProperties();
                mWidthSeekbar.setProgress(clampToSizeSeekbar(width));
            }
        });

        mHeightEditText.addTextChangedListener((SimpleTextWatcher) s -> {
            if (internalChanges) return;

            float height = safeParseFloat(s.toString());
            if (height >= 0) {
                mCurrentlyEditedButton.getProperties().setHeight(height);
                if (mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData) {
                    // Joysticks are square
                    mCurrentlyEditedButton.getProperties().setWidth(height);
                    mWidthSeekbar.setProgress(clampToSizeSeekbar(height));
                }
                mCurrentlyEditedButton.updateProperties();
                mHeightSeekbar.setProgress(clampToSizeSeekbar(height));
            }
        });

        mSwipeableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().isSwipeable = isChecked;
        });
        mToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().isToggle = isChecked;
        });
        mPassthroughSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().passThruEnabled = isChecked;
        });
        mForwardLockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            if(mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData){
                ((ControlJoystickData) mCurrentlyEditedButton.getProperties()).forwardLock = isChecked;
            }
        });
        mAbsoluteTrackingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            if(mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData){
                ((ControlJoystickData) mCurrentlyEditedButton.getProperties()).absolute = isChecked;
            }
        });

        mAlphaSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (internalChanges) return;
                mCurrentlyEditedButton.getProperties().opacity = mAlphaSeekbar.getProgress() / 100f;
                mCurrentlyEditedButton.getControlView().setAlpha(mAlphaSeekbar.getProgress() / 100f);
                setPercentageText(mAlphaPercentTextView, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mStrokeWidthSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (internalChanges) return;
                mCurrentlyEditedButton.getProperties().strokeWidth = mStrokeWidthSeekbar.getProgress() / 10F;
                mCurrentlyEditedButton.setBackground();
                setPercentageText(mStrokePercentTextView, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mCornerRadiusSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (internalChanges) return;
                mCurrentlyEditedButton.getProperties().cornerRadius = mCornerRadiusSeekbar.getProgress();
                mCurrentlyEditedButton.setBackground();
                setPercentageText(mCornerRadiusPercentTextView, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mWidthSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (internalChanges || !fromUser) return;
                mWidthEditText.setText(String.valueOf(progress));
                if (mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData) {
                    mHeightEditText.setText(String.valueOf(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mHeightSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (internalChanges || !fromUser) return;
                mHeightEditText.setText(String.valueOf(progress));
                if (mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData) {
                    mWidthEditText.setText(String.valueOf(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mButtonImageSelectTextView.setOnClickListener(v -> {
            if (mImagePickerLauncher != null) mImagePickerLauncher.launch("image/*");
        });

        mButtonImageDrawTextView.setOnClickListener(v -> openPixelEditor());

        mButtonImageClearTextView.setOnClickListener(v -> {
            if (mCurrentlyEditedButton == null) return;
            String oldPath = mCurrentlyEditedButton.getProperties().customImagePath;
            if (oldPath != null && !oldPath.isEmpty()) {
                //noinspection ResultOfMethodCallIgnored
                new File(oldPath).delete();
            }
            mCurrentlyEditedButton.getProperties().customImagePath = null;
            mCurrentlyEditedButton.updateProperties();
            updateButtonImageLabel(null);
        });


        for (int i = 0; i < mKeycodeSpinners.length; ++i) {
            int finalI = i;
            mKeycodeTextviews[i].setOnClickListener(v -> keyboardDialog.setOnKeycodeSelectListener(index -> {
                mKeycodeSpinners[finalI].setSelection(index);
                updateKeycodeText(index, finalI);
            }).show());

            mKeycodeSpinners[i].setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    updateKeycodeText(position, finalI);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }


        mOrientationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Side note, spinner listeners are fired later than all the other ones.
                // Meaning the internalChanges bool is useless here.

                if (mCurrentlyEditedButton instanceof ControlDrawer) {
                    ((ControlDrawer) mCurrentlyEditedButton).drawerData.orientation = ControlDrawerData.intToOrientation(mOrientationSpinner.getSelectedItemPosition());
                    ((ControlDrawer) mCurrentlyEditedButton).syncButtons();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        mDisplayInGameCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().displayInGame = isChecked;
        });

        mDisplayInMenuCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().displayInMenu = isChecked;
        });

        mSelectStrokeColor.setOnClickListener(v -> {
            mColorSelector.setAlphaEnabled(false);
            mColorSelector.setColorSelectionListener(color -> {
                mCurrentlyEditedButton.getProperties().strokeColor = color;
                mCurrentlyEditedButton.setBackground();
            });
            appearColor(isAtRight(), mCurrentlyEditedButton.getProperties().strokeColor);
        });

        mSelectBackgroundColor.setOnClickListener(v -> {
            mColorSelector.setAlphaEnabled(true);
            mColorSelector.setColorSelectionListener(color -> {
                mCurrentlyEditedButton.getProperties().bgColor = color;
                mCurrentlyEditedButton.setBackground();
            });
            appearColor(isAtRight(), mCurrentlyEditedButton.getProperties().bgColor);
        });
    }

    private void updateKeycodeText(int index, int finalI) {
        // Side note, spinner listeners are fired later than all the other ones.
        // Meaning the internalChanges bool is useless here.
        if (index < mSpecialArray.size()) {
            mCurrentlyEditedButton.getProperties().keycodes[finalI] = mKeycodeSpinners[finalI].getSelectedItemPosition() - mSpecialArray.size();
        } else {
            mCurrentlyEditedButton.getProperties().keycodes[finalI] = EfficientAndroidLWJGLKeycode.getValueByIndex(mKeycodeSpinners[finalI].getSelectedItemPosition() - mSpecialArray.size());
        }
        mKeycodeTextviews[finalI].setText((String) mKeycodeSpinners[finalI].getSelectedItem());
    }

    private float safeParseFloat(String string) {
        float out = -1; // -1
        try {
            out = Float.parseFloat(string);
        } catch (NumberFormatException e) {
            Logging.e("EditControlPopup", e.toString());
        }
        return out;
    }

    public void setCurrentlyEditedButton(ControlInterface button) {
        if (mCurrentlyEditedButton != null)
            mCurrentlyEditedButton.getControlView().removeOnLayoutChangeListener(mLayoutChangedListener);
        mCurrentlyEditedButton = button;
        mCurrentlyEditedButton.getControlView().addOnLayoutChangeListener(mLayoutChangedListener);
    }
}
