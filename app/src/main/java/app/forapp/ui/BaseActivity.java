package app.forapp.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import app.forapp.R;

/** Right-to-left, edge-to-edge screens with the shared header. */
abstract class BaseActivity extends Activity {
    protected LinearLayout root;

    /** Persian everywhere, so system dialogs and pickers are right-to-left too, whatever the phone's language. */
    @Override
    protected void attachBaseContext(Context base) {
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(new Locale("fa"));
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(d.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (!night && Build.VERSION.SDK_INT >= 27) {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(d.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        root = Ui.col(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setTextDirection(View.TEXT_DIRECTION_RTL);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                top = s.top; bottom = s.bottom; left = s.left; right = s.right;
            } else {
                top = in.getSystemWindowInsetTop(); bottom = in.getSystemWindowInsetBottom();
                left = in.getSystemWindowInsetLeft(); right = in.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return in;
        });
        setContentView(root);
    }

    /** Header with a back arrow and a title; returns the end-side container for icons. */
    protected LinearLayout backHeader(String title) {
        LinearLayout h = Ui.rowLayout(this);
        Ui.pad(h, 6, 4, 8, 4);
        h.setMinimumHeight(Ui.dp(this, 56));
        h.addView(Ui.iconButton(this, R.drawable.ic_back, "بازگشت", v -> finish()));
        TextView t = Ui.text(this, title, 17, R.color.fg, Ui.W_BLACK);
        Ui.ellipsize(t);
        h.addView(t, Ui.weight1());
        LinearLayout end = Ui.rowLayout(this);
        end.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        h.addView(end);
        root.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.hairline(this, R.color.faint));
        return end;
    }

    protected FrameLayout body() {
        FrameLayout f = new FrameLayout(this);
        root.addView(f, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return f;
    }
}
