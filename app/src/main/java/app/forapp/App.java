package app.forapp;

import android.app.Application;

import app.forapp.core.Forwarder;
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
        }, "forapp-start").start();
    }
}
