package app.forapp.core;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs only while something is waiting to be sent and the phone has internet. */
public final class SendJob extends JobService {
    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            try {
                Forwarder.sendDue(getApplicationContext(), System.currentTimeMillis() + 8 * 60_000L);
            } finally {
                jobFinished(params, false);
            }
        }, "forapp-job");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Anything not finished stays in the queue; ask the system to run us again.
        return true;
    }
}
