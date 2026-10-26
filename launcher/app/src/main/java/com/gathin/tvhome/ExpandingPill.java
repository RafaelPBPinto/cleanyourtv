package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Layout;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * A top bar pill that rests as a round icon and slides its label out while focused (config.xml expand_on_focus).
 * The label stays in the TextView the whole time, so code that updates it (the Wi-Fi name) needs no change: the
 * pill only animates its width, which clips the text, and fades the text in behind the growing edge.
 */
final class ExpandingPill {

    private static final int EXPAND_MS = 380;
    private static final int COLLAPSE_MS = 300;
    /** The label starts to show once the pill is this far open, so it never fades in over the icon. */
    private static final float LABEL_START = 0.3f;

    private final TextView pill;
    private final boolean reducedMotion;
    private final int collapsedWidth;
    /** The icon's inset in the round pill. */
    private final int collapsedPadding;
    /** TopButtonBase's padding and icon gap, which the open pill keeps so its label looks as it always did. */
    private final int startPadding;
    private final int endPadding;
    private final int iconGap;
    private final TextUtils.TruncateAt ellipsize;
    private final ColorStateList textColors;
    private ValueAnimator animator;
    /** 0 is the round icon, 1 the full label. */
    private float progress;

    private ExpandingPill(TextView pill, float density, boolean reducedMotion) {
        this.pill = pill;
        this.reducedMotion = reducedMotion;
        collapsedWidth = Math.round(42 * density);
        collapsedPadding = Math.round(10 * density);
        startPadding = Math.round(16 * density);
        endPadding = Math.round(20 * density);
        iconGap = Math.round(9 * density);
        ellipsize = pill.getEllipsize();
        textColors = pill.getTextColors();
    }

    /** Makes {@code pill} expanding, keeping any focus listener it already had. Its text is the label. */
    static void attach(TextView pill, float density, boolean reducedMotion) {
        ExpandingPill e = new ExpandingPill(pill, density, reducedMotion);
        pill.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        pill.setSingleLine(true);
        if (TextUtils.isEmpty(pill.getText()) && pill.getContentDescription() != null) pill.setText(pill.getContentDescription());
        e.apply(0f);
        View.OnFocusChangeListener previous = pill.getOnFocusChangeListener();
        pill.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            e.animateTo(focused ? 1f : 0f);
        });
        pill.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                // A new Wi-Fi name while open: resize to it rather than clip it or leave a gap.
                if (e.progress > 0f) e.apply(e.progress);
            }
        });
    }

    private int expandedWidth() {
        CharSequence text = pill.getText();
        if (TextUtils.isEmpty(text)) return collapsedWidth;
        Drawable icon = pill.getCompoundDrawablesRelative()[0];
        int iconWidth = icon != null ? icon.getIntrinsicWidth() + iconGap : 0;
        int full = startPadding + iconWidth + (int) Math.ceil(Layout.getDesiredWidth(text, pill.getPaint())) + endPadding;
        return Math.max(collapsedWidth, Math.min(full, pill.getMaxWidth()));
    }

    private void animateTo(float target) {
        if (animator != null) animator.cancel();
        if (target > 0f) pill.setEllipsize(null);   // clipped by the growing edge, not cut to "…" at every width
        animator = ValueAnimator.ofFloat(progress, target);
        long full = reducedMotion ? Motion.REDUCED_MS : target > 0f ? EXPAND_MS : COLLAPSE_MS;
        animator.setDuration(Math.max(1, Math.round(full * Math.abs(target - progress))));
        animator.setInterpolator(Motion.EASE);
        animator.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && target >= 1f) {
                    pill.setTextColor(textColors);
                    pill.setEllipsize(ellipsize);
                }
            }
        });
        animator.start();
    }

    private void apply(float p) {
        progress = p;
        // The icon eases from its place in the circle to its place in the labelled pill.
        pill.setPaddingRelative(Math.round(collapsedPadding + (startPadding - collapsedPadding) * p), 0,
                Math.round(collapsedPadding + (endPadding - collapsedPadding) * p), 0);
        pill.setCompoundDrawablePadding(Math.round(iconGap * p));
        ViewGroup.LayoutParams lp = pill.getLayoutParams();
        int width = Math.round(collapsedWidth + (expandedWidth() - collapsedWidth) * p);
        if (lp.width != width) {
            lp.width = width;
            pill.setLayoutParams(lp);
        }
        float alpha = Math.max(0f, Math.min(1f, (p - LABEL_START) / (1f - LABEL_START)));
        int color = textColors.getColorForState(pill.getDrawableState(), textColors.getDefaultColor());
        pill.setTextColor(Color.argb(Math.round(Color.alpha(color) * alpha), Color.red(color), Color.green(color), Color.blue(color)));
    }
}
