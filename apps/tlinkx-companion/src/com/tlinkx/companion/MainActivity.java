package com.tlinkx.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CarModeService.Callback {
    private static final String PREFS = "tlinkx_prefs";

    private UiTheme theme;
    private SharedPreferences prefs;
    private CarModeService carService;
    private boolean bound = false;
    private boolean readinessPromptedThisLaunch = false;
    private final ArrayDeque<String> logs = new ArrayDeque<>();

    // Hero status views
    private TextView statusBadge;
    private TextView statusSubtitle;
    private TextView primaryActionBtn;
    private TextView chipTouch;
    private TextView chipDock;
    private TextView chipEq;

    // Phone readiness chips
    private TextView readyDataChip;
    private TextView readyBtChip;
    private TextView readyGpsChip;

    // 4-slot Car Launcher rows
    private final ImageView[] slotIcons = new ImageView[CarLauncherConfig.SLOT_COUNT];
    private final TextView[] slotNames = new TextView[CarLauncherConfig.SLOT_COUNT];

    // Settings value views
    private Switch dockSwitch;
    private TextView dockSideValue;
    private Switch autoVolSwitch;
    private final TextView[] eqChips = new TextView[4];
    private final TextView[] voiceValues = new TextView[4];

    // Advanced drawer
    private LinearLayout advancedContent;
    private TextView advancedChevron;
    private TextView presetValue;
    private TextView rotValue;
    private TextView fitValue;
    private TextView rootStatusValue;
    private boolean advancedExpanded = false;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            carService = ((CarModeService.LocalBinder) service).getService();
            bound = true;
            carService.setCallback(MainActivity.this);
            refreshStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            carService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ensureV2Defaults();
        theme = new UiTheme(this);
        styleSystemBars();
        buildUi();
        bindService(new Intent(this, CarModeService.class), conn, Context.BIND_AUTO_CREATE);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshReadinessChips();
        refreshLauncherSlots();
        if (!readinessPromptedThisLaunch) {
            readinessPromptedThisLaunch = true;
            ReadinessChecker.showPromptIfNeeded(this, this::refreshReadinessChips);
        }
    }

    private void styleSystemBars() {
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            w.setDecorFitsSystemWindows(false);
        } else {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        w.setAttributes(lp);
        w.setStatusBarColor(theme.bgCanvas);
        w.setNavigationBarColor(theme.bgCanvas);
        View decor = w.getDecorView();
        decor.setBackgroundColor(theme.bgCanvas);
        int flags = decor.getSystemUiVisibility();
        if (!theme.isDark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        decor.setSystemUiVisibility(flags);
    }

    private void ensureV2Defaults() {
        if (!prefs.getBoolean("v2_defaults_applied", false)) {
            prefs.edit()
                    .putString("preset", CarDisplayController.DEFAULT_PRESET)
                    .putInt("touch_rot", CarDisplayController.DEFAULT_TOUCH_ROT)
                    .putString("touch_fit", CarDisplayController.DEFAULT_TOUCH_FIT)
                    .putBoolean("dock_enabled", true)
                    .putBoolean("dock_right", true)
                    .putString("eq_preset", BluetoothAudioController.EQ_CAR_BALANCED)
                    .putBoolean("auto_vol_95", true)
                    .putBoolean("v2_defaults_applied", true)
                    .apply();
        }
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            if (carService != null) carService.setCallback(null);
            unbindService(conn);
            bound = false;
        }
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(theme.bgCanvas);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(12), pad, dp(24));

        // 1. App Top Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(14);
        header.setLayoutParams(hLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView appTitle = new TextView(this);
        appTitle.setText("T-LinkX");
        appTitle.setTextColor(theme.textPrimary);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(appTitle);

        TextView carModel = new TextView(this);
        carModel.setText("Toyota T-Link Companion");
        carModel.setTextColor(theme.textSecondary);
        carModel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titleCol.addView(carModel);
        header.addView(titleCol);

        statusBadge = new TextView(this);
        statusBadge.setText("\u25CF  Paused");
        statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusBadge.setBackground(theme.roundedRect(this, theme.bgSubtle, Color.TRANSPARENT, 99, false));
        statusBadge.setTextColor(theme.textSecondary);
        header.addView(statusBadge);
        root.addView(header);

        // 2. Hero Connection Card
        LinearLayout heroCard = theme.makeCard(this);

        statusSubtitle = new TextView(this);
        statusSubtitle.setText("Tap Start before plugging your phone into your car's USB port.");
        statusSubtitle.setTextColor(theme.textSecondary);
        statusSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        statusSubtitle.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(14);
        heroCard.addView(statusSubtitle, subLp);

        primaryActionBtn = theme.makePillButton(this, "Start Car Mode", theme.accentPrimary, theme.accentPrimaryText, 52);
        primaryActionBtn.setOnClickListener(v -> toggleAutoMode());
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.bottomMargin = dp(14);
        heroCard.addView(primaryActionBtn, actLp);

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipTouch = makeStatusChip("Touch: Off");
        chipDock = makeStatusChip("Dock: Auto");
        chipEq = makeStatusChip("EQ: Balanced");
        chipRow.addView(chipTouch, weightLp(1f, 6));
        chipRow.addView(chipDock, weightLp(1f, 6));
        chipRow.addView(chipEq, weightLp(1f, 0));
        heroCard.addView(chipRow);

        // Phone Readiness Strip (Data / Bluetooth / GPS) — tappable to fix missing services
        LinearLayout readyRow = new LinearLayout(this);
        readyRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rrLp.topMargin = dp(8);
        readyRow.setLayoutParams(rrLp);
        readyDataChip = makeStatusChip("Data");
        readyBtChip = makeStatusChip("Bluetooth");
        readyGpsChip = makeStatusChip("Location");
        View.OnClickListener checkReadyClick = v ->
                ReadinessChecker.showPromptIfNeeded(this, this::refreshReadinessChips);
        readyDataChip.setOnClickListener(checkReadyClick);
        readyBtChip.setOnClickListener(checkReadyClick);
        readyGpsChip.setOnClickListener(checkReadyClick);
        readyRow.addView(readyDataChip, weightLp(1f, 6));
        readyRow.addView(readyBtChip, weightLp(1f, 6));
        readyRow.addView(readyGpsChip, weightLp(1f, 0));
        heroCard.addView(readyRow);
        root.addView(heroCard);

        // 3. Car Home Launcher Apps Card (4 customizable Netflix-style picker slots!)
        LinearLayout launcherCard = theme.makeCard(this);
        launcherCard.addView(theme.makeSectionTitle(this, "CAR HOME LAUNCHER APPS (SHOWN ON CONNECT)"));

        for (int i = 0; i < CarLauncherConfig.SLOT_COUNT; i++) {
            final int slot = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(50));
            row.setBackground(theme.roundedRect(this, Color.TRANSPARENT, Color.TRANSPARENT, 10, true));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> showInstalledAppPicker(slot));

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            ivLp.rightMargin = dp(12);
            iv.setLayoutParams(ivLp);
            slotIcons[i] = iv;
            row.addView(iv);

            LinearLayout txtCol = new LinearLayout(this);
            txtCol.setOrientation(LinearLayout.VERTICAL);
            txtCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView slotTitle = new TextView(this);
            slotTitle.setTextColor(theme.textPrimary);
            slotTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            slotTitle.setTypeface(Typeface.DEFAULT_BOLD);
            slotNames[i] = slotTitle;
            txtCol.addView(slotTitle);

            TextView slotSub = new TextView(this);
            slotSub.setText("Slot " + (i + 1) + " \u2022 Tap to change app");
            slotSub.setTextColor(theme.textSecondary);
            slotSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            txtCol.addView(slotSub);
            row.addView(txtCol);

            TextView chev = new TextView(this);
            chev.setText("Change \u203A");
            chev.setTextColor(theme.accentPrimary);
            chev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            chev.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(chev);

            launcherCard.addView(row);
            if (i < CarLauncherConfig.SLOT_COUNT - 1) {
                launcherCard.addView(theme.makeDivider(this));
            }
        }
        root.addView(launcherCard);

        // 4. Quick Drive Actions Card (2x2 clean grid)
        LinearLayout quickCard = theme.makeCard(this);
        quickCard.addView(theme.makeSectionTitle(this, "QUICK ACTIONS"));

        LinearLayout qRow1 = new LinearLayout(this);
        qRow1.setOrientation(LinearLayout.HORIZONTAL);
        qRow1.addView(makeQuickTile("\u2302", "Car Home", "Preview app picker", false, v -> {
            startActivity(new Intent(this, CarLauncherActivity.class));
        }), weightLp(1f, 8));
        qRow1.addView(makeQuickTile("\u25EB", "Split-Screen", "Maps + Music", false,
                v -> ShortcutRouter.executeAction(this, ShortcutRouter.ACT_SPLIT_SCREEN, "App")), weightLp(1f, 0));
        LinearLayout.LayoutParams qr1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qr1Lp.bottomMargin = dp(8);
        quickCard.addView(qRow1, qr1Lp);

        LinearLayout qRow2 = new LinearLayout(this);
        qRow2.setOrientation(LinearLayout.HORIZONTAL);
        qRow2.addView(makeQuickTile("\u263D", "Day / Night", "Switch theme", false,
                v -> ShortcutRouter.executeAction(this, ShortcutRouter.ACT_TOGGLE_THEME, "App")), weightLp(1f, 8));
        qRow2.addView(makeQuickTile("\u21BA", "Reset Screen", "Restore phone", true,
                v -> resetAllNow()), weightLp(1f, 0));
        quickCard.addView(qRow2);
        root.addView(quickCard);

        // 5. Car Dock & Audio Card
        LinearLayout dockAudioCard = theme.makeCard(this);
        dockAudioCard.addView(theme.makeSectionTitle(this, "CAR DOCK & AUDIO"));

        dockSwitch = new Switch(this);
        dockSwitch.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("dock_enabled", checked).apply();
            updateAllOptionViews();
        });
        dockAudioCard.addView(makeSwitchRow("Floating Car Dock", "Appears after picking an app from Car Home", dockSwitch));
        dockAudioCard.addView(theme.makeDivider(this));

        dockSideValue = makeValueLabel();
        dockAudioCard.addView(makePickerRow("Dock Side", "Right side is closer for RHD cars", dockSideValue, v -> {
            boolean next = !prefs.getBoolean("dock_right", true);
            prefs.edit().putBoolean("dock_right", next).apply();
            updateAllOptionViews();
        }));
        dockAudioCard.addView(theme.makeDivider(this));

        TextView eqTitle = new TextView(this);
        eqTitle.setText("Car Speaker EQ");
        eqTitle.setTextColor(theme.textPrimary);
        eqTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        eqTitle.setTypeface(Typeface.DEFAULT_BOLD);
        dockAudioCard.addView(eqTitle);

        TextView eqSub = new TextView(this);
        eqSub.setText("Tuned for Toyota Display Audio (Bluetooth)");
        eqSub.setTextColor(theme.textSecondary);
        eqSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams eqSubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eqSubLp.bottomMargin = dp(10);
        dockAudioCard.addView(eqSub, eqSubLp);

        LinearLayout eqRow = new LinearLayout(this);
        eqRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] eqLabels = new String[]{"Balanced", "Bass", "Vocal", "Custom"};
        String[] quickEqKeys = new String[]{
                BluetoothAudioController.EQ_CAR_BALANCED,
                BluetoothAudioController.EQ_DEEP_BASS,
                BluetoothAudioController.EQ_VOCAL_CLARITY,
                BluetoothAudioController.EQ_CUSTOM
        };
        for (int i = 0; i < 4; i++) {
            final String presetKey = quickEqKeys[i];
            TextView chip = new TextView(this);
            chip.setText(eqLabels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setMinimumHeight(dp(40));
            chip.setPadding(dp(6), dp(8), dp(6), dp(8));
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                prefs.edit().putString("eq_preset", presetKey).apply();
                updateAllOptionViews();
                if (bound && carService != null) carService.updateLiveEqPreset(presetKey);
                if (BluetoothAudioController.EQ_CUSTOM.equals(presetKey)) {
                    startActivity(new Intent(this, EqTunerActivity.class));
                }
            });
            eqChips[i] = chip;
            eqRow.addView(chip, weightLp(1f, i < 3 ? 6 : 0));
        }
        dockAudioCard.addView(eqRow);

        TextView openEqBtn = theme.makePillButton(this, "Open 10-Band EQ Graph & All Presets  \u203A",
                theme.bgSubtle, theme.accentPrimary, 44);
        openEqBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        openEqBtn.setOnClickListener(v -> startActivity(new Intent(this, EqTunerActivity.class)));
        LinearLayout.LayoutParams oebLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        oebLp.topMargin = dp(10);
        dockAudioCard.addView(openEqBtn, oebLp);
        dockAudioCard.addView(theme.makeDivider(this));

        autoVolSwitch = new Switch(this);
        autoVolSwitch.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean("auto_vol_95", checked).apply();
            updateAllOptionViews();
        });
        dockAudioCard.addView(makeSwitchRow("Auto Car Volume (95%)", "Cleanest audio signal, restored on unplug", autoVolSwitch));
        root.addView(dockAudioCard);

        // 6. Steering-Wheel Voice Button Shortcuts Card
        LinearLayout wheelCard = theme.makeCard(this);
        wheelCard.addView(theme.makeSectionTitle(this, "STEERING-WHEEL VOICE BUTTON"));

        String[] tapTitles = new String[]{"1\u00D7 Press", "2\u00D7 Press", "3\u00D7 Press", "4\u00D7 Press"};
        String[] tapHints = new String[]{
                "Single tap on steering wheel",
                "Double tap within 0.65 s",
                "Triple tap within 0.65 s",
                "Quadruple tap within 0.65 s"
        };
        for (int i = 0; i < 4; i++) {
            final int slot = i + 1;
            voiceValues[i] = makeValueLabel();
            wheelCard.addView(makePickerRow(tapTitles[i], tapHints[i], voiceValues[i],
                    v -> showVoiceActionPicker(slot, tapTitles[slot - 1])));
            if (i < 3) wheelCard.addView(theme.makeDivider(this));
        }
        root.addView(wheelCard);

        // 7. Collapsible Advanced & Diagnostics Card (Hidden by default)
        LinearLayout advCard = theme.makeCard(this);
        LinearLayout advHeader = new LinearLayout(this);
        advHeader.setOrientation(LinearLayout.HORIZONTAL);
        advHeader.setGravity(Gravity.CENTER_VERTICAL);
        advHeader.setMinimumHeight(dp(36));
        advHeader.setClickable(true);
        advHeader.setFocusable(true);
        advHeader.setOnClickListener(v -> toggleAdvancedDrawer());

        LinearLayout advTitleCol = new LinearLayout(this);
        advTitleCol.setOrientation(LinearLayout.VERTICAL);
        advTitleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView advTitle = new TextView(this);
        advTitle.setText("Advanced & Diagnostics");
        advTitle.setTextColor(theme.textPrimary);
        advTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        advTitle.setTypeface(Typeface.DEFAULT_BOLD);
        advTitleCol.addView(advTitle);
        TextView advSub = new TextView(this);
        advSub.setText("Display calibration, touch alignment & logs");
        advSub.setTextColor(theme.textSecondary);
        advSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        advTitleCol.addView(advSub);
        advHeader.addView(advTitleCol);

        advancedChevron = new TextView(this);
        advancedChevron.setText("\u25BC");
        advancedChevron.setTextColor(theme.textSecondary);
        advancedChevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        advHeader.addView(advancedChevron);
        advCard.addView(advHeader);

        advancedContent = new LinearLayout(this);
        advancedContent.setOrientation(LinearLayout.VERTICAL);
        advancedContent.setVisibility(View.GONE);
        advancedContent.addView(theme.makeDivider(this));

        presetValue = makeValueLabel();
        advancedContent.addView(makePickerRow("Display Resolution", "Sharp HiDPI recommended for 1024\u00D7600", presetValue, v -> {
            String cur = prefs.getString("preset", CarDisplayController.DEFAULT_PRESET);
            String next = CarDisplayController.PRESET_HIDPI.equals(cur)
                    ? CarDisplayController.PRESET_NATIVE : CarDisplayController.PRESET_HIDPI;
            prefs.edit().putString("preset", next).apply();
            updateAllOptionViews();
            applyTouchCfgAsync();
        }));
        advancedContent.addView(theme.makeDivider(this));

        rotValue = makeValueLabel();
        advancedContent.addView(makePickerRow("Touch Rotation", "Default calibrated to 270\u00B0", rotValue, v -> {
            int next = (prefs.getInt("touch_rot", CarDisplayController.DEFAULT_TOUCH_ROT) + 90) % 360;
            prefs.edit().putInt("touch_rot", next).apply();
            updateAllOptionViews();
            applyTouchCfgAsync();
        }));
        advancedContent.addView(theme.makeDivider(this));

        fitValue = makeValueLabel();
        advancedContent.addView(makePickerRow("Touch Edge Fit", "Compensates letterbox margins", fitValue, v -> {
            String cur = prefs.getString("touch_fit", CarDisplayController.DEFAULT_TOUCH_FIT);
            String next = CarDisplayController.FIT_X_INSET.equals(cur) ? CarDisplayController.FIT_Y_INSET
                    : CarDisplayController.FIT_Y_INSET.equals(cur) ? CarDisplayController.FIT_FULL
                    : CarDisplayController.FIT_X_INSET;
            prefs.edit().putString("touch_fit", next).apply();
            updateAllOptionViews();
            applyTouchCfgAsync();
        }));
        advancedContent.addView(theme.makeDivider(this));

        rootStatusValue = makeValueLabel();
        advancedContent.addView(makePickerRow("System Access", "KernelSU root & daemon health", rootStatusValue,
                v -> refreshStatus()));
        advancedContent.addView(theme.makeDivider(this));

        LinearLayout advBtns = new LinearLayout(this);
        advBtns.setOrientation(LinearLayout.HORIZONTAL);
        TextView openTlinkBtn = theme.makePillButton(this, "Open Official T-Link", theme.bgSubtle, theme.textPrimary, 44);
        openTlinkBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        openTlinkBtn.setOnClickListener(v -> new Thread(() -> CarDisplayController.launchOfficialTlink(this)).start());

        TextView viewLogBtn = theme.makePillButton(this, "View Diagnostics", theme.bgSubtle, theme.textPrimary, 44);
        viewLogBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        viewLogBtn.setOnClickListener(v -> showDiagnosticsDialog());

        advBtns.addView(openTlinkBtn, weightLp(1f, 8));
        advBtns.addView(viewLogBtn, weightLp(1f, 0));
        advancedContent.addView(advBtns);

        advCard.addView(advancedContent);
        root.addView(advCard);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        updateAllOptionViews();
        refreshLauncherSlots();
        refreshReadinessChips();
    }

    private void showInstalledAppPicker(int slotIndex) {
        Toast.makeText(this, "Loading installed apps...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final List<CarLauncherConfig.AppEntry> apps = CarLauncherConfig.loadInstalledLaunchableApps(this);
            runOnUiThread(() -> {
                ArrayAdapter<CarLauncherConfig.AppEntry> adapter = new ArrayAdapter<CarLauncherConfig.AppEntry>(
                        this, 0, apps) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        CarLauncherConfig.AppEntry item = getItem(position);
                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(18), dp(10), dp(18), dp(10));

                        ImageView iv = new ImageView(MainActivity.this);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        if (item != null && item.icon != null) iv.setImageDrawable(item.icon);
                        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(36), dp(36));
                        ivLp.rightMargin = dp(14);
                        row.addView(iv, ivLp);

                        TextView tv = new TextView(MainActivity.this);
                        tv.setText(item != null ? item.label : "");
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                        tv.setTypeface(Typeface.DEFAULT_BOLD);
                        row.addView(tv);
                        return row;
                    }
                };

                new AlertDialog.Builder(this)
                        .setTitle("Choose App for Slot " + (slotIndex + 1))
                        .setAdapter(adapter, (dialog, which) -> {
                            CarLauncherConfig.AppEntry picked = apps.get(which);
                            CarLauncherConfig.setSlotPackage(this, slotIndex, picked.packageName);
                            refreshLauncherSlots();
                            Toast.makeText(this, "Slot " + (slotIndex + 1) + " -> " + picked.label, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
    }

    private void refreshLauncherSlots() {
        for (int i = 0; i < CarLauncherConfig.SLOT_COUNT; i++) {
            CarLauncherConfig.AppEntry e = CarLauncherConfig.resolveSlot(this, i);
            if (slotNames[i] != null) slotNames[i].setText(e.label);
            if (slotIcons[i] != null) {
                if (e.icon != null) {
                    slotIcons[i].setImageDrawable(e.icon);
                } else {
                    slotIcons[i].setImageResource(android.R.drawable.sym_def_app_icon);
                }
            }
        }
    }

    private void refreshReadinessChips() {
        ReadinessChecker.Status st = ReadinessChecker.check(this);
        styleReadinessChip(readyDataChip, st.dataOn, "Data");
        styleReadinessChip(readyBtChip, st.bluetoothOn, "Bluetooth");
        styleReadinessChip(readyGpsChip, st.locationOn, "Location");
    }

    private void styleReadinessChip(TextView chip, boolean ok, String name) {
        if (chip == null) return;
        chip.setText((ok ? "\u2713 " : "\u26A0 ") + name);
        chip.setTextColor(ok ? theme.accentSuccessText : theme.accentWarnText);
        chip.setBackground(theme.roundedRect(this,
                ok ? theme.accentSuccessBg : theme.accentWarnBg, Color.TRANSPARENT, 10, true));
    }

    private void showVoiceActionPicker(int slot, String title) {
        String[] labels = new String[ShortcutRouter.ALL_ACTIONS.length];
        String current = ShortcutRouter.getActionForSlot(this, slot);
        int checkedIdx = 0;
        for (int i = 0; i < ShortcutRouter.ALL_ACTIONS.length; i++) {
            labels[i] = ShortcutRouter.labelForAction(ShortcutRouter.ALL_ACTIONS[i]);
            if (ShortcutRouter.ALL_ACTIONS[i].equals(current)) checkedIdx = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Voice Button: " + title)
                .setSingleChoiceItems(labels, checkedIdx, (dialog, which) -> {
                    String chosen = ShortcutRouter.ALL_ACTIONS[which];
                    prefs.edit().putString("voice_tap_" + slot, chosen).apply();
                    updateAllOptionViews();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDiagnosticsDialog() {
        new Thread(() -> {
            String disp = CarDisplayController.getCurrentDisplaySummary();
            String bridge = CarDisplayController.inspectTouchBridgeAndLog();
            StringBuilder sb = new StringBuilder();
            sb.append("Display:\n").append(disp).append("\n\n");
            if (!logs.isEmpty()) {
                sb.append("Recent App Events:\n");
                for (String s : logs) sb.append(s).append('\n');
                sb.append('\n');
            }
            sb.append(bridge);
            final String content = sb.toString();

            runOnUiThread(() -> {
                ScrollView sv = new ScrollView(this);
                TextView tv = new TextView(this);
                tv.setText(content);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                tv.setPadding(dp(16), dp(12), dp(16), dp(12));
                sv.addView(tv);

                new AlertDialog.Builder(this)
                        .setTitle("T-LinkX Diagnostics (v2.2)")
                        .setView(sv)
                        .setPositiveButton("Close", null)
                        .show();
            });
        }).start();
    }

    private void toggleAdvancedDrawer() {
        advancedExpanded = !advancedExpanded;
        advancedContent.setVisibility(advancedExpanded ? View.VISIBLE : View.GONE);
        advancedChevron.setText(advancedExpanded ? "\u25B2" : "\u25BC");
    }

    private void toggleAutoMode() {
        boolean currentlyAuto = prefs.getBoolean("auto_trigger", true);
        boolean daemonRunning = CarDisplayController.getTouchDaemonStatus().startsWith("RUNNING");
        if (bound && carService != null && carService.isCarDisplayActive()) {
            // Active drive -> disconnect & reset screen
            Intent i = new Intent(this, CarModeService.class);
            i.setAction(CarModeService.ACTION_AUTO_DETACH);
            startService(i);
            return;
        }
        if (currentlyAuto && daemonRunning) {
            // Pause Zero-Touch Auto-Trigger
            prefs.edit().putBoolean("auto_trigger", false).apply();
            new Thread(() -> {
                applyTouchCfgSync();
                CarDisplayController.stopTouchDaemon();
                if (bound && carService != null && carService.isMonitoring()) {
                    Intent i = new Intent(this, CarModeService.class);
                    i.setAction(CarModeService.ACTION_STOP);
                    startService(i);
                }
                refreshStatus();
            }).start();
        } else {
            // Enable Zero-Touch Auto-Trigger (no persistent foreground service needed when unplugged!)
            ReadinessChecker.showPromptIfNeeded(this, () -> {
                prefs.edit().putBoolean("auto_trigger", true).apply();
                refreshReadinessChips();
                new Thread(() -> {
                    CarDisplayController.installTouchEnvironment(this, false);
                    applyTouchCfgSync();
                    CarDisplayController.startTouchDaemon(this);
                    refreshStatus();
                }).start();
            });
        }
    }

    private void applyTouchCfgSync() {
        int rot = prefs.getInt("touch_rot", CarDisplayController.DEFAULT_TOUCH_ROT);
        String fit = prefs.getString("touch_fit", CarDisplayController.DEFAULT_TOUCH_FIT);
        boolean autoTrig = prefs.getBoolean("auto_trigger", true);
        String preset = prefs.getString("preset", CarDisplayController.DEFAULT_PRESET);
        CarDisplayController.writeTouchConfig(rot, fit, autoTrig, preset);
    }

    private void applyTouchCfgAsync() {
        new Thread(this::applyTouchCfgSync).start();
    }

    private void resetAllNow() {
        new Thread(() -> {
            if (bound && carService != null) {
                carService.deactivateCarMode();
            } else {
                CarDisplayController.resetDisplay();
            }
            runOnUiThread(() -> Toast.makeText(this, "Display & backlight restored", Toast.LENGTH_SHORT).show());
            refreshStatus();
        }).start();
    }

    private void refreshStatus() {
        new Thread(() -> {
            boolean root = RootShell.hasRoot();
            String daemon = root ? CarDisplayController.getTouchDaemonStatus() : "No root";
            boolean daemonOk = daemon.startsWith("RUNNING");
            boolean mon = bound && carService != null && carService.isMonitoring();
            boolean carAct = bound && carService != null && carService.isCarDisplayActive();

            boolean autoTrig = prefs.getBoolean("auto_trigger", true);
            runOnUiThread(() -> {
                if (!root) {
                    statusBadge.setText("\u25CF  Root Needed");
                    statusBadge.setBackground(theme.roundedRect(this, theme.accentDangerBg, Color.TRANSPARENT, 99, false));
                    statusBadge.setTextColor(theme.accentDangerText);
                    statusSubtitle.setText("Enable root for T-LinkX inside KernelSU Next, then enable Zero-Touch Mode.");
                } else if (carAct) {
                    statusBadge.setText("\u25CF  Connected");
                    statusBadge.setBackground(theme.roundedRect(this, theme.accentSuccessBg, Color.TRANSPARENT, 99, false));
                    statusBadge.setTextColor(theme.accentSuccessText);
                    statusSubtitle.setText("Connected to car display — fullscreen and direct touch are active.");
                } else if (daemonOk && autoTrig) {
                    statusBadge.setText("\u25CF  Auto-Ready");
                    statusBadge.setBackground(theme.roundedRect(this, theme.accentSuccessBg, Color.TRANSPARENT, 99, false));
                    statusBadge.setTextColor(theme.accentSuccessText);
                    statusSubtitle.setText("Zero-touch mode active — just plug into your car's USB port. No app or notification runs while unplugged.");
                } else {
                    statusBadge.setText("\u25CF  Paused");
                    statusBadge.setBackground(theme.roundedRect(this, theme.bgSubtle, Color.TRANSPARENT, 99, false));
                    statusBadge.setTextColor(theme.textSecondary);
                    statusSubtitle.setText("Tap Enable Zero-Touch Mode once so T-LinkX wakes automatically whenever you plug into your car.");
                }

                if (carAct) {
                    primaryActionBtn.setText("Disconnect & Reset Screen");
                    primaryActionBtn.setBackground(theme.roundedRect(this, theme.accentDangerBg, Color.TRANSPARENT, 14, true));
                    primaryActionBtn.setTextColor(theme.accentDangerText);
                } else if (daemonOk && autoTrig) {
                    primaryActionBtn.setText("Pause Auto-Trigger");
                    primaryActionBtn.setBackground(theme.roundedRect(this, theme.bgSubtle, Color.TRANSPARENT, 14, true));
                    primaryActionBtn.setTextColor(theme.textPrimary);
                } else {
                    primaryActionBtn.setText("Enable Zero-Touch Mode");
                    primaryActionBtn.setBackground(theme.roundedRect(this, theme.accentPrimary, Color.TRANSPARENT, 14, true));
                    primaryActionBtn.setTextColor(theme.accentPrimaryText);
                }

                chipTouch.setText(daemonOk ? "\u2713 Touch Ready" : "Touch Off");
                chipTouch.setTextColor(daemonOk ? theme.accentSuccessText : theme.textSecondary);
                chipTouch.setBackground(theme.roundedRect(this,
                        daemonOk ? theme.accentSuccessBg : theme.bgSubtle, Color.TRANSPARENT, 10, false));

                rootStatusValue.setText(root ? (daemonOk ? "Root OK \u2022 Bridge On" : "Root OK \u2022 Idle") : "Grant in KernelSU");
                updateAllOptionViews();
                refreshReadinessChips();
            });
        }).start();
    }

    private void updateAllOptionViews() {
        boolean dockOn = prefs.getBoolean("dock_enabled", true);
        boolean dockRight = prefs.getBoolean("dock_right", true);
        boolean autoVol = prefs.getBoolean("auto_vol_95", true);
        String eq = prefs.getString("eq_preset", BluetoothAudioController.EQ_CAR_BALANCED);
        if ("avanza_balanced".equals(eq)) eq = BluetoothAudioController.EQ_CAR_BALANCED;
        String preset = prefs.getString("preset", CarDisplayController.DEFAULT_PRESET);
        int rot = prefs.getInt("touch_rot", CarDisplayController.DEFAULT_TOUCH_ROT);
        String fit = prefs.getString("touch_fit", CarDisplayController.DEFAULT_TOUCH_FIT);

        dockSwitch.setChecked(dockOn);
        dockSideValue.setText(dockRight ? "Right (RHD)" : "Left (LHD)");
        autoVolSwitch.setChecked(autoVol);

        chipDock.setText(dockOn ? (dockRight ? "Dock: Right" : "Dock: Left") : "Dock: Off");
        chipEq.setText("EQ: " + shortEqLabel(eq));

        String[] quickEqKeys = new String[]{
                BluetoothAudioController.EQ_CAR_BALANCED,
                BluetoothAudioController.EQ_DEEP_BASS,
                BluetoothAudioController.EQ_VOCAL_CLARITY,
                BluetoothAudioController.EQ_CUSTOM
        };
        for (int i = 0; i < 4; i++) {
            boolean selected = quickEqKeys[i].equals(eq);
            eqChips[i].setBackground(theme.roundedRect(this,
                    selected ? theme.accentPrimary : theme.bgSubtle, Color.TRANSPARENT, 10, true));
            eqChips[i].setTextColor(selected ? theme.accentPrimaryText : theme.textSecondary);
        }

        for (int i = 0; i < 4; i++) {
            String act = ShortcutRouter.getActionForSlot(this, i + 1);
            voiceValues[i].setText(ShortcutRouter.labelForAction(act) + "  \u203A");
        }

        presetValue.setText(CarDisplayController.PRESET_HIDPI.equals(preset) ? "Sharp HiDPI" : "Native 1:1");
        rotValue.setText(rot + "\u00B0");
        fitValue.setText(CarDisplayController.FIT_X_INSET.equals(fit) ? "1024\u00D7600 Inset"
                : CarDisplayController.FIT_Y_INSET.equals(fit) ? "Portrait Inset" : "Full");
    }

    private String shortEqLabel(String eq) {
        if (BluetoothAudioController.EQ_DEEP_BASS.equals(eq)) return "Deep Bass";
        if (BluetoothAudioController.EQ_VOCAL_CLARITY.equals(eq)) return "Vocal";
        if (BluetoothAudioController.EQ_ROCK_POP.equals(eq)) return "Rock/Pop";
        if (BluetoothAudioController.EQ_ACOUSTIC_JAZZ.equals(eq)) return "Acoustic";
        if (BluetoothAudioController.EQ_EDM_HIPHOP.equals(eq)) return "EDM";
        if (BluetoothAudioController.EQ_NIGHT_DRIVE.equals(eq)) return "Night";
        if (BluetoothAudioController.EQ_CUSTOM.equals(eq)) return "Custom";
        if (BluetoothAudioController.EQ_OFF.equals(eq)) return "Off";
        return "Balanced";
    }

    private TextView makeStatusChip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(theme.textSecondary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(6), dp(8), dp(6), dp(8));
        tv.setBackground(theme.roundedRect(this, theme.bgSubtle, Color.TRANSPARENT, 10, false));
        return tv;
    }

    private LinearLayout makeQuickTile(String icon, String title, String subtitle, boolean danger, View.OnClickListener click) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(theme.roundedRect(this,
                danger ? theme.accentDangerBg : theme.bgSubtle, Color.TRANSPARENT, 14, true));
        tile.setPadding(dp(12), dp(12), dp(12), dp(12));
        tile.setMinimumHeight(dp(74));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(click);

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        ic.setTextColor(danger ? theme.accentDangerText : theme.accentPrimary);
        tile.addView(ic);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(danger ? theme.accentDangerText : theme.textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(4);
        tile.addView(t, tLp);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(danger ? theme.accentDangerText : theme.textSecondary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tile.addView(s);
        return tile;
    }

    private LinearLayout makeSwitchRow(String title, String subtitle, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(theme.textSecondary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        col.addView(s);

        row.addView(col);
        row.addView(sw);
        return row;
    }

    private LinearLayout makePickerRow(String title, String subtitle, TextView valueView, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setBackground(theme.roundedRect(this, Color.TRANSPARENT, Color.TRANSPARENT, 10, true));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(click);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(theme.textPrimary);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(theme.textSecondary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        col.addView(s);

        row.addView(col);
        row.addView(valueView);
        return row;
    }

    private TextView makeValueLabel() {
        TextView tv = new TextView(this);
        tv.setTextColor(theme.accentPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(8), dp(4), 0, dp(4));
        return tv;
    }

    @Override
    public void onLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (logs) {
            logs.addLast("[" + ts + "] " + msg);
            while (logs.size() > 60) logs.removeFirst();
        }
    }

    @Override
    public void onStateUpdate(boolean monitoring, boolean carActive) {
        refreshStatus();
    }

    private LinearLayout.LayoutParams weightLp(float weight, int rightMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = dp(rightMarginDp);
        return lp;
    }

    private int dp(int v) {
        return theme.dp(this, v);
    }
}
