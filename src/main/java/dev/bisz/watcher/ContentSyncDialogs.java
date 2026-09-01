package dev.bisz.watcher;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

final class ContentSyncDialogs {
    private ContentSyncDialogs() {
    }

    static boolean confirmDownloads(int count) {
        return confirm("Found " + count + " new content files. Confirm download?");
    }

    static boolean confirmCleanup(int count) {
        return confirm("Found " + count + " outdated managed content files. Confirm synchronization?");
    }

    static void showComplete(int downloadedCount) {
        String message = downloadedCount > 0
                ? downloadedCount + " new content files downloaded. A restart is needed to load all the content."
                : "Managed content cleanup is ready. A restart is needed to finish synchronization.";
        showMessage(
                message,
                "DevWatcher Content Sync",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    static void showError(String message) {
        showMessage(message, "DevWatcher Content Sync Error", JOptionPane.ERROR_MESSAGE);
    }

    private static boolean confirm(String message) {
        prepareAwt();
        if (GraphicsEnvironment.isHeadless()) {
            Watcher.LOGGER.error("Content synchronization confirmation cannot be shown in a headless environment");
            return false;
        }
        AtomicBoolean accepted = new AtomicBoolean();
        invokeAndWait(() -> {
            JOptionPane optionPane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
            JDialog dialog = optionPane.createDialog(null, "DevWatcher Content Sync");
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setModal(true);
            dialog.setAlwaysOnTop(true);
            try {
                dialog.setLocationRelativeTo(null);
                dialog.setVisible(true);
                accepted.set(Integer.valueOf(JOptionPane.YES_OPTION).equals(optionPane.getValue()));
            } finally {
                dialog.dispose();
            }
        });
        return accepted.get();
    }

    private static void showMessage(String message, String title, int messageType) {
        prepareAwt();
        if (GraphicsEnvironment.isHeadless()) {
            Watcher.LOGGER.error("{}: {}", title, message);
            return;
        }
        invokeAndWait(() -> {
            JOptionPane optionPane = new JOptionPane(message, messageType, JOptionPane.DEFAULT_OPTION);
            JDialog dialog = optionPane.createDialog(null, title);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setModal(true);
            dialog.setAlwaysOnTop(true);
            try {
                dialog.setLocationRelativeTo(null);
                dialog.setVisible(true);
            } finally {
                dialog.dispose();
            }
        });
    }

    private static void invokeAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while displaying a content synchronization dialog", error);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Could not display a content synchronization dialog", error.getCause());
        }
    }

    private static void prepareAwt() {
        System.setProperty("java.awt.headless", "false");
    }
}
