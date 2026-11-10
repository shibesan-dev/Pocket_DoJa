package opendoja.android;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Label;

import opendoja.host.OpenDoJaLog;

/**
 * On-device jar-to-dex conversion for JAM games, based on the dexlib (dx) port from
 * JL-Mod/J2MELoader. ART cannot execute JVM bytecode, so every game jar is converted to
 * a dex file once and then loaded with a {@link DexClassLoader}.
 */
public final class JamDexHost {
    private static final String TAG = "OpenDoJa";

    private JamDexHost() {
    }

    public static File dexFor(File jarFile) {
        String name = jarFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        return new File(jarFile.getParentFile(), base + ".dex");
    }

    /**
     * Bump this whenever the jar-rewrite logic below changes (SJIS fixups, US_INFO/GameScreen
     * patches, etc.). Cached dex files are reused across APK updates as long as this version and
     * the game jar are unchanged, so an update no longer forces every game to be re-dexed (slow
     * "Booting..." on large titles).
     */
    private static final int DEX_REWRITE_VERSION = 3;

    private static volatile long appUpdateTimeMillis = 0L;

    /** Retained for API compatibility; cache validity now depends on DEX_REWRITE_VERSION. */
    public static void setAppUpdateTime(long millis) {
        appUpdateTimeMillis = millis;
    }

    private static File dexMarkerFor(File jarFile) {
        return new File(jarFile.getParentFile(), jarFile.getName() + ".dex.ok");
    }

