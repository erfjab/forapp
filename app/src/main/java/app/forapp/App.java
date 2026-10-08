package app.forapp;

import android.app.Application;

import app.forapp.core.Forwarder;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Forwarder.createChannel(this);
        new Thread(() -> {
            Forwarder.cleanup(this);
            Forwarder.schedule(this);
        }, "forapp-start").start();
    }
}
