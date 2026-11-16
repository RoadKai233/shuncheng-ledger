package cn.personal.shunfeng;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;

/** Local-only glass surfaces. Backdrop pixels are sampled and cached only on explicit refresh. */
public final class GlassStyle {
    private static final int GREEN = 0xff17775c;
    private static final int MUTED = 0xff52685f;
    private GlassStyle() {}

    public static void applyButton(Button button, boolean primary) {
        applyButton(button, primary, systemDark(button.getContext()));
    }

    public static void applyButton(Button button, boolean primary, boolean dark) {
        float radius = dp(button, 26);
        button.setBackgroundTintList(null);
        button.setBackground(ripple(button, radius, primary, false, dark));
        button.setTextColor(new ColorStateList(
            new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            dark ? new int[]{0xff70877b, primary ? 0xffa7efce : 0xffd6e8dd}
                 : new int[]{0xff95a79f, primary ? 0xff075f4b : 0xff214f43}));
        button.setStateListAnimator(null);
        button.setElevation(dp(button, primary ? 2.5f : 1.3f));
        outline(button, radius);
        pressFeedback(button);
    }

    public static void applyChip(View view, boolean selected) {
        applyChip(view, selected, systemDark(view.getContext()));
    }

    public static void applyChip(View view, boolean selected, boolean dark) {
        float radius = dp(view, 24);
        view.setBackgroundTintList(null);
        view.setBackground(ripple(view, radius, false, selected, dark));
        view.setStateListAnimator(null);
        view.setElevation(dp(view, selected ? 1.5f : 0.4f));
        outline(view, radius);
        pressFeedback(view);
    }

