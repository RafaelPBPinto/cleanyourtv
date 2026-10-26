package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * With top_shelf, a dock app that has no titles to show still gets a shelf: its own artwork as a floating card with a
 * glass edge and one sweep of light when it appears, the name and "Last opened" beside it like a tvOS lockup, and a
 * glow in the app's colour around the card that fades out well before the rest of the screen. The glow is one small
 * bitmap stretched over the screen and tinted, as the ambient glow is, so changing apps never repaints it.
 */
final class AppSpotlight {
    private static final float CARD_W_DP = 208, CARD_H_DP = 117, RADIUS_DP = 16;
    /** Where the glow is brightest, as a share of the screen: behind the card. */
    private static final float GLOW_X = 0.17f, GLOW_Y = 0.52f;
    private static final float GLOW_ALPHA = 0.8f;

    private final Context ctx;
    private final float density;
    private final boolean animate;
    private final FrameLayout card;
    private final ImageView artwork;
    private final View sheen;
    private final ImageView glow;
    private int glowColor;
    private Drawable shown;

    private AppSpotlight(Context ctx, ViewGroup root, View below, LinearLayout hero, TextView name, boolean animate) {
        this.ctx = ctx;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.animate = animate;

        // The hero's name, meta line and dots move into a column beside the card.
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        while (hero.getChildCount() > 0) {
            View v = hero.getChildAt(0);
            hero.removeViewAt(0);
            text.addView(v);
        }
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        // A card is a solid block where text is not: give it more air above the row that peeks in below it.
        hero.setPadding(hero.getPaddingLeft(), hero.getPaddingTop(), hero.getPaddingRight(), Math.round(22 * density));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f);

        card = new FrameLayout(ctx);
        float radius = RADIUS_DP * density;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        card.setElevation(28 * density);
        card.setBackground(new ColorDrawable(0xFF141416));
        artwork = new ImageView(ctx);
        artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(artwork, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // A band of light that crosses the card once, the way a tvOS icon catches the light as it is picked up.
        sheen = new View(ctx);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}));
        sheen.setAlpha(0f);
        card.addView(sheen, new FrameLayout.LayoutParams(Math.round(CARD_W_DP * 0.6f * density), ViewGroup.LayoutParams.MATCH_PARENT));
        // The glass edge: a hairline of light around the card.
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(radius);
        edge.setStroke(Math.max(1, Math.round(density)), 0x33FFFFFF);
        card.setForeground(edge);

        hero.addView(card, new LinearLayout.LayoutParams(Math.round(CARD_W_DP * density), Math.round(CARD_H_DP * density)));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.setMarginStart(Math.round(36 * density));
        hero.addView(text, textLp);

        glow = new ImageView(ctx);
        glow.setScaleType(ImageView.ScaleType.FIT_XY);
        glow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glow.forceHasOverlappingRendering(false);
        glow.setImageDrawable(glowDrawable());
        glow.setAlpha(0f);
        root.addView(glow, root.indexOfChild(below) + 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Null when a view it needs is missing. */
    static AppSpotlight attach(Context ctx, ViewGroup root, View below, LinearLayout hero, TextView name, boolean animate) {
        if (root == null || below == null || hero == null || name == null) return null;
        return new AppSpotlight(ctx, root, below, hero, name, animate);
    }

    /** Puts this app's artwork on the card and its colour in the glow, and shows both. */
    void show(Drawable live, int color) {
        boolean changed = fill(live, color);
        glow.animate().cancel();
        if (!animate) {
            glow.setColorFilter(glowColor);
            glow.setAlpha(GLOW_ALPHA);
            return;
        }
        if (changed && glow.getAlpha() > 0f) {
            glow.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                glow.setColorFilter(glowColor);
                glow.animate().alpha(GLOW_ALPHA).setDuration(520).start();
            }).start();
        } else {
            glow.setColorFilter(glowColor);
            glow.animate().alpha(GLOW_ALPHA).setDuration(520).start();
        }
        if (changed) sweep();
    }

    void hide() {
        glow.animate().cancel();
        if (animate) glow.animate().alpha(0f).setDuration(300).start();
        else glow.setAlpha(0f);
    }

    /** True when the card now shows a different app. */
    private boolean fill(Drawable live, int color) {
        if (live == shown) return false;
        shown = live;
        glowColor = (color & 0x00FFFFFF) | 0xFF000000;
        // A private copy: a tile's own drawable would blank if the card changed its bounds.
        Drawable d = live == null ? null
                : live.getConstantState() != null ? live.getConstantState().newDrawable(ctx.getResources()).mutate() : live;
        boolean banner = d != null && d.getIntrinsicHeight() > 0 && d.getIntrinsicWidth() / (float) d.getIntrinsicHeight() > 1.4f;
        artwork.setImageDrawable(d);
        if (banner) {
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artwork.setPadding(0, 0, 0, 0);
            card.setBackground(new ColorDrawable(0xFF141416));
        } else {
            // An icon sits in the middle of a card in its own colour, darkened, as tvOS shows an app without a banner.
            artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Math.round(CARD_H_DP * 0.2f * density);
            artwork.setPadding(pad, pad, pad, pad);
            card.setBackground(new ColorDrawable(blend(glowColor, 0xFF101012, 0.55f)));
        }
        return true;
    }

    private void sweep() {
        if (!animate) return;
        float w = CARD_W_DP * density;
        sheen.animate().cancel();
        sheen.setTranslationX(-w * 0.7f);
        sheen.setAlpha(1f);
        sheen.animate().translationX(w * 1.1f).setStartDelay(220).setDuration(1100).setInterpolator(Motion.EASE)
                .withEndAction(() -> sheen.setAlpha(0f)).start();
    }

    /** White light falling off from behind the card, tinted per app; stretched, it reads as a soft spot of colour. */
    private Drawable glowDrawable() {
        int w = 320, h = 180;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(w * GLOW_X, h * GLOW_Y, w * 0.62f,
                new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x33FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.3f, 0.65f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // Keep the glow off the dock: it fades out downwards before the tiles start.
        Paint fade = new Paint();
        fade.setShader(new LinearGradient(0, h * 0.6f, 0, h * 0.82f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        c.drawRect(0, 0, w, h, fade);
        BitmapDrawable d = new BitmapDrawable(ctx.getResources(), bmp);
        d.setFilterBitmap(true);
        return d;
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }
}
