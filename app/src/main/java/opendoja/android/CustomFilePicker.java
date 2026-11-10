package opendoja.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal built-in file/folder picker used as a fallback when the device has no system
 * DocumentsUI (ACTION_OPEN_DOCUMENT / ACTION_OPEN_DOCUMENT_TREE). It browses the real
 * filesystem (the app holds MANAGE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE) so every
 * import/select action still works on such devices.
 */
public final class CustomFilePicker {
    public interface FileListener {
        void onFile(File file);
    }

    public interface FolderListener {
        void onFolder(File folder);
    }

    private CustomFilePicker() {
    }

    public static void pickFile(Activity activity, String title, String[] extensions, FileListener listener) {
        if (!ensureBrowseAccess(activity)) {
            return;
        }
        browse(activity, startDir(activity), title, extensions, listener, null);
    }

    public static void pickFolder(Activity activity, String title, FolderListener listener) {
        if (!ensureBrowseAccess(activity)) {
            return;
        }
        browse(activity, startDir(activity), title, null, null, listener);
    }

    private static boolean ensureBrowseAccess(Activity activity) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    android.content.Intent intent = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                } catch (Exception exception) {
                    try {
                        activity.startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception ignored) {
                    }
                }
                Toast.makeText(activity, "Grant 'All files access', then try again", Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    private static File startDir(Activity activity) {
        File start = Environment.getExternalStorageDirectory();
        if (start == null || !start.isDirectory()) {
            start = activity.getExternalFilesDir(null);
        }
        if (start == null || !start.isDirectory()) {
            start = activity.getFilesDir();
        }
        return start;
    }

    private static void browse(Activity activity, File dir, String title, String[] extensions,
                               FileListener fileListener, FolderListener folderListener) {
        if (dir == null || !dir.isDirectory()) {
            Toast.makeText(activity, "Not a directory", Toast.LENGTH_SHORT).show();
            return;
        }
        File[] children = dir.listFiles();
        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    dirs.add(child);
                } else if (child.isFile() && matches(child.getName(), extensions)) {
                    files.add(child);
                }
            }
        }
        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        final List<String> items = new ArrayList<>();
        final List<File> targets = new ArrayList<>();
        File parent = dir.getParentFile();
        if (parent != null) {
            items.add(".. (parent)");
            targets.add(parent);
        }
        if (folderListener != null) {
            items.add(">> Use this folder: " + dir.getName());
            targets.add(dir);
        }
        for (File d : dirs) {
            items.add(d.getName() + "/");
            targets.add(d);
        }
        for (File f : files) {
            items.add(f.getName());
            targets.add(f);
        }

        new AlertDialog.Builder(activity)
                .setTitle(title + "\n" + dir.getAbsolutePath())
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= targets.size()) {
                        return;
                    }
                    File chosen = targets.get(which);
                    if (chosen.isDirectory()) {
                        if (folderListener != null && which == (parent != null ? 1 : 0)
                                && chosen.equals(dir)) {
                            folderListener.onFolder(chosen);
                            return;
                        }
                        browse(activity, chosen, title, extensions, fileListener, folderListener);
                    } else if (fileListener != null) {
                        fileListener.onFile(chosen);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static boolean matches(String name, String[] extensions) {
        if (extensions == null || extensions.length == 0) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lower.endsWith(extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}