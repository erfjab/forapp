package app.forapp.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.Telephony;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.forapp.R;
import app.forapp.core.Db;
import app.forapp.core.Fa;
import app.forapp.core.Forwarder;
import app.forapp.core.SmsParser;

/**
 * Which SMS senders count as which bank. Only these senders are ever read, which also stops a
 * fake "deposit" SMS sent from an ordinary phone number from confirming a payment.
 */
public final class BanksActivity extends BaseActivity {
    private static final int REQ_READ = 3;
    private LinearLayout list;
    private EditText pendingSenders;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout end = backHeader("بانک‌ها و فرستنده‌ها");
        end.addView(Ui.iconButton(this, R.drawable.ic_plus, "افزودن بانک", v -> edit(new Db.Bank())));
        ScrollView sv = new ScrollView(this);
        list = Ui.col(this);
        sv.addView(list);
        body().addView(sv, Ui.frameMatch());
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        list.removeAllViews();
        TextView intro = Ui.text(this, "فقط پیامک فرستنده‌های این فهرست خوانده می‌شود. این کار جلوی پیامک واریز جعلی از یک شماره‌ی معمولی را هم می‌گیرد."
                + " شماره‌ی فرستنده را از پیامک‌های قبلی بانک انتخاب کنید.", 12.5f, R.color.mid, Ui.W_REGULAR);
        intro.setLineSpacing(0, 1.3f);
        Ui.pad(intro, 20, 16, 20, 16);
        list.addView(intro);
        for (Db.Bank bk : Db.get(this).banks()) {
            List<String> senders = bk.senderList();
            LinearLayout r = Ui.row(this, bk.name, senders.isEmpty() ? "فرستنده تنظیم نشده" : Fa.d(String.join("، ", senders)),
                    Ui.value(this, "", R.color.mid));
            TextView sub = (TextView) ((LinearLayout) r.getChildAt(0)).getChildAt(1);
            if (senders.isEmpty()) sub.setTextColor(Ui.color(this, R.color.mid));
            else sub.setTextColor(Ui.color(this, R.color.fg));
            Ui.ripple(r, false);
            r.setOnClickListener(v -> edit(bk));
            list.addView(Ui.hairline(this, R.color.line));
            list.addView(r);
        }
        list.addView(Ui.hairline(this, R.color.line));
        View space = new View(this);
        list.addView(space, new LinearLayout.LayoutParams(1, Ui.dp(this, 32)));
    }

    private void edit(Db.Bank bk) {
        LinearLayout v = Ui.col(this);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Ui.pad(v, 4, 8, 4, 0);
        EditText name = new EditText(this);
        name.setText(bk.name);
        v.addView(Ui.field(this, "نام بانک", name, false));
        EditText senders = new EditText(this);
        senders.setText(bk.senders.replace(",", "\n"));
        LinearLayout sf = Ui.field(this, "شماره یا نام فرستنده، هر خط یکی", senders, true);
        senders.setSingleLine(false);
        senders.setMinLines(2);
        senders.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        senders.setHint("+98700011\nBankMellat");
        v.addView(sf);
        TextView pick = Ui.text(this, "انتخاب از پیامک‌های اخیر", 13, R.color.fg, Ui.W_BOLD);
        Ui.pad(pick, 20, 14, 20, 6);
        pick.setOnClickListener(x -> pickFromInbox(senders));
        v.addView(pick);

        AlertDialog.Builder bl = new AlertDialog.Builder(this).setView(v)
                .setPositiveButton("ذخیره", (d, w) -> {
                    bk.name = name.getText().toString().trim();
                    if (bk.name.isEmpty()) bk.name = "بانک";
                    List<String> clean = new ArrayList<>();
                    for (String s : senders.getText().toString().split("[,\\n،]")) if (!s.trim().isEmpty()) clean.add(Fa.ascii(s.trim()));
                    bk.senders = String.join(",", clean);
                    Db.get(this).saveBank(bk);
                    Forwarder.changed();
                    build();
                })
                .setNegativeButton("انصراف", null);
        if (bk.id > 0) bl.setNeutralButton("حذف", (d, w) -> Ui.confirm(this, "حذف بانک", "«" + bk.name + "» از فهرست و از همه‌ی مقصدها حذف می‌شود.", "حذف", () -> {
            Db.get(this).deleteBank(bk.id);
            build();
        }));
        bl.show();
    }

    /** Lists senders from the inbox (newest first) so the bank's real sender can be picked instead of typed. */
    private void pickFromInbox(EditText target) {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            pendingSenders = target;
            requestPermissions(new String[]{Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS}, REQ_READ);
            return;
        }
        Map<String, String[]> seen = new LinkedHashMap<>(); // canonical → {display address, latest body}
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Cursor c = getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY}, null, null, Telephony.Sms.DEFAULT_SORT_ORDER)) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < 1000) {
                String addr = c.getString(0);
                if (addr == null) continue;
                String key = SmsParser.canonicalSender(addr);
                if (!seen.containsKey(key)) seen.put(key, new String[]{addr, c.getString(1)});
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
            }
        } catch (Exception e) {
            Ui.toast(this, "خواندن پیامک‌ها ممکن نشد");
            return;
        }
        if (seen.isEmpty()) {
            Ui.toast(this, "پیامکی در صندوق دریافت پیدا نشد");
            return;
        }
        List<String> keys = new ArrayList<>(seen.keySet());
        // Senders whose texts look like deposits come first.
        keys.sort((a, b) -> Boolean.compare(looksBank(seen.get(b)[1]), looksBank(seen.get(a)[1])));
        String[] labels = new String[keys.size()];
        boolean[] checked = new boolean[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            String[] s = seen.get(keys.get(i));
            String body = s[1] == null ? "" : s[1].replace('\n', ' ');
            labels[i] = s[0] + "  ·  " + Fa.d(counts.get(keys.get(i))) + " پیامک\n" + (body.length() > 60 ? body.substring(0, 60) + "…" : body);
        }
        new AlertDialog.Builder(this).setTitle("فرستنده‌ی بانک را انتخاب کنید")
                .setMultiChoiceItems(labels, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton("افزودن", (d, w) -> {
                    StringBuilder t = new StringBuilder(target.getText().toString().trim());
                    for (int i = 0; i < keys.size(); i++) {
                        if (!checked[i]) continue;
                        String addr = seen.get(keys.get(i))[0];
                        if (t.indexOf(addr) >= 0) continue;
                        if (t.length() > 0) t.append('\n');
                        t.append(addr);
                    }
                    target.setText(t.toString());
                })
                .setNegativeButton("انصراف", null).show();
    }

    private static boolean looksBank(String body) {
        if (body == null) return false;
        SmsParser.Result r = SmsParser.parse(body);
        return r.amount != null && r.deposit != null;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_READ && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED && pendingSenders != null) {
            pickFromInbox(pendingSenders);
        } else if (code == REQ_READ) {
            Ui.toast(this, "بدون اجازه‌ی پیامک، شماره را دستی بنویسید");
        }
        pendingSenders = null;
    }
}
