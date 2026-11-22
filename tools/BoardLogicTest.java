import com.marcuspage.jewels.Board;
import java.util.Random;

public class BoardLogicTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; }
        else { fail++; System.out.println("FAIL: " + name); }
    }

    static Board fresh() { return new Board(); }

    public static void main(String[] args) {
        // 1. horizontal run of three
        Board b = fresh();
        b.cells[12][0] = 1; b.cells[12][1] = 1; b.cells[12][2] = 1;
        check("horizontal clears 3", b.resolve().count == 3);

        // 2. vertical run
        b = fresh();
        b.cells[10][3] = 2; b.cells[11][3] = 2; b.cells[12][3] = 2;
        check("vertical clears 3", b.resolve().count == 3);

        // 3. diagonal down-right
        b = fresh();
        b.cells[10][0] = 3; b.cells[11][1] = 3; b.cells[12][2] = 3;
        check("diagonal right clears 3", b.resolve().count == 3);

        // 4. diagonal down-left
        b = fresh();
        b.cells[10][5] = 4; b.cells[11][4] = 4; b.cells[12][3] = 4;
        check("diagonal left clears 3", b.resolve().count == 3);

        // 5. four in a row clears four
        b = fresh();
        for (int c = 0; c < 4; c++) b.cells[12][c] = 5;
        check("run of four clears 4", b.resolve().count == 4);

        // 6. two separate runs both clear
        b = fresh();
        b.cells[12][0] = 1; b.cells[12][1] = 1; b.cells[12][2] = 1;
        b.cells[11][3] = 2; b.cells[11][4] = 2; b.cells[11][5] = 2;
        check("two runs clear 6", b.resolve().count == 6);

        // 7. only two in a row is not a match
        b = fresh();
        b.cells[12][0] = 1; b.cells[12][1] = 1;
        check("pair is not a match", b.resolve().count == 0);

        // 8. gravity drops jewels into the gap
        b = fresh();
        b.cells[12][0] = 1; b.cells[12][1] = 1; b.cells[12][2] = 1; // clears bottom row
        b.cells[10][1] = 6;                                        // jewel above the gap
        b.resolve();
        check("gravity: jewel fell to bottom", b.cells[12][1] == 6 && b.cells[10][1] == 0);

        // 9. chain reaction: jewels fall and complete a second match
        b = fresh();
        b.cells[10][0] = 1; b.cells[11][0] = 1; b.cells[12][0] = 1;  // vertical 1s clear
        b.cells[8][0] = 3; b.cells[9][0] = 3;                        // fall to rows 11 and 12
        b.cells[12][1] = 3; b.cells[12][2] = 3;                      // complete the row of 3s
        Board.ClearResult r = b.resolve();
        check("chain clears 6 over 2 steps", r.count == 6 && r.chains == 2);

        // 10. target jewel cleared is reported
        b = fresh();
        b.cells[12][0] = 1; b.cells[12][1] = 1; b.cells[12][2] = 1;
        b.flash[12][1] = true;
        check("flash cleared reported", b.resolve().flashCleared);

        // 11. clearing a different match leaves the target standing
        b = fresh();
        b.cells[12][0] = 1; b.cells[12][1] = 1; b.cells[12][2] = 1;
        b.cells[12][5] = 3; b.flash[12][5] = true;
        check("other match leaves target", !b.resolve().flashCleared && b.flash[12][5]);

        // 12. the flash flag follows the jewel as it falls
        b = fresh();
        b.cells[9][0] = 4; b.flash[9][0] = true;
        b.applyGravity();
        check("flash flag moves with jewel", b.flash[12][0] && b.cells[12][0] == 4 && !b.flash[9][0]);

        // 13. generated stacks never start with a match
        boolean clean = true;
        Random rnd = new Random(7);
        for (int t = 0; t < 200; t++) {
            b = fresh();
            b.generateStack(3 + rnd.nextInt(6), 4 + rnd.nextInt(3), rnd);
            if (b.resolve().count != 0) clean = false;
        }
        check("generated stacks are match-free", clean);

        // 14. exactly one target jewel, never part of a match
        boolean oneFlash = true;
        for (int t = 0; t < 200; t++) {
            b = fresh();
            b.generateStack(5, 5, rnd);
            b.placeFlashJewel(5, rnd);
            int n = 0;
            for (int rr = 0; rr < Board.ROWS; rr++)
                for (int cc = 0; cc < Board.COLS; cc++) if (b.flash[rr][cc]) n++;
            if (n != 1) oneFlash = false;
            if (b.markMatches()) oneFlash = false;
            if (b.cells[Board.ROWS - 1][0] == 0 && b.cells[Board.ROWS-1][1]==0) { /* ignore empties */ }
        }
        check("one target jewel, no match", oneFlash);

        // 15. the target sits in the bottom row
        b = fresh();
        b.generateStack(4, 5, rnd);
        b.placeFlashJewel(5, rnd);
        boolean bottom = false;
        for (int c = 0; c < Board.COLS; c++) if (b.flash[Board.ROWS - 1][c]) bottom = true;
        check("target is in the bottom row", bottom);

        // 16. piece placement boundaries
        b = fresh();
        check("piece fits at top", b.canPlace(2, 0));
        check("piece cannot hang past the bottom", !b.canPlace(2, Board.ROWS - 2));
        check("piece fits flush at the bottom", b.canPlace(2, Board.ROWS - 3));
        check("piece cannot leave the well", !b.canPlace(-1, 0) && !b.canPlace(Board.COLS, 0));

        // 17. locking and stacking
        b = fresh();
        b.lock(0, 0, new int[]{1, 2, 3});
        check("lock writes three jewels", b.cells[0][0] == 1 && b.cells[1][0] == 2 && b.cells[2][0] == 3);
        check("occupied cell blocks placement", !b.canPlace(0, 1));

        // 18. stack height reporting
        b = fresh();
        b.cells[Board.ROWS - 1][0] = 1;
        b.cells[Board.ROWS - 2][0] = 1;
        check("stack height counts columns", b.stackHeight() == 2);

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
