package com.tlinkx.companion;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Netflix Account-Picker inspired full-screen Car Home Launcher shown on the car head unit
 * once T-Link mirroring connects. No side dock is shown until the driver picks an app.
 */
public class CarLauncherActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView clockView;
    private TextView dateView;
    private LinearLayout cardsRow;

    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            if (clockView != null) {
                Date now = new Date();
                clockView.setText(new SimpleDateFormat("HH:mm", Locale.US).format(now));
                dateView.setText(new SimpleDateFormat("EEE, d MMM", Locale.US).format(now));
                main.postDelayed(this, 10000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupImmersiveWindow();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Keep the launcher clean and uncluttered: hide floating Car Dock while choosing an app
        Intent hideDock = new Intent(this, CarModeService.class);
        hideDock.setAction(CarModeService.ACTION_HIDE_DOCK_FOR_LAUNCHER);
        try {
            startService(hideDock);
        } catch (Exception ignored) {
        }
        populateAppCards();
        clockTicker.run();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(clockTicker);
        super.onPause();
    }

    private void setupImmersiveWindow() {
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        w.setAttributes(lp);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bgGrad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0F172A"), Color.parseColor("#070A0F")});
        root.setBackground(bgGrad);
        root.setPadding(dp(28), dp(20), dp(28), dp(20));

        // Top Bar: Brand + Live Clock
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout brandGroup = new LinearLayout(this);
        brandGroup.setOrientation(LinearLayout.HORIZONTAL);
        brandGroup.setGravity(Gravity.CENTER_VERTICAL);
        brandGroup.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView brand = new TextView(this);
        brand.setText("T-LinkX");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brandGroup.addView(brand);

        TextView badge = new TextView(this);
        badge.setText("TOYOTA");
        badge.setTextColor(Color.parseColor("#38BDF8"));
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#1838BDF8"));
        badgeBg.setCornerRadius(dp(99));
        badgeBg.setStroke(dp(1), Color.parseColor("#4438BDF8"));
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.leftMargin = dp(10);
        brandGroup.addView(badge, bLp);
        topBar.addView(brandGroup);

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.HORIZONTAL);
        clockCol.setGravity(Gravity.CENTER_VERTICAL);

        dateView = new TextView(this);
        dateView.setTextColor(Color.parseColor("#94A3B8"));
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        dateView.setPadding(0, 0, dp(12), 0);
        clockCol.addView(dateView);

        clockView = new TextView(this);
        clockView.setTextColor(Color.WHITE);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        clockView.setTypeface(Typeface.DEFAULT_BOLD);
        clockCol.addView(clockView);
        topBar.addView(clockCol);

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Center Stage: Headline + 4 Netflix-style App Cards
        LinearLayout centerStage = new LinearLayout(this);
        centerStage.setOrientation(LinearLayout.VERTICAL);
        centerStage.setGravity(Gravity.CENTER);
        centerStage.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView headline = new TextView(this);
        headline.setText("Choose your drive");
        headline.setTextColor(Color.parseColor("#E2E8F0"));
        headline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlLp.bottomMargin = dp(22);
        centerStage.addView(headline, hlLp);

        cardsRow = new LinearLayout(this);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        cardsRow.setGravity(Gravity.CENTER);
        centerStage.addView(cardsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(centerStage);

        // Bottom Utility Pills (Subtle, unobtrusive)
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);

        bottomBar.addView(makeFooterPill("\u25EB  Split Maps + Music", v -> {
            notifyAppPicked();
            ShortcutRouter.executeAction(this, ShortcutRouter.ACT_SPLIT_SCREEN, "Home");
        }));
        bottomBar.addView(makeFooterPill("\u263D  Day / Night", v ->
                ShortcutRouter.executeAction(this, ShortcutRouter.ACT_TOGGLE_THEME, "Home")));
        bottomBar.addView(makeFooterPill("\u2699  T-LinkX Settings", v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        }));

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void populateAppCards() {
        if (cardsRow == null) return;
        cardsRow.removeAllViews();

        for (int i = 0; i < CarLauncherConfig.SLOT_COUNT; i++) {
            final CarLauncherConfig.AppEntry entry = CarLauncherConfig.resolveSlot(this, i);
            final String accentHex = CarLauncherConfig.SLOT_ACCENTS[i];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setClickable(true);
            card.setFocusable(true);
            card.setPadding(dp(14), dp(22), dp(14), dp(20));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.parseColor("#131C2B"));
            cardBg.setCornerRadius(dp(22));
            cardBg.setStroke(dp(1), Color.parseColor("#2A3B54"));
            card.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#44" + accentHex.substring(1))),
                    cardBg, null));
            card.setElevation(dp(6));

            // Rounded icon pedestal with subtle accent tint
            LinearLayout iconFrame = new LinearLayout(this);
            iconFrame.setGravity(Gravity.CENTER);
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setColor(Color.parseColor("#1C" + accentHex.substring(1)));
            iconBg.setCornerRadius(dp(22));
            iconBg.setStroke(dp(1), Color.parseColor("#55" + accentHex.substring(1)));
            iconFrame.setBackground(iconBg);
            LinearLayout.LayoutParams ifLp = new LinearLayout.LayoutParams(dp(92), dp(92));
            ifLp.bottomMargin = dp(14);
            iconFrame.setLayoutParams(ifLp);

            if (entry.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(entry.icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(64)));
                iconFrame.addView(iv);
            } else {
                TextView fallbackIcon = new TextView(this);
                fallbackIcon.setText(entry.label.length() > 0 ? entry.label.substring(0, 1).toUpperCase(Locale.US) : "?");
                fallbackIcon.setTextColor(Color.parseColor(accentHex));
                fallbackIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
                fallbackIcon.setTypeface(Typeface.DEFAULT_BOLD);
                iconFrame.addView(fallbackIcon);
            }
            card.addView(iconFrame);

            TextView label = new TextView(this);
            label.setText(entry.label);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            card.addView(label);

            card.setOnClickListener(v -> launchPickedApp(entry.packageName));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(192), 1f);
            lp.leftMargin = dp(8);
            lp.rightMargin = dp(8);
            cardsRow.addView(card, lp);
        }
    }

    private void launchPickedApp(String pkg) {
        notifyAppPicked();
        new Thread(() -> CarDisplayController.launchApp(this, pkg)).start();
    }

    private void notifyAppPicked() {
        Intent i = new Intent(this, CarModeService.class);
        i.setAction(CarModeService.ACTION_APP_PICKED);
        try {
            startService(i);
        } catch (Exception ignored) {
        }
    }

    private TextView makeFooterPill(String text, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#CBD5E1"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#141E2E"));
        bg.setCornerRadius(dp(99));
        bg.setStroke(dp(1), Color.parseColor("#26354C"));
        tv.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), bg, null));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
