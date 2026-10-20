package app.forapp.ui;

import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Executors;

import app.forapp.core.Db;
import app.forapp.core.ProxyServers;
import app.forapp.core.Proxies;
import app.forapp.core.Request;

/** Opens the proxy screens with sample data, so they run on a device and leave screenshots in /sdcard/Download. */
@RunWith(AndroidJUnit4.class)
public class ScreensTest {
    private final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    private final Context c = inst.getTargetContext();

    private static Proxies.Proxy proxy(String type, String host, int port, String user, Boolean ok, long ms, String err, long agoMs) {
        Proxies.Proxy p = new Proxies.Proxy();
        p.type = type;
        p.host = host;
        p.port = port;
        p.user = user;
        p.pass = user == null ? null : "s3cret";
        p.lastOk = ok;
        p.lastMs = ms;
        p.lastError = err;
        p.lastAt = ok == null ? 0 : System.currentTimeMillis() - agoMs;
        return p;
    }

    @Test public void proxyScreens() throws Exception {
        try (ProxyServers.Socks5 socks = new ProxyServers.Socks5(null, null)) {
            Db db = Db.get(c);
            Db.Dest d = new Db.Dest();
            d.name = "ربات فروش";
            d.url = "https://example.com/hook";
            d.proxyOn = true;
            d.proxies = new ArrayList<>();
            d.proxies.add(proxy(Proxies.SOCKS5, "127.0.0.1", socks.port(), null, true, 214, null, 5 * 60_000));
            d.proxies.add(proxy(Proxies.HTTP, "127.0.0.1", ProxyServers.deadPort(), null, false, 10_000, "timeout", 5 * 60_000));
            d.proxies.add(proxy(Proxies.SOCKS5, "127.0.0.1", ProxyServers.deadPort(), "user", null, 0, null, 0));
            long id = db.saveDest(d, new HashSet<>());

            Activity a = inst.startActivitySync(new Intent(c, DestinationEditActivity.class).putExtra("id", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertNotNull(a);
            inst.waitForIdleSync();
            inst.runOnMainSync(() -> {
                View section = find(a.getWindow().getDecorView(), "پروکسی");
                ScrollView sv = scroller(section);
                if (sv != null) sv.scrollTo(0, top(section, sv) - 40);
            });
            shot("1-destination");

            inst.runOnMainSync(() -> find(a.getWindow().getDecorView(), "تست پروکسی‌ها").performClick());
            Thread.sleep(6000);
            shot("2-test-sheet");
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            inst.waitForIdleSync();

            // A delivery that went through the second proxy, and one whose whole pass failed.
            Db.Msg m = Request.sample(false);
            m.fp = "screens-" + System.nanoTime();
            List<Db.Dest> to = new ArrayList<>();
            to.add(db.dest(id));
            long msgId = db.insertMessage(m, to);
            Db.Delivery del = db.deliveries(msgId).get(0);
            db.finishDelivery(del.id, Db.PENDING, 2, System.currentTimeMillis() + 600_000, null, "هیچ‌کدام از ۳ پروکسی نرسید", null,
                    "127.0.0.1:1080\tfail\tconnection refused\nproxy.mydomain.ir:3128\tfail\ttimeout\n91.107.3.21:1080\tfail\t407 · نام کاربری یا رمز پروکسی درست نیست");
            inst.runOnMainSync(() -> new DetailsSheet(a, msgId, Executors.newSingleThreadExecutor(), TimeZone.getDefault()).show());
            Thread.sleep(1500);
            shot("3-details-failed");
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            db.finishDelivery(del.id, Db.SENT, 3, System.currentTimeMillis(), 200, null, "پروکسی ۲ · SOCKS5",
                    "proxy.mydomain.ir:3128\tfail\ttimeout\n185.120.44.9:1080\tok\t۲۱۴ ms");
            inst.runOnMainSync(() -> new DetailsSheet(a, msgId, Executors.newSingleThreadExecutor(), TimeZone.getDefault()).show());
            Thread.sleep(1500);
            shot("4-details-sent");
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            a.finish();

            Intent edit = new Intent(c, ProxyEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ProxyEditActivity.EXTRA_INDEX, 2).putExtra(ProxyEditActivity.EXTRA_TARGET, "https://example.com/hook");
            ProxyEditActivity.put(edit, proxy(Proxies.SOCKS5, "91.107.3.21", 1080, "user", true, 680, null, 120_000));
            Activity e = inst.startActivitySync(edit);
            inst.waitForIdleSync();
            Thread.sleep(500);
            shot("5-edit-proxy");
            e.finish();

            Activity n = inst.startActivitySync(new Intent(c, ProxyEditActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ProxyEditActivity.EXTRA_TARGET, "https://example.com/hook"));
            inst.waitForIdleSync();
            Thread.sleep(500);
            shot("6-new-proxy");
            n.finish();
            db.deleteDest(id);
        }
    }

    private static View find(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = find(g.getChildAt(i), text);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static ScrollView scroller(View v) {
        for (Object p = v == null ? null : v.getParent(); p instanceof View; p = ((View) p).getParent())
            if (p instanceof ScrollView) return (ScrollView) p;
        return null;
    }

    private static int top(View v, View ancestor) {
        int y = 0;
        for (View x = v; x != ancestor && x != null; x = (View) x.getParent()) y += x.getTop();
        return y;
    }

    private void shot(String name) throws Exception {
        inst.waitForIdleSync();
        Thread.sleep(400);
        ParcelFileDescriptor pfd = inst.getUiAutomation().executeShellCommand("screencap -p /sdcard/Download/forapp-" + name + ".png");
        try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[1024];
            while (in.read(buf) > 0) { /* wait for screencap to finish */ }
        }
        pfd.close();
    }
}
