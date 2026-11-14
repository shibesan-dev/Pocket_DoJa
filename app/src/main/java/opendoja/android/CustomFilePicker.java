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
 * Built-in file/folder picker used for every import/select flow. It browses the real
 * filesystem (the app holds MANAGE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE), so it
 * works even on devices without DocumentsUI and is fully D-pad navigable.
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
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                        new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 9100);
                Toast.makeText(activity, "Grant storage access, then try again", Toast.LENGTH_LONG).show();
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
                } else if (fileListener != null && child.isFile()
                        && matches(child.getName(), extensions)) {
                    files.add(child);
                }
            }
        }
        dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (folderListener != null) {
            // Folder pickers need an explicit accept action: the system
            // DocumentsUI "Use this folder" button cannot be reached with a D-pad.
            // Keep it first so it is selected as soon as the dialog opens.
            items.add("Choose this folder: " + dir.getName());
            actions.add(() -> folderListener.onFolder(dir));
        }
        File parent = dir.getParentFile();
        if (parent != null) {
            items.add(".. (parent)");
            actions.add(() -> browse(activity, parent, title, extensions, fileListener, folderListener));
        }
        for (File d : dirs) {
            items.add(d.getName() + "/");
            actions.add(() -> browse(activity, d, title, extensions, fileListener, folderListener));
        }
        if (fileListener != null) {
            for (File f : files) {
                items.add(f.getName());
                actions.add(() -> fileListener.onFile(f));
            }
        }
        if (items.isEmpty()) {
            Toast.makeText(activity, "Nothing to show here", Toast.LENGTH_SHORT).show();
            return;
        }

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title + "\n" + dir.getAbsolutePath())
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> {
            android.widget.ListView list = dialog.getListView();
            if (list != null) {
                // Give the list D-pad focus immediately so a remote can browse
                // without touching the screen first.
                list.setFocusable(true);
                list.setFocusableInTouchMode(true);
                list.setSelector(new android.graphics.drawable.ColorDrawable(0x663080FF));
                // The selected item fill is the highlight; skip the platform
                // outline that would otherwise frame the whole list.
                FocusHighlight.disable(list);
                list.setSelection(0);
                list.post(list::requestFocus);
            }
        });
        dialog.show();
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