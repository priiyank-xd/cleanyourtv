package com.gathin.tvhome;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * The soft white halo around a focused item in the glass look (fork only). It paints only outside the item's shape
 * (an outer blur), so text and logos inside are never washed out. Used as a foreground it spills past the view's
 * bounds, so the parent must not clip its children; {@code inset} draws the shape that far inside the bounds instead,
 * for a glow view placed behind an item that clips to its outline. Android draws blur filters with the GPU from 9
 * (API 28); before that the halo is left out rather than drawn as a hard edge.
 */
final class GlowDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shape = new RectF();
    private final float radius, inset;

    /** {@code radius} in pixels, or below 0 for a circle; {@code blur} is how far the halo reaches, in pixels. */
    GlowDrawable(float radius, float blur, float inset, int color) {
        this.radius = radius;
        this.inset = inset;
        paint.setColor(color);
        if (blur > 0) paint.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.OUTER));
    }

    @Override
    public void draw(Canvas canvas) {
        if (Build.VERSION.SDK_INT < 28) return;
        shape.set(getBounds());
        shape.inset(inset, inset);
        if (radius < 0) canvas.drawOval(shape, paint);
        else canvas.drawRoundRect(shape, radius, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
