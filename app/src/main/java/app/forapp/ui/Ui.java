package app.forapp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import app.forapp.R;
import app.forapp.core.Fa;

/** Tiny view toolkit for the app's Swiss look: black/white, one red, hairlines, big numerals. */
final class Ui {
    private Ui() {}

    static final int W_REGULAR = 0, W_BOLD = 1, W_BLACK = 2;
    private static Typeface regular, bold, black;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int color(Context c, int res) {
        return c.getColor(res);
    }

    static Typeface font(Context c, int weight) {
        try {
            if (regular == null) {
                regular = c.getResources().getFont(R.font.vazir_regular);
                bold = c.getResources().getFont(R.font.vazir_bold);
                black = c.getResources().getFont(R.font.vazir_black);
            }
        } catch (Exception e) {
            return weight == W_REGULAR ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD;
        }
        return weight == W_BLACK ? black : weight == W_BOLD ? bold : regular;
    }

    static TextView text(Context c, CharSequence s, float sp, int colorRes, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(c, colorRes));
        t.setTypeface(font(c, weight));
        t.setIncludeFontPadding(true);
        return t;
    }

    static TextView ellipsize(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Amount with the 3-digit matching code in red, always left-to-right. */
    static CharSequence amount(Context c, Long amount) {
        if (amount == null) return "—";
        String s = Fa.group(amount);
        SpannableString sp = new SpannableString(s);
        int start = Math.max(0, s.length() - 3);
        sp.setSpan(new ForegroundColorSpan(color(c, R.color.red)), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    static TextView ltr(TextView t) {
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        return t;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout rowLayout(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    static View hairline(Context c, int colorRes) {
        View v = new View(c);
        v.setBackgroundColor(color(c, colorRes));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    static void pad(View v, int startDp, int topDp, int endDp, int bottomDp) {
        Context c = v.getContext();
        v.setPaddingRelative(dp(c, startDp), dp(c, topDp), dp(c, endDp), dp(c, bottomDp));
    }

    static void ripple(View v, boolean borderless) {
        TypedValue tv = new TypedValue();
        v.getContext().getTheme().resolveAttribute(borderless ? android.R.attr.selectableItemBackgroundBorderless
                : android.R.attr.selectableItemBackground, tv, true);
        v.setBackgroundResource(tv.resourceId);
        v.setClickable(true);
        v.setFocusable(true);
    }

    static ImageView icon(Context c, int res, int colorRes, int sizeDp) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(color(c, colorRes)));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return i;
    }

    static ImageButton iconButton(Context c, int res, String label, View.OnClickListener l) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(color(c, R.color.mid)));
        b.setContentDescription(label);
        b.setTooltipText(label);
        b.setScaleType(ImageView.ScaleType.CENTER);
        ripple(b, true);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        return b;
    }

    static Switch toggle(Context c, boolean on) {
        Switch s = new Switch(c);
        s.setChecked(on);
        int fg = color(c, R.color.fg), faint = color(c, R.color.faint), bg = color(c, R.color.bg);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{bg, bg}));
        s.setTrackTintList(new ColorStateList(states, new int[]{fg, faint}));
        s.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC);
        return s;
    }

    static GradientDrawable outline(Context c, int colorRes, float radiusDp, float strokeDp, boolean dashed) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        if (dashed) g.setStroke(dp(c, strokeDp), color(c, colorRes), dp(c, 5), dp(c, 4));
        else g.setStroke(dp(c, strokeDp), color(c, colorRes));
        g.setColor(0);
        return g;
    }

    static GradientDrawable fill(Context c, int colorRes, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(color(c, colorRes));
        return g;
    }

    /** Swiss underline input with a small label above it. */
    static LinearLayout field(Context c, String label, EditText e, boolean mono) {
        LinearLayout box = col(c);
        pad(box, 20, 14, 20, 0);
        TextView l = text(c, label, 11, R.color.mid, W_BOLD);
        box.addView(l);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, mono ? 13 : 15);
        e.setTextColor(color(c, R.color.fg));
        e.setHintTextColor(color(c, R.color.mid));
        e.setTypeface(mono ? Typeface.MONOSPACE : font(c, W_BOLD));
        e.setBackgroundTintList(ColorStateList.valueOf(color(c, R.color.fg)));
        e.setSingleLine(true);
        if (mono) {
            e.setTextDirection(View.TEXT_DIRECTION_LTR);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s, 11, R.color.mid, W_BOLD);
        pad(t, 20, 22, 20, 6);
        return t;
    }

    /** A settings row: title (+ optional subtitle) on the start side, a value or control on the end side. */
    static LinearLayout row(Context c, String title, String sub, View end) {
        LinearLayout r = rowLayout(c);
        pad(r, 20, 12, 20, 12);
        r.setMinimumHeight(dp(c, 52));
        LinearLayout t = col(c);
        t.addView(text(c, title, 14, R.color.fg, W_REGULAR));
        if (sub != null) t.addView(text(c, sub, 11.5f, R.color.mid, W_REGULAR));
        r.addView(t, weight1());
        if (end != null) {
            LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMarginStart(dp(c, 12));
            r.addView(end, p);
        }
        return r;
    }

    /** Value text with a chevron, for rows that open something. */
    static LinearLayout value(Context c, String s, int colorRes) {
        LinearLayout v = rowLayout(c);
        TextView t = text(c, s, 12.5f, colorRes, colorRes == R.color.mid ? W_REGULAR : W_BOLD);
        v.addView(t);
        ImageView chev = icon(c, R.drawable.ic_fwd, R.color.mid, 14);
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) chev.getLayoutParams();
        p.setMarginStart(dp(c, 4));
        v.addView(chev);
        return v;
    }

    static TextView button(Context c, String s, boolean solid, View.OnClickListener l) {
        TextView b = text(c, s, 14, solid ? R.color.bg : R.color.fg, W_BOLD);
        b.setGravity(Gravity.CENTER);
        pad(b, 12, 12, 12, 12);
        android.graphics.drawable.Drawable bg = solid ? fill(c, R.color.fg, 0) : outline(c, R.color.fg, 0, 1.5f, false);
        android.graphics.drawable.RippleDrawable rip = new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.mid)), bg, null);
        b.setBackground(rip);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    static FrameLayout.LayoutParams frameMatch() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    static void confirm(Activity a, String title, String msg, String yes, Runnable onYes) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(msg)
                .setPositiveButton(yes, (d, w) -> onYes.run())
                .setNegativeButton("انصراف", null).show();
    }
}
