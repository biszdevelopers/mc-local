package dev.bisz.watcher;

import org.apache.commons.lang3.text.WordUtils;

import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

final class StartupDialog {
    private StartupDialog() {
    }

    static boolean awaitConsent() {
        // Minecraft marks AWT as headless even though the physical client has a window.
        System.setProperty("java.awt.headless", "false");
        if (GraphicsEnvironment.isHeadless()) {
            Watcher.LOGGER.warn("Startup dialog was not opened because the environment is headless");
            return false;
        }

        AtomicBoolean accepted = new AtomicBoolean();
        Runnable showDialog = () -> accepted.set(showDialog());

        if (SwingUtilities.isEventDispatchThread()) {
            showDialog.run();
            return accepted.get();
        } else {
            try {
                SwingUtilities.invokeAndWait(showDialog);
                return accepted.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Watcher.LOGGER.warn("Interrupted while waiting for the startup dialog", exception);
                return false;
            } catch (InvocationTargetException exception) {
                Watcher.LOGGER.error("Could not display the startup dialog", exception.getCause());
                return false;
            }
        }
    }
    private static boolean showDialog() {
        String message = "DevWatcher keeps this Minecraft installation synchronized with the configured content server.\n\n" +
                "DevWatcher may perform the following during startup:\n" +
                "- Read and hash files in server-managed Minecraft runtime folders\n" +
                "- Download, verify, and install required content\n" +
                "- Quarantine outdated content previously managed by DevWatcher\n\n" +
                WordUtils.wrap("Unrelated local files are preserved. When content changes, DevWatcher asks before downloading and closes Minecraft after installation so the updated content can load on the next launch. By clicking \"Yes\", you remember this startup permission for future launches.", 100, "\n", false);

        JOptionPane optionPane = new JOptionPane(
                message,
                JOptionPane.QUESTION_MESSAGE,
                JOptionPane.YES_NO_OPTION
        );
        JDialog dialog = optionPane.createDialog(null, "DevWatcher Minecraft Content Sync");
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setModal(true);
        dialog.setAlwaysOnTop(true);
        dialog.setAutoRequestFocus(true);
        dialog.setFocusableWindowState(true);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                dialog.setAlwaysOnTop(true);
                dialog.toFront();
                dialog.requestFocus();
            }
        });

        try {
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
            return Integer.valueOf(JOptionPane.YES_OPTION).equals(optionPane.getValue());
        } finally {
            dialog.dispose();
        }
    }
}
