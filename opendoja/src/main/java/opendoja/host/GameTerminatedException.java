package opendoja.host;

public class GameTerminatedException extends RuntimeException {
    public GameTerminatedException() {
        super("Game terminated");
    }
}