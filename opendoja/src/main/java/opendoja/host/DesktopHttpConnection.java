package opendoja.host;

import com.docomostar.io.HttpConnection;

import java.io.*;
import java.net.*;

public final class DesktopHttpConnection implements HttpConnection {
    private static final String DEFAULT_USER_AGENT = "DoCoMo/2.0 DOJA_INET_CLIENT(c100;TJ)";

    private final URL url;
    private final int mode;
    private final boolean timeouts;
    private HttpURLConnection connection;
    private String requestMethod = GET;
    private long ifModifiedSince = 0L;
    private boolean userAgentProvidedByGame;
    private boolean requestLogged;
    private boolean responseLogged;
    // When non-null this connection serves a local synthetic body instead of hitting the
    // network (offline resource-manifest stub, see resolveResourceManifestStub).
    private final byte[] stubBody;

    public DesktopHttpConnection(URL url, int mode, boolean timeouts) {
        this.url = rewriteOutboundUrl(url);
        this.mode = mode;
        this.timeouts = timeouts;
        this.stubBody = resolveResourceManifestStub(this.url);
    }

    @Override
    public void connect() throws IOException {
        if (stubBody != null) {
            debug(() -> "HTTP resource-manifest stub (connect) " + url);
            return;
        }
        ensureConnection();
        logRequestIfNeeded();
        try {
            connection.connect();
        } catch (IOException exception) {
            OpenDoJaLog.warn(DesktopHttpConnection.class, "HTTP connect failed " + requestSummary(), exception);
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        if (connection != null) {
            connection.disconnect();
        }
    }

    @Override
    public InputStream openInputStream() throws IOException {
        if (stubBody != null) {
            debug(() -> "HTTP resource-manifest stub body bytes=" + stubBody.length + " " + url);
            return new ByteArrayInputStream(stubBody);
        }
        ensureConnection();
        logRequestIfNeeded();
        try {
            InputStream stream = normalizeResponseInputStream(connection.getInputStream());
            logResponseIfNeeded();
            return stream;
        } catch (IOException exception) {
            OpenDoJaLog.warn(DesktopHttpConnection.class, "HTTP openInputStream failed " + requestSummary(), exception);
            throw exception;
        }
    }

    @Override
    public DataInputStream openDataInputStream() throws IOException {
        return new DataInputStream(openInputStream());
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        if (stubBody != null) {
            return new ByteArrayOutputStream();
        }
        ensureConnection();
        connection.setDoOutput(true);
        logRequestIfNeeded();
        try {
            return maybeWrapOutputStream(connection.getOutputStream());
        } catch (IOException exception) {
            OpenDoJaLog.warn(DesktopHttpConnection.class, "HTTP openOutputStream failed " + requestSummary(), exception);
            throw exception;
        }
    }

    @Override
    public DataOutputStream openDataOutputStream() throws IOException {
        return new DataOutputStream(openOutputStream());
    }

    @Override
    public String getURL() {
        return url.toString();
    }

    @Override
    public void setRequestMethod(String method) throws IOException {
        this.requestMethod = method;
        if (stubBody != null) {
            return;
        }
        if (connection != null) {
            connection.setRequestMethod(method);
        }
    }

    @Override
    public void setRequestProperty(String key, String value) throws IOException {
        if (isUserAgentHeader(key)) {
            userAgentProvidedByGame = true;
        }
        if (stubBody != null) {
            return;
        }
        ensureConnection();
        connection.setRequestProperty(key, OpenDoJaIdentity.replaceDefaultUserIdToken(value));
    }

    @Override
    public int getResponseCode() throws IOException {
        if (stubBody != null) {
            return 200;
        }
        ensureConnection();
        logRequestIfNeeded();
        try {
            int code = connection.getResponseCode();
            logResponseIfNeeded(code);
            return code;
        } catch (IOException exception) {
            OpenDoJaLog.warn(DesktopHttpConnection.class, "HTTP getResponseCode failed " + requestSummary(), exception);
            throw exception;
        }
    }

    @Override
    public String getResponseMessage() throws IOException {
        if (stubBody != null) {
            return "OK";
        }
        ensureConnection();
        return connection.getResponseMessage();
    }

    @Override
    public String getHeaderField(String name) {
        if (stubBody != null) {
            if (name == null) {
                return null;
            }
            if ("Content-Length".equalsIgnoreCase(name)) {
                return Integer.toString(stubBody.length);
            }
            if ("Content-Type".equalsIgnoreCase(name)) {
                return "text/plain";
            }
            return null;
        }
        try {
            ensureConnection();
            return connection.getHeaderField(name);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public long getDate() {
        if (stubBody != null) {
            return 0L;
        }
        try {
            ensureConnection();
            return connection.getDate();
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public long getExpiration() {
        if (stubBody != null) {
            return 0L;
        }
        try {
            ensureConnection();
            return connection.getExpiration();
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public long getLastModified() {
        if (stubBody != null) {
            return 0L;
        }
        try {
            ensureConnection();
            return connection.getLastModified();
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public void setIfModifiedSince(long ifModifiedSince) {
        this.ifModifiedSince = ifModifiedSince;
        if (stubBody != null) {
            return;
        }
        if (connection != null) {
            connection.setIfModifiedSince(ifModifiedSince);
        }
    }

    @Override
    public String getType() {
        if (stubBody != null) {
            return "text/plain";
        }
        try {
            ensureConnection();
            return connection.getContentType();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String getEncoding() {
        if (stubBody != null) {
            return null;
        }
        try {
            ensureConnection();
            return connection.getContentEncoding();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public long getLength() {
        if (stubBody != null) {
            return stubBody.length;
        }
        try {
            ensureConnection();
            return connection.getContentLengthLong();
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * Offline resource-manifest stub. DoJa resource-based titles fetch {@code api.php?zc=10...},
     * compare the returned manifest against the files on the SD card, and demand a network
     * download when they differ. When the title's resource data has already been imported onto
     * the SD card (SD_BIND/&lt;AppName&gt;/), serve that folder's own {@code package.lst} so the
     * check matches the local files and the game runs without the (often dead) update server.
     */
    private static byte[] resolveResourceManifestStub(URL url) {
        recordStub("probe url=" + url);
        if (url == null) {
            return null;
        }
        String full = url.toString();
        boolean looksLikeManifest = full.contains("api.php")
                && (full.contains("zc=10&") || full.endsWith("zc=10"));
        if (!looksLikeManifest) {
            recordStub("skip url=" + full);
            return null;
        }
        try {
            DoJaRuntime runtime = DoJaRuntime.current();
            if (runtime == null) {
                recordStub("no runtime url=" + full);
                return null;
            }
            java.util.Map<String, String> parameters = runtime.parameters();
            String appName = parameters == null ? null : parameters.get("AppName");
            if (appName == null || appName.isBlank()) {
                recordStub("no AppName url=" + full + " keys="
                        + (parameters == null ? "null" : parameters.keySet()));
                return null;
            }
            java.nio.file.Path root = OpenDoJaPaths.hostDataRoot()
                    .resolve("storage").resolve("ext0").resolve("SD_BIND").resolve(sanitizeScope(appName));
            java.nio.file.Path manifest = findCaseInsensitive(root, "package.lst");
            if (manifest == null) {
                recordStub("no package.lst root=" + root + " url=" + full);
                return null;
            }
            byte[] body = java.nio.file.Files.readAllBytes(manifest);
            recordStub("active root=" + root + " bytes=" + body.length + " url=" + full);
            return body;
        } catch (Throwable throwable) {
            recordStub("failed " + throwable + " url=" + full);
            return null;
        }
    }

    /** Appends a line to hostDataRoot/pocketdoja-http-stub.txt, independent of log level. */
    private static void recordStub(String message) {
        try {
            java.nio.file.Path path = OpenDoJaPaths.hostDataRoot().resolve("pocketdoja-http-stub.txt");
            String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + message + "\n";
            java.nio.file.Files.write(path, line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private static java.nio.file.Path findCaseInsensitive(java.nio.file.Path dir, String name) {
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            return null;
        }
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
            return stream.filter(candidate -> candidate.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        } catch (java.io.IOException ignored) {
            return null;
        }
    }

    private static String sanitizeScope(String value) {
        StringBuilder builder = new StringBuilder();
        boolean replaced = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.') {
                builder.append(ch);
                replaced = false;
            } else if (!replaced) {
                builder.append('_');
                replaced = true;
            }
        }
        String sanitized = builder.toString();
        return sanitized.isEmpty() ? "app" : sanitized;
    }

    private void ensureConnection() throws IOException {
        if (connection != null) {
            return;
        }
        URLConnection raw = url.openConnection();
        if (!(raw instanceof HttpURLConnection httpURLConnection)) {
            throw new IOException("Unsupported non-HTTP URL: " + url);
        }
        connection = httpURLConnection;
        connection.setRequestMethod(requestMethod);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setDoInput(mode != javax.microedition.io.Connector.WRITE);
        connection.setDoOutput(mode != javax.microedition.io.Connector.READ);
        if (timeouts) {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
        }
        if (ifModifiedSince > 0L) {
            connection.setIfModifiedSince(ifModifiedSince);
        }
        connection.setRequestProperty("Connection", "keep-alive");
        if (!userAgentProvidedByGame) {
            connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
        }
    }

    private static boolean isUserAgentHeader(String key) {
        return key != null && "User-Agent".equalsIgnoreCase(key);
    }

    private static URL rewriteOutboundUrl(URL rawUrl) {
        try {
            return OpenDoJaIdentity.replaceDefaultUserIdToken(rewriteConfiguredHost(rawUrl));
        } catch (IOException exception) {
            return rawUrl;
        }
    }

    private static URL rewriteConfiguredHost(URL rawUrl) throws IOException {
        if (rawUrl == null) {
            return null;
        }
        String overrideHost = OpenDoJaLaunchArgs.get(OpenDoJaLaunchArgs.HTTP_OVERRIDE_DOMAIN, "").trim();
        if (overrideHost.isEmpty() || rawUrl.getHost() == null) {
            return rawUrl;
        }
        try {
            return new URI(
                    rawUrl.getProtocol(),
                    rawUrl.getUserInfo(),
                    overrideHost,
                    rawUrl.getPort(),
                    rawUrl.getPath(),
                    rawUrl.getQuery(),
                    rawUrl.getRef()).toURL();
        } catch (URISyntaxException exception) {
            throw new IOException("Could not rewrite outbound URL: " + rawUrl, exception);
        }
    }

    private static OutputStream maybeWrapOutputStream(OutputStream delegate) {
        if (OpenDoJaIdentity.defaultUserId().equals(OpenDoJaIdentity.userId())) {
            return delegate;
        }
        return new UserIdRewriteOutputStream(delegate);
    }

    private static InputStream normalizeResponseInputStream(InputStream delegate) {
        return new ReliableInputStream(delegate);
    }

    private void debug(java.util.function.Supplier<String> messageSupplier) {
        OpenDoJaLog.debug(DesktopHttpConnection.class, messageSupplier);
    }

    private void logRequestIfNeeded() {
        if (requestLogged) {
            return;
        }
        requestLogged = true;
        debug(() -> "HTTP request " + requestSummary());
    }

    private void logResponseIfNeeded() {
        if (responseLogged) {
            return;
        }
        int code;
        try {
            code = connection.getResponseCode();
        } catch (IOException exception) {
            code = -1;
        }
        logResponseIfNeeded(code);
    }

    private void logResponseIfNeeded(int code) {
        if (responseLogged) {
            return;
        }
        responseLogged = true;
        debug(() -> "HTTP response " + responseSummary(code));
    }

    private String requestSummary() {
        String type = connection == null ? null : connection.getRequestProperty("Content-Type");
        String length = connection == null ? null : connection.getRequestProperty("Content-Length");
        return "method=" + requestMethod
                + " url=" + url
                + " type=" + valueOrUnset(type)
                + " length=" + valueOrUnset(length);
    }

    private String responseSummary() {
        int code;
        try {
            code = connection.getResponseCode();
        } catch (IOException exception) {
            code = -1;
        }
        return responseSummary(code);
    }

    private String responseSummary(int code) {
        if (connection == null) {
            return requestSummary() + " code=" + code + " message=<stub>";
        }
        String message;
        try {
            message = connection.getResponseMessage();
        } catch (IOException exception) {
            message = "<unavailable:" + exception.getClass().getSimpleName() + ">";
        }
        return requestSummary()
                + " code=" + code
                + " message=" + message
                + " type=" + connection.getContentType()
                + " encoding=" + connection.getContentEncoding()
                + " length=" + connection.getContentLengthLong();
    }

    private static String valueOrUnset(String value) {
        return value == null || value.isBlank() ? "<unset>" : value;
    }

    private static final class ReliableInputStream extends java.io.FilterInputStream {
        private ReliableInputStream(InputStream delegate) {
            super(delegate);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            // InputStream.read(byte[], int, int) may legally return fewer bytes than requested even when
            // more data will arrive later. Some DoJa titles treat one read as "the whole response", so we
            // keep reading until the caller's buffer is full or EOF while preserving the usual 0/-1 cases.
            int totalRead = 0;
            while (totalRead < length) {
                int count = super.read(buffer, offset + totalRead, length - totalRead);
                if (count < 0) {
                    return totalRead == 0 ? -1 : totalRead;
                }
                totalRead += count;
                if (count == 0) {
                    break;
                }
            }
            return totalRead;
        }
    }

    private static final class UserIdRewriteOutputStream extends FilterOutputStream {
        private final byte[] target = OpenDoJaIdentity.defaultUserId().getBytes(DoJaEncoding.DEFAULT_CHARSET);
        private final byte[] replacement = OpenDoJaIdentity.userId().getBytes(DoJaEncoding.DEFAULT_CHARSET);
        private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
        private boolean closed;

        private UserIdRewriteOutputStream(OutputStream delegate) {
            super(delegate);
        }

        @Override
        public void write(int value) throws IOException {
            pending.write(value);
            drain(false);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            pending.write(data, offset, length);
            drain(false);
        }

        @Override
        public void flush() throws IOException {
            drain(false);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            drain(true);
            out.flush();
            out.close();
        }

        private void drain(boolean finalFlush) throws IOException {
            byte[] buffered = pending.toByteArray();
            int keep = finalFlush ? 0 : Math.max(0, target.length - 1);
            int writeLength = Math.max(0, buffered.length - keep);
            if (writeLength > 0) {
                out.write(rewriteMatches(buffered, writeLength));
            }
            pending.reset();
            if (!finalFlush && keep > 0 && buffered.length > writeLength) {
                pending.write(buffered, writeLength, buffered.length - writeLength);
            }
        }

        private byte[] rewriteMatches(byte[] source, int length) {
            byte[] rewritten = java.util.Arrays.copyOf(source, length);
            int lastStart = length - target.length;
            for (int i = 0; i <= lastStart; i++) {
                if (!matchesAt(source, i)) {
                    continue;
                }
                System.arraycopy(replacement, 0, rewritten, i, target.length);
                i += target.length - 1;
            }
            return rewritten;
        }

        private boolean matchesAt(byte[] source, int offset) {
            for (int i = 0; i < target.length; i++) {
                if (source[offset + i] != target[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
