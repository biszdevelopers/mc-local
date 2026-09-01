package dev.bisz.watcher;

import java.awt.Window;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Guid.IID;
import com.sun.jna.platform.win32.Guid.REFIID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

final class WindowsTaskbarGroup {
    private static final String APP_ID = "Relizc.Watcher.Minecraft";
    private static final short VT_EMPTY = 0;
    private static final short VT_LPWSTR = 31;
    private static final int PROPVARIANT_SIZE = Native.POINTER_SIZE == 8 ? 24 : 16;

    private static final IID IID_PROPERTY_STORE = new IID("{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}");
    private static final Memory APP_USER_MODEL_ID = createPropertyKey(
            "{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}", 5);

    private WindowsTaskbarGroup() {
    }

    static Handles group(Window logWindow, long minecraftHandle) {
        if (!Platform.isWindows() || minecraftHandle == 0) {
            return null;
        }

        long logHandle = Native.getWindowID(logWindow);
        if (logHandle == 0) {
            return null;
        }

        boolean initialized = initializeCom();
        try {
            setWindowAppId(minecraftHandle, APP_ID);
            setWindowAppId(logHandle, APP_ID);

            String minecraftAppId = getWindowAppId(minecraftHandle);
            String logAppId = getWindowAppId(logHandle);
            if (!APP_ID.equals(minecraftAppId) || !APP_ID.equals(logAppId)) {
                throw new IllegalStateException("Windows did not retain the shared taskbar application ID");
            }
            return new Handles(minecraftHandle, logHandle);
        } finally {
            if (initialized) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }

    static void clear(Handles handles) {
        if (!Platform.isWindows() || handles == null) {
            return;
        }

        boolean initialized = initializeCom();
        try {
            setWindowAppId(handles.logHandle(), null);
            setWindowAppId(handles.minecraftHandle(), null);
        } finally {
            if (initialized) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }

    private static boolean initializeCom() {
        HRESULT result = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED);
        return COMUtils.SUCCEEDED(result);
    }

    private static void setWindowAppId(long windowHandle, String appId) {
        PropertyStore propertyStore = openPropertyStore(windowHandle);
        try {
            Memory value = new Memory(PROPVARIANT_SIZE);
            value.clear();
            Memory text = null;
            if (appId == null) {
                value.setShort(0, VT_EMPTY);
            } else {
                text = new Memory((long) (appId.length() + 1) * Native.WCHAR_SIZE);
                text.setWideString(0, appId);
                value.setShort(0, VT_LPWSTR);
                value.setPointer(8, text);
            }

            HRESULT result = propertyStore.setValue(APP_USER_MODEL_ID, value);
            if (COMUtils.FAILED(result)) {
                throw new IllegalStateException("IPropertyStore.SetValue failed with " + result);
            }

            // Keep the native text allocation strongly reachable until SetValue has copied it.
            if (text != null) {
                text.getByte(0);
            }
        } finally {
            propertyStore.Release();
        }
    }

    private static String getWindowAppId(long windowHandle) {
        PropertyStore propertyStore = openPropertyStore(windowHandle);
        Memory value = new Memory(PROPVARIANT_SIZE);
        value.clear();
        try {
            HRESULT result = propertyStore.getValue(APP_USER_MODEL_ID, value);
            if (COMUtils.FAILED(result)) {
                throw new IllegalStateException("IPropertyStore.GetValue failed with " + result);
            }

            if (value.getShort(0) != VT_LPWSTR) {
                return null;
            }
            Pointer text = value.getPointer(8);
            return text == null ? null : text.getWideString(0);
        } finally {
            Ole32Extras.INSTANCE.PropVariantClear(value);
            propertyStore.Release();
        }
    }

    private static PropertyStore openPropertyStore(long windowHandle) {
        HWND hwnd = new HWND(Pointer.createConstant(windowHandle));
        PointerByReference result = new PointerByReference();
        HRESULT status = Shell32Extras.INSTANCE.SHGetPropertyStoreForWindow(
                hwnd,
                new REFIID(IID_PROPERTY_STORE),
                result
        );
        if (COMUtils.FAILED(status) || result.getValue() == null) {
            throw new IllegalStateException("SHGetPropertyStoreForWindow failed with " + status);
        }
        return new PropertyStore(result.getValue());
    }

    private static Memory createPropertyKey(String formatId, int propertyId) {
        GUID guid = new GUID(formatId);
        guid.write();

        Memory key = new Memory(20);
        key.write(0, guid.getPointer().getByteArray(0, 16), 0, 16);
        key.setInt(16, propertyId);
        return key;
    }

    record Handles(long minecraftHandle, long logHandle) {
    }

    private static final class PropertyStore extends Unknown {
        private PropertyStore(Pointer pointer) {
            super(pointer);
        }

        private HRESULT getValue(Pointer key, Pointer value) {
            return (HRESULT) _invokeNativeObject(
                    5,
                    new Object[] {getPointer(), key, value},
                    HRESULT.class
            );
        }

        private HRESULT setValue(Pointer key, Pointer value) {
            return (HRESULT) _invokeNativeObject(
                    6,
                    new Object[] {getPointer(), key, value},
                    HRESULT.class
            );
        }
    }

    private interface Shell32Extras extends StdCallLibrary {
        Shell32Extras INSTANCE = Native.load("shell32", Shell32Extras.class, W32APIOptions.DEFAULT_OPTIONS);

        HRESULT SHGetPropertyStoreForWindow(HWND hwnd, REFIID propertyStoreId, PointerByReference propertyStore);
    }

    private interface Ole32Extras extends StdCallLibrary {
        Ole32Extras INSTANCE = Native.load("ole32", Ole32Extras.class, W32APIOptions.DEFAULT_OPTIONS);

        HRESULT PropVariantClear(Pointer value);
    }
}
