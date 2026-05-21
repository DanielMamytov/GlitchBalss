package np.sairwv.glitchballs.ui.fragment

import android.app.Dialog
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import np.sairwv.glitchballs.R
import np.sairwv.glitchballs.databinding.FragmentGameBinding
import np.sairwv.glitchballs.databinding.DialogGameOverBinding // Убедись, что импорт есть

class GameFragment : Fragment(R.layout.fragment_game) {

    private val binding by viewBinding(FragmentGameBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        binding.gameView.onScoreChanged = { totalScore ->
            binding.tvScore.text = "Score: $totalScore"
        }

        binding.gameView.onHighScoreChanged = { highScore ->
            binding.tvRecorc.text = "Best: $highScore"
        }

        binding.gameView.onGameOverListener = { finalScore ->
            if (isAdded && !requireActivity().isFinishing) {
                showGameOverDialog(finalScore)
            }
        }
    }

    private fun showGameOverDialog(score: Int) {
        val dialog = Dialog(requireContext())

        val dialogBinding = DialogGameOverBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.tvScore.text = "SCORE: $score"
        dialogBinding.btnRestart.setOnClickListener {
            binding.gameView.restartGame()
            dialog.dismiss()
        }

        dialog.show()
    }
}