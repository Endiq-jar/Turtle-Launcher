package net.endiq.launcher.progresskeeper;

import static net.endiq.launcher.Tools.BYTE_TO_MB;

import com.endiq.turtlelauncher.utils.ZHTools;
import com.endiq.turtlelauncher.utils.file.FileTools;

import net.endiq.launcher.Tools;
import net.endiq.launcher.tasks.SpeedCalculator;

public class DownloaderProgressWrapper implements Tools.DownloaderFeedback {
    private final SpeedCalculator mSpeedCalculator = new SpeedCalculator(128);
    private final int mProgressString;
    private final String mProgressRecord;
    private long progressUpdateTime = 0;

    /**
     * A simple wrapper to send the downloader progress to ProgressKeeper
     * @param progressString the string that will be used in the progress reporter
     * @param progressRecord the record for ProgressKeeper
     */
    public DownloaderProgressWrapper(int progressString, String progressRecord) {
        this.mProgressString = progressString;
        this.mProgressRecord = progressRecord;
    }

    @Override
    public void updateProgress(long curr, long max) {
        long currentTime = ZHTools.getCurrentTimeMillis();
        if (currentTime - progressUpdateTime < 150) return;
        progressUpdateTime = currentTime;

        Object[] va;
        va = new Object[3];
        va[0] = curr / BYTE_TO_MB;
        va[1] = max / BYTE_TO_MB;
        va[2] = FileTools.formatFileSize(mSpeedCalculator.feed(curr));
        // the allocations are fine because thats how java implements variadic arguments in bytecode: an array of whatever
        int percent = max > 0 ? (int) Math.max((float) curr / max * 100, 0) : 0;
        ProgressKeeper.submitProgress(mProgressRecord, percent, mProgressString, va);
    }
}
