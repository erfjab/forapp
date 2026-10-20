package app.forapp.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import app.forapp.R;
import app.forapp.core.Fa;
import app.forapp.core.Prefs;
import app.forapp.core.Proxies;
import app.forapp.core.Trace;

/**
 * One proxy of a destination, as settings rows: type, address, port, and a login when the proxy needs one.
 * Nothing is stored here; the proxy goes back to the destination screen and is saved with the destination.
 */
public final class ProxyEditActivity extends BaseActivity {
    static final String EXTRA_INDEX = "index", EXTRA_TARGET = "target", EXTRA_DELETE = "delete";

    private Proxies.Proxy proxy;
    private int index;
    private String target;
    private String type;
    private EditText host, port, user, pass;
    private Switch auth;
    private LinearLayout authRows, seg;
    private TextView state;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        index = in.getIntExtra(EXTRA_INDEX, -1);
        target = in.getStringExtra(EXTRA_TARGET);
        proxy = index >= 0 ? read(in) : new Proxies.Proxy();
        type = proxy.type;

        backHeader(index >= 0 ? "پروکسی " + Fa.d(index + 1) : "پروکسی تازه");
        LinearLayout page = Ui.col(this);
        body().addView(page, Ui.frameMatch());

        ScrollView sv = new ScrollView(this);
        LinearLayout f = Ui.col(this);
        sv.addView(f);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        seg = Ui.rowLayout(this);
        seg.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        seg.setBackground(Ui.outline(this, R.color.fg, 0, 1.5f, false));
        f.addView(Ui.row(this, "نوع", null, seg));
        renderType();
        f.addView(Ui.hairline(this, R.color.line));

        host = input("1.2.3.4", false);
        host.setText(proxy.host);
        f.addView(inputRow("آدرس", host));
        f.addView(Ui.hairline(this, R.color.line));

