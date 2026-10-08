package app.forapp.ui;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.RemoteViews;

import java.util.TimeZone;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Fa;
import app.forapp.core.Prefs;

/**
 * Home-screen widgets. This class is the compact 2x1 one; {@link Strip} is the 4x1 one.
 * Both show today's deposits and their sum, and use red only when a send failed.
 */
public class Widget extends AppWidgetProvider {

    /** The 4x1 strip: deposits | sum | failures. */
    public static final class Strip extends Widget {}

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
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

        if (compact.length > 0) m.updateAppWidget(compact, compact(c, s, pi));
        if (strip.length > 0) m.updateAppWidget(strip, strip(c, s, pi));
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

    private static RemoteViews compact(Context c, State s, PendingIntent pi) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setViewVisibility(R.id.w_count, View.GONE);
        v.setViewVisibility(R.id.w_zero, View.GONE);
        v.setViewVisibility(R.id.w_off, View.GONE);
        if (!s.on) {
            v.setViewVisibility(R.id.w_off, View.VISIBLE);
            v.setTextViewText(R.id.w_line1, "خاموش");
            v.setTextViewText(R.id.w_line2, s.enabled ? "اجازه‌ی پیامک داده نشده" : "برای روشن کردن بزنید");
        } else if (s.count == 0) {
            v.setViewVisibility(R.id.w_zero, View.VISIBLE);
            v.setTextViewText(R.id.w_line1, "امروز آرام است");
            v.setTextViewText(R.id.w_line2, "منتظر اولین واریز");
        } else {
            v.setViewVisibility(R.id.w_count, View.VISIBLE);
            v.setTextViewText(R.id.w_count, Fa.d(s.count));
            v.setTextViewText(R.id.w_line1, Fa.group(s.toman));
            v.setTextViewText(R.id.w_line2, "تومان امروز");
        }
        v.setViewVisibility(R.id.w_badge, s.on && s.failed > 0 ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.w_badge, Fa.d(s.failed));
        v.setOnClickPendingIntent(R.id.w_root, pi);
        return v;
    }

    private static RemoteViews strip(Context c, State s, PendingIntent pi) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_strip);
        v.setViewVisibility(R.id.s_stats, s.on ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.s_off, s.on ? View.GONE : View.VISIBLE);
        if (!s.on) {
            v.setTextViewText(R.id.s_offsub, s.enabled ? "اجازه‌ی خواندن پیامک داده نشده" : "پیامک‌ها خوانده نمی‌شوند");
            v.setTextViewText(R.id.s_offbtn, s.enabled ? "باز کن" : "روشن کن");
        } else {
            boolean empty = s.count == 0;
            v.setTextViewText(R.id.s_count, Fa.d(s.count));
            v.setTextColor(R.id.s_count, c.getColor(empty ? R.color.thumb_off : R.color.fg));
            v.setTextViewText(R.id.s_sum, empty ? "هنوز واریزی نیامده" : Fa.group(s.toman));
            v.setTextViewText(R.id.s_sumcap, empty ? "آماده‌ی دریافت" : "تومان");
            v.setViewVisibility(R.id.s_sep2, empty ? View.GONE : View.VISIBLE);
            v.setViewVisibility(R.id.s_failbox, empty ? View.GONE : View.VISIBLE);
            boolean bad = s.failed > 0;
            v.setTextViewText(R.id.s_fail, bad ? Fa.d(s.failed) : "✓");
            v.setTextColor(R.id.s_fail, c.getColor(bad ? R.color.red : R.color.fg));
            v.setTextViewText(R.id.s_failcap, bad ? "ناموفق" : "همه رسید");
            v.setTextColor(R.id.s_failcap, c.getColor(bad ? R.color.red : R.color.mid));
        }
        v.setOnClickPendingIntent(R.id.s_root, pi);
        return v;
    }
}
