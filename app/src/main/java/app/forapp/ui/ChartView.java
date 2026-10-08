package app.forapp.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Calendar;

import app.forapp.R;
import app.forapp.core.Fa;

/** Today's deposits per hour: black bars, the part that had trouble in red, a red "now" line. */
final class ChartView extends View {
    private final int[] total = new int[24], bad = new int[24];
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

    void set(int[] totals, int[] bads) {
        System.arraycopy(totals, 0, total, 0, 24);
        System.arraycopy(bads, 0, bad, 0, 24);
        Calendar c = Calendar.getInstance();
        nowFrac = (c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f) / 24f;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 120));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float padX = Ui.dp(c, 20), top = Ui.dp(c, 16), labelH = Ui.dp(c, 16);
        float w = getWidth() - 2 * padX, base = getHeight() - labelH, h = base - top;
        int max = 4;
        for (int v : total) max = Math.max(max, v);
        float slot = w / 24f, gap = Ui.dp(c, 2);
        int nowHour = (int) (nowFrac * 24);
        // Time runs left to right like a clock, independent of the RTL layout.
        for (int i = 0; i < 24; i++) {
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
        float nx = padX + nowFrac * w;
        red.setStrokeWidth(Ui.dp(c, 1.5f));
        cv.drawLine(nx, top - Ui.dp(c, 6), nx, base, red);
        Paint rl = new Paint(label);
        rl.setColor(Ui.color(c, R.color.red));
        float lw = rl.measureText("اکنون");
        cv.drawText("اکنون", nx + lw + Ui.dp(c, 8) > padX + w ? nx - lw - Ui.dp(c, 4) : nx + Ui.dp(c, 4), top, rl);
        for (int i = 0; i <= 24; i += 6) {
            String s = Fa.d(i);
            float tx = padX + i * slot - (i == 24 ? label.measureText(s) : i == 0 ? 0 : label.measureText(s) / 2);
            cv.drawText(s, tx, getHeight() - Ui.dp(c, 2), label);
        }
    }
}
