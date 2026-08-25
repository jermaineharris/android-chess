package com.huttsmedia.chess

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
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
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChessUiState(gameStarted = savedStateHandle["gameStarted"] ?: false))
    val uiState: StateFlow<ChessUiState> = _uiState.asStateFlow()

    private var gameGeneration = 0
    private var aiJob: Job? = null

    init {
        if (_uiState.value.gameStarted) {
            val json = ChessNative.getState()
            if (JSONObject(json).has("error")) {
                savedStateHandle["gameStarted"] = false
                _uiState.value = ChessUiState()
            } else {
                applyState(json, _uiState.value.gameMode, thinking = false)
            }
        }
    }

    fun onNewGame(gameMode: GameMode) {
        cancelAi()
        gameGeneration += 1
        savedStateHandle["gameStarted"] = true
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
        val started = savedStateHandle["gameStarted"] ?: false
        _uiState.value = parseChessState(json, gameMode, started, thinking)
    }
}
