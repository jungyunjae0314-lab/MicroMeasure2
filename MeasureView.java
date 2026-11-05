package com.micromeasure;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 사진 표시 + 확대/이동 + 점 찍기 + 거리 표시.
 * 좌표는 모두 "표시용 비트맵 픽셀" 기준으로 저장하고,
 * 거리 계산 시 origScale(원본px / 표시px)을 곱해 원본 사진 픽셀로 환산한다.
 */
public class MeasureView extends View {

    public interface Listener { void onMeasureChanged(); }

    public static class Seg {
        public final PointF a, b;
        Seg(PointF a, PointF b) { this.a = a; this.b = b; }
    }

    public static final int MODE_MEASURE = 0;
    public static final int MODE_CALIB = 1;

    private static final int COLOR_MEAS = 0xFFFFD600;
    private static final int COLOR_CALIB = 0xFF00E5FF;
    private static final int COLOR_ACTIVE = 0xFFFF4081;

    private Bitmap bmp;
    private float origScale = 1f;
    private double umPerPx = 0;          // 0 = 보정 안 됨 (px 표시)
    private int mode = MODE_MEASURE;

    private final List<Seg> segs = new ArrayList<>();
    private PointF pending;
    private Seg calibSeg;
    private PointF calibPending;

    private float scale = 1f, tx = 0f, ty = 0f;
    private boolean needFit = true;

    private final float dp;
    private final int touchSlop;
    private Listener listener;

    private final Paint bmpPaint = new Paint();
    private final Paint loupeBmpPaint = new Paint();
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF tmpRect = new RectF();

    private final ScaleGestureDetector scaleDetector;

    private PointF dragPt;
    private float downX, downY, lastX, lastY, lastFx, lastFy;
    private boolean moved, multi, focusValid;

