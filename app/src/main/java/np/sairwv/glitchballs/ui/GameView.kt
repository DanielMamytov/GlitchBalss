package np.sairwv.glitchballs.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import np.sairwv.glitchballs.R
import java.util.Random
import kotlin.math.*

class GameView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // Слушатели для связи с Fragment
    var onGameOverListener: ((Int) -> Unit)? = null
    var onScoreChanged: ((Int) -> Unit)? = null
    var onHighScoreChanged: ((Int) -> Unit)? = null

    private val fallingBalls = mutableListOf<FallingBall>()
    private var backgroundBitmap: Bitmap? = null
    private val backgroundRect = Rect()

    // Хранилище рекорда
    private val sharedPrefs = context.getSharedPreferences("GlitchBallsPrefs", Context.MODE_PRIVATE)
    private var highScore = sharedPrefs.getInt("high_score", 0)
    private var currentScore = 0

    private val gridManager = GridManager(26, 8)
    private var bubbleRadius = 0f
    private val deadLineRow = 15 // Линия проигрыша

    private var visualShiftY = 0f
    private var isAnimating = false
    private var shotCounter = 0

    private var isBallFlying = false
    private var ballX = 0f
    private var ballY = 0f
    private var ballVelX = 0f
    private var ballVelY = 0f

    private var flyingBallColor = 0
    private var currentInCannonColor = (1..4).random()
    private var nextBallColor = (1..4).random()

    private val ballSpeed = 55f
    private var cannonAngle = -90f
    private var cannonX = 0f
    private var cannonY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glitchColors = intArrayOf(
        0xFF00FF41.toInt(), 0xFFFF0055.toInt(), 0xFF00E5FF.toInt(), 0xFFFFE700.toInt()
    )
    private val dashPathEffect = DashPathEffect(floatArrayOf(30f, 20f), 0f)

    init {
        backgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_background)
        // Отправляем начальные данные в UI после инициализации
        post {
            onHighScoreChanged?.invoke(highScore)
            onScoreChanged?.invoke(0)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bubbleRadius = w.toFloat() / (gridManager.cols + 0.5f) / 2f
        cannonX = w / 2f
        cannonY = h - 200f
        backgroundRect.set(0, 0, w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isAnimating) return true
        val dx = event.x - cannonX
        val dy = event.y - cannonY
        cannonAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat().coerceIn(-170f, -10f)

        if (event.action == MotionEvent.ACTION_UP && !isBallFlying) {
            isBallFlying = true
            ballX = cannonX; ballY = cannonY
            flyingBallColor = currentInCannonColor
            currentInCannonColor = nextBallColor
            nextBallColor = (1..4).random()
            val rad = Math.toRadians(cannonAngle.toDouble())
            ballVelX = cos(rad).toFloat() * ballSpeed
            ballVelY = sin(rad).toFloat() * ballSpeed
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val rowHeight = bubbleRadius * 2 * 0.866f

        // Логика анимации сдвига сетки
        if (isAnimating) {
            visualShiftY += bubbleRadius * 0.25f
            if (visualShiftY >= rowHeight) {
                gridManager.shiftDown()
                visualShiftY = 0f
                isAnimating = false
                checkGameOver() // Проверка после сдвига
            }
            invalidate()
        }

        // Фон
        if (backgroundBitmap != null) canvas.drawBitmap(backgroundBitmap!!, null, backgroundRect, null)
        else canvas.drawColor(0xFF020202.toInt())

        if (!isBallFlying && !isAnimating) drawAim(canvas)
        drawFallingBalls(canvas)

        // Отрисовка сетки
        canvas.save()
        canvas.translate(0f, visualShiftY)
        drawGrid(canvas)
        canvas.restore()

        drawDeadLine(canvas, rowHeight)
        drawCannon(canvas)

        // Летящий шар
        if (isBallFlying) {
            updateBall()
            drawBubble(canvas, ballX, ballY, flyingBallColor)
            invalidate()
        }
    }

    private fun drawAim(canvas: Canvas) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = glitchColors[currentInCannonColor - 1]
        paint.alpha = 150; paint.strokeWidth = 8f; paint.style = Paint.Style.STROKE; paint.pathEffect = dashPathEffect
        val path = Path(); path.moveTo(cannonX, cannonY)
        var curX = cannonX; var curY = cannonY
        val rad = Math.toRadians(cannonAngle.toDouble())
        var vx = cos(rad).toFloat(); var vy = sin(rad).toFloat()
        val step = bubbleRadius * 0.5f

        for (i in 0..100) {
            curX += vx * step; curY += vy * step
            if (curX < bubbleRadius || curX > width - bubbleRadius) { vx *= -1; path.lineTo(curX, curY) }
            if (checkStaticCollision(curX, curY)) break
            if (curY < 0) break
        }
        path.lineTo(curX, curY)
        canvas.drawPath(path, paint)
    }

    private fun checkStaticCollision(x: Float, y: Float): Boolean {
        for (r in 0 until gridManager.rows) {
            for (c in 0 until gridManager.cols) {
                if (gridManager.cells[r][c] == 0) continue
                val p = getGridCoords(r, c)
                if (hypot(x - p.x, y - (p.y + visualShiftY)) < bubbleRadius * 1.5f) return true
            }
        }
        return false
    }

    private fun drawFallingBalls(canvas: Canvas) {
        val iterator = fallingBalls.iterator()
        while (iterator.hasNext()) {
            val ball = iterator.next()
            paint.reset(); paint.isAntiAlias = true; paint.color = ball.color; paint.alpha = ball.alpha
            canvas.drawCircle(ball.x, ball.y, bubbleRadius * 0.95f, paint)
            ball.x += ball.velX; ball.y += ball.velY; ball.velY += 1.5f; ball.alpha -= 8
            if (ball.alpha <= 0 || ball.y > height) iterator.remove() else invalidate()
        }
    }

    private fun snapToGrid() {
        isBallFlying = false
        var bestR = 0; var bestC = 0; var minDist = Float.MAX_VALUE
        for (r in 0 until gridManager.rows) {
            for (c in 0 until gridManager.cols) {
                if (gridManager.cells[r][c] != 0) continue
                val p = getGridCoords(r, c)
                val d = hypot(ballX - p.x, (ballY - visualShiftY) - p.y)
                if (d < minDist) { minDist = d; bestR = r; bestC = c }
            }
        }

        gridManager.cells[bestR][bestC] = flyingBallColor
        val matched = mutableSetOf<Point>()
        gridManager.findMatch(bestR, bestC, flyingBallColor, matched)

        if (matched.size >= 3) {
            currentScore += matched.size * 10

            // Проверка рекорда
            if (currentScore > highScore) {
                highScore = currentScore
                sharedPrefs.edit().putInt("high_score", highScore).apply()
                onHighScoreChanged?.invoke(highScore)
            }
            onScoreChanged?.invoke(currentScore)

            matched.forEach { p ->
                val coords = getGridCoords(p.y, p.x)
                fallingBalls.add(FallingBall(coords.x, coords.y + visualShiftY,
                    glitchColors[gridManager.cells[p.y][p.x] - 1], (Random().nextFloat() - 0.5f) * 15f, -10f))
            }
            gridManager.removeMatches(matched)

            val orphans = gridManager.getOrphans()
            orphans.forEach { p ->
                val coords = getGridCoords(p.y, p.x)
                fallingBalls.add(FallingBall(coords.x, coords.y + visualShiftY,
                    glitchColors[gridManager.cells[p.y][p.x] - 1], 0f, 5f))
                gridManager.cells[p.y][p.x] = 0
            }
        }

        shotCounter++
        if (shotCounter >= 3) {
            isAnimating = true
            shotCounter = 0
        } else {
            // Если анимации сдвига нет, проверяем проигрыш прямо сейчас
            checkGameOver()
        }
        invalidate()
    }

    private fun drawGrid(canvas: Canvas) {
        for (r in 0 until gridManager.rows) {
            for (c in 0 until gridManager.cols) {
                val color = gridManager.cells[r][c]
                if (color != 0) {
                    val p = getGridCoords(r, c)
                    drawBubble(canvas, p.x, p.y, color)
                }
            }
        }
    }

    private fun getGridCoords(r: Int, c: Int): PointF {
        val isOdd = (r + gridManager.topRowOffset) % 2 != 0
        val xOff = if (isOdd) bubbleRadius else 0f
        return PointF(c * bubbleRadius * 2 + bubbleRadius + xOff, r * (bubbleRadius * 2 * 0.866f) + bubbleRadius)
    }

    private fun updateBall() {
        ballX += ballVelX; ballY += ballVelY
        if (ballX < bubbleRadius || ballX > width - bubbleRadius) ballVelX *= -1
        if (checkCollision() || ballY < visualShiftY + bubbleRadius) snapToGrid()
    }

    private fun checkCollision(): Boolean {
        for (r in 0 until gridManager.rows) {
            for (c in 0 until gridManager.cols) {
                if (gridManager.cells[r][c] == 0) continue
                val p = getGridCoords(r, c)
                if (hypot(ballX - p.x, ballY - (p.y + visualShiftY)) < bubbleRadius * 1.6f) return true
            }
        }
        return false
    }

    private fun drawBubble(canvas: Canvas, x: Float, y: Float, type: Int) {
        if (type <= 0) return
        paint.reset(); paint.isAntiAlias = true; paint.color = glitchColors[type - 1]
        canvas.drawCircle(x, y, bubbleRadius * 0.95f, paint)
        paint.color = Color.WHITE; paint.alpha = 100
        canvas.drawCircle(x - bubbleRadius * 0.3f, y - bubbleRadius * 0.3f, bubbleRadius * 0.2f, paint)
    }

    private fun drawDeadLine(canvas: Canvas, rowHeight: Float) {
        paint.reset(); paint.isAntiAlias = true; paint.color = Color.RED; paint.strokeWidth = 12f
        val staticY = (deadLineRow + 1) * rowHeight
        paint.alpha = 150; canvas.drawLine(0f, staticY, width.toFloat(), staticY, paint)
        paint.alpha = 255; paint.textSize = 40f; paint.style = Paint.Style.FILL
        canvas.drawText("CRITICAL LIMIT", 40f, staticY - 20f, paint)
    }

    private fun drawCannon(canvas: Canvas) {
        canvas.save(); canvas.translate(cannonX, cannonY); canvas.rotate(cannonAngle + 90f)
        paint.reset(); paint.isAntiAlias = true; paint.color = Color.DKGRAY
        canvas.drawRect(-25f, -50f, 25f, 20f, paint)
        paint.color = glitchColors[currentInCannonColor - 1]
        canvas.drawCircle(0f, -15f, bubbleRadius * 0.85f, paint)
        canvas.restore()
    }

    private fun checkGameOver() {
        for (r in deadLineRow until gridManager.rows) {
            for (c in 0 until gridManager.cols) {
                if (gridManager.cells[r][c] != 0) {
                    isBallFlying = false
                    isAnimating = false
                    post { onGameOverListener?.invoke(currentScore) }
                    return
                }
            }
        }
    }

    fun restartGame() {
        gridManager.cells.forEach { it.fill(0) }
        gridManager.topRowOffset = 0
        gridManager.generateInitialLevel()
        currentScore = 0
        shotCounter = 0
        fallingBalls.clear()
        isBallFlying = false; isAnimating = false; visualShiftY = 0f
        currentInCannonColor = (1..4).random(); nextBallColor = (1..4).random()

        onScoreChanged?.invoke(currentScore)
        onHighScoreChanged?.invoke(highScore)

        invalidate()
    }
}