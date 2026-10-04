import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Local tests for src/Main.java and src/Myers.java. Not part of the submission (only src/ is
 * uploaded). Run from the project folder:
 *
 *   javac -d out src/*.java test/TestMain.java
 *   java -cp out TestMain
 *
 * Every output is checked the way the assignment says the grader checks it:
 *   lines:     the output rebuilds A and B exactly, is minimal, and has '-' before '+' in each block
 *   highlight: also, after each paired '+' line there is a '?' line whose ranges are well formed,
 *              removing the marked characters leaves identical text, and the count is minimal.
 * Minimality uses an independent oracle: minimal edits = n + m - 2 * LCS (dynamic programming).
 */
public class TestMain {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        test("examples from the problem statement", TestMain::statementExamples);
        test("reading files into lines", TestMain::readingLines);
        test("edge cases", TestMain::edgeCases);
        test("exhaustive small inputs", TestMain::exhaustive);
        test("random line files", TestMain::randomFiles);
        test("random highlight pairs", TestMain::randomHighlight);
        test("unreadable file and bad arguments", TestMain::errors);
        test("sample files", TestMain::samples);
        test("performance", TestMain::performance);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // Running the program
    // ------------------------------------------------------------------

    record Result(int exitCode, String out, String err) {}

    /** Bytes <-> String with ISO-8859-1: one char per byte, so nothing is lost or changed. */
    static String str(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** UTF-8 text as a "byte string" (used for Unicode test content). */
    static String utf8(String s) {
        return str(s.getBytes(StandardCharsets.UTF_8));
    }

    static Result run(String command, String contentA, String contentB) throws IOException {
        Path fa = Files.createTempFile("A", ".txt");
        Path fb = Files.createTempFile("B", ".txt");
        try {
            Files.write(fa, bytes(contentA));
            Files.write(fb, bytes(contentB));
            return runArgs(command, fa.toString(), fb.toString());
        } finally {
            Files.delete(fa);
            Files.delete(fb);
        }
    }

    static Result runArgs(String... args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, out, new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, str(out.toByteArray()), err.toString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Checkers (independent of the implementation)
    // ------------------------------------------------------------------

    /** Splits file content into lines exactly as the problem statement describes. */
    static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        if (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    static <T> int lcs(List<T> a, List<T> b) {
        int[] prev = new int[b.size() + 1];
        int[] cur = new int[b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                cur[j] = a.get(i - 1).equals(b.get(j - 1)) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.size()];
    }

    static List<Integer> codePoints(String byteString) {
        List<Integer> list = new ArrayList<>();
        new String(bytes(byteString), StandardCharsets.UTF_8).codePoints().forEach(list::add);
        return list;
    }

    /**
     * Checks a "lines" or "highlight" output for A -> B. Pass checkMinimal=false for inputs too
     * big for the O(n·m) oracle.
     */
    static void verify(String contentA, String contentB, String output, boolean highlight, boolean checkMinimal) {
        List<String> a = splitLines(contentA);
        List<String> b = splitLines(contentB);
        List<String> rebuiltA = new ArrayList<>();
        List<String> rebuiltB = new ArrayList<>();
        check(output.isEmpty() || output.endsWith("\n"), "output must end with a newline");
        List<String> out = output.isEmpty() ? List.of() : List.of(output.substring(0, output.length() - 1).split("\n", -1));

        int edits = 0;
        List<String> blockDeletes = new ArrayList<>();  // '-' lines of the current block
        int blockInserts = 0;                           // '+' lines seen so far in the current block
        for (int i = 0; i < out.size(); i++) {
            String line = out.get(i);
            check(!line.isEmpty(), "empty output line at " + i);
            char prefix = line.charAt(0);
            String text = line.substring(1);
            if (prefix == ' ') {
                rebuiltA.add(text);
                rebuiltB.add(text);
                blockDeletes.clear();
                blockInserts = 0;
            } else if (prefix == '-') {
                check(blockInserts == 0, "'-' after '+' in a change block at output line " + i);
                rebuiltA.add(text);
                blockDeletes.add(text);
                edits++;
            } else if (prefix == '+') {
                rebuiltB.add(text);
                edits++;
                boolean paired = blockInserts < blockDeletes.size();
                boolean hasRangeLine = i + 1 < out.size() && out.get(i + 1).startsWith("?");
                if (highlight && paired) {
                    check(hasRangeLine, "missing '?' line after paired '+' line " + i);
                    verifyRanges(blockDeletes.get(blockInserts), text, out.get(i + 1));
                    i++;
                } else {
                    check(!hasRangeLine, "unexpected '?' line after '+' line " + i);
                }
                blockInserts++;
            } else {
                throw new AssertionError("bad prefix at output line " + i + ": " + line);
            }
        }
        check(rebuiltA.equals(a), "the output does not rebuild A");
        check(rebuiltB.equals(b), "the output does not rebuild B");
        if (checkMinimal) {
            int minimal = a.size() + b.size() - 2 * lcs(a, b);
            check(edits == minimal, edits + " edits, minimal is " + minimal);
        }
    }

    /** Checks one "? old | new" line against the old and new line (as byte strings). */
    static void verifyRanges(String oldLine, String newLine, String rangeLine) {
        check(rangeLine.matches("\\? (\\.|\\d+-\\d+(,\\d+-\\d+)*) \\| (\\.|\\d+-\\d+(,\\d+-\\d+)*)"),
                "badly formatted range line: " + rangeLine);
        String[] sides = rangeLine.substring(2).split(" \\| ");
        List<Integer> oldCp = codePoints(oldLine);
        List<Integer> newCp = codePoints(newLine);
        List<Integer> keptOld = keepUnmarked(oldCp, sides[0]);
        List<Integer> keptNew = keepUnmarked(newCp, sides[1]);
        check(keptOld.equals(keptNew), "after removing marked characters the lines differ: " + rangeLine);
        int marked = (oldCp.size() - keptOld.size()) + (newCp.size() - keptNew.size());
        int minimal = oldCp.size() + newCp.size() - 2 * lcs(oldCp, newCp);
        check(marked == minimal, marked + " marked characters, minimal is " + minimal);
    }

    /** Removes the ranges' characters; also checks ranges are ordered, not overlapping or touching. */
    static List<Integer> keepUnmarked(List<Integer> chars, String ranges) {
        boolean[] marked = new boolean[chars.size()];
        if (!ranges.equals(".")) {
            int previousEnd = -1;
            for (String r : ranges.split(",")) {
                String[] se = r.split("-");
                check(!se[0].matches("0\\d+") && !se[1].matches("0\\d+"), "leading zero in " + r);
                int s = Integer.parseInt(se[0]);
                int e = Integer.parseInt(se[1]);
                check(s < e && e <= chars.size(), "range out of bounds: " + r);
                check(s > previousEnd, "ranges out of order, overlapping or touching: " + ranges);
                for (int i = s; i < e; i++) {
                    marked[i] = true;
                }
                previousEnd = e;
            }
        }
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < chars.size(); i++) {
            if (!marked[i]) {
                kept.add(chars.get(i));
            }
        }
        return kept;
    }

    /** Runs both commands on A -> B and verifies both outputs. Returns the highlight output. */
    static String both(String contentA, String contentB) throws IOException {
        Result lines = run("lines", contentA, contentB);
        check(lines.exitCode == 0, "exit code " + lines.exitCode);
        verify(contentA, contentB, lines.out, false, true);
        Result highlight = run("highlight", contentA, contentB);
        check(highlight.exitCode == 0, "exit code " + highlight.exitCode);
        verify(contentA, contentB, highlight.out, true, true);
        return highlight.out;
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    static void statementExamples() throws IOException {
        // Myers' paper example, one letter per line. Several 5-edit diffs exist and the grader
        // accepts any of them, so check validity + minimality (5 edits) instead of one fixed text.
        String paper = both("a\nb\nc\na\nb\nb\na\n", "c\nb\na\nb\na\nc\n");
        check(paper.chars().filter(ch -> ch == '\n').count() == 9, "paper example has 4 keeps + 5 edits");
        assertEquals(" a\n-b\n+x\n c\n", run("lines", "a\nb\nc\n", "a\nx\nc\n").out, "delete-first rule");
        assertEquals(" server:\n-  port = 8000\n+  port = 8080\n? 11-12 | 11-12\n",
                both("server:\n  port = 8000\n", "server:\n  port = 8080\n"), "port 8000 -> 8080");
        assertEquals("-a = 1\n-b = 2\n+a = 10\n? . | 5-6\n",
                both("a = 1\nb = 2\n", "a = 10\n"), "pure insertion with an unpaired line");
        String emoji = both(utf8("hi 😀\n"), utf8("hi 😃\n"));
        check(emoji.endsWith("? 3-4 | 3-4\n"), "emoji is one character: " + emoji);
    }

    static void readingLines() throws IOException {
        assertEquals("", run("lines", "", "").out, "two empty files: no output");
        assertEquals("+\n", run("lines", "", "\n").out, "\"\\n\" is one empty line");
        assertEquals(" a\n", run("lines", "a", "a\n").out, "\"a\" and \"a\\n\" are the same line");
        assertEquals(" a\n \n b\n", run("lines", "a\n\nb", "a\n\nb\n").out, "blank line in the middle");
        assertEquals("-a\r\n-b\r\n+a\n+b\n", run("lines", "a\r\nb\r\n", "a\nb\n").out, "\\r is part of the line");
        assertEquals("-x\n+x\r\n? . | 1-2\n", run("highlight", "x\n", "x\r\n").out, "\\r counts as one character");
        String invalid = "ok\n\u00ff\u00fe bad bytes\n";   // 0xFF 0xFE: not valid UTF-8
        Result r = run("lines", invalid, "ok\n\u00ff\u00fe bad  bytes\n");
        assertEquals(" ok\n-\u00ff\u00fe bad bytes\n+\u00ff\u00fe bad  bytes\n", r.out, "invalid UTF-8 compared as bytes");
    }

    static void edgeCases() throws IOException {
        both("", "x\ny\n");                                  // empty A
        both("x\ny\n", "");                                  // empty B
        assertEquals(" a\n b\n", both("a\nb\n", "a\nb\n"), "identical files: keep lines only");
        both("a\nb\nc\n", "x\ny\n");                         // completely different
        both("1\n2\n3\n", "1\nnew\n2\n3\nend\n");            // only insertions
        both("1\n2\n3\n4\n", "1\n4\n");                      // only deletions
        both("a\nb\nc\nd\ne\n", "a\nB\nC\nD\ne\n");          // consecutive changes
        both("a\nb\nc\n", "X\nb\nc\n");                      // change at the start
        both("a\nb\nc\n", "a\nb\nX\n");                      // change at the end
        both("}\n}\n}\n", "}\n}\n}\n}\n");                   // repeated lines
        both("x\nx\nx\ny\n", "x\ny\nx\n");
        assertEquals("-f(a, b, c)\n+f(x, b, z)\n? 2-3,8-9 | 2-3,8-9\n",
                both("f(a, b, c)\n", "f(x, b, z)\n"), "several changes in one line");
        assertEquals("-abc\n+abc  \n? . | 3-5\n", both("abc\n", "abc  \n"), "whitespace change");
        both(utf8("你好，世界\nnaïve café\n"), utf8("你好，朋友\nnaive cafe\n"));   // Unicode
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longLine.append("tok").append(i).append(' ');
        }
        both(longLine + "\n", longLine.toString().replace("tok2500 ", "TOK2500 ") + "\n");  // long line
        both("def area(r):\n    return 3.14 * r * r\n\nprint(area(2))\n",
             "import math\n\ndef area(r):\n    return math.pi * r * r\n\nprint(area(2))\n");  // source code
    }

    /** Every pair of files made of lines "a"/"b", 0 to 5 lines each: lots of equally short diffs. */
    static void exhaustive() throws IOException {
        List<String> files = new ArrayList<>();
        for (int len = 0; len <= 5; len++) {
            for (int bits = 0; bits < (1 << len); bits++) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    sb.append(((bits >> i) & 1) == 0 ? "a\n" : "b\n");
                }
                files.add(sb.toString());
            }
        }
        for (String fa : files) {
            for (String fb : files) {
                verifyDirect(fa, fb);
            }
        }
    }