    private static Drawable ripple(View view, float radius, boolean primary, boolean selected, boolean dark) {
        GlassSurface surface = new GlassSurface(radius, dp(view, 0.75f), primary, selected, dark);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE); mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x2a9ae6c2 : primary ? 0x24ffffff : 0x1817775c), surface, mask);
    }
    private static void outline(View view, final float radius) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline result) {
                result.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                result.setAlpha(0.20f);
            }
        });
        view.setClipToOutline(true);
    }
    private static void pressFeedback(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
            if (!v.isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(100)
                        .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).setDuration(170)
                        .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                default: break;
            }
            // Let the native View perform its click, ripple, focus and accessibility handling.
            return false;
            }
        });
    }
    private static boolean systemDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static float dp(View view, float value) {
        return view.getResources().getDisplayMetrics().density * value;
    }

    private static final class GlassSurface extends Drawable {
        private final float radius, edgeWidth;
        private final boolean primary, selected, dark;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private int alpha = 255;
        private boolean enabled = true;
        private ColorFilter filter;
        GlassSurface(float radius, float edgeWidth, boolean primary, boolean selected, boolean dark) {
            this.radius = radius; this.edgeWidth = edgeWidth; this.primary = primary; this.selected = selected; this.dark = dark;
        }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            rect.set(bounds.left + edgeWidth, bounds.top + edgeWidth, bounds.right - edgeWidth, bounds.bottom - edgeWidth);
            paint.setStyle(Paint.Style.FILL); paint.setColorFilter(filter);
            paint.setAlpha(enabled ? alpha : (int) (alpha * 0.4f));
            // Tint is transparent and concentrated at the lower rim, never an opaque green fill.
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                dark ? (primary ? new int[]{0x604c9a7c, 0x385ba98b, 0x70447e66}
                                : selected ? new int[]{0x4c618875, 0x263d715a, 0x48577765}
                                : new int[]{0x34455c50, 0x184c6557, 0x40374d41})
                     : (primary ? new int[]{0x549ff4dc, 0x36c6fff0, 0x706ac9b2}
                                : selected ? new int[]{0x65ffffff, 0x28d8fff1, 0x50b6e4d3}
                                : new int[]{0x65ffffff, 0x18ffffff, 0x38d3e7df}),
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            int saved = canvas.save(); canvas.clipPath(clip);
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                dark ? new int[]{0x30cde9d9, 0x00cde9d9, 0x00cde9d9, 0x16a0d4b8}
                     : new int[]{0x78ffffff, 0x00ffffff, 0x00ffffff, 0x26ffffff},
                new float[]{0f, 0.32f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            canvas.restoreToCount(saved);
            // Uneven bright/dark edge response reads as glass thickness, not a raised white pill.
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(edgeWidth);
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                dark ? new int[]{0x75bfe6cf, 0x2a6d9a80, 0x68010905, 0x50aad4bc}
                     : new int[]{0xf5ffffff, 0x5cffffff, 0x1639584d, 0xbaffffff},
                new float[]{0f, 0.37f, 0.65f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null); paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] states) {
            boolean next = false;
            for (int state : states) if (state == android.R.attr.state_enabled) next = true;
            if (next == enabled) return false;
            enabled = next; invalidateSelf(); return true;
        }
        @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { this.filter = filter; invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Clear glass: the background is genuinely sampled and refracted, not hidden by a white card. */
    public static final class Panel extends LinearLayout {
        private static final int DOWNSAMPLE = 6;
        private static final int BLUR_RADIUS = 1;
        private static final int SAMPLE_PADDING = 48;
        private final View backdrop;
        private boolean dark;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path roundedClip = new Path(), lensClip = new Path();
        private final RectF surface = new RectF(), destination = new RectF(), border = new RectF(), lens = new RectF();
        private final int[] panelLocation = new int[2], sourceLocation = new int[2];
        private Bitmap sample, refracted;
        private Canvas sampleCanvas;
        private int[] pixels, scratch, opticalPixels;
        private float[] sampleX, sampleY;
        private boolean dirty = true, capturing;
        private final float corner;
        private int selectedIndex;
        private float selectedPosition, motion;
        private ValueAnimator selectionAnimator;
        public interface OnSelectionRequestedListener { void onSelectionRequested(int index); }
        private OnSelectionRequestedListener selectionRequestedListener;
        private final int touchSlop;
        private boolean dragging;
        private float downX, downY, dragStartPosition;
        private int activePointerId = -1;

        public Panel(Context context, View backdrop) {
            this(context, backdrop, systemDark(context));
        }
        public Panel(Context context, View backdrop, boolean dark) {
            super(context); this.backdrop = backdrop; this.dark = dark;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            corner = dp(this, 36);
            setWillNotDraw(false); setOrientation(HORIZONTAL);
            setBackgroundColor(Color.TRANSPARENT);
            setElevation(dp(this, 10));
            outline(this, corner);
        }
        public void setDarkMode(boolean dark) {
            if (this.dark == dark) return;
            this.dark = dark;
            releaseSample();
            refreshBackdrop();
        }
        public void setOnSelectionRequestedListener(OnSelectionRequestedListener listener) {
            selectionRequestedListener = listener;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (selectionRequestedListener == null) return super.onInterceptTouchEvent(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                activePointerId = event.getPointerId(0); downX = event.getX(); downY = event.getY();
                dragStartPosition = selectedPosition; dragging = false;
            } else if (action == MotionEvent.ACTION_MOVE && activePointerId != -1) {
                int pointer = event.findPointerIndex(activePointerId);
                if (pointer >= 0) {
                    float dx = event.getX(pointer) - downX, dy = event.getY(pointer) - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        cancelSelectionAnimation(); dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                }
            }
            return dragging || super.onInterceptTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!dragging) return super.onTouchEvent(event);
            int action = event.getActionMasked(), pointer = event.findPointerIndex(activePointerId);
            if (action == MotionEvent.ACTION_MOVE && pointer >= 0) {
                float cell = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight()) / (float) Math.max(1, getChildCount());
                float position = dragStartPosition + (event.getX(pointer) - downX) / cell;
                selectedPosition = Math.max(0f, Math.min(Math.max(0, getChildCount() - 1), position));
                motion = 0.65f; invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
                    (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == activePointerId)) {
                int requested = action == MotionEvent.ACTION_CANCEL ? selectedIndex : Math.round(selectedPosition);
                dragging = false; activePointerId = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                setSelectedIndex(requested, true);
                if (action != MotionEvent.ACTION_CANCEL && selectionRequestedListener != null)
                    selectionRequestedListener.onSelectionRequested(requested);
                return true;
            }
            return true;
        }
        public void refreshBackdrop() {
            dirty = true; postInvalidateOnAnimation();
        }
        public int getSelectedIndex() { return selectedIndex; }
        public void jumpToSelectedIndex(int index) { setSelectedIndex(index, false); }
        public void cancelSelectionAnimation() {
            if (selectionAnimator != null) { selectionAnimator.cancel(); selectionAnimator = null; }
            motion = 0f; invalidate();
        }
        /** Keep this Panel instance mounted during tab changes so the same lens can flow between them. */
        public void setSelectedIndex(int index, boolean animate) {
            int count = getChildCount() > 0 ? getChildCount() : 3;
            final int target = Math.max(0, Math.min(count - 1, index));
            cancelSelectionAnimation();
            selectedIndex = target;
            if (!animate || getWidth() == 0 || Math.abs(selectedPosition - target) < 0.001f) {
                selectedPosition = target; invalidate(); return;
            }
            final float start = selectedPosition, distance = Math.abs(target - start);
            selectionAnimator = ValueAnimator.ofFloat(0f, 1f);
            selectionAnimator.setDuration(distance > 1f ? 440 : 370);
            selectionAnimator.setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1f));
            selectionAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (Float) animation.getAnimatedValue();
                    selectedPosition = start + (target - start) * t;
                    motion = (float) Math.sin(Math.PI * t) * Math.min(1f, distance);
                    // Only the lens moves. The cached backdrop is not re-captured each animation frame.
                    invalidate();
                }
            });
            selectionAnimator.start();
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh); dirty = true;
            surface.set(0, 0, w, h); roundedClip.reset();
            roundedClip.addRoundRect(surface, Math.min(corner, h / 2f), Math.min(corner, h / 2f), Path.Direction.CW);
            sampleX = null; sampleY = null;
        }
        @Override protected void onDraw(Canvas canvas) {
            if (dirty && !capturing && getWidth() > 0 && getHeight() > 0) { dirty = false; captureBackdrop(); }
            int saved = canvas.save(); canvas.clipPath(roundedClip);
            if (refracted != null && !refracted.isRecycled()) canvas.drawBitmap(refracted, null, surface, bitmapPaint);
            // The faint tint follows the theme; scene pixels and physical refraction remain visible.
            paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
            paint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                dark ? new int[]{0x2834433d, 0x14232e29, 0x40101814}
                     : new int[]{0x2cffffff, 0x14fafffe, 0x24e0eee9}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(surface, paint); paint.setShader(null);
            drawSelectionLens(canvas);
            canvas.restoreToCount(saved);
            drawOpticalEdge(canvas, surface, Math.min(corner, getHeight() / 2f), false);
            super.onDraw(canvas);
        }
        private void drawSelectionLens(Canvas canvas) {
            int count = Math.max(1, getChildCount());
            float position = Math.max(0f, Math.min(count - 1, selectedPosition));
            int from = (int) position, to = Math.min(count - 1, from + 1);
            float fraction = position - from;
            View a = getChildAt(from), b = getChildAt(to);
            if (a == null || b == null || a.getWidth() == 0) return;
            float left = a.getLeft() + (b.getLeft() - a.getLeft()) * fraction;
            float right = a.getRight() + (b.getRight() - a.getRight()) * fraction;
            float expansion = dp(this, 8) * motion;
            float vertical = dp(this, 1.8f) * motion;
            lens.set(left + dp(this, 1.5f) - expansion, getPaddingTop() + dp(this, 0.5f) + vertical,
                right - dp(this, 1.5f) + expansion, getHeight() - getPaddingBottom() - dp(this, 0.5f) - vertical);
            float radius = lens.height() / 2f;
            // A broad, low-alpha contact shadow anchors the moving volume inside the clear bar.
            border.set(lens); border.offset(0, dp(this, 1));
            paint.setShader(null); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(this, 3));
            paint.setColor(dark ? 0x70000402 : 0x0e304d43); canvas.drawRoundRect(border, radius, radius, paint);
            lensClip.reset(); lensClip.addRoundRect(lens, radius, radius, Path.Direction.CW);
            int saved = canvas.save(); canvas.clipPath(lensClip);
            if (sample != null && !sample.isRecycled()) {
                int zoomed = canvas.save();
                float zoom = 1.10f + motion * 0.045f;
                canvas.scale(zoom, zoom, lens.centerX(), lens.centerY());
                destination.set(-SAMPLE_PADDING, -SAMPLE_PADDING,
                    sample.getWidth() * DOWNSAMPLE - SAMPLE_PADDING,
                    sample.getHeight() * DOWNSAMPLE - SAMPLE_PADDING);
                canvas.drawBitmap(sample, null, destination, bitmapPaint);
                canvas.restoreToCount(zoomed);
            }
            paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
            paint.setShader(new LinearGradient(lens.left, lens.top, lens.right, lens.bottom,
                dark ? new int[]{0x486b907d, 0x243b6e57, 0x4852685a}
                     : new int[]{0x62ffffff, 0x20c7ffee, 0x38a5ddc9}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(lens, paint); paint.setShader(null);
            canvas.restoreToCount(saved);
            drawOpticalEdge(canvas, lens, radius, true);
        }
        private void drawOpticalEdge(Canvas canvas, RectF rect, float radius, boolean selection) {
            float edge = dp(this, selection ? 0.9f : 1.05f);
            border.set(rect); border.inset(edge / 2, edge / 2);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(edge); paint.setAlpha(255);
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                dark ? new int[]{0x82c1e9d2, 0x3c769985, 0x78020a05, 0x426e9b81, 0x72b7ddc7}
                     : new int[]{0xfaffffff, 0x83ffffff, 0x16375247, 0x9ce8fff7, 0xecffffff},
                new float[]{0f, 0.25f, 0.52f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(border, radius, radius, paint);
            border.inset(dp(this, 1.3f), dp(this, 1.3f));
            paint.setStrokeWidth(dp(this, 0.65f));
            paint.setShader(new LinearGradient(rect.right, rect.top, rect.left, rect.bottom,
                dark ? new int[]{0x0084b89b, 0x0084b89b, 0x64030805, 0x4384b89b}
                     : new int[]{0x00ffffff, 0x00ffffff, 0x344a8470, 0x80ffffff},
                new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(border, Math.max(0, radius - dp(this, 1.3f)), Math.max(0, radius - dp(this, 1.3f)), paint);
            paint.setShader(null); paint.setStyle(Paint.Style.FILL);
        }
        private boolean validBackdrop() {
            if (backdrop == null || backdrop == this || backdrop.getWidth() <= 0 || backdrop.getHeight() <= 0) return false;
            for (ViewParent parent = getParent(); parent != null; parent = parent.getParent()) if (parent == backdrop) return false;
            return true;
        }
        private void captureBackdrop() {
            if (!validBackdrop()) return;
            capturing = true;
            try {
                int width = (getWidth() + SAMPLE_PADDING * 2 + DOWNSAMPLE - 1) / DOWNSAMPLE;
                int height = (getHeight() + SAMPLE_PADDING * 2 + DOWNSAMPLE - 1) / DOWNSAMPLE;
                int opticalWidth = Math.max(1, (getWidth() + DOWNSAMPLE - 1) / DOWNSAMPLE);
                int opticalHeight = Math.max(1, (getHeight() + DOWNSAMPLE - 1) / DOWNSAMPLE);
                if (sample == null || sample.getWidth() != width || sample.getHeight() != height ||
                        refracted == null || refracted.getWidth() != opticalWidth || refracted.getHeight() != opticalHeight) {
                    releaseSample();
                    sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); sampleCanvas = new Canvas(sample);
                    refracted = Bitmap.createBitmap(opticalWidth, opticalHeight, Bitmap.Config.ARGB_8888);
                    pixels = new int[width * height]; scratch = new int[width * height];
                    opticalPixels = new int[opticalWidth * opticalHeight];
                }
                backdrop.getLocationInWindow(sourceLocation); getLocationInWindow(panelLocation);
                sample.eraseColor(dark ? 0xff101613 : 0xfff5f6f2);
                int saved = sampleCanvas.save();
                try {
                    sampleCanvas.scale(1f / DOWNSAMPLE, 1f / DOWNSAMPLE);
                    // Direct View.draw(Canvas) bypasses the parent's child-drawing transform.
                    // Apply the source's scroll offset explicitly to capture the visible viewport.
                    sampleCanvas.translate(sourceLocation[0] - panelLocation[0] + SAMPLE_PADDING - backdrop.getScrollX(),
                        sourceLocation[1] - panelLocation[1] + SAMPLE_PADDING - backdrop.getScrollY());
                    backdrop.draw(sampleCanvas);
                } finally { sampleCanvas.restoreToCount(saved); }
                sample.getPixels(pixels, 0, width, 0, 0, width, height);
                blur(pixels, scratch, width, height, BLUR_RADIUS, true);
                blur(scratch, pixels, width, height, BLUR_RADIUS, false);
                sample.setPixels(pixels, 0, width, 0, 0, width, height);
                if (sampleX == null || sampleX.length != opticalPixels.length) prepareOptics(opticalWidth, opticalHeight);
                for (int i = 0; i < opticalPixels.length; i++) opticalPixels[i] = interpolate(pixels, width, height, sampleX[i], sampleY[i]);
                refracted.setPixels(opticalPixels, 0, opticalWidth, 0, 0, opticalWidth, opticalHeight);
            } catch (RuntimeException unavailable) { releaseSample(); }
            catch (OutOfMemoryError lowMemory) { releaseSample(); }
            finally { capturing = false; }
        }
        /** SDF normals bend the scene at the convex rim; geometry is cached until the size changes. */
        private void prepareOptics(int width, int height) {
            sampleX = new float[width * height]; sampleY = new float[width * height];
            float halfW = getWidth() / 2f, halfH = getHeight() / 2f;
            float radius = Math.min(corner, halfH), rim = Math.min(dp(this, 11), halfH * 0.6f);
            float strength = dp(this, 7.5f);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                float px = (x + 0.5f) * getWidth() / width - halfW;
                float py = (y + 0.5f) * getHeight() / height - halfH;
                float qx = Math.abs(px) - (halfW - radius), qy = Math.abs(py) - (halfH - radius);
                float outsideX = Math.max(qx, 0), outsideY = Math.max(qy, 0);
                float length = (float) Math.sqrt(outsideX * outsideX + outsideY * outsideY);
                float distance = Math.min(Math.max(qx, qy), 0) + length - radius;
                float nx, ny;
                if (length > 0.001f) { nx = outsideX / length * Math.signum(px); ny = outsideY / length * Math.signum(py); }
                else if (qx > qy) { nx = Math.signum(px); ny = 0; }
                else { nx = 0; ny = Math.signum(py); }
                float depth = Math.max(0, -distance);
                float bend = depth < rim ? strength * (float) Math.sin(Math.PI * (1 - depth / rim) * 0.75f) : 0f;
                int at = y * width + x;
                sampleX[at] = (halfW + px * 0.992f - nx * bend + SAMPLE_PADDING) / DOWNSAMPLE - 0.5f;
                sampleY[at] = (halfH + py * 0.992f - ny * bend + SAMPLE_PADDING) / DOWNSAMPLE - 0.5f;
            }
        }
        private static int interpolate(int[] pixels, int width, int height, float x, float y) {
            x = Math.max(0, Math.min(width - 1f, x)); y = Math.max(0, Math.min(height - 1f, y));
            int left = (int) x, top = (int) y, right = Math.min(width - 1, left + 1), bottom = Math.min(height - 1, top + 1);
            float fx = x - left, fy = y - top;
            int a = pixels[top * width + left], b = pixels[top * width + right];
            int c = pixels[bottom * width + left], d = pixels[bottom * width + right];
            int result = 0xff000000;
            for (int shift = 0; shift <= 16; shift += 8) {
                float upper = ((a >> shift) & 255) * (1 - fx) + ((b >> shift) & 255) * fx;
                float lower = ((c >> shift) & 255) * (1 - fx) + ((d >> shift) & 255) * fx;
                result |= Math.round(upper * (1 - fy) + lower * fy) << shift;
            }
            return result;
        }
        private static void blur(int[] input, int[] output, int width, int height, int radius, boolean horizontal) {
            int lines = horizontal ? height : width, length = horizontal ? width : height;
            int step = horizontal ? 1 : width, divisor = radius * 2 + 1;
            for (int line = 0; line < lines; line++) {
                int start = horizontal ? line * width : line, r = 0, g = 0, b = 0;
                for (int i = -radius; i <= radius; i++) {
                    int pixel = input[start + Math.max(0, Math.min(length - 1, i)) * step];
                    r += (pixel >>> 16) & 255; g += (pixel >>> 8) & 255; b += pixel & 255;
                }
                for (int at = 0; at < length; at++) {
                    output[start + at * step] = 0xff000000 | ((r / divisor) << 16) | ((g / divisor) << 8) | (b / divisor);
                    int remove = input[start + Math.max(0, at - radius) * step];
                    int add = input[start + Math.min(length - 1, at + radius + 1) * step];
                    r += ((add >>> 16) & 255) - ((remove >>> 16) & 255);
                    g += ((add >>> 8) & 255) - ((remove >>> 8) & 255); b += (add & 255) - (remove & 255);
                }
            }
        }
        private void releaseSample() {
            if (sampleCanvas != null) sampleCanvas.setBitmap(null);
            sampleCanvas = null;
            if (sample != null && !sample.isRecycled()) sample.recycle();
            if (refracted != null && !refracted.isRecycled()) refracted.recycle();
            sample = null; refracted = null; pixels = null; scratch = null; opticalPixels = null; sampleX = null; sampleY = null;
        }
        @Override protected void onDetachedFromWindow() {
            cancelSelectionAnimation(); releaseSample(); dirty = true;
            super.onDetachedFromWindow();
        }
    }

    /** Small vector-like line icons, independent of fonts and emoji rendering. */
    public static final class TabIcon extends View {
        private final int index;
        private boolean selected, dark;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        public TabIcon(Context context, int index, boolean selected) {
            this(context, index, selected, systemDark(context));
        }
        public TabIcon(Context context, int index, boolean selected, boolean dark) {
            super(context); this.index = index; this.selected = selected; this.dark = dark;
            super.setSelected(selected);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        public void setDarkMode(boolean dark) {
            if (this.dark != dark) { this.dark = dark; invalidate(); }
        }
        @Override public void setSelected(boolean selected) {
            super.setSelected(selected);
            if (this.selected != selected) { this.selected = selected; invalidate(); }
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desired = (int) (dp(this, 26) + 0.5f);
            setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight());
            if (size <= 0) return;
            int saved = canvas.save();
            canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
            canvas.scale(size / 28f, size / 28f);
            paint.setColor(dark ? (selected ? 0xff8ad8b3 : 0xffb6c6bc) : (selected ? GREEN : MUTED));
            paint.setStrokeWidth(selected ? 1.9f : 1.7f);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            if (index == 0) {
                rect.set(4, 4, 11.5f, 13); canvas.drawRoundRect(rect, 2, 2, paint);
                rect.set(16.5f, 4, 24, 9.5f); canvas.drawRoundRect(rect, 2, 2, paint);
                rect.set(4, 18, 11.5f, 24); canvas.drawRoundRect(rect, 2, 2, paint);
                rect.set(16.5f, 14.5f, 24, 24); canvas.drawRoundRect(rect, 2, 2, paint);
            } else if (index == 1) {
                path.reset(); path.moveTo(6, 3.5f); path.lineTo(22, 3.5f); path.lineTo(22, 24.5f);
                path.lineTo(18, 22); path.lineTo(14, 24.5f); path.lineTo(10, 22); path.lineTo(6, 24.5f); path.close();
                canvas.drawPath(path, paint);
                canvas.drawLine(10, 9, 18, 9, paint); canvas.drawLine(10, 13.5f, 18, 13.5f, paint);
                canvas.drawLine(10, 18, 15.5f, 18, paint);
            } else {
                canvas.drawCircle(14, 8.5f, 4.2f, paint);
                path.reset(); path.moveTo(5.5f, 23); path.cubicTo(5.5f, 13.5f, 22.5f, 13.5f, 22.5f, 23);
                path.cubicTo(18, 25, 10, 25, 5.5f, 23); canvas.drawPath(path, paint);
            }
            canvas.restoreToCount(saved);
        }
    }
}
