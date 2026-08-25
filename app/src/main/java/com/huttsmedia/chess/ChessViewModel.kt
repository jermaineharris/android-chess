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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    private var clockJob: Job? = null
    private var whiteMs = 0L
    private var blackMs = 0L
    private var clocksOn = false
    private var lastArchivedPgn: String? = null
    private val stockfish = StockfishEngine(application)
    private var analysisJob: Job? = null
    private var analysisGen = 0
    private var engineCp: Int? = null
    private var engineMate: Int? = null
    private var enginePv: List<String> = emptyList()
    private var engineName: String? = null
    private var engineDepth: Int = 0
    private var analyzing = false

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(value) { prefs.edit().putBoolean(KEY_SOUND, value).apply() }

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) { prefs.edit().putBoolean(KEY_HAPTICS, value).apply() }

    var showCoordinates: Boolean
        get() = prefs.getBoolean(KEY_COORDS, true)
        set(value) { prefs.edit().putBoolean(KEY_COORDS, value).apply() }

    var showArrow: Boolean
        get() = prefs.getBoolean(KEY_ARROW, true)
        set(value) { prefs.edit().putBoolean(KEY_ARROW, value).apply() }

    var pieceStyle: PieceStyle
        get() = runCatching { PieceStyle.valueOf(prefs.getString(KEY_STYLE, PieceStyle.STANDARD.name)!!) }
            .getOrDefault(PieceStyle.STANDARD)
        set(value) { prefs.edit().putString(KEY_STYLE, value.name).apply() }

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

    fun lastClockMs(): Long = prefs.getLong(KEY_CLOCK, 0)

    fun history(): List<HistoryEntry> {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HistoryEntry(o.optLong("time"), o.optString("summary"), o.optString("save"))
        }.reversed()
    }

    fun onNewGame(gameMode: GameMode, clockMs: Long = 0) {
        cancelAi()
        gameGeneration += 1
        savedStateHandle["gameStarted"] = true
        prefs.edit().putBoolean(KEY_STARTED, true).putLong(KEY_CLOCK, clockMs).apply()
        rememberMode(gameMode)
        lastArchivedPgn = null
        whiteMs = clockMs
        blackMs = clockMs
        clocksOn = clockMs > 0 && gameMode is GameMode.TwoPlayer
        val vsAi = gameMode is GameMode.VsAI
        val analysis = gameMode is GameMode.Analysis
        val playAsWhite = when (gameMode) {
            is GameMode.VsAI -> gameMode.playerColor == PieceColor.WHITE
            GameMode.TwoPlayer, GameMode.Analysis -> true
        }
        val difficulty = when (gameMode) {
            is GameMode.VsAI -> gameMode.difficulty.nativeValue
            GameMode.TwoPlayer, GameMode.Analysis -> 1
        }
        clearEngineOverlay()
        applyState(ChessNative.newGame(vsAi, playAsWhite, difficulty, analysis), gameMode, thinking = false)
        startClockLoop()
        maybeRequestAi()
        if (analysis) startAnalysis()
    }

    fun onSquareClick(position: Position) {
        if (!_uiState.value.gameStarted || _uiState.value.isAiThinking) return
        applyNative { ChessNative.onSquareClick(position.row, position.col) }
        maybeRequestAi()
        maybeResumeAnalysis()
    }

    fun onPromote(pieceType: PieceType) {
        if (!_uiState.value.gameStarted || _uiState.value.isAiThinking) return
        applyNative { ChessNative.promote(pieceType.name) }
        maybeRequestAi()
        maybeResumeAnalysis()
    }

    fun onUndo() {
        if (!_uiState.value.gameStarted) return
        cancelAi()
        applyNative { ChessNative.undo() }
        maybeRequestAi()
        maybeResumeAnalysis()
    }

    fun onRedo() {
        if (!_uiState.value.gameStarted || _uiState.value.isAiThinking) return
        applyNative { ChessNative.redo() }
        maybeRequestAi()
        maybeResumeAnalysis()
    }

    fun onHint() {
        if (!_uiState.value.gameStarted || _uiState.value.isAiThinking) return
        applyNative { ChessNative.hint() }
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
        applyNative { ChessNative.resign() }
    }

    fun onOfferDraw() {
        if (!_uiState.value.gameStarted || _uiState.value.isAiThinking) return
        applyNative { ChessNative.offerDraw() }
        if (_uiState.value.gameMode is GameMode.TwoPlayer && _uiState.value.drawOfferPending) {
            applyNative { ChessNative.toggleFlip() }
        }
        maybeRequestAi()
    }

    fun onAcceptDraw() {
        applyNative { ChessNative.acceptDraw() }
    }

    fun onDeclineDraw() {
        applyNative { ChessNative.declineDraw() }
    }

    fun onClaimDraw() {
        applyNative { ChessNative.claimDraw() }
    }

    fun onImportText(text: String): String? {
        cancelAi()
        gameGeneration += 1
        savedStateHandle["gameStarted"] = true
        prefs.edit().putBoolean(KEY_STARTED, true).apply()
        lastArchivedPgn = null
        clocksOn = false
        whiteMs = 0
        blackMs = 0
        val mode = _uiState.value.gameMode
        val vsAi = mode is GameMode.VsAI
        val playAsWhite = when (mode) {
            is GameMode.VsAI -> mode.playerColor == PieceColor.WHITE
            GameMode.TwoPlayer, GameMode.Analysis -> true
        }
        val difficulty = when (mode) {
            is GameMode.VsAI -> mode.difficulty.nativeValue
            GameMode.TwoPlayer, GameMode.Analysis -> 1
        }
        val json = ChessNative.importText(text, vsAi, playAsWhite, difficulty, mode is GameMode.Analysis)
        if (JSONObject(json).has("error")) {
            return JSONObject(json).optString("error")
        }
        applyState(json, mode, thinking = false)
        maybeRequestAi()
        maybeResumeAnalysis()
        return null
    }

    fun onLoadHistory(entry: HistoryEntry) {
        cancelAi()
        gameGeneration += 1
        savedStateHandle["gameStarted"] = true
        prefs.edit().putBoolean(KEY_STARTED, true).apply()
        lastArchivedPgn = entry.save
        clocksOn = false
        val mode = runCatching { parseSavedGameMode(entry.save) }.getOrDefault(GameMode.TwoPlayer)
        applyState(ChessNative.importSave(entry.save), mode, thinking = false)
        maybeResumeAnalysis()
    }

    fun refreshSettings() {
        _uiState.value = _uiState.value.copy()
    }

    private fun applyNative(block: () -> String) {
        val mode = _uiState.value.gameMode
        applyState(block(), mode, thinking = false)
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
            maybeResumeAnalysis()
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
        maybeResumeAnalysis()
    }

    private fun clearStarted() {
        savedStateHandle["gameStarted"] = false
        prefs.edit().putBoolean(KEY_STARTED, false).apply()
        clocksOn = false
        _uiState.value = ChessUiState()
    }

    fun onGotoPly(ply: Int) {
        if (!_uiState.value.gameStarted) return
        cancelAi()
        applyNative { ChessNative.gotoPly(ply) }
        maybeRequestAi()
        maybeResumeAnalysis()
    }

    fun onPlayUci(uci: String) {
        if (!_uiState.value.gameStarted || _uiState.value.isAiThinking) return
        applyNative { ChessNative.playUci(uci) }
        maybeRequestAi()
        maybeResumeAnalysis()
    }

    fun onToggleAnalysis() {
        if (_uiState.value.analyzing) stopAnalysis() else startAnalysis()
    }

    override fun onCleared() {
        stopAnalysis()
        stockfish.quit()
        super.onCleared()
    }

    private fun maybeRequestAi() {
        val state = _uiState.value
        val mode = state.gameMode as? GameMode.VsAI ?: return
        if (state.promotionPending || state.gameOver) return
        if (state.turn == mode.playerColor) return
        val gen = gameGeneration
        _uiState.value = state.copy(isAiThinking = true)
        aiJob = viewModelScope.launch {
            delay(200)
            if (gen != gameGeneration) return@launch
            val fen = _uiState.value.fen
            val json = withContext(Dispatchers.Default) {
                val elo = when (mode.difficulty) {
                    AiDifficulty.BEGINNER -> 1350
                    AiDifficulty.INTERMEDIATE -> 1600
                    AiDifficulty.ADVANCED -> 1900
                    AiDifficulty.GRANDMASTER -> null
                }
                val time = when (mode.difficulty) {
                    AiDifficulty.BEGINNER -> 250
                    AiDifficulty.INTERMEDIATE -> 500
                    AiDifficulty.ADVANCED -> 900
                    AiDifficulty.GRANDMASTER -> 1500
                }
                val uci = stockfish.bestMove(fen, time, elo)
                if (uci != null) ChessNative.playUci(uci) else ChessNative.aiMove()
            }
            if (gen != gameGeneration) return@launch
            applyState(json, mode, thinking = false)
        }
    }

    private fun cancelAi() {
        gameGeneration += 1
        aiJob?.cancel()
        aiJob = null
        stockfish.stopSearch()
    }

    private fun startAnalysis() {
        val state = _uiState.value
        if (!state.gameStarted) return
        analysisGen += 1
        val gen = analysisGen
        analyzing = true
        analysisJob?.cancel()
        stockfish.stopSearch()
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            val fen = _uiState.value.fen
            val stmWhite = _uiState.value.turn == PieceColor.WHITE
            if (stockfish.start()) {
                stockfish.searchInfinite(fen) { info ->
                    if (gen != analysisGen) return@searchInfinite
                    applyEngineInfo(info, stmWhite)
                }
            } else {
                val raw = ChessNative.analyze(3)
                val obj = JSONObject(raw)
                val pv = obj.optJSONArray("pv")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                applyEngineInfo(
                    EngineInfo(
                        depth = obj.optInt("depth"),
                        cp = obj.optInt("cp"),
                        mate = null,
                        pvUci = pv,
                        engine = "Hutts search"
                    ),
                    stmWhite
                )
            }
            if (gen == analysisGen) {
                analyzing = false
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(analyzing = false)
                }
            }
        }
        _uiState.value = _uiState.value.copy(analyzing = true, engineName = engineName)
    }

    private fun stopAnalysis() {
        analysisGen += 1
        analyzing = false
        analysisJob?.cancel()
        stockfish.stopSearch()
        _uiState.value = _uiState.value.copy(analyzing = false)
    }

    private fun maybeResumeAnalysis() {
        if (_uiState.value.gameMode is GameMode.Analysis) {
            startAnalysis()
        }
    }

    private fun applyEngineInfo(info: EngineInfo, stmWhite: Boolean) {
        val whiteCp = info.cp?.let { if (stmWhite) it else -it }
        val whiteMate = info.mate?.let { if (stmWhite) it else -it }
        engineCp = whiteCp
        engineMate = whiteMate
        enginePv = info.pvUci
        engineName = info.engine
        engineDepth = info.depth
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                evalCp = whiteCp,
                evalMate = whiteMate,
                evalWhite = true,
                pvUci = info.pvUci,
                engineName = info.engine,
                engineDepth = info.depth,
                analyzing = analyzing
            )
        }
    }

    private fun clearEngineOverlay() {
        stopAnalysis()
        engineCp = null
        engineMate = null
        enginePv = emptyList()
        engineName = null
        engineDepth = 0
    }

    private fun startClockLoop() {
        clockJob?.cancel()
        if (!clocksOn) return
        clockJob = viewModelScope.launch {
            while (isActive) {
                delay(100)
                val state = _uiState.value
                if (!clocksOn || !state.gameStarted || state.gameOver) continue
                if (state.turn == PieceColor.WHITE) {
                    whiteMs = (whiteMs - 100).coerceAtLeast(0)
                    if (whiteMs == 0L) {
                        applyNative { ChessNative.flagLoss(true) }
                        clocksOn = false
                    }
                } else {
                    blackMs = (blackMs - 100).coerceAtLeast(0)
                    if (blackMs == 0L) {
                        applyNative { ChessNative.flagLoss(false) }
                        clocksOn = false
                    }
                }
                _uiState.value = _uiState.value.copy(whiteClockMs = whiteMs, blackClockMs = blackMs, clocksEnabled = clocksOn)
            }
        }
    }

    private fun applyState(json: String, gameMode: GameMode, thinking: Boolean) {
        val started = savedStateHandle["gameStarted"] ?: prefs.getBoolean(KEY_STARTED, false)
        val parsed = parseChessState(json, gameMode, started, thinking, whiteMs, blackMs, clocksOn)
        _uiState.value = parsed.copy(
            evalCp = engineCp,
            evalMate = engineMate,
            evalWhite = true,
            pvUci = enginePv,
            engineName = engineName,
            engineDepth = engineDepth,
            analyzing = analyzing
        )
        persistSave()
        archiveIfFinished(parsed)
        if (parsed.gameOver) clocksOn = false
    }

    private fun archiveIfFinished(state: ChessUiState) {
        if (!state.gameOver || state.pgn.isBlank()) return
        if (state.pgn == lastArchivedPgn) return
        lastArchivedPgn = state.pgn
        val save = ChessNative.exportSave()
        if (runCatching { JSONObject(save).has("error") }.getOrDefault(true)) return
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        arr.put(
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("summary", state.gameStatus ?: "Game")
                .put("save", save)
        )
        while (arr.length() > 20) {
            arr.remove(0)
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
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
            GameMode.TwoPlayer, GameMode.Analysis -> Unit
        }
    }

    companion object {
        private const val PREFS = "hutts_chess"
        private const val KEY_SAVE = "save"
        private const val KEY_STARTED = "started"
        private const val KEY_PLAY_WHITE = "play_white"
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_SOUND = "sound"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_COORDS = "coords"
        private const val KEY_ARROW = "arrow"
        private const val KEY_STYLE = "style"
        private const val KEY_CLOCK = "clock_ms"
        private const val KEY_HISTORY = "history"
    }
}
