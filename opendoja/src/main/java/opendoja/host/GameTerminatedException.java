package opendoja.host;

/**
 * Thrown by {@link DoJaRuntime#shutdown()} to unwind an application's own thread when
 * the app calls {@code IApplication.terminate()}.
 *
 * <p>It deliberately extends {@link Error} instead of {@link RuntimeException}. Handset
 * titles were written for a VM where termination unwinds the app thread regardless of
 * their own exception handling. Many titles (e.g. Dash's {@code GameCanvas.run()} and
 * {@code DrawMain()}) wrap their whole loop in {@code catch (Exception)}; a termination
 * signal derived from {@code Exception} is silently swallowed there, leaving the game
 * loop spinning forever and causing the next launch to race a zombie thread. Per the
 * Java language, {@code catch (Exception)} cannot catch an {@code Error}.</p>
 */
public class GameTerminatedException extends Error {
    public GameTerminatedException() {
        super("Game terminated");
    }
}