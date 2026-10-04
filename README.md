# Assignment 1 — Implementing Myers' diff (Java)

```
java Main lines     A B    # Part A: minimal line diff of file A to file B
java Main highlight A B    # Part B: the same diff, plus changed-character ranges
```

## Files

```
src/Main.java     reads the files as bytes, splits lines, prints Part A / Part B output
src/Myers.java    Myers' O(ND) algorithm, used for lines AND for characters
test/TestMain.java  local tests (not submitted: only myers.toml and src/ are uploaded)
samples/          small input pairs: *_old.* -> *_new.*
```

`myers.toml` and `.gitignore` come from `cpsdiff init --lang java` (see "Submitting" below).

## Build, run, test

```
javac -d out src/*.java test/TestMain.java
java -cp out Main lines     samples/paper_old.txt  samples/paper_new.txt
java -cp out Main highlight samples/config_old.txt samples/config_new.txt
java -cp out TestMain
cpsdiff test                    # official public tests
```

## Output (from the problem statement)

- One line per step: `' '` keep, `-` delete (only in A), `+` insert (only in B), then the line's
  bytes exactly as in the file (including any `\r`), then `\n`.
- **Delete-first rule:** in every change block, all `-` lines come before all `+` lines.
- **highlight:** in each block the 1st `-` is paired with the 1st `+`, the 2nd with the 2nd, and
  so on. After each paired `+` line: `? <old ranges> | <new ranges>`. Ranges count Unicode code
  points from 0 and the end is excluded, e.g. `3-5,9-12`. Touching ranges are merged, and `.` means
  nothing changed on that side.
- Cannot read a file → nothing on stdout, a message on stderr, exit code **2**.

```
$ java -cp out Main highlight samples/config_old.txt samples/config_new.txt
 server:
-  port = 8000
-  host = localhost
+  port = 8080
? 11-12 | 11-12
+  host = 0.0.0.0
? 9-18 | 9-16
```

## How it works

### Reading lines (`Main.Lines`)
The file is read as raw bytes (`Files.readAllBytes`) and split on the byte `\n`. If the last piece
is empty it is dropped, and `\r` stays part of the line. Each line is stored as a `start`/`end`
offset into the byte array, so no text decoding happens in Part A. Invalid UTF-8 is therefore no
problem.

