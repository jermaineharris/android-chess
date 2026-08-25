package com.huttsmedia.chess

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChessViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChessUiState())
    val uiState: StateFlow<ChessUiState> = _uiState.asStateFlow()

    fun onNewGame(gameMode: GameMode) {
        val vsAi = gameMode is GameMode.VsAI
        val playAsWhite = when (gameMode) {
            is GameMode.VsAI -> gameMode.playerColor == PieceColor.WHITE
            GameMode.TwoPlayer -> true
        }
        val difficulty = when (gameMode) {
            is GameMode.VsAI -> gameMode.difficulty.nativeValue
            GameMode.TwoPlayer -> 1
        }
        applyState(ChessNative.newGame(vsAi, playAsWhite, difficulty), gameMode)
    }

    fun onSquareClick(position: Position) {
        val mode = _uiState.value.gameMode
        applyState(ChessNative.onSquareClick(position.row, position.col), mode)
        maybeRequestAi()
    }

    fun onPromote(pieceType: PieceType) {
        val mode = _uiState.value.gameMode
        applyState(ChessNative.promote(pieceType.name), mode)
        maybeRequestAi()
    }

    private fun maybeRequestAi() {
        val state = _uiState.value
        val mode = state.gameMode as? GameMode.VsAI ?: return
        if (state.promotionPending || isGameOver(state)) return
        if (state.turn == mode.playerColor) return
        viewModelScope.launch {
            delay(400)
            val json = withContext(Dispatchers.Default) { ChessNative.aiMove() }
            applyState(json, mode)
        }
    }

    fun isGameOver(state: ChessUiState = _uiState.value): Boolean {
        return state.gameStatus?.startsWith("Checkmate") == true ||
            state.gameStatus?.startsWith("Stalemate") == true
    }

    private fun applyState(json: String, gameMode: GameMode) {
        _uiState.value = parseChessState(json, gameMode)
    }
}
