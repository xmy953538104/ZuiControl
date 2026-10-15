package android.os;

/** Compile-only platform API. Android supplies the implementation. */
public abstract class UEventObserver {
    public static final class UEvent {
        public String get(String key) { throw new UnsupportedOperationException(); }
    }
    public abstract void onUEvent(UEvent event);
    public final void startObserving(String match) { throw new UnsupportedOperationException(); }
    public final void stopObserving() { throw new UnsupportedOperationException(); }
}
