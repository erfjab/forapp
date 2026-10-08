package app.forapp.ui;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import java.util.TimeZone;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Fa;
import app.forapp.core.Prefs;

/**
 * Home-screen widgets. This class is the compact 2x1 one; {@link Strip} is the 4x1 one.
 *
 * Launchers (MIUI's among them) ignore an app's fonts in widget layouts, so the content is drawn here with the
 * app's own Vazirmatn into white-on-transparent images. The layout tints each image with a colour resource
 * (fg, mid, red), so the widget still follows the phone's light or dark theme without being redrawn.
 */
public class Widget extends AppWidgetProvider {

    /** The 4x1 strip: deposits | sum | failures. */
    public static final class Strip extends Widget {}

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        refreshAsync(c);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        refreshAsync(c); // resized
    }

    private void refreshAsync(Context c) {
        PendingResult pr = goAsync();
        new Thread(() -> {
            try { refresh(c); } finally { pr.finish(); }
        }, "forapp-widget").start();
    }

    /** Redraws every placed widget; cheap, so it is called on every change of the log. Call off the main thread. */
    public static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] compact = m.getAppWidgetIds(new ComponentName(c, Widget.class));
        int[] strip = m.getAppWidgetIds(new ComponentName(c, Strip.class));
        if (compact.length == 0 && strip.length == 0) return;

        Prefs p = Prefs.of(c);
        boolean perm = c.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        State s = new State(p.enabled() && perm, p.enabled(),
                Db.get(c).todayStats(Fa.dayStart(System.currentTimeMillis(), TimeZone.getDefault())));
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        for (int id : compact) update(m, id, () -> views(c, m, id, s, pi, false));
        for (int id : strip) update(m, id, () -> views(c, m, id, s, pi, true));
    }

    /** A widget that cannot be drawn must never take the app down with it. */
    private static void update(AppWidgetManager m, int id, java.util.function.Supplier<RemoteViews> v) {
        try {
            m.updateAppWidget(id, v.get());
        } catch (RuntimeException e) {
            android.util.Log.w("ForApp", "widget " + id + " not updated", e);
        }
    }

    private static final class State {
        final boolean on, enabled;
        final int count, failed;
        final long toman;

        State(boolean on, boolean enabled, long[] stats) {
            this.on = on;
            this.enabled = enabled;
            count = (int) stats[0];
            toman = stats[1];
            failed = (int) stats[2];
        }
    }

    // ---------- layers ----------

    private static RemoteViews views(Context c, AppWidgetManager m, int id, State s, PendingIntent pi, boolean strip) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        float d = c.getResources().getDisplayMetrics().density;
        Bundle o = m.getAppWidgetOptions(id);
        // Portrait size; launchers that do not report it get the design size.
        int wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (wDp <= 0) wDp = strip ? 350 : 170;
        if (hDp <= 0) hDp = strip ? 84 : 80;
        wDp -= 2 * MARGIN; // the card's margin
        hDp -= 2 * MARGIN;
        int wd = Math.max(80, wDp), hd = Math.max(40, Math.min(hDp, strip ? 110 : 120));
        // Widgets get a small image budget (about 1.5 screens of pixels, less on some phones): three 1-byte layers,
        // kept under ~600 KB together, drawn at a lower density when the widget is large.
        d = Math.min(d, (float) Math.sqrt(600_000 / (3.0 * wd * hd)));
        Art a = new Art(c, wd, hd, d);
        if (strip) a.strip(s); else a.compact(s);
        v.setImageViewBitmap(R.id.w_fg, a.fg);
        v.setImageViewBitmap(R.id.w_mid, a.mid);
        v.setImageViewBitmap(R.id.w_red, a.red);
        v.setOnClickPendingIntent(R.id.w_root, pi);
        return v;
    }

    private static final int MARGIN = 4;

    /** Draws one widget's content; sizes are in dp and scaled down together when the widget is narrow. */
    private static final class Art {
        final Context c;
        final float d;
        final int w, h;
        final Bitmap fg, mid, red;
        final Canvas cf, cm, cr;
        final Typeface regular, bold, black;
        float k = 1f; // shrink factor for narrow widgets

        Art(Context c, int wDp, int hDp, float density) {
            this.c = c;
            d = density;
            w = Math.round(wDp * d);
            h = Math.round(hDp * d);
            fg = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            mid = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            red = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            cf = new Canvas(fg);
            cm = new Canvas(mid);
            cr = new Canvas(red);
            regular = Ui.font(c, Ui.W_REGULAR);
            bold = Ui.font(c, Ui.W_BOLD);
            black = Ui.font(c, Ui.W_BLACK);
        }

        float px(float dp) { return dp * d * k; }

        /** One right-to-left line; Latin names and digits keep their own order inside it. */
        StaticLayout line(String s, Typeface tf, float sp, int maxW) {
            TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            p.setColor(Color.WHITE);
            p.setTypeface(tf);
            p.setTextSize(px(sp));
            int width = Math.max(1, Math.min(maxW, (int) Math.ceil(Layout.getDesiredWidth(s, p))));
            return StaticLayout.Builder.obtain(s, 0, s.length(), p, width)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
        }

        /** Draws with its right edge at {@code right} and its top at {@code top}. */
        void put(Canvas cv, StaticLayout l, float right, float top) {
            cv.save();
            cv.translate(right - l.getWidth(), top);
            l.draw(cv);
            cv.restore();
        }

        /** Two stacked lines, right-aligned at {@code right}, centred vertically. */
        void pair(Canvas c1, StaticLayout a, Canvas c2, StaticLayout b, float right, float gap) {
            float total = a.getHeight() + gap + b.getHeight();
            float top = (h - total) / 2f;
            put(c1, a, right, top);
            put(c2, b, right, top + a.getHeight() + gap);
        }

        // ---------- compact 2x1 ----------

        void compact(State s) {
            float start = 16, end = 14;
            if (!s.on) {
                fitTo(8 + 12 + 120 + start + end);
                Paint dot = fill(Color.WHITE);
                cm.drawCircle(w - px(start) - px(4), h / 2f, px(4), dot);
                float right = w - px(start) - px(8) - px(12);
                int max = (int) (right - px(end));
                pair(cf, line("خاموش", black, 14, max), cm,
                        line(s.enabled ? "اجازه‌ی پیامک داده نشده" : "برای روشن کردن بزنید", regular, 10, max), right, px(1));
                return;
            }
            if (s.count == 0) {
                fitTo(44 + 12 + 110 + start + end);
                float r = px(21), cx = w - px(start) - r, cy = h / 2f;
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(px(1.5f));
                ring.setColor(Color.argb(110, 255, 255, 255));
                ring.setPathEffect(new DashPathEffect(new float[]{px(4), px(3)}, 0));
                cm.drawCircle(cx, cy, r, ring);
                StaticLayout zero = line(Fa.d(0), black, 20, w);
                put(cm, zero, cx + zero.getWidth() / 2f, cy - zero.getHeight() / 2f);
                float right = cx - r - px(12);
                int max = (int) (right - px(end));
                pair(cf, line("امروز آرام است", black, 13, max), cm, line("منتظر اولین واریز", regular, 10, max), right, px(1));
                return;
            }
            StaticLayout num = line(Fa.d(s.count), black, 38, w);
            StaticLayout sum = line(Fa.group(s.toman), bold, 13, w);
            fitTo((num.getWidth() + sum.getWidth()) / (d * k) + 12 + start + end);
            num = line(Fa.d(s.count), black, 38, w);
            float right = w - px(start);
            put(cf, num, right, (h - num.getHeight()) / 2f);
            right -= num.getWidth() + px(12);
            int max = (int) (right - px(end));
            StaticLayout a = line(Fa.group(s.toman), bold, 13, max), b = line("تومان امروز", regular, 10, max);
            if (s.failed == 0) {
                pair(cf, a, cm, b, right, 0);
                return;
            }
            // failures: a third, red line; red appears nowhere else
            StaticLayout f = line(Fa.d(s.failed) + " ناموفق", bold, 10, max);
            float top = (h - a.getHeight() - b.getHeight() - f.getHeight()) / 2f;
            put(cf, a, right, top);
            put(cm, b, right, top + a.getHeight());
            put(cr, f, right, top + a.getHeight() + b.getHeight());
        }

        // ---------- strip 4x1 ----------

        void strip(State s) {
            float pad = 18, gap = 14;
            if (!s.on) {
                fitTo(8 + 12 + 150 + 80 + 2 * pad);
                cm.drawCircle(w - px(pad) - px(4), h / 2f, px(4), fill(Color.WHITE));
                // pill on the left: solid fg with the label cut out, so it shows the card colour
                StaticLayout label = line(s.enabled ? "باز کن" : "روشن کن", bold, 12, w);
                float pw = label.getWidth() + px(28), ph = label.getHeight() + px(10), pl = px(pad);
                RectF pill = new RectF(pl, (h - ph) / 2f, pl + pw, (h + ph) / 2f);
                cf.drawRoundRect(pill, ph / 2f, ph / 2f, fill(Color.WHITE));
                label.getPaint().setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                put(cf, label, pill.right - px(14), pill.top + px(5));
                float right = w - px(pad) - px(8) - px(12);
                int max = (int) (right - pill.right - px(12));
                pair(cf, line("فوروارد خاموش است", black, 14, max), cm,
                        line(s.enabled ? "اجازه‌ی خواندن پیامک داده نشده" : "پیامک‌ها خوانده نمی‌شوند", regular, 10, max), right, px(1));
                return;
            }
            boolean empty = s.count == 0;
            StaticLayout num0 = line(Fa.d(s.count), black, 32, w), sum0 = line(empty ? "هنوز واریزی نیامده" : Fa.group(s.toman), bold, 15, w);
            fitTo((num0.getWidth() + sum0.getWidth()) / (d * k) + 2 * pad + 2 * gap + (empty ? 0 : 60 + 2 * gap) + 2);

            // deposits
            float right = w - px(pad);
            StaticLayout num = line(Fa.d(s.count), black, 32, w), cap = line("واریز امروز", regular, 10, w);
            float colW = Math.max(num.getWidth(), cap.getWidth());
            pair(empty ? cm : cf, num, cm, cap, right, 0);
            right -= colW + px(gap);
            hairline(right);
            right -= px(1) + px(gap);

            // failures (left end), then the sum fills what is between
            float left = px(pad);
            if (!empty) {
                boolean bad = s.failed > 0;
                StaticLayout fcap = line(bad ? "ناموفق" : "همه رسید", regular, 10, w);
                float fw = Math.max(fcap.getWidth(), px(44)), cx = left + fw / 2f;
                if (bad) {
                    StaticLayout f = line(Fa.d(s.failed), black, 20, w);
                    float top = (h - f.getHeight() - fcap.getHeight()) / 2f;
                    put(cr, f, cx + f.getWidth() / 2f, top);
                    put(cr, fcap, cx + fcap.getWidth() / 2f, top + f.getHeight());
                } else {
                    float ih = px(16), top = (h - ih - px(4) - fcap.getHeight()) / 2f;
                    check(cf, cx, top + ih / 2f, ih);
                    put(cm, fcap, cx + fcap.getWidth() / 2f, top + ih + px(4));
                }
                left += fw + px(gap);
                hairline(left);
                left += px(1) + px(gap);
            }
            int max = (int) (right - left);
            pair(cf, line(empty ? "هنوز واریزی نیامده" : Fa.group(s.toman), bold, empty ? 12.5f : 15, max), cm,
                    line(empty ? "آماده‌ی دریافت" : "تومان", regular, 10, max), right, empty ? px(2) : 0);
        }

        void hairline(float x) {
            Paint p = fill(Color.argb(70, 255, 255, 255));
            float lh = px(38);
            cm.drawRect(x - px(1), (h - lh) / 2f, x, (h + lh) / 2f, p);
        }

        void check(Canvas cv, float cx, float cy, float size) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(size / 8f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            float u = size / 24f;
            Path path = new Path();
            path.moveTo(cx - 7 * u, cy + 0.5f * u);
            path.lineTo(cx - 2.5f * u, cy + 5 * u);
            path.lineTo(cx + 7 * u, cy - 4.5f * u);
            cv.drawPath(path, p);
        }

        /** Shrinks everything when the natural width (dp) does not fit. */
        void fitTo(float neededDp) {
            float avail = w / d;
            k = Math.max(0.6f, Math.min(1f, avail / neededDp));
        }

        static Paint fill(int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            return p;
        }
    }
}