        port = input(type.equals(Proxies.HTTP) ? "8080" : "1080", false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (proxy.port > 0) port.setText(String.valueOf(proxy.port));
        f.addView(inputRow("پورت", port));
        f.addView(Ui.hairline(this, R.color.line));

        auth = Ui.toggle(this, proxy.auth());
        f.addView(Ui.row(this, "احراز هویت", "نام کاربری و رمز", auth));
        authRows = Ui.col(this);
        user = input("user", false);
        user.setText(proxy.user == null ? "" : proxy.user);
        pass = input("••••••", true);
        pass.setText(proxy.pass == null ? "" : proxy.pass);
        authRows.addView(Ui.hairline(this, R.color.line));
        authRows.addView(inputRow("نام کاربری", user));
        authRows.addView(Ui.hairline(this, R.color.line));
        authRows.addView(inputRow("رمز", pass));
        f.addView(authRows);
        auth.setOnCheckedChangeListener((v, on) -> authRows.setVisibility(on ? View.VISIBLE : View.GONE));
        authRows.setVisibility(auth.isChecked() ? View.VISIBLE : View.GONE);
        f.addView(Ui.hairline(this, R.color.line));

        LinearLayout paste = Ui.row(this, "چسباندن از کلیپ‌بورد", "همه‌ی ردیف‌ها را از یک آدرس پروکسی پر می‌کند", null);
        Ui.ripple(paste, false);
        paste.setOnClickListener(v -> paste());
        f.addView(paste);
        f.addView(Ui.hairline(this, R.color.line));

        state = Ui.text(this, "", 13, R.color.fg, Ui.W_BOLD);
        Ui.pad(state, 14, 11, 14, 11);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), 0);
        f.addView(state, sp);
        if (proxy.lastOk != null) showState(proxy.lastOk, proxy.lastMs, proxy.lastError, proxy.lastAt);
        else state.setVisibility(View.GONE);

        if (index >= 0) {
            TextView del = Ui.text(this, "حذف این پروکسی", 13.5f, R.color.red, Ui.W_BOLD);
            del.setGravity(Gravity.CENTER);
            Ui.pad(del, 12, 18, 12, 24);
            del.setOnClickListener(v -> Ui.confirm(this, "حذف پروکسی", proxy.hostPort() + " از فهرست این مقصد حذف می‌شود.", "حذف", () -> {
                Intent out = new Intent();
                out.putExtra(EXTRA_INDEX, index);
                out.putExtra(EXTRA_DELETE, true);
                setResult(RESULT_OK, out);
                finish();
            }));
            f.addView(del, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Test and save stay at the bottom, above the keyboard.
        page.addView(Ui.hairline(this, R.color.line));
        LinearLayout foot = Ui.rowLayout(this);
        Ui.pad(foot, 20, 12, 20, 16);
        foot.addView(Ui.button(this, "تست", false, v -> test()), Ui.weight1());
        View gap = new View(this);
        foot.addView(gap, Ui.lp(Ui.dp(this, 10), 1));
        foot.addView(Ui.button(this, "ذخیره", true, v -> save()), Ui.weight1());
        page.addView(foot);
    }

    private void renderType() {
        seg.removeAllViews();
        for (String t : new String[]{Proxies.HTTP, Proxies.SOCKS5}) {
            boolean on = t.equals(type);
            TextView v = Ui.text(this, Proxies.HTTP.equals(t) ? "HTTP" : "SOCKS5", 12, on ? R.color.bg : R.color.fg, Ui.W_BLACK);
            Ui.pad(v, 14, 5, 14, 5);
            if (on) v.setBackground(Ui.fill(this, R.color.fg, 0));
            v.setOnClickListener(x -> {
                type = t;
                renderType();
                if (port != null) port.setHint(Proxies.HTTP.equals(t) ? "8080" : "1080");
            });
            seg.addView(v);
        }
    }

    private EditText input(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setBackground(null);
        e.setPadding(0, 0, 0, 0);
        e.setTextSize(14);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextColor(Ui.color(this, R.color.fg));
        e.setHintTextColor((Ui.color(this, R.color.mid) & 0x00FFFFFF) | 0x80000000);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        if (secret) e.setTypeface(Typeface.MONOSPACE);
        return e;
    }

    /** A settings row whose value is typed in place, on the end side. */
    private LinearLayout inputRow(String title, EditText e) {
        LinearLayout r = Ui.rowLayout(this);
        Ui.pad(r, 20, 4, 20, 4);
        r.setMinimumHeight(Ui.dp(this, 52));
        TextView t = Ui.text(this, title, 14, R.color.fg, Ui.W_REGULAR);
        r.addView(t);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMarginStart(Ui.dp(this, 16));
        r.addView(e, p);
        r.setOnClickListener(v -> {
            e.requestFocus();
            e.setSelection(e.length());
            getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(e, 0);
        });
        return r;
    }

    private void paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
        if (text == null || text.toString().trim().isEmpty()) {
            Ui.toast(this, "کلیپ‌بورد خالی است");
            return;
        }
        try {
            Proxies.Proxy p = Proxies.parse(text.toString().trim().split("\\s+")[0]);
            type = p.type;
            renderType();
            host.setText(p.host);
            port.setText(String.valueOf(p.port));
            auth.setChecked(p.auth());
            user.setText(p.user == null ? "" : p.user);
            pass.setText(p.pass == null ? "" : p.pass);
            Ui.toast(this, "از کلیپ‌بورد پر شد");
        } catch (IllegalArgumentException e) {
            Ui.toast(this, e.getMessage());
        }
    }

    /** The form as a proxy, or null after pointing at what is wrong. */
    private Proxies.Proxy form() {
        String h = host.getText().toString().trim(), pt = port.getText().toString().trim();
        String err = Proxies.check(h, pt);
        if (err != null) {
            EditText at = h.isEmpty() || err.contains("سرور") ? host : port;
            at.setError(err);
            at.requestFocus();
            return null;
        }
        Proxies.Proxy p = proxy.copy();
        p.type = type;
        p.host = h.replace("[", "").replace("]", "");
        p.port = Integer.parseInt(pt);
        boolean a = auth.isChecked() && !user.getText().toString().trim().isEmpty();
        p.user = a ? user.getText().toString().trim() : null;
        p.pass = a ? pass.getText().toString() : null;
        boolean same = p.type.equals(proxy.type) && p.host.equals(proxy.host) && p.port == proxy.port;
        if (!same) { // a different proxy: the old test says nothing about it
            p.lastOk = null;
            p.lastMs = 0;
            p.lastError = null;
            p.lastAt = 0;
        }
        return p;
    }

    private void test() {
        Proxies.Proxy p = form();
        if (p == null) return;
        if (target == null || target.isEmpty()) {
            Ui.toast(this, "اول آدرس مقصد را بنویسید");
            return;
        }
        state.setVisibility(View.VISIBLE);
        state.setText("در حال تست…");
        state.setTextColor(Ui.color(this, R.color.mid));
        state.setBackground(Ui.outline(this, R.color.faint, 0, 1.5f, false));
        int timeout = Prefs.of(this).timeoutSec();
        new Thread(() -> {
            Proxies.Probe r = Proxies.probe(p, target, timeout);
            Trace.i("SETTINGS", "proxy test " + p.typeLabel() + " " + p.hostPort() + " → " + Trace.host(target) + ": "
                    + (r.ok ? "OK in " + r.ms + "ms" : r.error));
            runOnUiThread(() -> {
                proxy = p;
                proxy.lastOk = r.ok;
                proxy.lastMs = r.ms;
                proxy.lastError = r.error;
                proxy.lastAt = System.currentTimeMillis();
                showState(r.ok, r.ms, r.error, proxy.lastAt);
            });
        }, "forapp-proxy-test").start();
    }

    private void showState(boolean ok, long ms, String error, long at) {
        // The host and the error are isolated so they read left to right inside the Persian line.
        String where = target == null ? "" : "تا \u2068" + app.forapp.core.Trace.host(target) + "\u2069";
        String when = at > 0 ? Fa.ago(at, System.currentTimeMillis()) : "";
        String second = where.isEmpty() ? when : when.isEmpty() ? where : where + " · " + when;
        state.setVisibility(View.VISIBLE);
        state.setText((ok ? "● وصل شد · " + Fa.d(ms) + " میلی‌ثانیه" : "● وصل نشد · \u2068" + (error == null ? "" : error) + "\u2069")
                + (second.isEmpty() ? "" : "\n" + second));
        state.setTextColor(Ui.color(this, ok ? R.color.fg : R.color.red));
        state.setBackground(Ui.outline(this, ok ? R.color.fg : R.color.red, 0, 1.5f, false));
    }

    private void save() {
        Proxies.Proxy p = form();
        if (p == null) return;
        Intent out = new Intent();
        out.putExtra(EXTRA_INDEX, index);
        put(out, p);
        setResult(RESULT_OK, out);
        finish();
    }

    // ---------- passing a proxy between screens ----------

    static void put(Intent i, Proxies.Proxy p) {
        i.putExtra("p_id", p.id);
        i.putExtra("p_type", p.type);
        i.putExtra("p_host", p.host);
        i.putExtra("p_port", p.port);
        i.putExtra("p_user", p.user);
        i.putExtra("p_pass", p.pass);
        if (p.lastOk != null) i.putExtra("p_ok", p.lastOk);
        i.putExtra("p_ms", p.lastMs);
        i.putExtra("p_err", p.lastError);
        i.putExtra("p_at", p.lastAt);
    }

    static Proxies.Proxy read(Intent i) {
        Proxies.Proxy p = new Proxies.Proxy();
        p.id = i.getLongExtra("p_id", 0);
        p.type = i.getStringExtra("p_type") == null ? Proxies.SOCKS5 : i.getStringExtra("p_type");
        p.host = i.getStringExtra("p_host") == null ? "" : i.getStringExtra("p_host");
        p.port = i.getIntExtra("p_port", 0);
        p.user = i.getStringExtra("p_user");
        p.pass = i.getStringExtra("p_pass");
        p.lastOk = i.hasExtra("p_ok") ? i.getBooleanExtra("p_ok", false) : null;
        p.lastMs = i.getLongExtra("p_ms", 0);
        p.lastError = i.getStringExtra("p_err");
        p.lastAt = i.getLongExtra("p_at", 0);
        return p;
    }
}
