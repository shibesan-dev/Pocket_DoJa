package javax.microedition.io;

import opendoja.host.DesktopHttpConnection;
import opendoja.host.DoJaRuntime;
import opendoja.host.OpenDoJaLog;

import java.io.*;
import java.net.URL;
import java.util.Locale;

public class Connector {
    public static final int READ = 1;
    public static final int WRITE = 2;
    public static final int READ_WRITE = 3;

    public static Connection open(String name) throws IOException {
        return open(name, READ_WRITE);
    }

    public static Connection open(String name, int mode) throws IOException {
        return open(name, mode, false);
    }

    public static Connection open(String name, int mode, boolean timeouts) throws IOException {
        opendoja.host.OpenDoJaLog.info(Connector.class, "open: " + name + " mode=" + mode);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return new DesktopHttpConnection(new URL(name), mode, timeouts);
        }
        if (lower.startsWith("resource:///")) {
            return new SimpleStreamConnection(
                    openInputStream(name),
                    noOutput(),
                    name,
                    null,
                    -1L
            );
        }
        if (lower.startsWith("scratchpad:///")) {
            return new SimpleStreamConnection(
                    openInputStream(name),
                    openOutputStream(name),
                    name,
                    "application/octet-stream",
                    -1L
            );
        }
        if (lower.startsWith("file:///")) {
            int dataLength = tekkenDataLength(name);
            if (dataLength > 0) {
                return new SimpleStreamConnection(
                        new java.io.ByteArrayInputStream(createFilledBuffer(dataLength)),
                        new java.io.ByteArrayOutputStream(),
                        name,
                        "application/octet-stream",
                        (long) dataLength
                );
            }
            return new SimpleStreamConnection(
                    new java.io.ByteArrayInputStream(new byte[0]),
                    new java.io.ByteArrayOutputStream(),
                    name,
                    "application/octet-stream",
                    0L
            );
        }
        throw new ConnectionNotFoundException("Unsupported connector URI: " + name);
    }

    public static DataInputStream openDataInputStream(String name) throws IOException {
        return asDataInputStream(openInputStream(name));
    }

    public static DataOutputStream openDataOutputStream(String name) throws IOException {
        return asDataOutputStream(openOutputStream(name));
    }

    public static InputStream openInputStream(String name) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("resource:///")) {
            try {
                InputStream stream = DoJaRuntime.openLaunchResourceStream(name);
                opendoja.host.OpenDoJaLog.info(Connector.class,
                        "Connector.openInputStream: name=" + name + " stream=" + stream + " available=" + stream.available());
                return asDataInputStream(stream);
            } catch (FileNotFoundException e) {
                opendoja.host.OpenDoJaLog.warn(Connector.class, "resource open failed: " + name, e);
                ConnectionNotFoundException missing = new ConnectionNotFoundException(e.getMessage());
                missing.initCause(e);
                throw missing;
            } catch (IOException e) {
                opendoja.host.OpenDoJaLog.warn(Connector.class, "resource open failed: " + name, e);
                throw e;
            }
        }
        if (lower.startsWith("scratchpad:///")) {
            DoJaRuntime runtime = DoJaRuntime.current();
            if (runtime == null) {
                throw new IOException("No active DoJa runtime for scratchpad URI: " + name);
            }
            if (runtime.scratchpadPackedFile() == null && runtime.scratchpadRoot() == null) {
                throw new ConnectionNotFoundException("ScratchPad is not configured for this application");
            }
            ScratchpadLocation location = ScratchpadLocation.parse(name);
            opendoja.host.OpenDoJaLog.debug(Connector.class,
                    "scratchpad open: " + name + " index=" + location.index()
                            + " pos=" + location.position() + " len=" + location.length());
            return asDataInputStream(runtime.openScratchpadInput(location.index(), location.position(), location.length()));
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return asDataInputStream(((DesktopHttpConnection) open(name, READ, false)).openInputStream());
        }
        if (lower.startsWith("file:///")) {
            int dataLength = tekkenDataLength(name);
            if (dataLength > 0) {
                return asDataInputStream(new java.io.ByteArrayInputStream(new byte[dataLength]));
            }
            return asDataInputStream(new java.io.ByteArrayInputStream(new byte[0]));
        }
        throw new ConnectionNotFoundException("Unsupported input connector URI: " + name);
    }

    public static OutputStream openOutputStream(String name) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("scratchpad:///")) {
            DoJaRuntime runtime = DoJaRuntime.current();
            if (runtime == null) {
                throw new IOException("No active DoJa runtime for scratchpad URI: " + name);
            }
            if (runtime.scratchpadPackedFile() == null && runtime.scratchpadRoot() == null) {
                throw new ConnectionNotFoundException("ScratchPad is not configured for this application");
            }
            ScratchpadLocation location = ScratchpadLocation.parse(name);
            return asDataOutputStream(runtime.openScratchpadOutput(location.index(), location.position(), location.length()));
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return asDataOutputStream(((DesktopHttpConnection) open(name, READ_WRITE, false)).openOutputStream());
        }
        if (lower.startsWith("file:///")) {
            return new java.io.ByteArrayOutputStream();
        }
        throw new ConnectionNotFoundException("Unsupported output connector URI: " + name);
    }

    private static OutputStream noOutput() {
        return null;
    }

    private static byte[] createFilledBuffer(int length) {
        byte[] buffer = new byte[length];
        java.util.Arrays.fill(buffer, (byte) 1);
        return buffer;
    }


    private static final java.util.Map<String, Integer> TEKKEN_FAT_SIZES = new java.util.HashMap<>();
    private static volatile boolean tekkenFatLoaded;

    private static int tekkenDataLength(String name) {
        if (name == null) {
            return 0;
        }
        String path = name;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String marker = "tekken2_SO903i_";
        int markerIndex = path.indexOf(marker);
        if (markerIndex < 0 || !path.toLowerCase().endsWith(".dat")) {
            return 0;
        }
        String numberPart = path.substring(markerIndex + marker.length(), path.length() - 4);
        int number;
        try {
            number = Integer.parseInt(numberPart);
        } catch (NumberFormatException ignored) {
            return 0;
        }
        int fileIndex = number / 10;
        String entryName = "tk2_" + String.format(java.util.Locale.ROOT, "%03d", fileIndex);
        ensureTekkenFatLoaded();
        Integer size = TEKKEN_FAT_SIZES.get(entryName);
        return size == null ? 0 : size;
    }

    private static void ensureTekkenFatLoaded() {
        if (tekkenFatLoaded) {
            return;
        }
        synchronized (TEKKEN_FAT_SIZES) {
            if (tekkenFatLoaded) {
                return;
            }
            try {
                java.io.InputStream in = DoJaRuntime.openLaunchResourceStream("resource:///fat.bin");
                if (in != null) {
                    byte[] data = in.readAllBytes();
                    in.close();
                    int count = (data.length - 16) / 36;
                    for (int i = 0; i < count; i++) {
                        int record = 16 + i * 36;
                        if (record + 36 > data.length) {
                            break;
                        }
                        int length = 0;
                        while (length < 15 && data[record + 9 + length] != (byte) ' '
                                && data[record + 9 + length] != 0) {
                            length++;
                        }
                        if (length <= 0) {
                            continue;
                        }
                        String entryName = new String(data, record + 9, length,
                                java.nio.charset.StandardCharsets.US_ASCII).trim();
                        if (entryName.isEmpty()) {
                            continue;
                        }
                        int size = ((data[record + 32] & 0xFF) << 24)
                                | ((data[record + 33] & 0xFF) << 16)
                                | ((data[record + 34] & 0xFF) << 8)
                                | (data[record + 35] & 0xFF);
                        if (size > 0) {
                            TEKKEN_FAT_SIZES.put(entryName, size);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            tekkenFatLoaded = true;
        }
    }


    private static DataInputStream asDataInputStream(InputStream input) {
        return input instanceof DataInputStream dataInput ? dataInput : new DataInputStream(input);
    }

    private static DataOutputStream asDataOutputStream(OutputStream output) {
        return output instanceof DataOutputStream dataOutput ? dataOutput : new DataOutputStream(output);
    }

    private record ScratchpadLocation(int index, long position, long length) {
        private static ScratchpadLocation parse(String raw) {
            String body = raw.substring("scratchpad:///".length());
            int split = 0;
            while (split < body.length() && Character.isDigit(body.charAt(split))) {
                split++;
            }
            int index = Integer.parseInt(body.substring(0, split).trim());
            long position = 0L;
            long length = -1L;
            String params = body.substring(split)
                    .replace(';', '&')
                    .replace(',', '&')
                    .replace(':', '&');
            for (String token : params.split("&")) {
                token = token.trim();
                if (token.isEmpty()) {
                    continue;
                }
                if (token.startsWith("pos=")) {
                    position = Long.parseLong(token.substring("pos=".length()).trim());
                } else if (token.startsWith("length=")) {
                    length = Long.parseLong(token.substring("length=".length()).trim());
                }
            }
            return new ScratchpadLocation(index, position, length);
        }
    }

    private static final class SimpleStreamConnection implements ContentConnection {
        private final InputStream input;
        private final OutputStream output;
        private final String encoding;
        private final String type;
        private final long length;

        private SimpleStreamConnection(InputStream input, OutputStream output, String encoding, String type, long length) {
            this.input = input;
            this.output = output;
            this.encoding = encoding;
            this.type = type;
            this.length = length;
        }

        @Override
        public InputStream openInputStream() {
            return input;
        }

        @Override
        public DataInputStream openDataInputStream() {
            return asDataInputStream(input);
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            if (output == null) {
                throw new IOException("Output not supported");
            }
            return output;
        }

        @Override
        public DataOutputStream openDataOutputStream() throws IOException {
            return asDataOutputStream(openOutputStream());
        }

        @Override
        public void close() throws IOException {
            if (input != null) {
                input.close();
            }
            if (output != null) {
                output.close();
            }
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public String getEncoding() {
            return encoding;
        }

        @Override
        public long getLength() {
            return length;
        }
    }
}
