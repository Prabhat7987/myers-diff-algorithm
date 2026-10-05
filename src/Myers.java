import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Myers' O(ND) diff algorithm (Eugene W. Myers, 1986), the direct V[k] version from T2.
 *
 * It compares two int arrays, so the same code diffs
 *   - lines      (Part A: Main gives every distinct line an integer id), and
 *   - characters (Part B: every Unicode code point is already an int).
 *
 * THE EDIT GRAPH
 *   We walk a grid from (0, 0) to (N, M), where N = a.length and M = b.length.
 *   (x, y) = current position: x items of a and y items of b have been used up.
 *
 *     DELETE  (x, y) -> (x + 1, y)       move right: a[x] is removed
 *     INSERT  (x, y) -> (x, y + 1)       move down:  b[y] is added
 *     MATCH   (x, y) -> (x + 1, y + 1)   diagonal:   only allowed when a[x] == b[y], costs nothing
 *
 *   Several MATCH steps in a row are called a SNAKE.
 *   D = number of INSERT + DELETE steps on a path. The smallest D is the minimal diff.
 *
 * DIAGONALS
 *   k = x - y is the diagonal number of point (x, y).
 *     DELETE: x grows by 1           -> k = x - y grows by 1    (k + 1)
 *     INSERT: y grows by 1           -> k = x - y shrinks by 1  (k - 1)
 *     MATCH:  x and y both grow by 1 -> k stays the same        (we stay on diagonal k)
 *   So after D edits we can only be on diagonals -D, -D+2, ..., D-2, D:
 *   every edit moves k by exactly 1, so k has the same parity (odd/even) as D.
 *
 * V[k]
 *   V[k] = the furthest x reached so far on diagonal k.
 *   Knowing x and k is enough to know the whole point, because k = x - y  means  y = x - k.
 *
 * THE ALGORITHM
 *   For D = 0, 1, 2, ...:
 *     for every diagonal k = -D, -D+2, ..., D:
 *       1. arrive on diagonal k with one more edit: an INSERT from diagonal k+1 or a DELETE from
 *          diagonal k-1, whichever gets further (larger x);
 *       2. follow the SNAKE (free MATCH steps) as far as possible;
 *       3. store the end of the snake in V[k].
 *   The first time a path reaches (N, M), D is the minimum number of edits: smaller D values
 *   were all tried first and none of them reached the end.
 *
 *   To rebuild the actual path afterwards, we save a copy of V after every round D (the "trace")
 *   and walk it backwards from (N, M) to (0, 0).
 */
final class Myers {

    // The edit script uses one byte per step. Main.java relies on these values.
    static final byte KEEP = 0;    // a MATCH: the item is in both a and b
    static final byte DELETE = 1;  // move right: the item is only in a
    static final byte INSERT = 2;  // move down:  the item is only in b

    /**
     * Returns a minimal edit script turning a into b: one byte per step. Walking it, KEEP moves
     * on in both a and b, DELETE moves on in a, INSERT moves on in b.
     */
    static byte[] diff(int[] a, int[] b) {
        // Small preparation step: items that already match at the very start or the very end
        // can always be kept (taking a MATCH never makes a path longer). We cut them off, run
        // Myers on the middle part only, and add them back as KEEP steps at the end.
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix
                && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) {
            suffix++;
        }
        int[] middleA = Arrays.copyOfRange(a, prefix, a.length - suffix);
        int[] middleB = Arrays.copyOfRange(b, prefix, b.length - suffix);

        byte[] middleScript;
        if (middleA.length == 0) {
            // Nothing left of a: the only path goes straight down, one INSERT per item of b.
            // (Running the search here would save V for every one of those D rounds for nothing.)
            middleScript = new byte[middleB.length];
            Arrays.fill(middleScript, INSERT);
        } else if (middleB.length == 0) {
            // Nothing left of b: the only path goes straight right, one DELETE per item of a.
            middleScript = new byte[middleA.length];
            Arrays.fill(middleScript, DELETE);
        } else {
            List<int[]> trace = findShortestPath(middleA, middleB);
            middleScript = buildScript(middleA, middleB, trace);
        }

