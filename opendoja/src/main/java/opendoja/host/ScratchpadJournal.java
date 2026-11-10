package opendoja.host;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Automatic crash protection for the packed DoJa scratchpad ({@code .sp}).
 *
 * <p>i-mode titles write their save/scratchpad in place through
 * {@code Connector.openOutputStream("scratchpad:///...")}. If the game is killed
 * mid-write (app swiped away, or the game thread force-stopped on exit) the target
 * region is left half-updated; the title then refuses to boot until the whole
 * scratchpad is replaced (which is why removing/reimporting a game "fixes" it).</p>
 *
 * <p>Before an output stream is handed to the game, the bytes it is about to
 * overwrite are snapshotted into a sidecar journal ({@code <name>.sp.undo}). When the
 * stream closes normally the journal is dropped, i.e. the write is committed. If the
 * process dies first, the next session's {@link #recover(Path)} restores the snapshot
 * and discards the journal, rolling the interrupted write back to the last consistent
 * state while preserving every earlier completed write.</p>
 *
 * <p>Only existing bytes are journalled: append/growth writes (nothing to roll back)
 * bypass the journal entirely, keeping the common hot path cheap.</p>
 */
final class ScratchpadJournal {
    private static final int MAGIC = 0x4F444A55; // "ODJU"
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Long.BYTES;
    private static final int ENTRY_HEADER_BYTES = Long.BYTES + Integer.BYTES + Integer.BYTES;
    private static final long MAX_JOURNAL_BYTES = 64L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static final Map<Path, Batch> ACTIVE = new HashMap<>();

    private ScratchpadJournal() {
    }

    private static Path journalPath(Path packedFile) {
        return packedFile.resolveSibling(packedFile.getFileName().toString() + ".undo");
    }