    public MeasureView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        loupeBmpPaint.setFilterBitmap(false);

        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(0xC0000000);
        outline.setStrokeCap(Paint.Cap.ROUND);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);

        text.setColor(Color.WHITE);
        text.setTextSize(14 * dp);
        text.setFakeBoldText(true);

        labelBg.setColor(0xB0000000);

        hint.setColor(0xFF9E9E9E);
        hint.setTextSize(16 * dp);
        hint.setTextAlign(Paint.Align.CENTER);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float ns = clamp(scale * d.getScaleFactor(), minScale(), maxScale());
                float f = ns / scale;
                float fx = d.getFocusX(), fy = d.getFocusY();
                tx = fx - (fx - tx) * f;
                ty = fy - (fy - ty) * f;
                scale = ns;
                invalidate();
                return true;
            }
        });
    }

    // ───────────── public API ─────────────

    public void setListener(Listener l) { listener = l; }

    public void setImage(Bitmap b, float origScale) {
        if (bmp != null && bmp != b) bmp.recycle();
        bmp = b;
        this.origScale = origScale;
        segs.clear();
        pending = null;
        calibSeg = null;
        calibPending = null;
        dragPt = null;
        needFit = true;
        invalidate();
        notifyChanged();
    }

    public Bitmap getBitmap() { return bmp; }

    public void setUmPerPx(double v) { umPerPx = v; invalidate(); }

    public double getUmPerPx() { return umPerPx; }

    public void setMode(int m) {
        mode = m;
        pending = null;
        dragPt = null;
        invalidate();
        notifyChanged();
    }

    public int getMode() { return mode; }

    public boolean hasCalibLine() { return calibSeg != null; }

    /** 기준선 길이 (원본 사진 픽셀). */
    public double getCalibOrigPx() { return calibSeg == null ? 0 : segOrigPx(calibSeg); }

    public void clearCalib() {
        calibSeg = null;
        calibPending = null;
        invalidate();
        notifyChanged();
    }

    public List<Seg> getSegs() { return segs; }

    public double segOrigPx(Seg s) {
        return Math.hypot(s.b.x - s.a.x, s.b.y - s.a.y) * origScale;
    }

    /** 원본 사진 픽셀 좌표 (CSV 출력용). */
    public float toOrig(float v) { return v * origScale; }

    public String formatDist(double origPx) {
        if (umPerPx <= 0) return String.format(Locale.US, "%.1f px", origPx);
        double um = origPx * umPerPx;
        if (um >= 1000) return String.format(Locale.US, "%.3f mm", um / 1000.0);
        if (um >= 10) return String.format(Locale.US, "%.1f µm", um);
        return String.format(Locale.US, "%.2f µm", um);
    }

    public void undo() {
        if (mode == MODE_CALIB) {
            if (calibPending != null) calibPending = null;
            else calibSeg = null;
        } else {
            if (pending != null) pending = null;
            else if (!segs.isEmpty()) segs.remove(segs.size() - 1);
        }
        dragPt = null;
        invalidate();
        notifyChanged();
    }

    public void clearAll() {
        segs.clear();
        pending = null;
        dragPt = null;
        invalidate();
        notifyChanged();
    }

    public void fitToView() {
        needFit = true;
        invalidate();
    }

    /** 측정선/수치를 원본 해상도 사진 위에 그려서 반환 (저장용). */
    public Bitmap renderAnnotated(String caption) {
        Bitmap out = bmp.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        float u = Math.max(out.getWidth(), out.getHeight()) / 1000f;

        Paint ol = new Paint(Paint.ANTI_ALIAS_FLAG);
        ol.setStyle(Paint.Style.STROKE);
        ol.setColor(0xC0000000);
        ol.setStrokeWidth(4 * u);
        ol.setStrokeCap(Paint.Cap.ROUND);
        Paint st = new Paint(ol);
        st.setColor(COLOR_MEAS);
        st.setStrokeWidth(2 * u);
        Paint tx2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        tx2.setColor(Color.WHITE);
        tx2.setTextSize(22 * u);
        tx2.setFakeBoldText(true);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xB0000000);

        for (int i = 0; i < segs.size(); i++) {
            Seg s = segs.get(i);
            c.drawLine(s.a.x, s.a.y, s.b.x, s.b.y, ol);
            c.drawLine(s.a.x, s.a.y, s.b.x, s.b.y, st);
            endTicks(c, s.a.x, s.a.y, 8 * u, ol, st);
            endTicks(c, s.b.x, s.b.y, 8 * u, ol, st);
            String lab = (i + 1) + ": " + formatDist(segOrigPx(s));
            float mx = (s.a.x + s.b.x) / 2, my = (s.a.y + s.b.y) / 2 - 14 * u;
            label(c, lab, mx, my, tx2, bg, 6 * u);
        }
        if (caption != null) {
            float w = tx2.measureText(caption);
            float x = 12 * u + w / 2, y = out.getHeight() - 20 * u;
            label(c, caption, x, y, tx2, bg, 6 * u);
        }
        return out;
    }

    // ───────────── drawing ─────────────

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        needFit = true;
    }

    private void doFit() {
        if (bmp == null || getWidth() == 0 || getHeight() == 0) return;
        scale = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
        tx = (getWidth() - bmp.getWidth() * scale) / 2f;
        ty = (getHeight() - bmp.getHeight() * scale) / 2f;
        needFit = false;
    }

    private float minScale() {
        if (bmp == null) return 0.01f;
        return 0.5f * Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
    }

    private float maxScale() { return 40f * dp; }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF111111);
        if (bmp == null) {
            c.drawText("📷 촬영 또는 🖼 갤러리로 사진을 불러오세요", getWidth() / 2f, getHeight() / 2f, hint);
            return;
        }
        if (needFit) doFit();

        // 많이 확대하면 픽셀을 그대로 보여줌 (경계 판단이 정확해짐)
        bmpPaint.setFilterBitmap(scale < 3f);
        c.save();
        c.translate(tx, ty);
        c.scale(scale, scale);
        c.drawBitmap(bmp, 0, 0, bmpPaint);
        c.restore();

        outline.setStrokeWidth(3.5f * dp);
        stroke.setStrokeWidth(1.5f * dp);

        if (mode == MODE_MEASURE) {
            for (int i = 0; i < segs.size(); i++) {
                Seg s = segs.get(i);
                drawSeg(c, s, COLOR_MEAS, (i + 1) + ": " + formatDist(segOrigPx(s)));
            }
            if (pending != null) drawEnd(c, pending, COLOR_MEAS);
        } else {
            for (Seg s : segs) drawSegFaint(c, s);
            if (calibSeg != null) {
                drawSeg(c, calibSeg, COLOR_CALIB,
                        String.format(Locale.US, "기준선 %.1f px", segOrigPx(calibSeg)));
            }
            if (calibPending != null) drawEnd(c, calibPending, COLOR_CALIB);
        }

        if (dragPt != null) drawLoupe(c, dragPt);
    }

    private float sx(float x) { return tx + x * scale; }
    private float sy(float y) { return ty + y * scale; }

    private void drawSeg(Canvas c, Seg s, int color, String label) {
        float ax = sx(s.a.x), ay = sy(s.a.y), bx = sx(s.b.x), by = sy(s.b.y);
        stroke.setColor(color);
        c.drawLine(ax, ay, bx, by, outline);
        c.drawLine(ax, ay, bx, by, stroke);
        drawEnd(c, s.a, color);
        drawEnd(c, s.b, color);
        label(c, label, (ax + bx) / 2, (ay + by) / 2 - 14 * dp, text, labelBg, 5 * dp);
    }

    private void drawSegFaint(Canvas c, Seg s) {
        stroke.setColor(0x80FFD600);
        c.drawLine(sx(s.a.x), sy(s.a.y), sx(s.b.x), sy(s.b.y), stroke);
    }

    /** 끝점: 가운데를 비운 링 + 바깥 십자 눈금 → 점 위치가 가려지지 않음. */
    private void drawEnd(Canvas c, PointF p, int color) {
        float x = sx(p.x), y = sy(p.y);
        int col = (p == dragPt) ? COLOR_ACTIVE : color;
        stroke.setColor(col);
        float r = 9 * dp;
        c.drawCircle(x, y, r, outline);
        c.drawCircle(x, y, r, stroke);
        endTicks(c, x, y, r, outline, stroke);
    }

    private static void endTicks(Canvas c, float x, float y, float r, Paint ol, Paint st) {
        float a = r * 0.6f, b = r * 1.8f;
        float[] pts = {x - b, y, x - a, y, x + a, y, x + b, y, x, y - b, x, y - a, x, y + a, x, y + b};
        c.drawLines(pts, ol);
        c.drawLines(pts, st);
    }

    private void label(Canvas c, String s, float cx, float cy, Paint tp, Paint bg, float pad) {
        float w = tp.measureText(s);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float h = fm.descent - fm.ascent;
        tmpRect.set(cx - w / 2 - pad, cy - h / 2 - pad, cx + w / 2 + pad, cy + h / 2 + pad);
        c.drawRoundRect(tmpRect, pad, pad, bg);
        c.drawText(s, cx - w / 2, cy - (fm.ascent + fm.descent) / 2, tp);
    }

    /** 점을 끄는 동안 손가락에 가려지지 않게 모서리에 확대창 표시. */
    private void drawLoupe(Canvas c, PointF p) {
        float R = 65 * dp, m = 12 * dp;
        float px = sx(p.x), py = sy(p.y);
        float cx = R + m, cy = R + m;
        if (px < 2 * R + 3 * m && py < 2 * R + 3 * m) cx = getWidth() - R - m;

        float L = Math.min(Math.max(scale * 4f, 2f * dp), 60f * dp);

        c.save();
        clipPath.reset();
        clipPath.addCircle(cx, cy, R, Path.Direction.CW);
        c.clipPath(clipPath);
        c.drawColor(Color.BLACK);
        c.translate(cx - p.x * L, cy - p.y * L);
        c.scale(L, L);
        c.drawBitmap(bmp, 0, 0, loupeBmpPaint);
        c.restore();

        Paint cr = new Paint(Paint.ANTI_ALIAS_FLAG);
        cr.setColor(0xFFFF1744);
        cr.setStrokeWidth(1 * dp);
        float g = 4 * dp;
        c.drawLine(cx - R, cy, cx - g, cy, cr);
        c.drawLine(cx + g, cy, cx + R, cy, cr);
        c.drawLine(cx, cy - R, cx, cy - g, cr);
        c.drawLine(cx, cy + g, cx, cy + R, cr);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(Color.WHITE);
        border.setStrokeWidth(2 * dp);
        c.drawCircle(cx, cy, R, border);
    }

    // ───────────── touch ─────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return true;
        scaleDetector.onTouchEvent(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                moved = false;
                multi = false;
                focusValid = false;
                dragPt = hitTest(e.getX(), e.getY());
                invalidate();
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                dragPt = null;
                focusValid = false;
                invalidate();
                break;

            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2) {
                    float fx = (e.getX(0) + e.getX(1)) / 2f, fy = (e.getY(0) + e.getY(1)) / 2f;
                    if (focusValid) {
                        tx += fx - lastFx;
                        ty += fy - lastFy;
                    }
                    lastFx = fx;
                    lastFy = fy;
                    focusValid = true;
                    invalidate();
                    break;
                }
                if (multi) break;
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                if (!moved && Math.hypot(e.getX() - downX, e.getY() - downY) > touchSlop) moved = true;
                if (moved) {
                    if (dragPt != null) {
                        // 상대 이동: 손가락 위치와 무관하게 점을 미세하게 옮김
                        dragPt.x = clamp(dragPt.x + dx / scale, 0, bmp.getWidth());
                        dragPt.y = clamp(dragPt.y + dy / scale, 0, bmp.getHeight());
                        notifyChanged();
                    } else {
                        tx += dx;
                        ty += dy;
                    }
                    invalidate();
                }
                lastX = e.getX();
                lastY = e.getY();
                break;

            case MotionEvent.ACTION_POINTER_UP:
                focusValid = false;
                break;

            case MotionEvent.ACTION_UP:
                if (!moved && !multi && dragPt == null) {
                    addPoint((e.getX() - tx) / scale, (e.getY() - ty) / scale);
                }
                dragPt = null;
                invalidate();
                break;

            case MotionEvent.ACTION_CANCEL:
                dragPt = null;
                invalidate();
                break;
        }
        return true;
    }

    private void addPoint(float x, float y) {
        if (x < 0 || y < 0 || x > bmp.getWidth() || y > bmp.getHeight()) return;
        PointF p = new PointF(x, y);
        if (mode == MODE_CALIB) {
            if (calibSeg != null) {
                calibSeg = null;
                calibPending = p;
            } else if (calibPending == null) {
                calibPending = p;
            } else {
                calibSeg = new Seg(calibPending, p);
                calibPending = null;
            }
        } else {
            if (pending == null) {
                pending = p;
            } else {
                segs.add(new Seg(pending, p));
                pending = null;
            }
        }
        invalidate();
        notifyChanged();
    }

    private PointF hitTest(float x, float y) {
        List<PointF> cand = new ArrayList<>();
        if (mode == MODE_CALIB) {
            if (calibPending != null) cand.add(calibPending);
            if (calibSeg != null) { cand.add(calibSeg.a); cand.add(calibSeg.b); }
        } else {
            if (pending != null) cand.add(pending);
            for (int i = segs.size() - 1; i >= 0; i--) {
                cand.add(segs.get(i).a);
                cand.add(segs.get(i).b);
            }
        }
        PointF best = null;
        double bestD = 30 * dp;
        for (PointF p : cand) {
            double d = Math.hypot(sx(p.x) - x, sy(p.y) - y);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private void notifyChanged() {
        if (listener != null) listener.onMeasureChanged();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
