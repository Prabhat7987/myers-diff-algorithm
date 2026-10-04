import java.util.*;

public class MyersDiff {

    static class Edit {
        String type;
        Character value;

        Edit(String type, Character value) {
            this.type = type;
            this.value = value;
        }

        @Override
        public String toString() {
            if (type.equals("MATCH")) {
                return "  " + value;
            } else if (type.equals("INSERT")) {
                return "+ " + value;
            } else {
                return "- " + value;
            }
        }
    }

    public static List<Edit> diff(String a, String b) {

        int n = a.length();
        int m = b.length();
        int max = n + m;

        /*
         * V[k] = furthest x reached on diagonal k
         *
         * HashMap is used because k can be negative.
         */
        Map<Integer, Integer> v = new HashMap<>();

        // Initial position
        v.put(1, 0);

        // Save V for every edit distance D
        List<Map<Integer, Integer>> trace = new ArrayList<>();

        int finalD = -1;

        // D = number of insert/delete operations
        for (int d = 0; d <= max; d++) {

            boolean reachedEnd = false;

            /*
             * Possible diagonals for this D:
             * -d, -d+2, ..., d-2, d
             */
            for (int k = -d; k <= d; k += 2) {

                int x;

                /*
                 * Decide:
                 * INSERT or DELETE
                 */
                if (k == -d ||
                    (k != d &&
                     v.getOrDefault(k - 1, Integer.MIN_VALUE)
                       < v.getOrDefault(k + 1, Integer.MIN_VALUE))) {

                    // INSERT
                    x = v.getOrDefault(k + 1, 0);

                } else {

                    // DELETE
                    x = v.getOrDefault(k - 1, 0) + 1;
                }

                int y = x - k;

                /*
                 * Snake:
                 * Keep moving diagonally while characters match.
                 */
                while (x < n &&
                       y < m &&
                       a.charAt(x) == b.charAt(y)) {

                    x++;
                    y++;
                }

                // Save furthest x for this diagonal
                v.put(k, x);

                // Did we reach the end?
                if (x >= n && y >= m) {
                    reachedEnd = true;
                    finalD = d;
                    break;
                }
            }

            // Save V for this D
            trace.add(new HashMap<>(v));

            if (reachedEnd) {
                break;
            }
        }

        /*
         * Reconstruct the shortest edit script.
         */
        List<Edit> result = new ArrayList<>();

        int x = n;
        int y = m;

        for (int d = finalD; d > 0; d--) {

            // V from previous edit distance
            Map<Integer, Integer> previous = trace.get(d - 1);

            int k = x - y;

            int previousK;

            /*
             * Decide whether current position came from:
             * INSERT or DELETE
             */
            if (k == -d ||
                (k != d &&
                 previous.getOrDefault(k - 1, Integer.MIN_VALUE)
                   < previous.getOrDefault(k + 1, Integer.MIN_VALUE))) {

                // INSERT
                previousK = k + 1;

            } else {

                // DELETE
                previousK = k - 1;
            }

            int previousX =
                    previous.getOrDefault(previousK, 0);

            int previousY =
                    previousX - previousK;

            /*
             * Walk backwards through the snake.
             *
             * Every diagonal step is a MATCH.
             */
            while (x > previousX && y > previousY) {

                result.add(
                    new Edit(
                        "MATCH",
                        a.charAt(x - 1)
                    )
                );

                x--;
                y--;
            }

            /*
             * Now one actual edit remains.
             */
            if (x == previousX) {

                // INSERT
                result.add(
                    new Edit(
                        "INSERT",
                        b.charAt(y - 1)
                    )
                );

                y--;

            } else {

                // DELETE
                result.add(
                    new Edit(
                        "DELETE",
                        a.charAt(x - 1)
                    )
                );

                x--;
            }
        }

        /*
         * Any remaining diagonal moves are matches.
         */
        while (x > 0 && y > 0) {

            result.add(
                new Edit(
                    "MATCH",
                    a.charAt(x - 1)
                )
            );

            x--;
            y--;
        }

        // We built result backwards
        Collections.reverse(result);

        return result;
    }

    public static void main(String[] args) {

        String oldText = "ABC";
        String newText = "ABDC";

        List<Edit> edits = diff(oldText, newText);

        for (Edit edit : edits) {
            System.out.println(edit);
        }
    }
}
