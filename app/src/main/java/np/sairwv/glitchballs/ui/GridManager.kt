package np.sairwv.glitchballs.ui

import android.graphics.Point
import kotlin.random.Random

class GridManager(val rows: Int, val cols: Int) {

    val cells = Array(rows) { IntArray(cols) { 0 } }
    var topRowOffset = 0

    init {
        generateInitialLevel()
    }

    fun getOrphans(): List<Point> {
        val connected = mutableSetOf<Point>()

        for (c in 0 until cols) {
            if (cells[0][c] != 0) {
                traverseConnection(0, c, connected)
            }
        }

        val orphans = mutableListOf<Point>()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val p = Point(c, r)
                if (cells[r][c] != 0 && p !in connected) {
                    orphans.add(p)
                }
            }
        }
        return orphans
    }
    fun generateInitialLevel() {
        for (r in 0 until 8) {
            for (c in 0 until cols) {
                if (Random.nextFloat() > 0.1f) {
                    cells[r][c] = (1..4).random()
                }
            }
        }
    }

    fun shiftDown() {
        for (r in rows - 1 downTo 1) {
            for (c in 0 until cols) {
                cells[r][c] = cells[r - 1][c]
            }
        }
        for (c in 0 until cols) {
            cells[0][c] = (1..4).random()
        }
        topRowOffset = 1 - topRowOffset
    }

    fun getNeighbors(row: Int, col: Int): List<Point> {
        val neighbors = mutableListOf<Point>()
        val isOdd = (row + topRowOffset) % 2 != 0

        val directions = if (!isOdd) {
            arrayOf(Pair(0, -1), Pair(0, 1), Pair(-1, -1), Pair(-1, 0), Pair(1, -1), Pair(1, 0))
        } else {
            arrayOf(Pair(0, -1), Pair(0, 1), Pair(-1, 0), Pair(-1, 1), Pair(1, 0), Pair(1, 1))
        }

        for (d in directions) {
            val nr = row + d.first
            val nc = col + d.second
            if (nr in 0 until rows && nc in 0 until cols) {
                neighbors.add(Point(nc, nr))
            }
        }
        return neighbors
    }

    fun findMatch(row: Int, col: Int, targetColor: Int, matched: MutableSet<Point>) {
        val p = Point(col, row)
        if (p in matched || cells[row][col] != targetColor || targetColor == 0) return

        matched.add(p)
        for (n in getNeighbors(row, col)) {
            findMatch(n.y, n.x, targetColor, matched)
        }
    }

    fun removeMatches(matches: Set<Point>) {
        for (p in matches) {
            cells[p.y][p.x] = 0
        }
    }

    private fun traverseConnection(row: Int, col: Int, visited: MutableSet<Point>) {
        val p = Point(col, row)
        if (p in visited || cells[row][col] == 0) return
        visited.add(p)
        for (n in getNeighbors(row, col)) {
            traverseConnection(n.y, n.x, visited)
        }
    }
}