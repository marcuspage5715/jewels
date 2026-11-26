package com.marcuspage.jewels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The playfield and the Columns rules: matches in three directions,
 * jewel removal, gravity and the chain reaction that follows.
 */
public class Board {

    public static final int COLS = 6;
    public static final int ROWS = 13;

    /** 0 is empty, 1..6 are jewel colours. */
    public final int[][] cells = new int[ROWS][COLS];
    /** Marks the single target jewel of a Flash round. Moves with the jewel. */
    public final boolean[][] flash = new boolean[ROWS][COLS];

    private final boolean[][] marked = new boolean[ROWS][COLS];

    public static class ClearResult {
        public int count;
        public boolean flashCleared;
        public int chains;
        /** {row, col, colour} for each jewel removed, in removal order. */
        public final List<int[]> cells = new ArrayList<>();
    }

    public void clear() {
        for (int r = 0; r < ROWS; r++) {
            Arrays.fill(cells[r], 0);
            Arrays.fill(flash[r], false);
        }
    }

    /** Can a three-jewel column stand with its top jewel at (x, y)? */
    public boolean canPlace(int x, int y) {
        if (x < 0 || x >= COLS) return false;
        for (int i = 0; i < 3; i++) {
            int r = y + i;
            if (r < 0) continue;
            if (r >= ROWS) return false;
            if (cells[r][x] != 0) return false;
        }
        return true;
    }

    public void lock(int x, int y, int[] colors) {
        for (int i = 0; i < 3; i++) {
            int r = y + i;
            if (r >= 0 && r < ROWS && x >= 0 && x < COLS) {
                cells[r][x] = colors[i];
            }
        }
    }

    /** Flags every jewel that is part of a run of three or more. */
    public boolean markMatches() {
        boolean any = false;
        for (int r = 0; r < ROWS; r++) Arrays.fill(marked[r], false);

        // Horizontal runs.
        for (int r = 0; r < ROWS; r++) {
            int c = 0;
            while (c < COLS) {
                int v = cells[r][c];
                if (v == 0) { c++; continue; }
                int e = c;
                while (e + 1 < COLS && cells[r][e + 1] == v) e++;
                if (e - c + 1 >= 3) {
                    for (int k = c; k <= e; k++) { marked[r][k] = true; any = true; }
                }
                c = e + 1;
            }
        }

        // Vertical runs.
        for (int c = 0; c < COLS; c++) {
            int r = 0;
            while (r < ROWS) {
                int v = cells[r][c];
                if (v == 0) { r++; continue; }
                int e = r;
                while (e + 1 < ROWS && cells[e + 1][c] == v) e++;
                if (e - r + 1 >= 3) {
                    for (int k = r; k <= e; k++) { marked[k][c] = true; any = true; }
                }
                r = e + 1;
            }
        }

        // Diagonal runs, down and to the right.
        for (int r0 = 0; r0 < ROWS; r0++) {
            for (int c0 = 0; c0 < COLS; c0++) {
                if (r0 > 0 && c0 > 0) continue; // only start at the top row or left column
                int r = r0, c = c0;
                while (r < ROWS && c < COLS) {
                    int v = cells[r][c];
                    if (v == 0) { r++; c++; continue; }
                    int len = 1;
                    while (r + len < ROWS && c + len < COLS && cells[r + len][c + len] == v) len++;
                    if (len >= 3) {
                        for (int k = 0; k < len; k++) { marked[r + k][c + k] = true; any = true; }
                    }
                    r += len; c += len;
                }
            }
        }

        // Diagonal runs, down and to the left.
        for (int r0 = 0; r0 < ROWS; r0++) {
            for (int c0 = 0; c0 < COLS; c0++) {
                if (r0 > 0 && c0 < COLS - 1) continue; // only start at the top row or right column
                int r = r0, c = c0;
                while (r < ROWS && c >= 0) {
                    int v = cells[r][c];
                    if (v == 0) { r++; c--; continue; }
                    int len = 1;
                    while (r + len < ROWS && c - len >= 0 && cells[r + len][c - len] == v) len++;
                    if (len >= 3) {
                        for (int k = 0; k < len; k++) { marked[r + k][c - k] = true; any = true; }
                    }
                    r += len; c -= len;
                }
            }
        }
        return any;
    }

