package dev.tilesmile;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A drawing of one or more pages. The pages being painted live in a working
 * folder (one PNG each, autosaved); Save copies them to a named drawing and
 * Open copies a named drawing back. A page with no file yet is blank.
 */
final class Project {
    interface Result { void complete(Exception error); }

    static final int MAX_PAGES = 100;
    /** One queue for every page read and write, so a save always lands before a later read. */
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final File work, drawings;
    int count, index;
    String name;

    Project(Context context) {
        prefs = context.getSharedPreferences("project", Context.MODE_PRIVATE);
        work = new File(context.getFilesDir(), "work");
        drawings = new File(context.getFilesDir(), "drawings");
        count = Math.max(1, Math.min(MAX_PAGES, prefs.getInt("count", 1)));
        index = Math.max(0, Math.min(count - 1, prefs.getInt("index", 0)));
        name = prefs.getString("name", "");
        // Before pages, the single canvas was saved as canvas.png.
        File old = new File(context.getFilesDir(), "canvas.png");
        if (old.exists() && !page(0).exists() && (work.isDirectory() || work.mkdirs())) old.renameTo(page(0));
    }

    File page(int i) { return new File(work, (i + 1) + ".png"); }
    File current() { return page(index); }

    void select(int i) { index = Math.max(0, Math.min(count - 1, i)); remember(); }

    boolean add() {
        if (count >= MAX_PAGES) return false;
        index = count++;
        remember();
        return true;
    }

    private void remember() {
        prefs.edit().putInt("count", count).putInt("index", index).putString("name", name).apply();
    }

    static boolean validName(String name) {
        return name != null && !name.trim().isEmpty() && name.length() <= 64 && name.matches("[\\p{L}\\p{N} _-]+");
    }

    /** Named drawings, sorted. Runs on the caller's thread. */
    List<String> names() {
        String[] found = drawings.list((dir, file) -> new File(dir, file).isDirectory());
        List<String> names = new ArrayList<>(found == null ? Arrays.asList() : Arrays.asList(found));
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Copies the working pages to {@code target}; queued after any pending page save. */
    void save(String target, Result result) {
        final int pages = count, at = index;
        name = target; remember();
        IO.execute(() -> {
            try {
                File folder = new File(drawings, target);
                replaceFolder(work, folder, pages);
                write(new File(folder, "pages"), (pages + " " + at).getBytes("UTF-8"));
                result.complete(null);
            } catch (IOException | RuntimeException e) { result.complete(e); }
        });
    }

    /** Replaces the working pages with a named drawing's; {@link #count}/{@link #index} update before result. */
    void open(String source, Result result) {
        IO.execute(() -> {
            try {
                File folder = new File(drawings, source);
                int pages = 1, at = 0;
                File info = new File(folder, "pages");
                if (info.exists()) {
                    String[] parts = new String(read(info), "UTF-8").trim().split(" ");
                    pages = Math.max(1, Math.min(MAX_PAGES, Integer.parseInt(parts[0])));
                    at = parts.length > 1 ? Math.max(0, Math.min(pages - 1, Integer.parseInt(parts[1]))) : 0;
                }
                replaceFolder(folder, work, pages);
                count = pages; index = at; name = source; remember();
                result.complete(null);
            } catch (IOException | RuntimeException e) { result.complete(e); }
        });
    }

    /** Discards the working pages: one blank page, unnamed. */
    void startNew(Result result) {
        count = 1; index = 0; name = ""; remember();
        IO.execute(() -> {
            clearPages(work);
            result.complete(null);
        });
    }

    private static void replaceFolder(File from, File to, int pages) throws IOException {
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Cannot create " + to.getName());
        clearPages(to);
        for (int i = 1; i <= pages; i++) {
            File page = new File(from, i + ".png");
            if (page.exists()) {
                File temp = new File(to, i + ".png.tmp");
                write(temp, read(page));
                if (!temp.renameTo(new File(to, i + ".png"))) throw new IOException("Cannot write page " + i);
            }
        }
    }

    private static void clearPages(File folder) {
        File[] old = folder.listFiles((dir, file) -> file.endsWith(".png") || file.endsWith(".tmp"));
        if (old != null) for (File file : old) file.delete();
    }

    private static byte[] read(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int done = 0, n;
            while (done < bytes.length && (n = in.read(bytes, done, bytes.length - done)) > 0) done += n;
            if (done != bytes.length) throw new IOException("Short read " + file.getName());
        }
        return bytes;
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }
}