    /**
     * Snapshots the range about to be overwritten and wraps {@code delegate} so that a
     * normal close commits (drops the journal) and an interrupted process leaves it for
     * {@link #recover(Path)}.
     */
    static OutputStream begin(Path packedFile, long offset, long length, OutputStream delegate) throws IOException {
        Path journal = journalPath(packedFile);
        synchronized (LOCK) {
            // A leftover journal from a previous crash must be applied before we read our
            // own pre-image, otherwise we would journal bytes from the corrupted file.
            if (!ACTIVE.containsKey(journal) && Files.exists(journal)) {
                recoverLocked(packedFile, journal);
            }
            long fileSize = fileSize(packedFile);
            long safeOffset = Math.max(0L, offset);
            long available = Math.max(0L, fileSize - safeOffset);
            long snapshotLength = length < 0L ? available : Math.min(Math.max(0L, length), available);
            if (snapshotLength <= 0L) {
                // Nothing existing is overwritten (append/growth), so there is nothing to
                // roll back and no journal is needed.
                return delegate;
            }
            byte[] preImage = readRange(packedFile, safeOffset, snapshotLength);
            Batch batch = ACTIVE.get(journal);
            if (batch == null) {
                batch = new Batch();
                ACTIVE.put(journal, batch);
                writeHeader(journal, fileSize);
            }
            appendEntry(journal, safeOffset, preImage);
            batch.active++;
        }
        return new FilterOutputStream(delegate) {
            private boolean finished;

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    if (!finished) {
                        finished = true;
                        commit(journal);
                    }
                }
            }
        };
    }

    /**
     * Session-start recovery. Called from {@link ScratchpadStorage#initialize()} before the
     * title touches its scratchpad. Any journal present belongs to a previous session (the
     * current session has not written yet), so stale in-process "active" bookkeeping from a
     * killed session is discarded first; otherwise reopening a game without restarting the
     * emulator would skip recovery and keep using the half-written save.
     */
    static void recoverSessionStart(Path packedFile) {
        if (packedFile == null) {
            return;
        }
        Path journal = journalPath(packedFile);
        synchronized (LOCK) {
            ACTIVE.remove(journal);
            if (!Files.exists(journal)) {
                return;
            }
            recoverLocked(packedFile, journal);
        }
    }

    /**
     * Drops in-flight bookkeeping for {@code packedFile} while leaving the journal file on
     * disk, so a later {@link #recoverSessionStart(Path)} (possibly in the same process)
     * still rolls back the interrupted write.
     */
    static void abandon(Path packedFile) {
        if (packedFile == null) {
            return;
        }
        Path journal = journalPath(packedFile);
        synchronized (LOCK) {
            ACTIVE.remove(journal);
        }
    }

    private static void recoverLocked(Path packedFile, Path journal) {
        if (!Files.exists(journal)) {
            return;
        }
        try {
            restore(packedFile, journal);
            OpenDoJaLog.warn(ScratchpadJournal.class,
                    "recovered interrupted scratchpad write for " + packedFile);
        } catch (Throwable throwable) {
            OpenDoJaLog.warn(ScratchpadJournal.class,
                    "could not recover interrupted scratchpad write for " + packedFile
                            + "; leaving scratchpad unchanged", throwable);
        } finally {
            try {
                Files.deleteIfExists(journal);
            } catch (IOException ignored) {
            }
        }
    }

    private static void commit(Path journal) {
        synchronized (LOCK) {
            Batch batch = ACTIVE.get(journal);
            if (batch == null) {
                return;
            }
            batch.active--;
            if (batch.active <= 0) {
                ACTIVE.remove(journal);
                try {
                    Files.deleteIfExists(journal);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void restore(Path packedFile, Path journal) throws IOException {
        long size = Files.size(journal);
        if (size <= 0L || size > MAX_JOURNAL_BYTES) {
            throw new IOException("unexpected scratchpad journal size " + size);
        }
        byte[] data = Files.readAllBytes(journal);
        if (data.length < HEADER_BYTES) {
            throw new IOException("truncated scratchpad journal header");
        }
        int position = 0;
        int magic = readInt(data, position);
        position += Integer.BYTES;
        int version = readInt(data, position);
        position += Integer.BYTES;
        long baseLength = readLong(data, position);
        position += Long.BYTES;
        if (magic != MAGIC || version != VERSION) {
            throw new IOException("unrecognized scratchpad journal");
        }
        // Validate the whole journal before touching the live file so a torn journal can
        // never leave the scratchpad half-restored.
        Map<Long, byte[]> restores = new LinkedHashMap<>();
        while (position < data.length) {
            if (data.length - position < ENTRY_HEADER_BYTES) {
                throw new IOException("truncated scratchpad journal entry");
            }
            long offset = readLong(data, position);
            position += Long.BYTES;
            int length = readInt(data, position);
            position += Integer.BYTES;
            int expectedCrc = readInt(data, position);
            position += Integer.BYTES;
            if (offset < 0L || length < 0 || data.length - position < length) {
                throw new IOException("invalid scratchpad journal entry");
            }
            byte[] bytes = Arrays.copyOfRange(data, position, position + length);
            position += length;
            CRC32 crc = new CRC32();
            crc.update(bytes);
            if ((int) crc.getValue() != expectedCrc) {
                throw new IOException("scratchpad journal checksum mismatch");
            }
            restores.put(offset, bytes);
        }
        if (restores.isEmpty()) {
            return;
        }
        try (RandomAccessFile file = new RandomAccessFile(packedFile.toFile(), "rw")) {
            for (Map.Entry<Long, byte[]> entry : restores.entrySet()) {
                file.seek(entry.getKey());
                file.write(entry.getValue());
            }
            // Undo any growth performed by the interrupted write batch.
            if (baseLength >= 0L && file.length() > baseLength) {
                file.setLength(baseLength);
            }
        }
    }

    private static void writeHeader(Path journal, long baseLength) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
            file.setLength(0L);
            writeInt(file, MAGIC);
            writeInt(file, VERSION);
            file.writeLong(baseLength);
        }
    }

    private static void appendEntry(Path journal, long offset, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
            file.seek(file.length());
            file.writeLong(offset);
            writeInt(file, bytes.length);
            writeInt(file, (int) crc.getValue());
            file.write(bytes);
        }
    }

    private static byte[] readRange(Path packedFile, long offset, long length) throws IOException {
        if (length <= 0L) {
            return new byte[0];
        }
        if (length > Integer.MAX_VALUE) {
            throw new IOException("scratchpad write range too large to journal: " + length);
        }
        byte[] buffer = new byte[(int) length];
        try (RandomAccessFile file = new RandomAccessFile(packedFile.toFile(), "r")) {
            file.seek(offset);
            file.readFully(buffer);
        }
        return buffer;
    }

    private static long fileSize(Path packedFile) {
        try {
            return Files.exists(packedFile) ? Files.size(packedFile) : 0L;
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static void writeInt(RandomAccessFile file, int value) throws IOException {
        file.write((value >>> 24) & 0xFF);
        file.write((value >>> 16) & 0xFF);
        file.write((value >>> 8) & 0xFF);
        file.write(value & 0xFF);
    }

    private static int readInt(byte[] data, int position) {
        return ((data[position] & 0xFF) << 24)
                | ((data[position + 1] & 0xFF) << 16)
                | ((data[position + 2] & 0xFF) << 8)
                | (data[position + 3] & 0xFF);
    }

    private static long readLong(byte[] data, int position) {
        long value = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (data[position + i] & 0xFFL);
        }
        return value;
    }

    private static final class Batch {
        private int active;
    }
}