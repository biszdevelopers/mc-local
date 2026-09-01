package dev.bisz.watcher;

public final class ModIdentity {
    public static final String MOD_ID = "watcher";

    private ModIdentity() {
    }

    public static String namespaced(String path) {
        return MOD_ID + ":" + path;
    }
}
