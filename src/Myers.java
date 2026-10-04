import java.util.Arrays;

/**
 * Myers' O(ND) diff algorithm (Eugene W. Myers, 1986), written from scratch.
 *
 * It works on two int arrays, so the same code diffs
 *   - lines      (Part A: each distinct line is given an integer id), and
 *   - characters (Part B: each Unicode code point is already an int).
 *
 * EDIT GRAPH. Comparing a (length n) with b (length m) means walking a grid from (0,0) to (n,m):
 *   step right  (x,y) -> (x+1,y)    delete a[x]
 *   step down   (x,y) -> (x,y+1)    insert b[y]
 *   diagonal    (x,y) -> (x+1,y+1)  free, only allowed when a[x] == b[y]   (a run of these = a "snake")
 * The fewest right+down steps (D) is the minimal diff. Diagonal number k = x - y.
 *
 * THE GREEDY IDEA (T2). For D = 0, 1, 2, ... keep V[k] = the furthest x reachable on diagonal k
 * with D edits. A D-path on diagonal k comes from diagonal k+1 (step down) or k-1 (step right),
 * whichever got further, and then follows its snake. The first D that reaches (n,m) is minimal.
 *
 * LINEAR SPACE (section 4b of the paper). Remembering every V to rebuild the path needs O(D^2)
 * memory, too much for the 500,000-line tests. So we run the SAME greedy loop from both ends at
 * once: forwards from the top-left corner and backwards from the bottom-right corner. When the
 * two meet on a diagonal, that point lies on a shortest path (the "middle snake"). We split the
 * problem there and solve each half the same way. Time stays O((n+m)·D), memory is O(n+m).
 * git uses this same approach.
 */
final class Myers {

    static final byte KEEP = 0;
    static final byte DELETE = 1;
    static final byte INSERT = 2;

    private final int[] a;
    private final int[] b;
    private final int[] forwardV;  // forwardV[k + offset]  = furthest x on diagonal k, searching forwards
    private final int[] backwardV; // backwardV[k + offset] = smallest x on diagonal k, searching backwards
    private final int offset;      // k ranges over -b.length .. a.length, so shift it to a valid index
    private final byte[] script;   // the edit script being built: KEEP / DELETE / INSERT
    private int length;

    private Myers(int[] a, int[] b) {
        this.a = a;
        this.b = b;
        this.forwardV = new int[a.length + b.length + 3];   // +3: one spare slot each side for sentinels
        this.backwardV = new int[a.length + b.length + 3];
        this.offset = b.length + 1;
        this.script = new byte[a.length + b.length];
    }

    /**
     * Returns a minimal edit script turning a into b: one byte per step. Walking it, KEEP moves
     * on in both a and b, DELETE moves on in a, INSERT moves on in b.
     */
    static byte[] diff(int[] a, int[] b) {
        Myers m = new Myers(a, b);
        m.compare(0, a.length, 0, b.length);
        return Arrays.copyOf(m.script, m.length);
    }

    /** Appends a minimal script for a[aLo..aHi) versus b[bLo..bHi). */
    private void compare(int aLo, int aHi, int bLo, int bHi) {
        // Matching items at the start or end can always be kept: strip them.
        int prefix = 0;
        while (aLo < aHi && bLo < bHi && a[aLo] == b[bLo]) {
            aLo++;
            bLo++;
            prefix++;
        }
        int suffix = 0;
        while (aLo < aHi && bLo < bHi && a[aHi - 1] == b[bHi - 1]) {
            aHi--;
            bHi--;
            suffix++;
        }

        add(KEEP, prefix);
        if (aLo == aHi) {
            add(INSERT, bHi - bLo);            // nothing left of a: insert the rest of b
        } else if (bLo == bHi) {
            add(DELETE, aHi - aLo);            // nothing left of b: delete the rest of a
        } else {
            int[] mid = middleSnake(aLo, aHi, bLo, bHi);
            compare(aLo, mid[0], bLo, mid[1]); // top-left half
            compare(mid[0], aHi, mid[1], bHi); // bottom-right half
        }
        add(KEEP, suffix);
    }

