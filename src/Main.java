import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Assignment 1: Myers' diff.
 *
 *   java Main lines     A B    Part A: minimal line diff of file A to file B
 *   java Main highlight A B    Part B: the same diff, plus changed-character ranges
 *
 * Output: one line per step of the edit script, prefix ' ' (keep), '-' (delete) or '+' (insert),
 * then the line's bytes exactly as in the file, then '\n'. In each change block all '-' lines
 * come before all '+' lines. In highlight mode, after each paired '+' line we print
 *   ? <changed ranges in the old line> | <changed ranges in the new line>
 * If a file cannot be read: nothing on stdout, a message on stderr, exit code 2.
 */
public class Main {

    public static void main(String[] args) throws IOException {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        int exitCode = run(args, out, System.err);
        out.flush();
        System.exit(exitCode);
    }

    /** Runs one command and returns the exit code. Separate from main() so tests can call it. */
    static int run(String[] args, OutputStream out, PrintStream err) throws IOException {
        if (args.length != 3 || !(args[0].equals("lines") || args[0].equals("highlight"))) {
            err.println("usage: java Main lines|highlight <fileA> <fileB>");
            return 2;
        }
        Lines a;
        Lines b;
        try {
            a = new Lines(Files.readAllBytes(Path.of(args[1])));
            b = new Lines(Files.readAllBytes(Path.of(args[2])));
        } catch (IOException | InvalidPathException e) {   // unreadable file or invalid path
            err.println("error: cannot read file: " + e.getMessage());
            return 2;   // nothing has been printed on stdout yet
        }

        int[][] ids = lineIds(a, b);
        byte[] script = Myers.diff(ids[0], ids[1]);
        printDiff(out, a, b, script, args[0].equals("highlight"));
        return 0;
    }

    // ------------------------------------------------------------------
    // Reading files into lines (raw bytes)
    // ------------------------------------------------------------------

    /**
     * A file split into lines. Line i is data[start[i] .. end[i]). We split on the byte '\n',
     * keep any '\r' as part of the line, and drop the last piece if it is empty, so a final
     * newline adds no extra line and an empty file has no lines.
     */
    static final class Lines {
        final byte[] data;
        final int[] start;
        final int[] end;
        final int count;

        Lines(byte[] data) {
            this.data = data;
            int newlines = 0;
            for (byte c : data) {
                if (c == '\n') {
                    newlines++;
                }
            }
            int capacity = newlines + 1;
            int[] s = new int[capacity];
            int[] e = new int[capacity];
            int n = 0;
            int lineStart = 0;
            for (int i = 0; i < data.length; i++) {
                if (data[i] == '\n') {
                    s[n] = lineStart;
                    e[n] = i;
                    n++;
                    lineStart = i + 1;
                }
            }
            if (lineStart < data.length) {          // last line without a final '\n'
                s[n] = lineStart;
                e[n] = data.length;
                n++;
            }
            this.start = s;
            this.end = e;
            this.count = n;
        }
    }

    /** A line's bytes, usable as a HashMap key (equal bytes = equal key). */
    record LineKey(byte[] data, int start, int end) {
        @Override
        public boolean equals(Object o) {
            return o instanceof LineKey k && Arrays.equals(data, start, end, k.data, k.start, k.end);
        }

        @Override
        public int hashCode() {
            int h = 1;
            for (int i = start; i < end; i++) {
                h = 31 * h + data[i];
            }
            return h;
        }
    }

    /**
     * Gives every distinct line an integer id, so Myers compares ints instead of byte arrays.
     * Returns {ids of A's lines, ids of B's lines}.
     */
    static int[][] lineIds(Lines a, Lines b) {
        Map<LineKey, Integer> idOf = new HashMap<>(2 * (a.count + b.count) + 16);
        int[][] result = {new int[a.count], new int[b.count]};
        Lines[] files = {a, b};
        for (int f = 0; f < 2; f++) {
            Lines lines = files[f];
            for (int i = 0; i < lines.count; i++) {
                LineKey key = new LineKey(lines.data, lines.start[i], lines.end[i]);
                Integer id = idOf.putIfAbsent(key, idOf.size());
                result[f][i] = (id != null) ? id : idOf.size() - 1;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Part A and Part B output
    // ------------------------------------------------------------------

    static void printDiff(OutputStream out, Lines a, Lines b, byte[] script, boolean highlight)
            throws IOException {
        int x = 0;   // next line of A
        int y = 0;   // next line of B
        int p = 0;   // position in the script
        while (p < script.length) {
            if (script[p] == Myers.KEEP) {
                printLine(out, ' ', a, x);
                x++;
                y++;
                p++;
                continue;
            }
            // A change block: count its deletions and insertions...
            int deletes = 0;
            int inserts = 0;
            while (p < script.length && script[p] != Myers.KEEP) {
                if (script[p] == Myers.DELETE) {
                    deletes++;
                } else {
                    inserts++;
                }
                p++;
            }
            // ...then print all '-' lines before all '+' lines (the delete-first rule).
            for (int i = 0; i < deletes; i++) {
                printLine(out, '-', a, x + i);
            }
            for (int j = 0; j < inserts; j++) {
                printLine(out, '+', b, y + j);
                // Part B: the j-th '+' line is paired with the j-th '-' line of the block.
                if (highlight && j < deletes) {
                    printRanges(out, a, x + j, b, y + j);
                }
            }
            x += deletes;
            y += inserts;
        }
    }

    static void printLine(OutputStream out, char prefix, Lines lines, int i) throws IOException {
        out.write(prefix);
        out.write(lines.data, lines.start[i], lines.end[i] - lines.start[i]);
        out.write('\n');
    }

    /**
     * Part B: runs Myers on the code points of an old/new line pair, so the number of marked
     * characters is minimal, and prints  "? <old ranges> | <new ranges>".
     */
    static void printRanges(OutputStream out, Lines a, int i, Lines b, int j) throws IOException {
        int[] oldChars = codePoints(a, i);
        int[] newChars = codePoints(b, j);
        boolean[] deleted = new boolean[oldChars.length];
        boolean[] inserted = new boolean[newChars.length];
        int x = 0;
        int y = 0;
        for (byte op : Myers.diff(oldChars, newChars)) {
            if (op == Myers.KEEP) {
                x++;
                y++;
            } else if (op == Myers.DELETE) {
                deleted[x++] = true;
            } else {
                inserted[y++] = true;
            }
        }
        String line = "? " + ranges(deleted) + " | " + ranges(inserted) + "\n";
        out.write(line.getBytes(StandardCharsets.US_ASCII));
    }

    /** The line's characters as Unicode code points (an emoji is one, a '\r' is one). */
    static int[] codePoints(Lines lines, int i) {
        String text = new String(lines.data, lines.start[i], lines.end[i] - lines.start[i], StandardCharsets.UTF_8);
        return text.codePoints().toArray();
    }

    /** Turns marked positions into "start-end" ranges (end excluded), e.g. "3-5,9-12", or "." if none. */
    static String ranges(boolean[] marked) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < marked.length) {
            if (!marked[i]) {
                i++;
                continue;
            }
            int startOfRun = i;
            while (i < marked.length && marked[i]) {    // touching marks merge into one range
                i++;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(startOfRun).append('-').append(i);
        }
        return sb.length() == 0 ? "." : sb.toString();
    }
}
