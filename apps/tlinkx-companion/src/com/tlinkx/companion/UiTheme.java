package com.tlinkx.companion;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared design tokens and Material-inspired UI primitives supporting both Light and Dark themes.
 */
public final class UiTheme {
    public final boolean isDark;
    public final int bgCanvas;
    public final int bgCard;
    public final int bgCardElevated;
    public final int bgSubtle;
    public final int strokeCard;
    public final int textPrimary;
    public final int textSecondary;
    public final int textMuted;
    public final int accentPrimary;
    public final int accentPrimaryText;
    public final int accentSuccessBg;
    public final int accentSuccessText;
    public final int accentDangerBg;
    public final int accentDangerText;
    public final int accentWarnBg;
    public final int accentWarnText;
    public final int rippleColor;
    public final int dividerColor;

    public UiTheme(Context ctx) {
        int nightMask = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        this.isDark = (nightMask == Configuration.UI_MODE_NIGHT_YES);

        if (isDark) {
            bgCanvas = Color.parseColor("#0B0F14");
            bgCard = Color.parseColor("#141B24");
            bgCardElevated = Color.parseColor("#1B2430");
            bgSubtle = Color.parseColor("#1E2836");
            strokeCard = Color.parseColor("#263344");
            textPrimary = Color.parseColor("#F1F5F9");
            textSecondary = Color.parseColor("#94A3B8");
            textMuted = Color.parseColor("#64748B");
            accentPrimary = Color.parseColor("#0EA5E9");
            accentPrimaryText = Color.parseColor("#FFFFFF");
            accentSuccessBg = Color.parseColor("#123524");
            accentSuccessText = Color.parseColor("#4ADE80");
            accentDangerBg = Color.parseColor("#3B161B");
            accentDangerText = Color.parseColor("#FCA5A5");
            accentWarnBg = Color.parseColor("#3A250B");
            accentWarnText = Color.parseColor("#FCD34D");
            rippleColor = Color.parseColor("#33FFFFFF");
            dividerColor = Color.parseColor("#223040");
        } else {
            bgCanvas = Color.parseColor("#F3F6FA");
            bgCard = Color.parseColor("#FFFFFF");
            bgCardElevated = Color.parseColor("#F8FAFC");
            bgSubtle = Color.parseColor("#E9EFF6");
            strokeCard = Color.parseColor("#DCE4EE");
            textPrimary = Color.parseColor("#0F172A");
            textSecondary = Color.parseColor("#475569");
            textMuted = Color.parseColor("#64748B");
            accentPrimary = Color.parseColor("#0284C7");
            accentPrimaryText = Color.parseColor("#FFFFFF");
            accentSuccessBg = Color.parseColor("#DCFCE7");
            accentSuccessText = Color.parseColor("#15803D");
            accentDangerBg = Color.parseColor("#FEE2E2");
            accentDangerText = Color.parseColor("#B91C1C");
            accentWarnBg = Color.parseColor("#FEF3C7");
            accentWarnText = Color.parseColor("#B45309");
            rippleColor = Color.parseColor("#22000000");
            dividerColor = Color.parseColor("#E2E8F0");
        }
    }

    public int dp(Context ctx, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    public Drawable roundedRect(Context ctx, int fillColor, int strokeColor, int radiusDp, boolean withRipple) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(ctx, radiusDp));
        if (strokeColor != Color.TRANSPARENT) {
            gd.setStroke(dp(ctx, 1), strokeColor);
        }
        if (!withRipple) return gd;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), gd, null);
    }

    public LinearLayout makeCard(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(ctx, bgCard, strokeCard, 18, false));
        int p = dp(ctx, 16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 14);
        card.setLayoutParams(lp);
        return card;
    }

    public TextView makeSectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(textSecondary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 10);
        tv.setLayoutParams(lp);
        return tv;
    }

    public View makeDivider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(dividerColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1)));
        lp.topMargin = dp(ctx, 10);
        lp.bottomMargin = dp(ctx, 10);
        v.setLayoutParams(lp);
        return v;
    }

    public TextView makePillButton(Context ctx, String label, int bgColor, int fgColor, int heightDp) {
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextColor(fgColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(roundedRect(ctx, bgColor, Color.TRANSPARENT, 14, true));
        btn.setMinimumHeight(dp(ctx, heightDp));
        btn.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
        btn.setClickable(true);
        btn.setFocusable(true);
        return btn;
    }
}