        byte[] script = new byte[prefix + middleScript.length + suffix];
        Arrays.fill(script, 0, prefix, KEEP);
        System.arraycopy(middleScript, 0, script, prefix, middleScript.length);
        Arrays.fill(script, prefix + middleScript.length, script.length, KEEP);
        return script;
    }

    /**
     * FORWARD PASS: runs D = 0, 1, 2, ... until a path reaches (N, M).
     *
     * Returns the trace: trace.get(d) is a copy of V as it was at the end of round d.
     * The number of saved rounds, trace.size(), is exactly the minimal D.
     */
    private static List<int[]> findShortestPath(int[] a, int[] b) {
        int n = a.length;
        int m = b.length;
        int max = n + m;  // the worst case: delete all of a, insert all of b

        // NEGATIVE k: Java arrays cannot have index -3, but k can be anywhere from -max to +max.
        // So V[k] is stored at v[k + offset]. With offset = max + 1, diagonal -max lands on
        // index 1 and diagonal +max on index 2*max + 1. One spare slot on each side covers the
        // V[k + 1] / V[k - 1] look-ups at the edges.
        int offset = max + 1;
        int[] v = new int[2 * max + 3];

        // Starting trick from the paper: pretend there is a point on diagonal 1 with x = 0.
        // Then round D = 0, k = 0 takes "INSERT from diagonal 1" with x = V[1] = 0, which simply
        // means "start at (0, 0)", without counting an edit.
        v[offset + 1] = 0;

        List<int[]> trace = new ArrayList<>();

        for (int d = 0; d <= max; d++) {
            // After d edits, the reachable diagonals are -d, -d+2, ..., d (see class comment).
            for (int k = -d; k <= d; k += 2) {

                // ---- Step 1: arrive on diagonal k with one more edit ----
                // Two candidates, both from round d-1:
                //   INSERT from diagonal k+1: move down,  x stays the same  -> x = V[k+1]
                //   DELETE from diagonal k-1: move right, x grows by one    -> x = V[k-1] + 1
                // Decision:
                //   k == -d: diagonal k-1 = -d-1 was not reachable in round d-1, so INSERT is the only choice.
                //   k ==  d: diagonal k+1 =  d+1 was not reachable in round d-1, so DELETE is the only choice.
                //   otherwise: take the neighbour that reached further. If V[k-1] < V[k+1], coming
                //   down from k+1 gives the larger x. On a tie we take DELETE (x = V[k-1] + 1 is
                //   then the larger one anyway).
                boolean insert = (k == -d) || (k != d && v[offset + k - 1] < v[offset + k + 1]);

                int x;
                if (insert) {
                    x = v[offset + k + 1];       // INSERT: (x, y) -> (x, y + 1), x unchanged
                } else {
                    x = v[offset + k - 1] + 1;   // DELETE: (x, y) -> (x + 1, y), x grows by 1
                }
                // We are on diagonal k, and k = x - y, so y = x - k.
                int y = x - k;

                // ---- Step 2: follow the SNAKE ----
                // While both sequences still have items left (x < n, y < m) and the next items are
                // equal (a[x] == b[y]), take a free MATCH step: (x, y) -> (x + 1, y + 1).
                // x and y both grow, so we stay on the same diagonal k.
                while (x < n && y < m && a[x] == b[y]) {
                    x++;
                    y++;
                }

                // ---- Step 3: remember how far diagonal k got ----
                v[offset + k] = x;

                // ---- Reached the end? ----
                // (N, M) means all of a and all of b are used up. This is the first round that gets
                // there, so d is the minimal number of edits. Rounds 0..d-1 are already in the
                // trace; that is all buildScript needs.
                if (x >= n && y >= m) {
                    return trace;
                }
            }
            // Save V for this round, but only the diagonals this round actually used:
            // -d, -d+2, ..., d  (that is d + 1 values). round[(k + d) / 2] holds V[k].
            trace.add(saveRound(v, offset, d));
        }
        throw new IllegalStateException("unreachable: d = n + m always reaches (n, m)");
    }

    /** Copies V[-d], V[-d+2], ..., V[d] into a small array of d + 1 values. */
    private static int[] saveRound(int[] v, int offset, int d) {
        int[] round = new int[d + 1];
        for (int k = -d; k <= d; k += 2) {
            round[(k + d) / 2] = v[offset + k];
        }
        return round;
    }

    /** Reads V[k] from a saved round d (only diagonals -d, -d+2, ..., d were saved). */
    private static int savedV(int[] round, int d, int k) {
        return round[(k + d) / 2];
    }

    /**
     * BACKTRACKING: rebuilds the edit script from the trace, walking from (N, M) back to (0, 0).
     *
     * For each round d, from the last one down to 1, we ask: "on which diagonal was the path
     * before its d-th edit?" We answer with the SAME decision rule as the forward pass, applied
     * to the saved V of round d-1. Then we walk back over the snake (KEEP steps) and over the one
     * INSERT or DELETE that started it. The steps come out in reverse order, so we fill the
     * script from its end towards its start.
     */
    private static byte[] buildScript(int[] a, int[] b, List<int[]> trace) {
        int n = a.length;
        int m = b.length;
        byte[] steps = new byte[n + m];   // enough room: a path never has more than n + m steps
        int pos = steps.length;           // we write backwards: steps[--pos]

        int x = n;                        // start at the end point (N, M)
        int y = m;

        for (int d = trace.size(); d > 0; d--) {
            int[] previous = trace.get(d - 1);   // V after round d-1
            int k = x - y;                        // the diagonal we are on now

            // Same decision as in the forward pass: did the d-th edit come down from k+1 (INSERT)
            // or right from k-1 (DELETE)?
            boolean insert = (k == -d)
                    || (k != d && savedV(previous, d - 1, k - 1) < savedV(previous, d - 1, k + 1));
            int previousK = insert ? k + 1 : k - 1;
            int previousX = savedV(previous, d - 1, previousK);   // where that diagonal ended in round d-1
            int previousY = previousX - previousK;                // y = x - k again

            // The point right after the edit, where the snake of round d started:
            //   after an INSERT (down):  (previousX,     previousY + 1)
            //   after a DELETE (right):  (previousX + 1, previousY)
            int snakeStartX = insert ? previousX : previousX + 1;

            // Walk back over the snake: every diagonal step is a KEEP (MATCH).
            while (x > snakeStartX) {
                steps[--pos] = KEEP;
                x--;
                y--;
            }

            // Walk back over the edit itself.
            steps[--pos] = insert ? INSERT : DELETE;
            x = previousX;
            y = previousY;
        }

        // Round 0 had no edit, only its snake from (0, 0) along diagonal 0: all KEEP steps.
        while (x > 0 && y > 0) {
            steps[--pos] = KEEP;
            x--;
            y--;
        }
        return Arrays.copyOfRange(steps, pos, steps.length);
    }
}
