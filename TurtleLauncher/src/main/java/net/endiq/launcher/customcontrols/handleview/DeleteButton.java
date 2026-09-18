package net.endiq.launcher.customcontrols.handleview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.ui.view.AnimButton;

import net.endiq.launcher.customcontrols.buttons.ControlInterface;

@SuppressLint("AppCompatCustomView")
public class DeleteButton extends AnimButton implements ActionButtonInterface {
    public DeleteButton(Context context) {super(context); init();}
    public DeleteButton(Context context, @Nullable AttributeSet attrs) {super(context, attrs); init();}

    public void init() {
        setOnClickListener(this);
        setText(R.string.generic_delete);
    }

    private ControlInterface mCurrentlySelectedButton = null;

    @Override
    public boolean shouldBeVisible() {
        return mCurrentlySelectedButton != null;
    }

    @Override
    public void setFollowedView(ControlInterface view) {
        mCurrentlySelectedButton = view;
    }

    @Override
    public void onClick() {
        if(mCurrentlySelectedButton == null) return;

        mCurrentlySelectedButton.removeButton();
    }
}
