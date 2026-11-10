package com.dualstack.lab;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns temporary execution files after the terminal page closes, until the child exits. */
final class DetachedTerminal implements TerminalSessionClient {
    private final ExecutableFile.Prepared prepared;
    private final File marker, logs;
    private final AtomicBoolean released = new AtomicBoolean();

    private DetachedTerminal(ExecutableFile.Prepared prepared, File marker, File logs) {
        this.prepared = prepared; this.marker = marker; this.logs = logs;
    }

    static void releaseWhenFinished(TerminalSession session, ExecutableFile.Prepared prepared, File marker, File logs) {
        DetachedTerminal owner = new DetachedTerminal(prepared, marker, logs);
        if (session != null && session.isRunning()) {
            session.updateTerminalSessionClient(owner);
            if (!session.isRunning()) owner.onSessionFinished(session);
        } else owner.release(null);
    }

    @Override public void onSessionFinished(TerminalSession session) {
        String text = session.getEmulator() == null ? "" : session.getEmulator().getScreen().getTranscriptText();
        release(text);
    }

    private void release(String text) {
        if (!released.compareAndSet(false, true)) return;
        Tasks.IO.execute(() -> {
            try {
                if (text != null) {
                    File log = File.createTempFile("terminal-output-", ".txt", logs);
                    Files.write(log.toPath(), text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException error) { android.util.Log.w("DetachedTerminal", "无法保存终端输出", error); }
            finally {
                try { if (prepared != null) prepared.close(); }
                catch (IOException error) { android.util.Log.w("DetachedTerminal", "无法清理执行文件", error); }
                try { if (marker != null) Files.deleteIfExists(marker.toPath()); }
                catch (IOException error) { android.util.Log.w("DetachedTerminal", "无法清理进程标记", error); }
            }
        });
    }

    @Override public void onTextChanged(TerminalSession session) { }
    @Override public void onTitleChanged(TerminalSession session) { }
    @Override public void onCopyTextToClipboard(TerminalSession session, String text) { }
    @Override public void onPasteTextFromClipboard(TerminalSession session) { }
    @Override public void onBell(TerminalSession session) { }
    @Override public void onColorsChanged(TerminalSession session) { }
    @Override public void onTerminalCursorStateChange(boolean state) { }
    @Override public void setTerminalShellPid(TerminalSession session, int pid) { }
    @Override public Integer getTerminalCursorStyle() { return 0; }
    @Override public void logError(String tag, String message) { android.util.Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception error) { android.util.Log.e(tag, message, error); }
    @Override public void logStackTrace(String tag, Exception error) { android.util.Log.e(tag, error.getMessage(), error); }
}
