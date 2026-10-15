package app.forapp.core;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;

/** Runs only while something is waiting to be sent and the phone has internet. */
public final class SendJob extends JobService {
    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        Trace.i("JOB", "background job started" + (params.isOverrideDeadlineExpired() ? " (deadline expired)" : ""));
        long t0 = System.currentTimeMillis();
        worker = new Thread(() -> {
            try {
                Forwarder.sendDue(getApplicationContext(), System.currentTimeMillis() + 8 * 60_000L);
            } catch (RuntimeException e) {
                Trace.e("JOB", "background job failed", e);
                throw e;
            } finally {
                Trace.i("JOB", "background job finished after " + (System.currentTimeMillis() - t0) + "ms");
                jobFinished(params, false);
            }
        }, "forapp-job");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Trace.i("JOB", "background job stopped by the system" + (Build.VERSION.SDK_INT >= 31 ? " · reason " + params.getStopReason() : ""));
        // Anything not finished stays in the queue; ask the system to run us again.
        return true;
    }
}