    /** Same checks as run(), but without temp files (faster for thousands of cases). */
    static void verifyDirect(String contentA, String contentB) throws IOException {
        Main.Lines a = new Main.Lines(bytes(contentA));
        Main.Lines b = new Main.Lines(bytes(contentB));
        int[][] ids = Main.lineIds(a, b);
        for (boolean highlight : new boolean[] {false, true}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Main.printDiff(out, a, b, Myers.diff(ids[0], ids[1]), highlight);
            verify(contentA, contentB, str(out.toByteArray()), highlight, true);
        }
    }

    static void randomFiles() throws IOException {
        Random random = new Random(1);
        String[] pool = {"", "}", "{", "x = 1", "x = 2", "return x;", "a\r", "  foo();", "// note"};
        for (int t = 0; t < 3000; t++) {
            verifyDirect(randomFile(random, pool, random.nextInt(30)), randomFile(random, pool, random.nextInt(30)));
        }
    }

    static String randomFile(Random random, String[] pool, int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append(pool[random.nextInt(pool.length)]).append('\n');
        }
        if (sb.length() > 0 && random.nextBoolean()) {
            sb.setLength(sb.length() - 1);   // sometimes no final newline
        }
        return sb.toString();
    }

    static void randomHighlight() throws IOException {
        Random random = new Random(2);
        String alphabet = "ab c=0😀é";
        for (int t = 0; t < 3000; t++) {
            String oldLine = randomText(random, alphabet, random.nextInt(25));
            String newLine = randomText(random, alphabet, random.nextInt(25));
            verifyDirect(utf8(oldLine + "\n"), utf8(newLine + "\n"));
        }
    }

    static String randomText(Random random, String alphabet, int length) {
        int[] cps = alphabet.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.appendCodePoint(cps[random.nextInt(cps.length)]);
        }
        return sb.toString();
    }

    static void errors() throws IOException {
        Path existing = Files.createTempFile("A", ".txt");
        try {
            for (String command : new String[] {"lines", "highlight"}) {
                Result r = runArgs(command, "no-such-file.txt", existing.toString());
                check(r.exitCode == 2 && r.out.isEmpty() && !r.err.isEmpty(), "missing file A: " + r);
                r = runArgs(command, existing.toString(), "no-such-file.txt");
                check(r.exitCode == 2 && r.out.isEmpty() && !r.err.isEmpty(), "missing file B: " + r);
                r = runArgs(command, existing.toString(), existing.getParent().toString());
                check(r.exitCode == 2 && r.out.isEmpty(), "a directory is not readable as a file: " + r);
            }
            check(runArgs("diff", "x", "y").exitCode == 2, "unknown command");
            check(runArgs("lines", "x").exitCode == 2, "missing argument");
        } finally {
            Files.delete(existing);
        }
    }

    static void samples() throws IOException {
        try (var dirStream = Files.list(Path.of("samples"))) {
            List<Path> olds = dirStream.filter(p -> p.getFileName().toString().contains("_old")).sorted().toList();
            check(!olds.isEmpty(), "no samples found (run from the project folder)");
            for (Path old : olds) {
                Path neu = old.resolveSibling(old.getFileName().toString().replace("_old", "_new"));
                verifyDirect(str(Files.readAllBytes(old)), str(Files.readAllBytes(neu)));
            }
        }
    }

    /**
     * Times big inputs (the limit for Java is 3 s per test on the grading machine). Minimality is
     * not re-checked here because the O(n·m) oracle is too slow for these sizes.
     */
    static void performance() throws IOException {
        Random random = new Random(3);
        // 500,000 lines with about 2,000 scattered edits.
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 500_000; i++) {
            String line = "    value_" + (i % 1000) + " = compute(" + random.nextInt(100) + ");\n";
            a.append(line);
            int roll = random.nextInt(500);
            if (roll == 0) {
                b.append("    inserted();\n").append(line);
            } else if (roll == 1) {
                b.append(line.replace("compute", "calc"));
            } else if (roll != 2) {
                b.append(line);
            }
        }
        time("500k lines, ~3k edits, lines", "lines", a.toString(), b.toString());
        time("500k lines, ~3k edits, highlight", "highlight", a.toString(), b.toString());

        // Completely different 5,000-line files: D = 10,000, the hardest case for O(ND).
        StringBuilder c = new StringBuilder();
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            c.append("old ").append(i).append('\n');
            d.append("new ").append(i).append('\n');
        }
        time("5k vs 5k completely different", "lines", c.toString(), d.toString());
    }

    static void time(String label, String command, String contentA, String contentB) throws IOException {
        long t0 = System.nanoTime();
        Result r = run(command, contentA, contentB);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(r.exitCode == 0, label + ": exit code " + r.exitCode);
        verify(contentA, contentB, r.out, command.equals("highlight"), false);
        System.out.println("      " + label + ": " + ms + " ms");
        check(ms < 3000, label + " took " + ms + " ms (limit 3000)");
    }

    // ------------------------------------------------------------------
    // Tiny test framework
    // ------------------------------------------------------------------

    interface Body {
        void run() throws Exception;
    }

    static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + "\n      " + t.getMessage());
            if (!(t instanceof AssertionError)) {
                t.printStackTrace(System.out);
            }
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void assertEquals(String expected, String actual, String what) {
        check(expected.equals(actual), what + "\n--- expected ---\n" + expected + "--- actual ---\n" + actual);
    }
}
