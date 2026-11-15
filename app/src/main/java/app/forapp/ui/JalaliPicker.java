package app.forapp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;

import java.util.TimeZone;

import app.forapp.core.Fa;

/**
 * A Jalali date in three wheels: day, month, year. The system DatePicker is Gregorian only,
 * so this is the framework NumberPicker with Persian labels.
 */
final class JalaliPicker {
    private JalaliPicker() {}

    interface OnPick { void picked(long dayStart); }

    /** Shows {@code dayMs}'s date; the result is a local midnight, never after {@code maxMs}. */
    static void show(Activity a, String title, long dayMs, long maxMs, TimeZone tz, OnPick onPick) {
        int[] j = Fa.jalali(dayMs, tz), last = Fa.jalali(maxMs, tz);
        NumberPicker day = wheel(a), month = wheel(a), year = wheel(a);

        month.setMinValue(1);
        month.setMaxValue(12);
        month.setDisplayedValues(Fa.MONTHS.clone());
        month.setValue(j[1]);

        year.setMinValue(last[0] - 2);
        year.setMaxValue(last[0]);
        String[] years = new String[3];
        for (int i = 0; i < 3; i++) years[i] = Fa.d(last[0] - 2 + i);
        year.setDisplayedValues(years);
        year.setWrapSelectorWheel(false);
        year.setValue(Math.max(last[0] - 2, j[0]));

        days(day, Fa.monthLength(year.getValue(), month.getValue()));
        day.setValue(Math.min(j[2], day.getMaxValue()));
        NumberPicker.OnValueChangeListener relen = (p, o, n) -> {
            int keep = day.getValue();
            days(day, Fa.monthLength(year.getValue(), month.getValue()));
            day.setValue(Math.min(keep, day.getMaxValue()));
        };
        month.setOnValueChangedListener(relen);
        year.setOnValueChangedListener(relen);

        // Right to left, so the wheels read like the date: day, month, year.
        LinearLayout row = Ui.rowLayout(a);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Ui.pad(row, 16, 8, 16, 0);
        row.addView(day, weight(1f));
        row.addView(month, weight(1.6f));
        row.addView(year, weight(1.2f));

        AlertDialog d = new AlertDialog.Builder(a).setTitle(title).setView(row)
                .setPositiveButton("تأیید", (x, w) -> {
                    long ms = Fa.jalaliDayStart(year.getValue(), month.getValue(), day.getValue(), tz);
                    onPick.picked(Math.min(ms, Fa.dayStart(maxMs, tz)));
                })
                .setNegativeButton("انصراف", null).show();
        Ui.grayCancel(d);
    }

    private static NumberPicker wheel(Activity a) {
        NumberPicker p = new NumberPicker(a);
        p.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS); // wheels only, no keyboard
        return p;
    }

    private static void days(NumberPicker p, int len) {
        if (p.getMaxValue() == len && p.getDisplayedValues() != null) return;
        p.setDisplayedValues(null); // must be cleared before the range shrinks
        p.setMinValue(1);
        p.setMaxValue(len);
        String[] v = new String[len];
        for (int i = 0; i < len; i++) v[i] = Fa.d(i + 1);
        p.setDisplayedValues(v);
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }
}