    /** True only if a previous conversion completed successfully for this exact jar + rewrite version. */
    private static boolean isDexCacheValid(File jarFile, File dexFile) {
        File marker = dexMarkerFor(jarFile);
        if (!marker.isFile()) {
            return false;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(marker))) {
            String versionLine = reader.readLine();
            String mtimeLine = reader.readLine();
            if (versionLine == null || mtimeLine == null) {
                return false;
            }
            int version = Integer.parseInt(versionLine.trim());
            long jarMtime = Long.parseLong(mtimeLine.trim());
            return version == DEX_REWRITE_VERSION
                    && jarMtime == jarFile.lastModified()
                    && dexFile.isFile() && dexFile.length() > 0
                    && dexFile.lastModified() >= jarFile.lastModified();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeDexMarker(File jarFile) {
        try (java.io.FileWriter writer = new java.io.FileWriter(dexMarkerFor(jarFile), false)) {
            writer.write(DEX_REWRITE_VERSION + "\n" + jarFile.lastModified() + "\n");
        } catch (Throwable ignored) {
        }
    }

    public static File ensureDex(File jarFile) throws IOException {
        File dexFile = dexFor(jarFile);
        // Re-writing the jar and running dx is expensive (large titles take >10s) and was the
        // cause of slow "Booting..." screens. Reuse the dex once it has been built for this jar
        // and rewrite version; the completion marker guarantees an interrupted conversion is
        // never treated as a valid cache.
        if (isDexCacheValid(jarFile, dexFile)) {
            return dexFile;
        }
        try {
            java.nio.file.Files.deleteIfExists(dexMarkerFor(jarFile).toPath());
        } catch (Throwable ignored) {
        }
        File rewrittenJar = new File(jarFile.getParentFile(), jarFile.getName() + ".sjisfix.jar");
        rewriteSjisInJar(jarFile, rewrittenJar);
        if (dexFile.exists()) {
            dexFile.delete();
        }
        long start = SystemClock.uptimeMillis();
        File inputJar = rewrittenJar;
        File outDir = null;
        java.io.ByteArrayOutputStream capturedOut = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream capturedErr = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previousOut = System.out;
        java.io.PrintStream previousErr = System.err;
        System.setOut(new java.io.PrintStream(capturedOut));
        System.setErr(new java.io.PrintStream(capturedErr));
        int result;
        try {
            com.android.dx.command.dexer.Main.main(new String[]{
                    "--no-optimize",
                    "--no-strict",
                    "--min-sdk-version=1",
                    "--output=" + dexFile.getAbsolutePath(),
                    inputJar.getAbsolutePath()});
            result = 0;
        } catch (Throwable throwable) {
            result = -1;
            opendoja.host.OpenDoJaLog.error(JamDexHost.class, "jar2dex threw", throwable);
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
        // dx keeps process-wide intern tables. Without trimming them, converting a different
        // game on every launch adds entries that are never collected, growing memory and GC
        // pressure for the whole app lifetime (a symptom of long-session slowdown/stutter).
        try {
            com.android.dx.rop.code.RegisterSpec.clearInternTable();
            com.android.dx.rop.type.Type.clearInternTable();
            com.android.dx.rop.type.Prototype.clearInternTable();
            com.android.dx.rop.cst.CstType.clearInternTable();
        } catch (Throwable ignored) {
        }
        String outText = capturedOut.toString();
        String errText = capturedErr.toString();
        if (!outText.isBlank()) {
            opendoja.host.OpenDoJaLog.info(JamDexHost.class, "jar2dex stdout: " + outText);
        }
        if (!errText.isBlank()) {
            opendoja.host.OpenDoJaLog.warn(JamDexHost.class, "jar2dex stderr: " + errText);
        }
        long elapsed = SystemClock.uptimeMillis() - start;
        if (result != 0 || !dexFile.exists() || dexFile.length() == 0) {
            throw new IOException("jar2dex produced no output for " + jarFile
                    + (errText.isBlank() ? "" : ": " + errText.trim()));
        }
        // ART refuses to open dex files that are still writable on newer Android builds.
        //noinspection ResultOfMethodCallIgnored
        dexFile.setReadOnly();
        writeDexMarker(jarFile);
        opendoja.host.OpenDoJaLog.info(JamDexHost.class, "jar2dex " + jarFile.getName() + " -> " + dexFile.getName()
                + " (" + dexFile.length() + " bytes) in " + elapsed + "ms");
        return dexFile;
    }

    private static void rewriteStringBufferInvokespecial(File inputJar, File outputJar) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(inputJar);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(outputJar);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                java.util.zip.ZipEntry outEntry = new java.util.zip.ZipEntry(entry.getName());
                zos.putNextEntry(outEntry);
                if (entry.isDirectory()) {
                    zos.closeEntry();
                    continue;
                }
                byte[] original = readEntry(zip, entry);
                String name = entry.getName();
                if (name.equals("Module.class")) {
                    original = rewriteModuleClass(original);
                } else if (name.equals("columns.class")) {
                    original = rewriteClass(original);
                }
                zos.write(original);
                zos.closeEntry();
            }
        }
    }

    private static byte[] readEntry(java.util.zip.ZipFile zip, java.util.zip.ZipEntry entry) throws IOException {
        try (java.io.InputStream in = zip.getInputStream(entry)) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private static byte[] rewriteClass(byte[] classBytes) {
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(classBytes);
        final String[] className = {null};
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        org.objectweb.asm.ClassVisitor visitor = new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                className[0] = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                // StringBuffer INVOKESPECIAL.append -> INVOKEVIRTUAL fix (applied to all methods in columns.class)
                mv = new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                boolean isInterface) {
                        if ("columns".equals(className[0])
                                && opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL
                                && "java/lang/StringBuffer".equals(owner)
                                && "append".equals(name)) {
                            opcode = org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
                if ("columns".equals(className[0]) && "start".equals(name) && "()V".equals(descriptor)) {
                    mv = new ColumnStartFixMV(org.objectweb.asm.Opcodes.ASM9, mv);
                }
                return mv;
            }
        };
        reader.accept(visitor, org.objectweb.asm.ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    static class ColumnStartFixMV extends org.objectweb.asm.MethodVisitor {
        ColumnStartFixMV(int api, org.objectweb.asm.MethodVisitor mv) {
            super(api, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, "columns", "m_module", "LModule;");
            super.visitInsn(Opcodes.RETURN);
            super.visitMaxs(1, 1);
        }

        @Override public void visitVarInsn(int opcode, int var) {}
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {}
        @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {}
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {}
        @Override public void visitLabel(org.objectweb.asm.Label label) {}
        @Override public void visitInsn(int opcode) {}
        @Override public void visitTypeInsn(int opcode, String type) {}
        @Override public void visitLdcInsn(Object value) {}
        @Override public void visitIntInsn(int opcode, int operand) {}
        @Override public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {}
        @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {}
        @Override public void visitLocalVariable(String name, String descriptor, String signature, org.objectweb.asm.Label start, org.objectweb.asm.Label end, int index) {}
        @Override public void visitLineNumber(int line, org.objectweb.asm.Label start) {}
        @Override public void visitParameter(String name, int access) {}
    }

    /**
     * Reprocess Module.class with ASM COMPUTE_FRAMES + EXPAND_FRAMES to recompute
     * stack map frames. The original class was compiled with an old toolchain that
     * produces bytecode dx cannot verify; recomputing frames resolves the
     * SimException: stack: underflow error.
     */
    private static byte[] rewriteModuleClass(byte[] classBytes) {
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(classBytes);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        org.objectweb.asm.ClassVisitor visitor = new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    public static ClassLoader createLoader(File dexFile, ClassLoader parent, Context context) {
        File optimized = new File(context.getCodeCacheDir(), "jam-dex");
        //noinspection ResultOfMethodCallIgnored
        optimized.mkdirs();
        if (dexFile.canWrite()) {
            if (!dexFile.setReadOnly()) {
                opendoja.host.OpenDoJaLog.warn(JamDexHost.class, "cannot mark dex read-only: " + dexFile);
            }
        }
        return new GameResourceClassLoader(dexFile.getAbsolutePath(), optimized.getAbsolutePath(),
                null, parent, siblingJar(dexFile));
    }

    /** The game jar that sits next to the converted dex (same basename). */
    private static File siblingJar(File dexFile) {
        String name = dexFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        File jar = new File(dexFile.getParentFile(), base + ".jar");
        return jar.isFile() ? jar : null;
    }

    /**
     * DexClassLoader only exposes entries of the dex/zip files on its dex path; game assets
     * (.dat images etc.) live inside the original game jar, which is not on that path.
     * The desktop host uses a URLClassLoader over the jar, where resources resolve natively.
     * This loader restores that behaviour by falling back to the game jar for resource lookups.
     */
    private static final class GameResourceClassLoader extends DexClassLoader {
        private final File jarFile;
        private ZipFile zipFile;

        GameResourceClassLoader(String dexPath, String optimizedDirectory,
                                String librarySearchPath, ClassLoader parent, File jarFile) {
            super(dexPath, optimizedDirectory, librarySearchPath, parent);
            this.jarFile = jarFile;
        }

        @Override
        public URL getResource(String name) {
            URL resource = super.getResource(name);
            if (resource != null) {
                return resource;
            }
            if (jarEntry(name) != null) {
                try {
                    return new URL("jar:file:" + jarFile.getAbsolutePath() + "!/" + name);
                } catch (java.net.MalformedURLException ignored) {
                }
            }
            return null;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            InputStream stream = super.getResourceAsStream(name);
            if (stream != null) {
                return stream;
            }
            try {
                ZipEntry entry = jarEntry(name);
                if (entry == null) {
                OpenDoJaLog.info(JamDexHost.class,
                        "GameResourceClassLoader.getResourceAsStream: entry not found: " + name);
                    return null;
                }
                InputStream zipStream = openZip().getInputStream(entry);
                if (zipStream == null) {
                OpenDoJaLog.info(JamDexHost.class,
                        "GameResourceClassLoader.getResourceAsStream: null stream for: " + name);
                    return null;
                }
                byte[] probe = new byte[8];
                int read = zipStream.read(probe);
                zipStream.close();
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < read; i++) {
                    if (i > 0) hex.append(' ');
                    hex.append(String.format("%02x", probe[i]));
                }
                OpenDoJaLog.info(JamDexHost.class,
                        "GameResourceClassLoader.getResourceAsStream: name=" + name
                                + " entrySize=" + entry.getSize()
                                + " read=" + read + " hex=[" + hex + "]");
                return openZip().getInputStream(entry);
            } catch (IOException exception) {
                opendoja.host.OpenDoJaLog.warn(JamDexHost.class, "game jar resource read failed: " + name, exception);
                return null;
            }
        }

        private ZipEntry jarEntry(String name) {
            if (jarFile == null || name == null || name.isEmpty()) {
                return null;
            }
            try {
                return openZip().getEntry(name);
            } catch (IOException exception) {
                opendoja.host.OpenDoJaLog.warn(JamDexHost.class, "cannot open game jar " + jarFile, exception);
                return null;
            }
        }

        private ZipFile openZip() throws IOException {
            synchronized (this) {
                if (zipFile == null) {
                    zipFile = new ZipFile(jarFile);
                }
                return zipFile;
            }
        }

        void close() {
            synchronized (this) {
                if (zipFile != null) {
                    try {
                        zipFile.close();
                    } catch (IOException ignored) {
                    }
                    zipFile = null;
                }
            }
        }
    }

    /** Releases the game jar handle held by a loader created here, if applicable. */
    public static void releaseLoader(ClassLoader loader) {
        if (loader instanceof GameResourceClassLoader) {
            ((GameResourceClassLoader) loader).close();
        }
    }

    private static void rewriteSjisInJar(File inputJar, File outputJar) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(inputJar);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(outputJar);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                java.util.zip.ZipEntry outEntry = new java.util.zip.ZipEntry(entry.getName());
                zos.putNextEntry(outEntry);
                if (entry.isDirectory()) { zos.closeEntry(); continue; }
                byte[] original = readEntry(zip, entry);
                if (entry.getName().endsWith(".class")) {
                    try {
                        original = rewriteSjisInClass(original);
                    } catch (Throwable throwable) {
                        opendoja.host.OpenDoJaLog.warn(JamDexHost.class, "SJIS rewrite failed for " + entry.getName(), throwable);
                    }
                }
                zos.write(original);
                zos.closeEntry();
            }
        }
    }
    private static boolean isSjisCharsetName(String value) {
        return "SJIS".equalsIgnoreCase(value)
                || "Shift_JIS".equalsIgnoreCase(value)
                || "MS_Kanji".equalsIgnoreCase(value)
                || "x-SJIS".equalsIgnoreCase(value)
                || "csShiftJIS".equalsIgnoreCase(value);
    }

    private static byte[] rewriteSjisInClass(byte[] classBytes) {
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(classBytes);
        // COMPUTE_MAXS keeps the original stack map frames. Our rewrite only changes a
        // constructor/method descriptor, so frame types are unchanged, and COMPUTE_FRAMES
        // would fail on game classes that cannot be loaded by the host class loader.
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        final String[] classNameHolder = {null};
        final boolean[] hasUsInfo = {false};
        final boolean[] hasNnInfo = {false};
        final boolean[] hasClinit = {false};
        org.objectweb.asm.ClassVisitor visitor = new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                classNameHolder[0] = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                             String signature, Object value) {
                if ((access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0
                        && "[Ljava/lang/String;".equals(descriptor)) {
                    if ("US_INFO".equals(name)) {
                        hasUsInfo[0] = true;
                    } else if ("NN_INFO".equals(name)) {
                        hasNnInfo[0] = true;
                    }
                }
                return super.visitField(access, name, descriptor, signature, value);
            }
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                org.objectweb.asm.MethodVisitor sjisMv = new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, mv) {
                    private boolean pendingAstore1 = false;

                    @Override
                    public void visitCode() {
                        pendingAstore1 = false;
                        super.visitCode();
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        pendingAstore1 = false;
                        super.visitVarInsn(opcode, var);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        pendingAstore1 = false;
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        pendingAstore1 = false;
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String && isSjisCharsetName((String) value)) {
                            value = "SJIS_i";
                        }
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        if (opcode == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
                                && "java/lang/Throwable".equals(owner)
                                && "toString".equals(name)
                                && "()Ljava/lang/String;".equals(descriptor)
                                && "GameCanvas".equals(classNameHolder[0])) {
                            super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
                                    "opendoja/host/OpenDoJaLog", "throwableToString",
                                    "(Ljava/lang/Throwable;)Ljava/lang/String;", false);
                            return;
                        }
                        // DoJa titles rely on the handset's Shift_JIS default charset, but ART
                        // decodes new String(byte[]) as UTF-8. Force the explicit DoJa charset
                        // without changing the surrounding new/dup sequence.
                        if (opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL
                                && "java/lang/String".equals(owner) && "<init>".equals(name)) {
                            if ("([B)V".equals(descriptor)) {
                                super.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC,
                                        "opendoja/host/DoJaEncoding", "DEFAULT_CHARSET",
                                        "Ljava/nio/charset/Charset;");
                                super.visitMethodInsn(opcode, owner, name,
                                        "([BLjava/nio/charset/Charset;)V", isInterface);
                                return;
                            }
                            if ("([BII)V".equals(descriptor)) {
                                super.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC,
                                        "opendoja/host/DoJaEncoding", "DEFAULT_CHARSET",
                                        "Ljava/nio/charset/Charset;");
                                super.visitMethodInsn(opcode, owner, name,
                                        "([BIILjava/nio/charset/Charset;)V", isInterface);
                                return;
                            }
                        }
                        // Encoding with the no-argument overload would use UTF-8 on Android and
                        // produce bytes the SJIS decoder above cannot read back.
                        if (opcode == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
                                && "java/lang/String".equals(owner) && "getBytes".equals(name)
                                && "()[B".equals(descriptor)) {
                            super.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC,
                                    "opendoja/host/DoJaEncoding", "DEFAULT_CHARSET",
                                    "Ljava/nio/charset/Charset;");
                            super.visitMethodInsn(opcode, owner, name,
                                    "(Ljava/nio/charset/Charset;)[B", isInterface);
                            return;
                        }
                        // java.io readers/writers without an explicit charset use
                        // Charset.defaultCharset(), which is UTF-8 on Android unless the hidden
                        // cache override succeeded. Force the DoJa charset so text streams
                        // (e.g. nexframe readStringArray) decode as Shift_JIS.
                        if (opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL
                                && "java/io/InputStreamReader".equals(owner) && "<init>".equals(name)
                                && "(Ljava/io/InputStream;)V".equals(descriptor)) {
                            super.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC,
                                    "opendoja/host/DoJaEncoding", "DEFAULT_CHARSET",
                                    "Ljava/nio/charset/Charset;");
                            super.visitMethodInsn(opcode, owner, name,
                                    "(Ljava/io/InputStream;Ljava/nio/charset/Charset;)V", isInterface);
                            return;
                        }
                        if (opcode == org.objectweb.asm.Opcodes.INVOKESPECIAL
                                && "java/io/OutputStreamWriter".equals(owner) && "<init>".equals(name)
                                && "(Ljava/io/OutputStream;)V".equals(descriptor)) {
                            super.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC,
                                    "opendoja/host/DoJaEncoding", "DEFAULT_CHARSET",
                                    "Ljava/nio/charset/Charset;");
                            super.visitMethodInsn(opcode, owner, name,
                                    "(Ljava/io/OutputStream;Ljava/nio/charset/Charset;)V", isInterface);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
                // GGenMobile's nexframe.StorageIO.cleanup()/deleteFile() wipe the whole SD
                // package whenever the live server's resource manifest differs from the
                // imported sdcarddata, which deletes data the user imported and makes the game
                // unplayable offline. Replace those two methods with "return true" so the
                // imported data is never removed.
                if ("nexframe/StorageIO".equals(classNameHolder[0])
                        && (("deleteFile".equals(name) && "(Ljava/lang/String;)Z".equals(descriptor))
                        || ("cleanup".equals(name) && "()Z".equals(descriptor)))) {
                    return new NoOpBooleanMV(org.objectweb.asm.Opcodes.ASM9, sjisMv);
                }
                if ("GameCanvas".equals(classNameHolder[0]) && "<clinit>".equals(name) && "()V".equals(descriptor)) {
                    hasClinit[0] = true;
                    if (hasUsInfo[0] && hasNnInfo[0]) {
                        return new GameCanvasInfoFillMV(org.objectweb.asm.Opcodes.ASM9, sjisMv, classNameHolder[0]);
                    }
                }
                if ("GameScreen".equals(classNameHolder[0]) && "d".equals(name) && "(II)I".equals(descriptor)) {
                    return new GameScreenDFixMV(org.objectweb.asm.Opcodes.ASM9, sjisMv);
                }
                return sjisMv;
            }

            @Override
            public void visitEnd() {
                // INERT FALLBACK FOR TESTING
                // Classes without a static initializer still need the defaults.
                // Defensive: Dash declares both fields and has a <clinit>, so
                // this path is unused for it, but keeps the rewrite general.
                if ("GameCanvas".equals(classNameHolder[0]) && !hasClinit[0]
                        && hasUsInfo[0] && hasNnInfo[0]) {
                    org.objectweb.asm.MethodVisitor mv = writer.visitMethod(
                            org.objectweb.asm.Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                    mv.visitCode();
                    emitInfoFill(mv, classNameHolder[0]);
                    mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
                    mv.visitMaxs(1, 0);
                    mv.visitEnd();
                }
                super.visitEnd();
            }
        };
        reader.accept(visitor, org.objectweb.asm.ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    /** Replaces a boolean-returning method body with {@code return true;} (discards original code). */
    static final class NoOpBooleanMV extends org.objectweb.asm.MethodVisitor {
        NoOpBooleanMV(int api, org.objectweb.asm.MethodVisitor mv) {
            super(api, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
            super.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        }

        @Override public void visitInsn(int opcode) { }
        @Override public void visitIntInsn(int opcode, int operand) { }
        @Override public void visitVarInsn(int opcode, int var) { }
        @Override public void visitTypeInsn(int opcode, String type) { }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) { }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bsm, Object... bsmArgs) { }
        @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) { }
        @Override public void visitLabel(org.objectweb.asm.Label label) { }
        @Override public void visitLdcInsn(Object value) { }
        @Override public void visitIincInsn(int var, int increment) { }
        @Override public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) { }
        @Override public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys, org.objectweb.asm.Label[] labels) { }
        @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) { }
        @Override public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) { }
        @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) { }
        @Override public void visitLocalVariable(String name, String descriptor, String signature, org.objectweb.asm.Label start, org.objectweb.asm.Label end, int index) { }
        @Override public void visitLineNumber(int line, org.objectweb.asm.Label start) { }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitMaxs(1, 0);
        }
    }

    /** Fills null entries of a game's static info array with empty strings.
     *  Called from bytecode injected into Dash's {@code GameCanvas} static
     *  initializer (see {@link #rewriteSjisInClass}); null-safe so it cannot
     *  break classes of other games that reuse these field names. */
    public static void fillNullStrings(String[] values) {
        if (values == null) {
            return;
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                values[i] = "";
            }
        }
    }

    /** Appends default info-string initialization to the tail of Dash's
     *  {@code GameCanvas} static initializer. The arrays are allocated by the
     *  original {@code <clinit>}, but their elements are only filled from
     *  network data; with the network unavailable the null elements make
     *  {@code DrawOption} throw NPE on {@code US_INFO[3].equals("1")} and
     *  {@code DrawMain} turns that into the "communication error" screen that
     *  closes the game. */
    static final class GameCanvasInfoFillMV extends org.objectweb.asm.MethodVisitor {
        private final String owner;
        private boolean injected;

        GameCanvasInfoFillMV(int api, org.objectweb.asm.MethodVisitor mv, String owner) {
            super(api, mv);
            this.owner = owner;
        }

        @Override
        public void visitInsn(int opcode) {
            if (!injected && opcode == org.objectweb.asm.Opcodes.RETURN) {
                injected = true;
                emitInfoFill(this, owner);
            }
            super.visitInsn(opcode);
        }
    }

    private static void emitInfoFill(org.objectweb.asm.MethodVisitor mv, String owner) {
        mv.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, owner, "US_INFO", "[Ljava/lang/String;");
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "opendoja/android/JamDexHost",
                "fillNullStrings", "([Ljava/lang/String;)V", false);
        mv.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, owner, "NN_INFO", "[Ljava/lang/String;");
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "opendoja/android/JamDexHost",
                "fillNullStrings", "([Ljava/lang/String;)V", false);
    }

    /** Guards {@code GameScreen.d(int,int)} against an out-of-range first argument. */
    static final class GameScreenDFixMV extends org.objectweb.asm.MethodVisitor {
        GameScreenDFixMV(int api, org.objectweb.asm.MethodVisitor mv) {
            super(api, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            org.objectweb.asm.Label ok1 = new org.objectweb.asm.Label();
            super.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 0);
            super.visitJumpInsn(org.objectweb.asm.Opcodes.IFGE, ok1);
            super.visitInsn(org.objectweb.asm.Opcodes.ICONST_M1);
            super.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            super.visitLabel(ok1);

            org.objectweb.asm.Label ok2 = new org.objectweb.asm.Label();
            super.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 0);
            super.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, 12);
            super.visitJumpInsn(org.objectweb.asm.Opcodes.IF_ICMPLT, ok2);
            super.visitInsn(org.objectweb.asm.Opcodes.ICONST_M1);
            super.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            super.visitLabel(ok2);
        }
    }
}
