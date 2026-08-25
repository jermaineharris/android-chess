package com.huttsmedia.chess

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ChessViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        ChessUiState(gameStarted = savedStateHandle["gameStarted"] ?: prefs.getBoolean(KEY_STARTED, false))
    )
    val uiState: StateFlow<ChessUiState> = _uiState.asStateFlow()

    private var gameGeneration = 0
    private var aiJob: Job? = null

    init {
        if (_uiState.value.gameStarted) {
            restoreExistingGame()
        }
    }

    fun lastAiColor(): PieceColor =
        if (prefs.getBoolean(KEY_PLAY_WHITE, true)) PieceColor.WHITE else PieceColor.BLACK

    fun lastAiDifficulty(): AiDifficulty {
        val stored = prefs.getInt(KEY_DIFFICULTY, 1)
        return AiDifficulty.entries.find { it.nativeValue == stored } ?: AiDifficulty.INTERMEDIATE
    }

    fun onNewGame(gameMode: GameMode) {
        cancelAi()
        gameGeneration += 1
        savedStateHandle["gameStarted"] = true
        prefs.edit().putBoolean(KEY_STARTED, true).apply()
        rememberMode(gameMode)
        val vsAi = gameMode is GameMode.VsAI
        val playAsWhite = when (gameMode) {
            is GameMode.VsAI -> gameMode.playerColor == PieceColor.WHITE
            GameMode.TwoPlayer -> true
        }
        val difficulty = when (gameMode) {
            is GameMode.VsAI -> gameMode.difficulty.nativeValue
            GameMode.TwoPlayer -> 1
        }
        applyState(ChessNative.newGame(vsAi, playAsWhite, difficulty), gameMode, thinking = false)
        maybeRequestAi()
    }

    fun onSquareClick(position: Position) {
        if (!_uiState.value.gameStarted || _uiState.value.isAiThinking) return
        val mode = _uiState.value.gameMode
        applyState(ChessNative.onSquareClick(position.row, position.col), mode, thinking = false)
        maybeRequestAi()
    }

    fun onPromote(pieceType: PieceType) {
        if (!_uiState.value.gameStarted || _uiState.value.isAiThinking) return
        val mode = _uiState.value.gameMode
        applyState(ChessNative.promote(pieceType.name), mode, thinking = false)
        maybeRequestAi()
    }

    fun onUndo() {
        if (!_uiState.value.gameStarted) return
        cancelAi()
        val mode = _uiState.value.gameMode
        applyState(ChessNative.undo(), mode, thinking = false)
        maybeRequestAi()
    }

    fun onDeselect() {
        if (!_uiState.value.gameStarted || _uiState.value.selectedPiece == null) return
        applyState(ChessNative.deselect(), _uiState.value.gameMode, thinking = _uiState.value.isAiThinking)
    }

    fun onToggleFlip() {
        if (!_uiState.value.gameStarted) return
        applyState(ChessNative.toggleFlip(), _uiState.value.gameMode, thinking = _uiState.value.isAiThinking)
    }

    fun onResign() {
        if (!_uiState.value.gameStarted || _uiState.value.gameOver || _uiState.value.isAiThinking) return
        cancelAi()
        applyState(ChessNative.resign(), _uiState.value.gameMode, thinking = false)
    }

    private fun storedMode(): GameMode {
        val save = prefs.getString(KEY_SAVE, null) ?: return GameMode.TwoPlayer
        return runCatching { parseSavedGameMode(save) }.getOrDefault(GameMode.TwoPlayer)
    }

    private fun restoreExistingGame() {
        val mode = storedMode()
        val native = runCatching { ChessNative.getState() }.getOrElse {
            clearStarted()
            return
        }
        val nativeObj = runCatching { JSONObject(native) }.getOrNull()
        if (nativeObj != null && !nativeObj.has("error")) {
            applyState(native, mode, thinking = false)
            maybeRequestAi()
            return
        }
        val save = prefs.getString(KEY_SAVE, null)
        if (save.isNullOrBlank()) {
            clearStarted()
            return
        }
        val restored = runCatching { ChessNative.importSave(save) }.getOrElse {
            clearStarted()
            return
        }
        if (runCatching { JSONObject(restored).has("error") }.getOrDefault(true)) {
            clearStarted()
            return
        }
        applyState(restored, runCatching { parseSavedGameMode(save) }.getOrDefault(mode), thinking = false)
        maybeRequestAi()
    }

    private fun clearStarted() {
        savedStateHandle["gameStarted"] = false
        prefs.edit().putBoolean(KEY_STARTED, false).apply()
        _uiState.value = ChessUiState()
    }

    private fun maybeRequestAi() {
        val state = _uiState.value
        val mode = state.gameMode as? GameMode.VsAI ?: return
        if (state.promotionPending || state.gameOver) return
        if (state.turn == mode.playerColor) return
        val gen = gameGeneration
        _uiState.value = state.copy(isAiThinking = true)
        aiJob = viewModelScope.launch {
            delay(350)
            if (gen != gameGeneration) return@launch
            val json = withContext(Dispatchers.Default) { ChessNative.aiMove() }
            if (gen != gameGeneration) return@launch
            applyState(json, mode, thinking = false)
        }
    }

    private fun cancelAi() {
        gameGeneration += 1
        aiJob?.cancel()
        aiJob = null
    }

    private fun applyState(json: String, gameMode: GameMode, thinking: Boolean) {
        val started = savedStateHandle["gameStarted"] ?: prefs.getBoolean(KEY_STARTED, false)
        _uiState.value = parseChessState(json, gameMode, started, thinking)
        persistSave()
    }

    private fun persistSave() {
        if (!_uiState.value.gameStarted) return
        val save = ChessNative.exportSave()
        if (runCatching { JSONObject(save).has("error") }.getOrDefault(true)) return
        prefs.edit()
            .putString(KEY_SAVE, save)
            .putBoolean(KEY_STARTED, true)
            .apply()
    }

    private fun rememberMode(gameMode: GameMode) {
        when (gameMode) {
            is GameMode.VsAI -> prefs.edit()
                .putBoolean(KEY_PLAY_WHITE, gameMode.playerColor == PieceColor.WHITE)
                .putInt(KEY_DIFFICULTY, gameMode.difficulty.nativeValue)
                .apply()
            GameMode.TwoPlayer -> Unit
        }
    }

    companion object {
        private const val PREFS = "hutts_chess"
        private const val KEY_SAVE = "save"
        private const val KEY_STARTED = "started"
        private const val KEY_PLAY_WHITE = "play_white"
        private const val KEY_DIFFICULTY = "difficulty"
    }
}