    /** Jewels above a gap fall into it. The target jewel keeps its flag. */
    public void applyGravity() {
        for (int c = 0; c < COLS; c++) {
            int write = ROWS - 1;
            for (int r = ROWS - 1; r >= 0; r--) {
                if (cells[r][c] != 0) {
                    if (r != write) {
                        cells[write][c] = cells[r][c];
                        flash[write][c] = flash[r][c];
                        cells[r][c] = 0;
                        flash[r][c] = false;
                    }
                    write--;
                }
            }
        }
    }

    /** Clears every match, lets jewels fall, and repeats while chains keep forming. */
    public ClearResult resolve() {
        ClearResult res = new ClearResult();
        while (markMatches()) {
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    if (!marked[r][c]) continue;
                    res.cells.add(new int[]{r, c, cells[r][c]});
                    if (flash[r][c]) res.flashCleared = true;
                    cells[r][c] = 0;
                    flash[r][c] = false;
                    res.count++;
                }
            }
            applyGravity();
            res.chains++;
        }
        return res;
    }

    /** Builds the starting stack for a Flash round, leaving no ready-made matches. */
    public void generateStack(int height, int colorCount, Random rnd) {
        clear();
        int top = Math.max(0, ROWS - height);
        for (int r = ROWS - 1; r >= top; r--) {
            for (int c = 0; c < COLS; c++) {
                int v = 0;
                for (int attempt = 0; attempt < 40; attempt++) {
                    int candidate = 1 + rnd.nextInt(colorCount);
                    if (!wouldMatch(r, c, candidate)) { v = candidate; break; }
                }
                if (v == 0) v = 1 + rnd.nextInt(colorCount);
                cells[r][c] = v;
            }
        }
        resolve();
    }

    private boolean wouldMatch(int r, int c, int v) {
        if (r + 2 < ROWS && cells[r + 1][c] == v && cells[r + 2][c] == v) return true;
        if (c - 2 >= 0 && cells[r][c - 1] == v && cells[r][c - 2] == v) return true;
        if (r + 2 < ROWS && c - 2 >= 0 && cells[r + 1][c - 1] == v && cells[r + 2][c - 2] == v) return true;
        if (r + 2 < ROWS && c + 2 < COLS && cells[r + 1][c + 1] == v && cells[r + 2][c + 2] == v) return true;
        return false;
    }

    /**
     * Drops the target jewel into the bottom row without creating a match.
     * Every legal (column, colour) pair is collected first and one is then
     * picked at random, so the target moves along the bottom row and changes
     * colour between rounds instead of always landing in the same corner.
     * Pairs that differ from the previous round's column and colour are
     * preferred whenever such a pair exists.
     *
     * @return the colour placed, or 0 if the bottom row could not take one
     */
    public int placeFlashJewel(int colorCount, Random rnd, int previousColor, int previousColumn) {
        List<int[]> all = new ArrayList<>();
        List<int[]> otherColor = new ArrayList<>();
        List<int[]> otherBoth = new ArrayList<>();

        for (int c = 0; c < COLS; c++) {
            int original = cells[ROWS - 1][c];
            for (int v = 1; v <= colorCount; v++) {
                cells[ROWS - 1][c] = v;
                boolean legal = !markMatches();
                cells[ROWS - 1][c] = original; // restore, so testing never eats a jewel
                if (!legal) continue;

                int[] candidate = new int[]{c, v};
                all.add(candidate);
                if (v != previousColor) otherColor.add(candidate);
                if (v != previousColor && c != previousColumn) otherBoth.add(candidate);
            }
        }

        List<int[]> pool = !otherBoth.isEmpty() ? otherBoth
                : !otherColor.isEmpty() ? otherColor
                : all;
        if (pool.isEmpty()) return 0;

        int[] pick = pool.get(rnd.nextInt(pool.size()));
        cells[ROWS - 1][pick[0]] = pick[1];
        flash[ROWS - 1][pick[0]] = true;
        return pick[1];
    }

    /** Column holding the target jewel, or -1 if there is none. */
    public int targetColumn() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (flash[r][c]) return c;
            }
        }
        return -1;
    }

    /** Height of the tallest column, used for the overflow check. */
    public int stackHeight() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (cells[r][c] != 0) return ROWS - r;
            }
        }
        return 0;
    }
}
