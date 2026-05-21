package np.sairwv.glitchballs.ui.activity

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import np.sairwv.glitchballs.R

class GameActivity : AppCompatActivity(R.layout.activity_game) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }
}