    /**
     * Returns a point (x, y) on a shortest path through the box [aLo,aHi] x [bLo,bHi], found by
     * running the greedy search forwards and backwards in turns until they overlap.
     *
     * delta = (diagonal of the top-left corner) - (diagonal of the bottom-right corner).
     * If delta is odd, D is odd and the searches first meet during a forward round;
     * if delta is even, they first meet during a backward round.
     */
    private int[] middleSnake(int aLo, int aHi, int bLo, int bHi) {
        final int minK = aLo - bHi;            // lowest diagonal inside the box
        final int maxK = aHi - bLo;            // highest diagonal inside the box
        final int forwardStart = aLo - bLo;    // diagonal of the top-left corner
        final int backwardStart = aHi - bHi;   // diagonal of the bottom-right corner
        final boolean oddDelta = ((forwardStart - backwardStart) & 1) != 0;

        int fMin = forwardStart, fMax = forwardStart;   // diagonals the forward search has reached
        int bMin = backwardStart, bMax = backwardStart; // diagonals the backward search has reached
        forwardV[offset + forwardStart] = aLo;
        backwardV[offset + backwardStart] = aHi;

        for (int d = 1; ; d++) {
            // ---------- Forward round d: one more edit, moving right/down from (aLo,bLo) ----------
            // Widen the diagonal range by one on each side if it stays inside the box. The new
            // outer neighbour gets the sentinel -1 so it is never chosen as "further".
            if (fMin > minK) {
                forwardV[offset + --fMin - 1] = -1;
            } else {
                fMin++;
            }
            if (fMax < maxK) {
                forwardV[offset + ++fMax + 1] = -1;
            } else {
                fMax--;
            }
            for (int k = fMax; k >= fMin; k -= 2) {
                int fromLeft = forwardV[offset + k - 1];   // furthest x on diagonal k-1
                int fromAbove = forwardV[offset + k + 1];  // furthest x on diagonal k+1
                int x;
                if (fromLeft >= fromAbove) {
                    x = fromLeft + 1;                      // step right: delete a[x]
                } else {
                    x = fromAbove;                         // step down: insert b[y]
                }
                int y = x - k;
                while (x < aHi && y < bHi && a[x] == b[y]) {   // follow the snake
                    x++;
                    y++;
                }
                forwardV[offset + k] = x;
                // Overlap: the backward search already reached this diagonal at x or before.
                if (oddDelta && k >= bMin && k <= bMax && backwardV[offset + k] <= x) {
                    return new int[] {x, y};
                }
            }

            // ---------- Backward round d: the mirror image, moving left/up from (aHi,bHi) ----------
            if (bMin > minK) {
                backwardV[offset + --bMin - 1] = Integer.MAX_VALUE;
            } else {
                bMin++;
            }
            if (bMax < maxK) {
                backwardV[offset + ++bMax + 1] = Integer.MAX_VALUE;
            } else {
                bMax--;
            }
            for (int k = bMax; k >= bMin; k -= 2) {
                int fromBelow = backwardV[offset + k - 1];  // smallest x on diagonal k-1
                int fromRight = backwardV[offset + k + 1];  // smallest x on diagonal k+1
                int x;
                if (fromBelow < fromRight) {
                    x = fromBelow;                          // step up (an insertion, walked backwards)
                } else {
                    x = fromRight - 1;                      // step left (a deletion, walked backwards)
                }
                int y = x - k;
                while (x > aLo && y > bLo && a[x - 1] == b[y - 1]) {  // follow the snake backwards
                    x--;
                    y--;
                }
                backwardV[offset + k] = x;
                if (!oddDelta && k >= fMin && k <= fMax && x <= forwardV[offset + k]) {
                    return new int[] {x, y};
                }
            }
        }
    }

    private void add(byte op, int count) {
        for (int i = 0; i < count; i++) {
            script[length++] = op;
        }
    }
}