Each distinct line gets an integer id (`lineIds`, a `HashMap` keyed on the line's bytes). Myers
then compares `int`s instead of byte arrays.

### Myers' algorithm (`Myers.java`)
Comparing `a` (length n) with `b` (length m) is a walk through a grid from (0,0) to (n,m):
a step **right** deletes `a[x]`, a step **down** inserts `b[y]`, and a **diagonal** step is free when
`a[x] == b[y]`. A run of diagonals is a **snake**. Diagonal number `k = x − y`. The path with the
fewest right+down steps (D) is the minimal diff.

**Greedy idea (T2):** for D = 0, 1, 2, …, `V[k]` = the furthest x reachable on diagonal k with D
edits. A D-path on diagonal k comes from k−1 (step right) or k+1 (step down), whichever got
further, then follows its snake. The first D that reaches the end is the minimum, because rounds
go in increasing D and taking a snake never costs an edit.

**Linear space:** storing every round's V to rebuild the path costs O(D²) memory, too much for the
500,000-line tests under 768 MiB. So `middleSnake` runs the same greedy loop **forwards** from the
top-left (`forwardV`) and **backwards** from the bottom-right (`backwardV`), one round each in
turn. When they overlap on a diagonal, that point is on a shortest path. `compare` splits there
and solves both halves the same way. Time O((n+m)·D), memory O(n+m). This is section 4b of
Myers' paper, and it is what git does.

Before searching, `compare` strips matching lines at the start and end. They can always be kept.

Where things are in `Myers.middleSnake`:

| What | Code |
|---|---|
| choose step right or down | `if (fromLeft >= fromAbove) x = fromLeft + 1; else x = fromAbove;` |
| **follow the snake** (forward) | `while (x < aHi && y < bHi && a[x] == b[y]) { x++; y++; }` |
| store the furthest x | `forwardV[offset + k] = x;` |
| overlap test (odd delta) | `if (oddDelta && k >= bMin && k <= bMax && backwardV[offset + k] <= x)` |
| backward snake | `while (x > aLo && y > bLo && a[x - 1] == b[y - 1]) { x--; y--; }` |
| sentinels at the edge of the range | `forwardV[... - 1] = -1`, `backwardV[... + 1] = Integer.MAX_VALUE` |

**Real trace** of this code on the paper example, A = `a b c a b b a`, B = `c b a b a c`
(n=7, m=6). Forward starts on diagonal 0 at (0,0), backward on diagonal 7−6 = 1 at (7,6).
delta = 0 − 1 is odd, so the meeting happens in a forward round.

| round | forward: k → (x,y) after the snake | backward: k → (x,y) after the snake |
|---|---|---|
| d=1 | k=1 → (1,0), k=−1 → (0,1) | k=2 → (5,3), k=0 → (6,6) |
| d=2 | k=2 → (3,1), k=0 → (2,2), k=−2 → (2,4) | k=3 → (4,1), k=1 → (3,2), k=−1 → (5,6) |
| d=3 | k=3 → (5,2): backward reached k=3 at x=4 ≤ 5, **overlap** | |

The split is at (5,2), so D = 2·3 − 1 = 5. The left part `a b c a b` → `c b` and the right part
`b a` → `a b a c` are solved the same way. Final output (5 edits, the minimum):

```
-a
-b
 c
-a
 b
+a
 b
 a
+c
```

(The PDF shows a different 5-edit diff. Several minimal diffs exist, and the grader accepts any
valid, minimal, delete-first one.)

### Part B (`Main.printRanges`)
Each paired line is decoded as UTF-8 into code points (`codePoints()`), so an emoji is one
character. `Myers.diff` runs again on those ints. Deleted positions in the old line and inserted
positions in the new line are marked, then joined into ranges. Because Myers is minimal, the
number of marked characters is the minimum, and removing them leaves identical text.

## Tests (`test/TestMain.java`)

Every output is checked the way the grader checks it: it rebuilds A and B exactly, has `-` before
`+` in each block, and is **minimal** (edits = n + m − 2·LCS, with the LCS from a separate dynamic
programme). In highlight mode, each `?` line must be well formed, removing the marked characters
must leave equal text, and the count must be minimal. Covered:

- the statement's examples (paper example, delete-first, `8000`→`8080`, `a = 10` with an unpaired line, emoji);
- line splitting: empty file, `\n`, no final newline, `\r\n` kept, `\r` counted, invalid UTF-8;
- edge cases: empty A/B, identical, completely different, only insertions/deletions,
  start/end changes, repeated lines, several changes in one line, Unicode, long lines, source code;
- every pair of a/b files up to 5 lines (exhaustive), 3,000 random files, 3,000 random highlight pairs;
- missing file / directory / bad arguments → exit 2 and empty stdout;
- the files in `samples/`;
- performance: 500,000 lines in about 0.4 s in-process.

Measured on a laptop with `java -Xmx192m` (a small heap, as in a memory-limited container),
including JVM start-up:

- 500k lines with ~20k edits: ~1.4 s;
- 20k vs 20k completely different lines: ~0.7 s.

The edit count matches `git diff --minimal`. The Java limit is 3 s.

## Submitting (from the cpsdiff guide)

1. On GitHub create a **private** repo named `myers-diff-algorithm` (empty, no README).
2. `mkdir myers-diff-algorithm && cd myers-diff-algorithm && cpsdiff init --lang java`
3. Copy `src/Main.java` and `src/Myers.java` from here into its `src/` (replace the starter
   `Main.java`). Optionally also copy `test/`, `samples/` and this README, and add `out/` to
   `.gitignore`.
4. `cpsdiff test`, then commit and push, then `cpsdiff submit`.
5. Before the code exam, make the repo public and submit its link.
