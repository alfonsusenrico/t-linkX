package com.tlinkx.companion;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sleek Android Auto-style floating Car Dock overlay tailored for 1024x600.
 * Defaults to the right edge for RHD vehicles and collapses cleanly to an edge pill.
 */
public final class CarDockOverlay {
    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout rootView;
    private LinearLayout buttonsContainer;
    private TextView clockView;
    private TextView collapseBtn;
    private boolean showing = false;
    private boolean collapsed = false;
    private boolean rightSide = true;

    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            if (clockView != null && showing) {
                clockView.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
                main.postDelayed(this, 15000);
            }
        }
    };

    public CarDockOverlay(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return showing;
    }

    public void show(boolean onRightSide) {
        this.rightSide = onRightSide;
        main.post(() -> {
            if (showing) {
                hideImmediate();
            }
            if (!Settings.canDrawOverlays(ctx)) {
                return;
            }
            buildView();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = (rightSide ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
            lp.x = dp(6);
            lp.y = 0;
            try {
                wm.addView(rootView, lp);
                showing = true;
                clockTicker.run();
            } catch (Exception ignored) {
                showing = false;
            }
        });
    }

    public void hide() {
        main.post(this::hideImmediate);
    }

    private void hideImmediate() {
        main.removeCallbacks(clockTicker);
        if (showing && rootView != null) {
            try {
                wm.removeView(rootView);
            } catch (Exception ignored) {
            }
        }
        showing = false;
        rootView = null;
    }

    private void buildView() {
        rootView = new LinearLayout(ctx);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EB0E141C"));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#4064748B"));
        rootView.setBackground(bg);
        rootView.setElevation(dp(8));
        int pad = dp(6);
        rootView.setPadding(pad, dp(8), pad, dp(8));

        clockView = new TextView(ctx);
        clockView.setTextColor(Color.parseColor("#F1F5F9"));
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        clockView.setTypeface(Typeface.DEFAULT_BOLD);
        clockView.setGravity(Gravity.CENTER);
        clockView.setPadding(0, dp(2), 0, dp(4));
        rootView.addView(clockView);

        buttonsContainer = new LinearLayout(ctx);
        buttonsContainer.setOrientation(LinearLayout.VERTICAL);
        buttonsContainer.setGravity(Gravity.CENTER_HORIZONTAL);

        buttonsContainer.addView(makeDockBtn("\u2302", "#0284C7", v -> {
            hide();
            Intent home = new Intent(ctx, CarLauncherActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ctx.startActivity(home);
        }));
        buttonsContainer.addView(makeDockBtn("\u25EB", "#7C3AED", v ->
                ShortcutRouter.executeAction(ctx, ShortcutRouter.ACT_SPLIT_SCREEN, "Dock")));
        buttonsContainer.addView(makeDockBtn("\u25B6\u2016", "#16A34A", v ->
                ShortcutRouter.executeAction(ctx, ShortcutRouter.ACT_PLAY_PAUSE, "Dock")));
        buttonsContainer.addView(makeDockBtn("\u23ED", "#1E293B", v ->
                ShortcutRouter.executeAction(ctx, ShortcutRouter.ACT_NEXT_TRACK, "Dock")));
        buttonsContainer.addView(makeDockBtn("EQ", "#0F766E", v -> {
            Intent eq = new Intent(ctx, EqTunerActivity.class);
            eq.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            ctx.startActivity(eq);
        }));
        buttonsContainer.addView(makeDockBtn("\u263D", "#1E293B", v ->
                ShortcutRouter.executeAction(ctx, ShortcutRouter.ACT_TOGGLE_THEME, "Dock")));
        buttonsContainer.addView(makeDockBtn("\u25D0", "#1E293B", v ->
                ShortcutRouter.executeAction(ctx, ShortcutRouter.ACT_TOGGLE_BLACKOUT, "Dock")));

        rootView.addView(buttonsContainer);

        collapseBtn = makeDockBtn(rightSide ? "\u203A" : "\u2039", "#161E29", v -> toggleCollapse());
        collapseBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        rootView.addView(collapseBtn);
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        buttonsContainer.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        clockView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (collapsed) {
            collapseBtn.setText(rightSide ? "\u2039" : "\u203A");
        } else {
            collapseBtn.setText(rightSide ? "\u203A" : "\u2039");
        }
    }

    private TextView makeDockBtn(String label, String hexBg, View.OnClickListener click) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(hexBg));
        d.setCornerRadius(dp(12));
        tv.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#44FFFFFF")), d, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(38));
        lp.topMargin = dp(5);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(click);
        return tv;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }
}
