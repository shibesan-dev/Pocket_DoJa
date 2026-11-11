package opendoja.host;

import com.nttdocomo.io.HttpConnection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Offline stand-in for the i-appli server member-check endpoint.
 *
 * <p>Locally installed titles derive their server base URL from the launch source URL. When the
 * package is installed from local storage that base becomes {@code file://}, turning the member
 * check into {@code file:///s/am?uid=...&c=1}. A physical handset would contact the appli server
 * here; without one the title refuses to start once its scratchpad grace period expires. Returning
 * the successful status byte plus a bounded offline grace period keeps the title usable offline
 * while leaving every other URL (including live HTTP requests) untouched.</p>
 */
public final class AppliServerStub {
    /** Number of offline days granted by the synthetic member-check reply. */
    private static final int OFFLINE_GRACE_DAYS = 10;
    /** Status byte 1 (success) followed by the big-endian day count read by the client. */
    private static final byte[] MEMBER_CHECK_BODY = {
            1,
            0, 0, 0, (byte) OFFLINE_GRACE_DAYS
    };

    private AppliServerStub() {
    }

    /**
     * Returns whether the URL is the appli-server member check ({@code /s/am} with {@code c=1})
     * on a local {@code file://} base, i.e. a request that can never reach a real server.
     */
    public static boolean isOfflineMemberCheck(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("file:///")) {
            return false;
        }
        int queryIndex = name.indexOf('?');
        if (queryIndex < 0) {
            return false;
        }
        if (!lower.substring(0, queryIndex).endsWith("/s/am")) {
            return false;
        }
        return "1".equals(queryParameter(name.substring(queryIndex + 1), "c"));
    }

    public static byte[] memberCheckBody() {
        return MEMBER_CHECK_BODY.clone();
    }

    public static HttpConnection openMemberCheck(String name) {
        OpenDoJaLog.info(AppliServerStub.class, "appli-server member-check stub: " + name);
        return new MemberCheckConnection(name);
    }

    private static String queryParameter(String query, String key) {
        for (String token : query.split("&")) {
            int separator = token.indexOf('=');
            String tokenKey = separator >= 0 ? token.substring(0, separator) : token;
            if (!key.equalsIgnoreCase(tokenKey.trim())) {
                continue;
            }
            return separator >= 0 ? token.substring(separator + 1).trim() : "";
        }
        return null;
    }

    /** Minimal in-memory HTTP connection that serves the synthetic member-check reply. */
    private static final class MemberCheckConnection implements HttpConnection {
        private final String url;
        private final byte[] body = MEMBER_CHECK_BODY.clone();

        private MemberCheckConnection(String url) {
            this.url = url;
        }

        @Override
        public void connect() {
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public DataInputStream openDataInputStream() {
            return new DataInputStream(openInputStream());
        }

        @Override
        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public DataOutputStream openDataOutputStream() {
            return new DataOutputStream(openOutputStream());
        }

        @Override
        public String getURL() {
            return url;
        }

        @Override
        public void setRequestMethod(String method) {
        }

        @Override
        public void setRequestProperty(String key, String value) {
        }

        @Override
        public int getResponseCode() {
            return HTTP_OK;
        }

        @Override
        public String getResponseMessage() {
            return "OK";
        }

        @Override
        public String getHeaderField(String name) {
            if (name == null) {
                return null;
            }
            if ("Content-Length".equalsIgnoreCase(name)) {
                return Integer.toString(body.length);
            }
            if ("Content-Type".equalsIgnoreCase(name)) {
                return getType();
            }
            return null;
        }

        @Override
        public long getDate() {
            return 0L;
        }

        @Override
        public long getExpiration() {
            return 0L;
        }

        @Override
        public long getLastModified() {
            return 0L;
        }

        @Override
        public void setIfModifiedSince(long timestamp) {
        }

        @Override
        public String getType() {
            return "application/octet-stream";
        }

        @Override
        public String getEncoding() {
            return null;
        }

        @Override
        public long getLength() {
            return body.length;
        }
    }
}
