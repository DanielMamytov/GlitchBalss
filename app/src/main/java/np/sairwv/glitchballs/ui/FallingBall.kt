package np.sairwv.glitchballs.ui

data class FallingBall(
    var x: Float,
    var y: Float,
    val color: Int,
    var velX: Float,
    var velY: Float,
    var alpha: Int = 255
)