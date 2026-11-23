package com.tlinkx.companion;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Interactive 10-Band Car Audio EQ Tuner with live draggable frequency-response spline graph.
 * Dragging any frequency column updates Android DynamicsProcessing live in real time.
 */
public class EqTunerActivity extends Activity {
    private static final String PREFS = "tlinkx_prefs";

    private UiTheme theme;
    private SharedPreferences prefs;
    private EqGraphView graphView;
    private TextView activePresetLabel;
    private LinearLayout presetStrip;
    private final float[] gains = new float[BluetoothAudioController.BAND_COUNT];
    private String currentPreset;
    private BluetoothAudioController localPreviewAudio;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        theme = new UiTheme(this);
        styleBars();

        currentPreset = prefs.getString("eq_preset", BluetoothAudioController.EQ_AVANZA_BALANCED);
        float[] init = BluetoothAudioController.getGainsForPreset(this, currentPreset);
        System.arraycopy(init, 0, gains, 0, gains.length);

        buildUi();
        applyLiveAudio();
    }

    @Override
    protected void onDestroy() {
        if (localPreviewAudio != null && BluetoothAudioController.getActive() == localPreviewAudio) {
            // Leave service audio running if CarModeService owns it; only release local preview
            localPreviewAudio.deactivateCarAudio();
        }
        super.onDestroy();
    }

    private void styleBars() {
        Window w = getWindow();
        w.setStatusBarColor(theme.bgCanvas);
        w.setNavigationBarColor(theme.bgCanvas);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (!theme.isDark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        decor.setSystemUiVisibility(flags);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bgCanvas);
        root.setFitsSystemWindows(true);
        int pad = dp(16);
        root.setPadding(pad, dp(12), pad, dp(16));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(12);
        header.setLayoutParams(hLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("10-Band Car Audio EQ");
        title.setTextColor(theme.textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        activePresetLabel = new TextView(this);
        activePresetLabel.setTextColor(theme.accentPrimary);
        activePresetLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        activePresetLabel.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(activePresetLabel);
        header.addView(titleCol);

        TextView resetBtn = theme.makePillButton(this, "Flat", theme.bgSubtle, theme.textPrimary, 40);
        resetBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        resetBtn.setOnClickListener(v -> {
            for (int i = 0; i < gains.length; i++) gains[i] = 0f;
            currentPreset = BluetoothAudioController.EQ_CUSTOM;
            BluetoothAudioController.saveCustomGains(this, gains);
            refreshPresetChips();
            graphView.invalidate();
            applyLiveAudio();
        });
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rbLp.rightMargin = dp(8);
        header.addView(resetBtn, rbLp);

        TextView doneBtn = theme.makePillButton(this, "Done", theme.accentPrimary, theme.accentPrimaryText, 40);
        doneBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        doneBtn.setOnClickListener(v -> finish());
        header.addView(doneBtn);
        root.addView(header);

        // Horizontal Presets Bar
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        presetStrip = new LinearLayout(this);
        presetStrip.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(presetStrip);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.bottomMargin = dp(12);
        root.addView(hsv, pLp);

        // Interactive Graph Card
        LinearLayout graphCard = theme.makeCard(this);
        graphCard.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        graphCard.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView hint = new TextView(this);
        hint.setText("Touch & drag anywhere on the curve to tune live (-12 dB to +12 dB)");
        hint.setTextColor(theme.textSecondary);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.bottomMargin = dp(6);
        graphCard.addView(hint, hintLp);

        graphView = new EqGraphView(this);
        graphView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        graphCard.addView(graphView);
        root.addView(graphCard);

        setContentView(root);
        refreshPresetChips();
    }

    private void refreshPresetChips() {
        activePresetLabel.setText("Active Preset: " + BluetoothAudioController.labelForPreset(currentPreset));
        presetStrip.removeAllViews();
        for (String key : BluetoothAudioController.ALL_PRESETS) {
            boolean selected = key.equals(currentPreset);
            TextView chip = new TextView(this);
            chip.setText(BluetoothAudioController.labelForPreset(key));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(dp(14), dp(9), dp(14), dp(9));
            chip.setBackground(theme.roundedRect(this,
                    selected ? theme.accentPrimary : theme.bgCard,
                    selected ? Color.TRANSPARENT : theme.strokeCard, 99, true));
            chip.setTextColor(selected ? theme.accentPrimaryText : theme.textSecondary);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                currentPreset = key;
                prefs.edit().putString("eq_preset", key).apply();
                float[] presetGains = BluetoothAudioController.getGainsForPreset(this, key);
                System.arraycopy(presetGains, 0, gains, 0, gains.length);
                refreshPresetChips();
                graphView.invalidate();
                applyLiveAudio();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            presetStrip.addView(chip, lp);
        }
    }

    private void applyLiveAudio() {
        BluetoothAudioController ctrl = BluetoothAudioController.getActive();
        if (ctrl == null) {
            localPreviewAudio = new BluetoothAudioController(this);
            ctrl = localPreviewAudio;
        }
        if (BluetoothAudioController.EQ_OFF.equals(currentPreset)) {
            ctrl.applyEqPreset(BluetoothAudioController.EQ_OFF);
        } else {
            ctrl.applyRawGains(gains, BluetoothAudioController.labelForPreset(currentPreset));
        }
    }

    private final class EqGraphView extends View {
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nodeOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nodeInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint valPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int activeBand = -1;

        public EqGraphView(Context context) {
            super(context);
            gridPaint.setColor(theme.dividerColor);
            gridPaint.setStrokeWidth(dp(1));

            zeroPaint.setColor(theme.textMuted);
            zeroPaint.setStrokeWidth(dp(1));

            linePaint.setColor(theme.accentPrimary);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(3));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);

            fillPaint.setStyle(Paint.Style.FILL);

            nodeOuterPaint.setColor(theme.accentPrimary);
            nodeOuterPaint.setStyle(Paint.Style.FILL);

            nodeInnerPaint.setColor(theme.bgCard);
            nodeInnerPaint.setStyle(Paint.Style.FILL);

            textPaint.setColor(theme.textSecondary);
            textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10.5f, getResources().getDisplayMetrics()));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);

            valPaint.setColor(theme.textPrimary);
            valPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9.5f, getResources().getDisplayMetrics()));
            valPaint.setTextAlign(Paint.Align.CENTER);
            valPaint.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float padLeft = dp(34);
            float padRight = dp(16);
            float padTop = dp(24);
            float padBottom = dp(28);
            float plotW = w - padLeft - padRight;
            float plotH = h - padTop - padBottom;

            // Horizontal dB gridlines: +12, +6, 0, -6, -12
            float[] dbMarks = new float[]{12f, 6f, 0f, -6f, -12f};
            textPaint.setTextAlign(Paint.Align.RIGHT);
            for (float db : dbMarks) {
                float y = padTop + plotH * (12f - db) / 24f;
                c.drawLine(padLeft, y, w - padRight, y, db == 0f ? zeroPaint : gridPaint);
                String lbl = (db > 0 ? "+" : "") + (int) db;
                c.drawText(lbl, padLeft - dp(6), y + dp(4), textPaint);
            }

            // Compute 10 band coordinates
            float[] xs = new float[BluetoothAudioController.BAND_COUNT];
            float[] ys = new float[BluetoothAudioController.BAND_COUNT];
            textPaint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < BluetoothAudioController.BAND_COUNT; i++) {
                xs[i] = padLeft + (plotW * i) / (BluetoothAudioController.BAND_COUNT - 1);
                ys[i] = padTop + plotH * (12f - gains[i]) / 24f;
                c.drawLine(xs[i], padTop, xs[i], padTop + plotH, gridPaint);
                c.drawText(BluetoothAudioController.BAND_LABELS[i], xs[i], h - dp(8), textPaint);
            }

            // Build smooth cubic spline curve
            Path curve = new Path();
            curve.moveTo(xs[0], ys[0]);
            for (int i = 0; i < xs.length - 1; i++) {
                float cx = (xs[i] + xs[i + 1]) * 0.5f;
                curve.cubicTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1]);
            }

            // Gradient fill under curve
            Path fill = new Path(curve);
            fill.lineTo(xs[xs.length - 1], padTop + plotH);
            fill.lineTo(xs[0], padTop + plotH);
            fill.close();
            int accent = theme.accentPrimary;
            int topFill = Color.argb(95, Color.red(accent), Color.green(accent), Color.blue(accent));
            int botFill = Color.argb(8, Color.red(accent), Color.green(accent), Color.blue(accent));
            fillPaint.setShader(new LinearGradient(0, padTop, 0, padTop + plotH, topFill, botFill, Shader.TileMode.CLAMP));
            c.drawPath(fill, fillPaint);

            // Stroke curve
            c.drawPath(curve, linePaint);

            // Draw nodes + live dB labels
            for (int i = 0; i < xs.length; i++) {
                float rOuter = (i == activeBand) ? dp(10) : dp(7);
                float rInner = (i == activeBand) ? dp(4.5f) : dp(3.5f);
                c.drawCircle(xs[i], ys[i], rOuter, nodeOuterPaint);
                c.drawCircle(xs[i], ys[i], rInner, nodeInnerPaint);
                String dbStr = String.format(Locale.US, "%+.1f", gains[i]);
                c.drawText(dbStr, xs[i], ys[i] - dp(12), valPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return false;

            float padLeft = dp(34);
            float padRight = dp(16);
            float padTop = dp(24);
            float padBottom = dp(28);
            float plotW = w - padLeft - padRight;
            float plotH = h - padTop - padBottom;

            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                float tx = ev.getX();
                float ty = ev.getY();
                int idx = Math.round(((tx - padLeft) / plotW) * (BluetoothAudioController.BAND_COUNT - 1));
                idx = Math.max(0, Math.min(BluetoothAudioController.BAND_COUNT - 1, idx));
                activeBand = idx;

                float norm = (ty - padTop) / plotH;
                norm = Math.max(0f, Math.min(1f, norm));
                float db = 12f - (norm * 24f);
                db = Math.round(db * 2f) / 2f; // 0.5 dB precision steps
                gains[idx] = db;

                if (!BluetoothAudioController.EQ_CUSTOM.equals(currentPreset)) {
                    currentPreset = BluetoothAudioController.EQ_CUSTOM;
                    refreshPresetChips();
                }
                BluetoothAudioController.saveCustomGains(EqTunerActivity.this, gains);
                applyLiveAudio();
                invalidate();
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activeBand = -1;
                invalidate();
                return true;
            }
            return super.onTouchEvent(ev);
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
