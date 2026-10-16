package app.forapp;

import android.app.Application;

import app.forapp.core.Forwarder;
import app.forapp.core.Inbox;
import app.forapp.core.Trace;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Trace.init(this);
        Forwarder.createChannel(this);
        Forwarder.watchNetwork(this);
        new Thread(() -> {
            Trace.startup(this);
            Forwarder.cleanup(this);
            Forwarder.schedule(this);
            Inbox.schedulePeriodic(this);
            Inbox.catchUpAndSend(this, "app start"); // bank SMS that came while the app was not running
        }, "forapp-start").start();
    }
}
