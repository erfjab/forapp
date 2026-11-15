package app.forapp.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Calendar;

import app.forapp.R;
import app.forapp.core.Fa;

/**
 * Bars over time: black, the part that had trouble in red. Per hour of today with a red "now" line
 * ({@link #set(int[], int[])}), or per day of a range ({@link #setDays}).
 */
final class ChartView extends View {
    private long[] total = new long[24], bad = new long[24];
    /** Day of the month under each daily bar; null in the hourly mode. */
    private int[] days;
    /** Hours of today: a red "now" line, and the hours after it drawn as an empty grid. */
    private boolean showNow = true;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), red = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(), axis = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float nowFrac;

    ChartView(Context c) {
        super(c);
        bar.setColor(Ui.color(c, R.color.fg));
        red.setColor(Ui.color(c, R.color.red));
        grid.setColor(Ui.color(c, R.color.faint));
        axis.setColor(Ui.color(c, R.color.fg));
        axis.setStrokeWidth(Ui.dp(c, 2));
        label.setColor(Ui.color(c, R.color.mid));
        label.setTextSize(Ui.dp(c, 10));
        label.setTypeface(Ui.font(c, Ui.W_BOLD));
        setContentDescription("نمودار واریزهای امروز در هر ساعت");
    }

    /** Today's counts per hour. */
    void set(int[] totals, int[] bads) {
        total = new long[24];
        bad = new long[24];
        for (int i = 0; i < 24; i++) {
            total[i] = totals[i];
            bad[i] = bads[i];
        }
        days = null;
        showNow = true;
        Calendar c = Calendar.getInstance();
        nowFrac = (c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f) / 24f;
        invalidate();
    }

    /** Amounts per hour of a past day (no "now" line), or of today (with it). */
    void setHours(long[] totals, long[] bads, boolean today) {
        total = totals.clone();
        bad = bads.clone();
        days = null;
        showNow = today;
        Calendar c = Calendar.getInstance();
        nowFrac = (c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f) / 24f;
        setContentDescription("نمودار مبلغ واریز در هر ساعت");
        invalidate();
    }

    /** Amounts per day; {@code dayOfMonth} labels the bars. */
    void setDays(long[] totals, long[] bads, int[] dayOfMonth) {
        total = totals.clone();
        bad = bads.clone();
        days = dayOfMonth.clone();
        showNow = false;
        setContentDescription("نمودار مبلغ واریز در هر روز");
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 120));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        int n = total.length;
        float padX = Ui.dp(c, 20), top = Ui.dp(c, 16), labelH = Ui.dp(c, 16);
        float w = getWidth() - 2 * padX, base = getHeight() - labelH, h = base - top;
        long max = 4; // a quiet day of counts still has short bars; amounts are far above this
        for (long v : total) max = Math.max(max, v);
        float slot = w / n, gap = n > 45 ? Ui.dp(c, 1) : Ui.dp(c, 2);
        int nowHour = showNow ? (int) (nowFrac * 24) : n;
        // Time runs left to right like a clock, independent of the RTL layout.
        for (int i = 0; i < n; i++) {
            float x = padX + i * slot;
            if (i > nowHour) {
                for (float y = base - Ui.dp(c, 6); y > top; y -= Ui.dp(c, 6)) cv.drawRect(x, y, x + slot - gap, y + 1, grid);
                continue;
            }
            float th = total[i] / (float) max * h, bh = Math.min(th, Math.max(bad[i] > 0 ? Ui.dp(c, 4) : 0, bad[i] / (float) max * h));
            if (th > 0) cv.drawRect(x, base - th, x + slot - gap, base - bh, bar);
            if (bh > 0) cv.drawRect(x, base - bh, x + slot - gap, base, red);
        }
        cv.drawLine(padX, base, padX + w, base, axis);
        if (showNow) {
            float nx = padX + nowFrac * w;
            red.setStrokeWidth(Ui.dp(c, 1.5f));
            cv.drawLine(nx, top - Ui.dp(c, 6), nx, base, red);
            Paint rl = new Paint(label);
            rl.setColor(Ui.color(c, R.color.red));
            float lw = rl.measureText("اکنون");
            cv.drawText("اکنون", nx + lw + Ui.dp(c, 8) > padX + w ? nx - lw - Ui.dp(c, 4) : nx + Ui.dp(c, 4), top, rl);
        }
        float ty = getHeight() - Ui.dp(c, 2);
        if (days == null) {
            for (int i = 0; i <= 24; i += 6) {
                String s = Fa.d(i);
                float tx = padX + i * slot - (i == 24 ? label.measureText(s) : i == 0 ? 0 : label.measureText(s) / 2);
                cv.drawText(s, tx, ty, label);
            }
        } else {
            int step = n <= 8 ? 1 : n <= 35 ? 5 : n <= 100 ? 15 : 30;
            for (int i = 0; i < n; i += step) {
                String s = Fa.d(days[i]);
                float tx = padX + i * slot + (slot - gap) / 2 - label.measureText(s) / 2;
                cv.drawText(s, Math.max(padX, tx), ty, label);
            }
        }
    }
}
