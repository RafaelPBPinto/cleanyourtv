package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * top_shelf parks every row where the dock rests, so the shelf above can show the focused app's titles on any row.
 * The rows that scroll up past that line would then cover the shelf; this erases them, but for the bottom edge of
 * the row just above, faded in over {@code peek} pixels, so Home still shows there is something to go back up to.
 * Erasing needs the grid in a hardware layer (as TopFade does), or it would punch through the whole window.
 */
final class RowPeek extends RecyclerView.ItemDecoration {

    private final float peek;
    private final Paint paint = new Paint();
    private float shaderLine = -1f;

    private RowPeek(float peek) {
        this.peek = peek;
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    static void attach(RecyclerView grid, float peek) {
        grid.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        grid.addItemDecoration(new RowPeek(peek));
    }

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        float line = parent.getPaddingTop();
        if (line <= 0f) return;
        if (line != shaderLine) {
            // Fully erased above the strip; within it the row above comes back, still faint where it meets the line.
            paint.setShader(new LinearGradient(0f, line - peek, 0f, line, new int[]{0xFF000000, 0x8C000000, 0x33000000},
                    new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
            shaderLine = line;
        }
        c.drawRect(0f, Math.min(0f, line - peek), parent.getWidth(), line, paint);
    }
}
