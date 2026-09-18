package net.endiq.launcher;

import android.graphics.Color;
import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.drawerlayout.widget.DrawerLayout;
import com.endiq.turtlelauncher.databinding.ActivityCustomControlsBinding;
import com.endiq.turtlelauncher.databinding.ViewControlMenuBinding;
import com.endiq.turtlelauncher.feature.background.BackgroundManager;
import com.endiq.turtlelauncher.feature.background.BackgroundType;
import com.endiq.turtlelauncher.setting.AllSettings;
import com.endiq.turtlelauncher.ui.activity.BaseActivity;
import com.endiq.turtlelauncher.ui.subassembly.menu.ControlMenu;
import com.endiq.turtlelauncher.ui.subassembly.view.GameMenuViewWrapper;
import net.endiq.launcher.customcontrols.ControlLayout;
import net.endiq.launcher.customcontrols.EditorExitable;
import java.io.IOException;






public class CustomControlsActivity extends BaseActivity implements EditorExitable {
	public static final String BUNDLE_CONTROL_PATH = "control_path";
	private ActivityCustomControlsBinding binding;
	private String mControlPath = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		parseBundle();
		binding = ActivityCustomControlsBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());

		ControlLayout controlLayout = binding.customctrlControllayout;
		DrawerLayout drawerLayout = binding.customctrlDrawerlayout;
		FrameLayout drawerNavigationView = binding.customctrlNavigationView;

		// TurtleLauncher: registered here (not inside ControlLayout, which is a plain
		// ViewGroup and can't call registerForActivityResult) - must happen unconditionally
		// during onCreate regardless of whether the button-image edit popup exists yet, per
		// the ActivityResultLauncher contract. Handed down to ControlLayout, which forwards
		// it to EditControlPopup the moment that popup actually gets created.
		ActivityResultLauncher<String> imagePickerLauncher = registerForActivityResult(
				new ActivityResultContracts.GetContent(),
				uri -> {
					if (uri != null) controlLayout.onCustomImagePicked(uri);
				}
		);
		controlLayout.setImagePickerLauncher(imagePickerLauncher);

		new GameMenuViewWrapper(this, v -> {
			boolean open = drawerLayout.isDrawerOpen(drawerNavigationView);

			if (open) drawerLayout.closeDrawer(drawerNavigationView);
			else drawerLayout.openDrawer(drawerNavigationView);
		}, false).setVisibility(true);

		BackgroundManager.setBackgroundImage(this, BackgroundType.CUSTOM_CONTROLS, binding.backgroundView, null);

		drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
		drawerLayout.setScrimColor(Color.TRANSPARENT);

		ViewControlMenuBinding controlMenuBinding = ViewControlMenuBinding.inflate(getLayoutInflater());
		new ControlMenu(this, this, controlMenuBinding, controlLayout, true);

		drawerNavigationView.addView(controlMenuBinding.getRoot());
		controlLayout.setModifiable(true);
		try {
			if (mControlPath == null) controlLayout.loadLayout((String) null);
			else controlLayout.loadLayout(mControlPath);
		} catch (IOException e) {
			Tools.showError(this, e);
		}

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				binding.customctrlControllayout.askToExit(CustomControlsActivity.this);
			}
		});
	}

	private void parseBundle() {
		Bundle bundle = getIntent().getExtras();
		if (bundle != null) {
			mControlPath = bundle.getString(BUNDLE_CONTROL_PATH);
		}
	}

	@Override
	public boolean shouldIgnoreNotch() {
		return AllSettings.getIgnoreNotch().getValue();
	}

	@Override
	public void exitEditor() {
		finish();
	}
}
