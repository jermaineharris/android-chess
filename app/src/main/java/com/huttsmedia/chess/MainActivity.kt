package com.huttsmedia.chess

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huttsmedia.chess.ui.theme.ChessTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChessTheme {
                val viewModel: ChessViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()

                DisposableEffect(uiState.gameStarted && !uiState.gameOver) {
                    if (uiState.gameStarted && !uiState.gameOver) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                ChessGame(
                    viewModel = viewModel,
                    onSquareClick = viewModel::onSquareClick,
                    onPromote = viewModel::onPromote,
                    onUndo = viewModel::onUndo
                )

                if (!uiState.gameStarted) {
                    NewGameDialog(
                        initialColor = viewModel.lastAiColor(),
                        initialDifficulty = viewModel.lastAiDifficulty(),
                        initialClock = viewModel.lastClockMs() > 0,
                        dismissable = false,
                        onDismiss = {},
                        onNewGame = viewModel::onNewGame
                    )
                }
            }
        }
    }
}

@Composable
fun NewGameDialog(
    initialColor: PieceColor,
    initialDifficulty: AiDifficulty,
    initialClock: Boolean,
    dismissable: Boolean,
    onDismiss: () -> Unit,
    onNewGame: (GameMode, Long) -> Unit
) {
    var showSettings by remember { mutableStateOf<((PieceColor, AiDifficulty) -> Unit)?>(null) }

    showSettings?.let { startGame ->
        var selectedColor by remember { mutableStateOf(initialColor) }
        var selectedDifficulty by remember { mutableStateOf(initialDifficulty) }
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            modifier = Modifier.fillMaxWidth(0.9f),
            onDismissRequest = {
                showSettings = null
                if (dismissable) onDismiss()
            },
            title = { Text("Start New Game") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Play as    ")
                        SingleChoiceSegmentedButtonRow {
                            PieceColor.entries.zip(listOf("White", "Black"))
                                .forEachIndexed { idx, (value, label) ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                        onClick = { selectedColor = value },
                                        selected = selectedColor == value,
                                        label = {
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    SingleChoiceSegmentedButtonRow {
                        AiDifficulty.entries.zip(listOf("Easy", "Medium", "Hard", "Master")).forEachIndexed { idx, (value, label) ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(idx, 4),
                                onClick = { selectedDifficulty = value },
                                selected = selectedDifficulty == value,
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button({
                        startGame(selectedColor, selectedDifficulty)
                    }, Modifier.fillMaxWidth()) {
                        Text("Start Game")
                    }
                }
            },
            confirmButton = {
                if (dismissable) {
                    TextButton(onClick = {
                        showSettings = null
                    }) { Text("Back") }
                }
            }
        )
    } ?: AlertDialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        title = { Text(text = "New Game") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                var useClock by remember { mutableStateOf(initialClock) }
                Button(onClick = { onNewGame(GameMode.TwoPlayer, if (useClock) 300_000L else 0L) }) {
                    Text("2-Player Local")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("5+0 clock", modifier = Modifier.padding(end = 8.dp))
                    Switch(checked = useClock, onCheckedChange = { useClock = it })
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    showSettings = { color, difficulty ->
                        onNewGame(GameMode.VsAI(color, difficulty), 0L)
                    }
                }) {
                    Text("Human vs AI")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onNewGame(GameMode.Analysis, 0L) }) {
                    Text("Analysis board")
                }
            }
        },
        confirmButton = {
            if (dismissable) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun ChessGame(
    viewModel: ChessViewModel,
    onSquareClick: (Position) -> Unit,
    onPromote: (PieceType) -> Unit,
    onUndo: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var showNewGameDialog by remember { mutableStateOf(false) }
    var confirmNewGame by remember { mutableStateOf(false) }
    var confirmResign by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var settingsTick by remember { mutableStateOf(0) }
    val promotionColor = uiState.promotionColor

    BackHandler(enabled = uiState.selectedPiece != null) {
        viewModel.onDeselect()
    }

    var hapticReady by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.moves.size, uiState.lastEvent, uiState.gameOver) {
        if (!hapticReady) {
            hapticReady = true
            return@LaunchedEffect
        }
        if (viewModel.hapticsEnabled) {
            val feedback = when (uiState.lastEvent) {
                "capture" -> HapticFeedbackConstants.LONG_PRESS
                "check", "mate", "resign", "flag" -> HapticFeedbackConstants.REJECT
                "draw" -> HapticFeedbackConstants.CONFIRM
                "move" -> HapticFeedbackConstants.CLOCK_TICK
                else -> null
            }
            feedback?.let { view.performHapticFeedback(it) }
        }
        if (viewModel.soundEnabled) {
            scope.launch { playMoveSound(uiState.lastEvent) }
        }
    }

    if (uiState.promotionPending && promotionColor != null) {
        PawnPromotionDialog(promotionColor, viewModel.pieceStyle, onPromote = onPromote)
    }
    if (confirmNewGame) {
        AlertDialog(
            onDismissRequest = { confirmNewGame = false },
            title = { Text("Start a new game?") },
            text = { Text("The current game will be replaced.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewGame = false
                    showNewGameDialog = true
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewGame = false }) { Text("Cancel") }
            }
        )
    }
    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            title = { Text("Resign?") },
            text = { Text("This will end the game.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmResign = false
                    viewModel.onResign()
                }) { Text("Resign") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResign = false }) { Text("Cancel") }
            }
        )
    }
    if (showNewGameDialog && uiState.gameStarted) {
        NewGameDialog(
            initialColor = viewModel.lastAiColor(),
            initialDifficulty = viewModel.lastAiDifficulty(),
            initialClock = viewModel.lastClockMs() > 0,
            dismissable = true,
            onDismiss = { showNewGameDialog = false },
            onNewGame = { mode, clock ->
                viewModel.onNewGame(mode, clock)
                showNewGameDialog = false
            }
        )
    }
    if (uiState.drawOfferPending && !uiState.gameOver) {
        AlertDialog(
            onDismissRequest = viewModel::onDeclineDraw,
            title = { Text("Draw offer") },
            text = { Text(uiState.gameStatus ?: "Accept the draw?") },
            confirmButton = { TextButton(onClick = viewModel::onAcceptDraw) { Text("Accept") } },
            dismissButton = { TextButton(onClick = viewModel::onDeclineDraw) { Text("Decline") } }
        )
    }
    if (showSettings) {
        SettingsDialog(viewModel) {
            settingsTick += 1
            showSettings = false
        }
    }
    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { text ->
                val err = viewModel.onImportText(text)
                if (err == null) showImport = false else Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            }
        )
    }
    if (showHistory) {
        HistoryDialog(
            entries = viewModel.history(),
            onDismiss = { showHistory = false },
            onLoad = {
                viewModel.onLoadHistory(it)
                showHistory = false
            }
        )
    }

    val topCaptured = if (uiState.isBoardFlipped) uiState.capturedByWhite else uiState.capturedByBlack
    val bottomCaptured = if (uiState.isBoardFlipped) uiState.capturedByBlack else uiState.capturedByWhite
    val turnLabel = when {
        uiState.isAiThinking -> "AI thinking…"
        uiState.gameOver -> uiState.gameStatus ?: "Game over"
        else -> "${uiState.turn.name.lowercase().replaceFirstChar { it.titlecase() }} to move"
    }
    val materialLabel = when {
        uiState.material > 0 -> "+${uiState.material}"
        uiState.material < 0 -> "${uiState.material}"
        else -> "even"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CapturedPiecesRow(topCaptured, viewModel.pieceStyle)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                turnLabel,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Material $materialLabel",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
            )
            uiState.opening?.takeIf { viewModel.showOpeningNames }?.let { name ->
                Text(
                    listOfNotNull(uiState.eco, name).joinToString(" · "),
                    fontSize = 13.sp
                )
            }
            if (uiState.engineName != null || uiState.analyzing) {
                val mate = uiState.evalMate
                val cp = uiState.evalCp
                val eval = when {
                    mate != null -> if (mate > 0) "M$mate" else "M${-mate}"
                    cp != null -> {
                        val pawns = cp / 100.0
                        if (pawns >= 0) "+%.2f".format(pawns) else "%.2f".format(pawns)
                    }
                    else -> "…"
                }
                Text(
                    "${uiState.engineName ?: "Engine"} d${uiState.engineDepth}  $eval",
                    fontSize = 13.sp
                )
            }
            if (uiState.clocksEnabled) {
                Text(
                    "White ${formatClock(uiState.whiteClockMs)}   Black ${formatClock(uiState.blackClockMs)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            MovesList(
                moves = uiState.moves,
                turn = uiState.turn,
                ply = uiState.ply,
                onPlyClick = viewModel::onGotoPly
            )
            Spacer(modifier = Modifier.height(8.dp))
            ChessBoard(
                uiState = uiState,
                onSquareClick = onSquareClick,
                showCoordinates = viewModel.showCoordinates,
                showArrow = viewModel.showArrow,
                pieceStyle = viewModel.pieceStyle,
                boardTheme = viewModel.boardTheme,
                settingsTick = settingsTick
            )
            val humanToMove = when (val mode = uiState.gameMode) {
                is GameMode.VsAI -> uiState.turn == mode.playerColor && !uiState.isAiThinking
                else -> !uiState.isAiThinking
            }
            if (viewModel.showOpeningNames && uiState.openingMoves.isNotEmpty() && humanToMove) {
                Spacer(modifier = Modifier.height(8.dp))
                OpeningTree(uiState.openingMoves, onPick = viewModel::onPlayUci)
            }
            Spacer(modifier = Modifier.height(8.dp))
            CapturedPiecesRow(bottomCaptured, viewModel.pieceStyle)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                OutlinedButton(onClick = onUndo, enabled = uiState.canUndo && !uiState.isAiThinking) {
                    Text("Undo")
                }
                OutlinedButton(
                    onClick = viewModel::onRedo,
                    enabled = uiState.canRedo && !uiState.isAiThinking
                ) {
                    Text("Redo")
                }
                OutlinedButton(onClick = viewModel::onToggleFlip, enabled = uiState.gameStarted) {
                    Text("Flip")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                OutlinedButton(
                    onClick = viewModel::onHint,
                    enabled = uiState.gameStarted && !uiState.gameOver && !uiState.isAiThinking && uiState.hintsLeft > 0
                ) {
                    Text(if (uiState.hintsLeft < 10) "Hint (${uiState.hintsLeft})" else "Hint")
                }
                OutlinedButton(
                    onClick = viewModel::onOfferDraw,
                    enabled = uiState.canOfferDraw && !uiState.isAiThinking
                ) {
                    Text("Offer draw")
                }
                OutlinedButton(
                    onClick = viewModel::onClaimDraw,
                    enabled = uiState.canClaimDraw && !uiState.isAiThinking
                ) {
                    Text("Claim")
                }
                OutlinedButton(
                    onClick = viewModel::onToggleAnalysis,
                    enabled = uiState.gameStarted && !uiState.isAiThinking
                ) {
                    Text(if (uiState.analyzing) "Stop engine" else "Engine")
                }
                OutlinedButton(
                    onClick = { confirmResign = true },
                    enabled = uiState.gameStarted && !uiState.gameOver && !uiState.isAiThinking && !uiState.analysis
                ) {
                    Text("Resign")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                Button(onClick = {
                    val inProgress = uiState.gameStarted && !uiState.gameOver && uiState.moves.isNotEmpty()
                    if (inProgress) confirmNewGame = true else showNewGameDialog = true
                }) {
                    Text("New Game")
                }
                OutlinedButton(
                    onClick = {
                        val pgn = uiState.pgn.ifBlank { "*" }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, pgn)
                        }
                        context.startActivity(Intent.createChooser(send, "Share PGN"))
                    },
                    enabled = uiState.gameStarted
                ) {
                    Text("Share PGN")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                OutlinedButton(onClick = { showImport = true }) { Text("Import") }
                OutlinedButton(onClick = { showHistory = true }) { Text("History") }
                OutlinedButton(onClick = { showSettings = true }) { Text("Settings") }
            }

            if (!uiState.gameOver) {
                uiState.gameStatus?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(it, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun MovesList(
    moves: List<String>,
    turn: PieceColor,
    ply: Int = 0,
    onPlyClick: (Int) -> Unit = {}
) {
    val rows = moves.chunked(2)
    val scroll = rememberScrollState()
    LaunchedEffect(moves.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val sheet = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(sheet)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .verticalScroll(scroll)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(vertical = 4.dp),
            Arrangement.SpaceEvenly
        ) {
            Text(
                "White",
                fontWeight = if (turn == PieceColor.WHITE) FontWeight.Bold else FontWeight.Normal,
                color = ink
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outline)
            Text(
                "Black",
                fontWeight = if (turn == PieceColor.BLACK) FontWeight.Bold else FontWeight.Normal,
                color = ink
            )
        }
        rows.forEachIndexed { index, move ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}.",
                    Modifier.width(28.dp).padding(start = 4.dp),
                    fontSize = 13.sp
                )
                val whitePly = index * 2 + 1
                Text(
                    move[0],
                    Modifier
                        .weight(1f)
                        .clickable { onPlyClick(whitePly) }
                        .background(if (ply == whitePly) Color(0x332196F3) else Color.Transparent),
                    textAlign = TextAlign.Center,
                    fontWeight = if (ply == whitePly) FontWeight.Bold else FontWeight.Normal
                )
                if (move.size == 2) {
                    val blackPly = whitePly + 1
                    VerticalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        move[1],
                        Modifier
                            .weight(1f)
                            .clickable { onPlyClick(blackPly) }
                            .background(if (ply == blackPly) Color(0x332196F3) else Color.Transparent),
                        textAlign = TextAlign.Center,
                        fontWeight = if (ply == blackPly) FontWeight.Bold else FontWeight.Normal
                    )
                } else {
                    VerticalDivider()
                    Text("", Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun OpeningTree(moves: List<OpeningMove>, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 140.dp)
            .border(1.dp, Color.Gray)
            .verticalScroll(rememberScrollState())
            .padding(6.dp)
    ) {
        Text("Opening tree", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        moves.take(8).forEach { child ->
            Text(
                "${child.san}  ${child.eco} ${child.name}  (${child.lines})",
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(child.uci) }
                    .padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
fun EvalBar(uiState: ChessUiState, flipped: Boolean) {
    val mate = uiState.evalMate
    val cp = uiState.evalCp
    val whiteShare = when {
        mate != null -> if (mate > 0) 0.96f else 0.04f
        cp != null -> (0.5f + cp / 800f).coerceIn(0.06f, 0.94f)
        else -> 0.5f
    }
    val topIsWhite = flipped
    val topShare = if (topIsWhite) whiteShare else 1f - whiteShare
    Column(
        Modifier
            .width(12.dp)
            .fillMaxHeight()
            .padding(start = 4.dp)
    ) {
        Box(Modifier.weight(topShare.coerceAtLeast(0.04f)).fillMaxWidth().background(if (topIsWhite) Color(0xFFF0F0F0) else Color(0xFF333333)))
        Box(Modifier.weight((1f - topShare).coerceAtLeast(0.04f)).fillMaxWidth().background(if (topIsWhite) Color(0xFF333333) else Color(0xFFF0F0F0)))
    }
}

@Composable
fun PawnPromotionDialog(color: PieceColor, pieceStyle: PieceStyle, onPromote: (PieceType) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(text = "Promote Pawn") },
        text = {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                for (pieceType in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                    Box(Modifier.clickable { onPromote(pieceType) }) {
                        ChessPiece(Piece(pieceType, color), 64.dp, pieceStyle)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun CapturedPiecesRow(pieces: List<Piece>, pieceStyle: PieceStyle = PieceStyle.STANDARD) {
    Box(
        Modifier
            .height(48.dp)
            .padding(4.dp), Alignment.Center
    ) {
        if (pieces.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(Modifier.padding(4.dp)) {
                    pieces.forEach { ChessPiece(it, 32.dp, pieceStyle) }
                }
            }
        }
    }
}

@Composable
fun ChessBoard(
    uiState: ChessUiState,
    onSquareClick: (Position) -> Unit,
    showCoordinates: Boolean = true,
    showArrow: Boolean = true,
    pieceStyle: PieceStyle = PieceStyle.STANDARD,
    boardTheme: BoardTheme = BoardTheme.GREEN,
    settingsTick: Int = 0
) {
    val flipped = uiState.isBoardFlipped
    val lastMove = uiState.lastMove
    val (lightSq, darkSq, lastSq) = boardTheme.squareColors()
    @Suppress("UNUSED_VARIABLE")
    val tick = settingsTick
    Column(Modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF5C3D24)),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            shape = RoundedCornerShape(6.dp)
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .aspectRatio(1.06f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .width(16.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                if (showCoordinates) {
                    for (displayRow in 0..7) {
                        val logicalRow = if (flipped) 7 - displayRow else displayRow
                        Text(
                            "${8 - logicalRow}",
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            color = Color(0xFFF3E6D0)
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                for (displayRow in 0..7) {
                    val logicalRow = if (flipped) 7 - displayRow else displayRow
                    Row(modifier = Modifier.weight(1f)) {
                        for (displayCol in 0..7) {
                            val logicalCol = if (flipped) 7 - displayCol else displayCol
                            val pos = Position(logicalRow, logicalCol)
                            val piece = uiState.pieces.getOrNull(logicalRow)?.getOrNull(logicalCol)
                            val isSelected = uiState.selectedPiece == pos
                            val isLegal = pos in uiState.legalMoves
                            val isLast = lastMove?.from == pos || lastMove?.to == pos
                            val isHint = uiState.hint?.from == pos || uiState.hint?.to == pos
                            val isKingCheck =
                                uiState.kingInCheck && piece?.type == PieceType.KING && piece.color == uiState.turn
                            val base = if ((logicalRow + logicalCol) % 2 == 0) lightSq else darkSq
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(
                                        when {
                                            isLast -> lastSq
                                            isHint -> Color(0xFF7EC8E3)
                                            else -> base
                                        }
                                    )
                    .clickable(enabled = !uiState.isAiThinking && (!uiState.gameOver || uiState.analysis)) {
                                        onSquareClick(pos)
                                    }
                                    .then(
                                        when {
                                            isSelected -> Modifier.border(2.dp, Color(0xFFF6F669))
                                            isKingCheck -> Modifier.border(2.dp, Color.Red)
                                            else -> Modifier
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                piece?.let { ChessPiece(it, pieceStyle = pieceStyle) }
                                if (isLegal) {
                                    Box(
                                        Modifier
                                            .size(if (piece == null) 14.dp else 28.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (piece == null) Color(0x66000000) else Color.Transparent
                                            )
                                            .then(
                                                if (piece != null) Modifier.border(3.dp, Color(0x99000000), CircleShape)
                                                else Modifier
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (showArrow) {
                Canvas(Modifier.fillMaxSize()) {
                    fun center(pos: Position): Offset {
                        val displayCol = if (flipped) 7 - pos.col else pos.col
                        val displayRow = if (flipped) 7 - pos.row else pos.row
                        val w = size.width / 8f
                        val h = size.height / 8f
                        return Offset((displayCol + 0.5f) * w, (displayRow + 0.5f) * h)
                    }
                    lastMove?.let { drawMoveArrow(center(it.from), center(it.to), Color(0xCC1B5E20)) }
                    uiState.hint?.let { drawMoveArrow(center(it.from), center(it.to), Color(0xCC0D47A1)) }
                    uiState.pvUci.firstOrNull()?.let { uciToLastMove(it) }?.let {
                        drawMoveArrow(center(it.from), center(it.to), Color(0xCC6A1B9A))
                    }
                }
            }
            }
            EvalBar(uiState = uiState, flipped = flipped)
        }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(16.dp))
            Row(Modifier.weight(1f)) {
                if (showCoordinates) {
                    for (displayCol in 0..7) {
                        val logicalCol = if (flipped) 7 - displayCol else displayCol
                        Text(
                            "${'a' + logicalCol}",
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoveArrow(
    from: Offset,
    to: Offset,
    color: Color
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val angle = atan2(dy, dx)
    val head = 22f
    drawLine(color, from, to, strokeWidth = 8f, cap = StrokeCap.Round)
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - head * cos(angle - 0.45f), to.y - head * sin(angle - 0.45f))
        lineTo(to.x - head * cos(angle + 0.45f), to.y - head * sin(angle + 0.45f))
        close()
    }
    drawPath(path, color)
}

@Composable
fun ChessPiece(piece: Piece, size: Dp? = null, pieceStyle: PieceStyle = PieceStyle.STANDARD) {
    val base = if (size != null) Modifier.size(size) else Modifier.fillMaxSize()
    if (pieceStyle == PieceStyle.STANDARD) {
        Image(
            painterResource(id = piece.type.stauntonResId(piece.color)),
            "${piece.color} ${piece.type}",
            base.padding(2.dp),
            contentScale = ContentScale.Fit
        )
        return
    }
    val blackTint = when (pieceStyle) {
        PieceStyle.HIGH_CONTRAST -> Color.Black
        else -> Color(0xFF1A1A1A)
    }
    val whiteFill = when (pieceStyle) {
        PieceStyle.FLAT -> Color(0xFFD8D8D8)
        PieceStyle.HIGH_CONTRAST -> Color.White
        PieceStyle.STANDARD -> Color(0xFFF7F7F7)
    }
    if (piece.color == PieceColor.WHITE && pieceStyle != PieceStyle.FLAT) {
        Box(base, contentAlignment = Alignment.Center) {
            Image(
                painterResource(id = piece.type.glyphResId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(if (pieceStyle == PieceStyle.HIGH_CONTRAST) 0.dp else 1.dp),
                colorFilter = ColorFilter.tint(Color(0xFF222222))
            )
            Image(
                painterResource(id = piece.type.glyphResId),
                "${piece.color} ${piece.type}",
                modifier = Modifier.fillMaxSize().padding(if (pieceStyle == PieceStyle.HIGH_CONTRAST) 2.dp else 3.dp),
                colorFilter = ColorFilter.tint(whiteFill)
            )
        }
    } else {
        Image(
            painterResource(id = piece.type.glyphResId),
            "${piece.color} ${piece.type}",
            base.padding(2.dp),
            colorFilter = ColorFilter.tint(if (piece.color == PieceColor.WHITE) whiteFill else blackTint)
        )
    }
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun BoardTheme.squareColors(): Triple<Color, Color, Color> = when (this) {
    BoardTheme.GREEN -> Triple(Color(0xFFEEEED2), Color(0xFF769656), Color(0xFFBACA2B))
    BoardTheme.BLUE -> Triple(Color(0xFFDEE3E6), Color(0xFF4B7399), Color(0xFF8CA2C0))
    BoardTheme.BROWN -> Triple(Color(0xFFF0D9B5), Color(0xFFB58863), Color(0xFFCDD26A))
    BoardTheme.GRAY -> Triple(Color(0xFFEAEAEA), Color(0xFF7A7A7A), Color(0xFFB8B8B8))
    BoardTheme.WALNUT -> Triple(Color(0xFFE8D0B0), Color(0xFF6B3F24), Color(0xFFC4A35A))
    BoardTheme.ICE -> Triple(Color(0xFFF3F8FC), Color(0xFF5B8FB9), Color(0xFF7EC8E3))
}

@Composable
fun SettingsDialog(viewModel: ChessViewModel, onDismiss: () -> Unit) {
    var sound by remember { mutableStateOf(viewModel.soundEnabled) }
    var haptics by remember { mutableStateOf(viewModel.hapticsEnabled) }
    var coords by remember { mutableStateOf(viewModel.showCoordinates) }
    var arrow by remember { mutableStateOf(viewModel.showArrow) }
    var openingNames by remember { mutableStateOf(viewModel.showOpeningNames) }
    var style by remember { mutableStateOf(viewModel.pieceStyle) }
    var board by remember { mutableStateOf(viewModel.boardTheme) }
    fun save() {
        viewModel.soundEnabled = sound
        viewModel.hapticsEnabled = haptics
        viewModel.showCoordinates = coords
        viewModel.showArrow = arrow
        viewModel.showOpeningNames = openingNames
        viewModel.pieceStyle = style
        viewModel.boardTheme = board
    }
    AlertDialog(
        onDismissRequest = {
            save()
            onDismiss()
        },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Move sounds")
                    Switch(checked = sound, onCheckedChange = { sound = it })
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Haptics")
                    Switch(checked = haptics, onCheckedChange = { haptics = it })
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Coordinates")
                    Switch(checked = coords, onCheckedChange = { coords = it })
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Last-move arrow")
                    Switch(checked = arrow, onCheckedChange = { arrow = it })
                }
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Opening names")
                    Switch(checked = openingNames, onCheckedChange = { openingNames = it })
                }
                Spacer(Modifier.height(8.dp))
                Text("Pieces")
                SingleChoiceSegmentedButtonRow {
                    PieceStyle.entries.zip(listOf("Standard", "Contrast", "Flat")).forEachIndexed { idx, (value, label) ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(idx, 3),
                            onClick = { style = value },
                            selected = style == value,
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Board colors")
                Spacer(Modifier.height(6.dp))
                BoardTheme.entries.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { theme ->
                            val (light, dark, _) = theme.squareColors()
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { board = theme }
                                    .border(
                                        width = if (board == theme) 2.dp else 1.dp,
                                        color = if (board == theme) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(Modifier.fillMaxWidth().height(22.dp)) {
                                    Box(Modifier.weight(1f).fillMaxHeight().background(light))
                                    Box(Modifier.weight(1f).fillMaxHeight().background(dark))
                                }
                                Text(
                                    when (theme) {
                                        BoardTheme.GREEN -> "Green"
                                        BoardTheme.BLUE -> "Blue"
                                        BoardTheme.BROWN -> "Brown"
                                        BoardTheme.GRAY -> "Gray"
                                        BoardTheme.WALNUT -> "Walnut"
                                        BoardTheme.ICE -> "Ice"
                                    },
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Engine is Stockfish 17.1 (NNUE), GPL-3.0, run as a separate process.",
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                save()
                onDismiss()
            }) { Text("Done") }
        }
    )
}

@Composable
fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import PGN or FEN") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                placeholder = { Text("Paste a PGN game or a FEN string") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Load") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun HistoryDialog(
    entries: List<HistoryEntry>,
    onDismiss: () -> Unit,
    onLoad: (HistoryEntry) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recent games") },
        text = {
            Column(Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) {
                    Text("Finished games will show up here.")
                } else {
                    entries.forEach { entry ->
                        Text(
                            entry.summary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLoad(entry) }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

