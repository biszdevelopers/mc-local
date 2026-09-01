package dev.bisz.watcher;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

import com.sun.jna.Platform;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;

final class MinecraftLogWindow {
    private static final String WINDOWS_APP_ID = "Relizc.Watcher.Minecraft";
    private static final int MAX_PENDING_MESSAGES = 10_000;
    private static final int MAX_DOCUMENT_CHARS = 2_000_000;
    private static final int MAX_MESSAGES_PER_TICK = 500;
    private static final AtomicReference<MinecraftLogWindow> INSTANCE = new AtomicReference<>();

    private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingMessageCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    private JFrame frame;
    private JTextArea logText;
    private Timer updateTimer;
    private WindowsTaskbarGroup.Handles taskbarHandles;

    private MinecraftLogWindow() {
    }

    static void open() {
        // Minecraft's launcher marks AWT as headless even for the physical client.
        // This client-only entrypoint runs before Swing/AWT is initialized.
        System.setProperty("java.awt.headless", "false");
        if (GraphicsEnvironment.isHeadless()) {
            Watcher.LOGGER.warn("Log window was not opened because the environment is headless");
            return;
        }

        configureWindowsTaskbarGroup();

        MinecraftLogWindow viewer = new MinecraftLogWindow();
        if (!INSTANCE.compareAndSet(null, viewer)) {
            viewer.shutdown();
            return;
        }

        if (SwingUtilities.isEventDispatchThread()) {
            viewer.createAndShowSafely();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(viewer::createAndShowSafely);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Watcher.LOGGER.warn("Interrupted while opening the dedicated log window", exception);
        } catch (InvocationTargetException exception) {
            Watcher.LOGGER.error("Could not open the dedicated log window", exception.getCause());
        }
    }

    static void append(String message) {
        MinecraftLogWindow viewer = INSTANCE.get();
        if (viewer != null) {
            viewer.enqueue(message);
        }
    }

    static void flushAndWait() {
        MinecraftLogWindow viewer = INSTANCE.get();
        if (viewer == null) {
            return;
        }

        if (SwingUtilities.isEventDispatchThread()) {
            viewer.flushPendingMessages();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(viewer::flushPendingMessages);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Watcher.LOGGER.warn("Interrupted while flushing the dedicated log window", exception);
        } catch (InvocationTargetException exception) {
            Watcher.LOGGER.error("Could not flush the dedicated log window", exception.getCause());
        }
    }

    static void close() {
        MinecraftLogWindow viewer = INSTANCE.getAndSet(null);
        if (viewer != null) {
            viewer.shutdown();
        }
    }

    private void createAndShowSafely() {
        try {
            createAndShow();
        } catch (RuntimeException | LinkageError exception) {
            Watcher.LOGGER.error("Could not create the Minecraft log window", exception);
            INSTANCE.compareAndSet(this, null);
            shutdown();
        }
    }

    private void createAndShow() {
        if (closed.get()) {
            return;
        }

        logText = new JTextArea();
        logText.setEditable(false);
        logText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logText.setLineWrap(false);

        frame = new JFrame("DevWatcher Minecraft Content Sync");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(new JScrollPane(logText), BorderLayout.CENTER);
        frame.setSize(1000, 600);
        frame.setLocationByPlatform(true);
        frame.setAutoRequestFocus(false);
        frame.setAlwaysOnTop(false);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                INSTANCE.compareAndSet(MinecraftLogWindow.this, null);
                shutdown();
            }
        });

        updateTimer = new Timer(100, event -> flushPendingMessages());
        updateTimer.start();

        if (Platform.isWindows()) {
            frame.addNotify();
            try {
                long minecraftWindow = Minecraft.getInstance().getWindow().getWindow();
                long minecraftHandle = GLFWNativeWin32.glfwGetWin32Window(minecraftWindow);
                taskbarHandles = WindowsTaskbarGroup.group(frame, minecraftHandle);
                if (taskbarHandles != null) {
                    Watcher.LOGGER.info("Grouped the log and Minecraft windows under one taskbar application");
                }
            } catch (RuntimeException | LinkageError exception) {
                Watcher.LOGGER.warn("Could not group the log window with Minecraft in the Windows taskbar", exception);
            }
        }

        frame.setVisible(true);
    }

    private static void configureWindowsTaskbarGroup() {
        if (!Platform.isWindows()) {
            return;
        }

        try {
            HRESULT result = Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString(WINDOWS_APP_ID));
            if (COMUtils.FAILED(result)) {
                Watcher.LOGGER.warn("Could not group the log window with Minecraft in the Windows taskbar: {}", result);
            }
        } catch (RuntimeException | LinkageError exception) {
            Watcher.LOGGER.warn("Could not configure the Minecraft taskbar window group", exception);
        }
    }

    private void enqueue(String message) {
        int count = pendingMessageCount.incrementAndGet();
        if (count <= MAX_PENDING_MESSAGES && !closed.get()) {
            pendingMessages.offer(message);
        } else {
            pendingMessageCount.decrementAndGet();
        }
    }

    private void flushPendingMessages() {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < MAX_MESSAGES_PER_TICK; i++) {
            String message = pendingMessages.poll();
            if (message == null) {
                break;
            }
            pendingMessageCount.decrementAndGet();
            batch.append(message);
        }

        if (batch.isEmpty()) {
            return;
        }

        logText.append(batch.toString());
        trimDocument();
        logText.setCaretPosition(logText.getDocument().getLength());
    }

    private void trimDocument() {
        Document document = logText.getDocument();
        int excess = document.getLength() - MAX_DOCUMENT_CHARS;
        if (excess > 0) {
            try {
                document.remove(0, excess);
            } catch (BadLocationException ignored) {
                // The document can only change on this Swing event thread.
            }
        }
    }

    private void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        pendingMessages.clear();

        SwingUtilities.invokeLater(() -> {
            if (updateTimer != null) {
                updateTimer.stop();
            }
            if (frame != null) {
                try {
                    WindowsTaskbarGroup.clear(taskbarHandles);
                } catch (RuntimeException | LinkageError exception) {
                    Watcher.LOGGER.warn("Could not clear the Windows taskbar window properties", exception);
                }
                taskbarHandles = null;
                frame.dispose();
            }
        });
    }

}
