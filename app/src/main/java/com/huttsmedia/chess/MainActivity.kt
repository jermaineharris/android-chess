package com.huttsmedia.chess

import android.content.ClipData
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
    dismissable: Boolean,
    onDismiss: () -> Unit,
    onNewGame: (GameMode) -> Unit
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
            confirmButton = { }
        )
    } ?: AlertDialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        title = { Text(text = "New Game") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = { onNewGame(GameMode.TwoPlayer) }) {
                    Text("2-Player Local")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    showSettings = { color, difficulty ->
                        onNewGame(GameMode.VsAI(color, difficulty))
                    }
                }) {
                    Text("Human vs AI")
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
    var showNewGameDialog by remember { mutableStateOf(false) }
    var confirmNewGame by remember { mutableStateOf(false) }
    var confirmResign by remember { mutableStateOf(false) }
    val promotionColor = uiState.promotionColor

    BackHandler(enabled = uiState.selectedPiece != null) {
        viewModel.onDeselect()
    }

    LaunchedEffect(uiState.moves.size, uiState.lastEvent, uiState.gameOver) {
        val feedback = when (uiState.lastEvent) {
            "capture" -> HapticFeedbackConstants.LONG_PRESS
            "check", "mate", "resign" -> HapticFeedbackConstants.REJECT
            "move" -> HapticFeedbackConstants.CLOCK_TICK
            else -> return@LaunchedEffect
        }
        view.performHapticFeedback(feedback)
    }

    if (uiState.promotionPending && promotionColor != null) {
        PawnPromotionDialog(promotionColor, onPromote = onPromote)
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
            dismissable = true,
            onDismiss = { showNewGameDialog = false },
            onNewGame = {
                viewModel.onNewGame(it)
                showNewGameDialog = false
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

    Scaffold { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CapturedPiecesRow(topCaptured)
            Spacer(modifier = Modifier.height(8.dp))
            Text(turnLabel, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("Material $materialLabel", fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            MovesList(moves = uiState.moves, turn = uiState.turn)
            Spacer(modifier = Modifier.height(8.dp))
            ChessBoard(
                uiState = uiState,
                onSquareClick = onSquareClick
            )
            Spacer(modifier = Modifier.height(8.dp))
            CapturedPiecesRow(bottomCaptured)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                OutlinedButton(onClick = onUndo, enabled = uiState.canUndo && !uiState.isAiThinking) {
                    Text("Undo")
                }
                OutlinedButton(onClick = viewModel::onToggleFlip, enabled = uiState.gameStarted) {
                    Text("Flip")
                }
                OutlinedButton(
                    onClick = { confirmResign = true },
                    enabled = uiState.gameStarted && !uiState.gameOver && !uiState.isAiThinking
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
                        val pgn = uiState.pgn.ifBlank { "1." }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PGN", pgn))
                        Toast.makeText(context, "PGN copied", Toast.LENGTH_SHORT).show()
                    },
                    enabled = uiState.gameStarted
                ) {
                    Text("Copy PGN")
                }
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
fun MovesList(moves: List<String>, turn: PieceColor) {
    val rows = moves.chunked(2)
    val scroll = rememberScrollState()
    LaunchedEffect(moves.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .border(2.dp, Color.Gray)
            .verticalScroll(scroll)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            Text("White", fontWeight = if (turn == PieceColor.WHITE) FontWeight.Bold else FontWeight.Normal)
            VerticalDivider(color = MaterialTheme.colorScheme.primary)
            Text("Black", fontWeight = if (turn == PieceColor.BLACK) FontWeight.Bold else FontWeight.Normal)
        }
        rows.forEachIndexed { index, move ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}.",
                    Modifier.width(28.dp).padding(start = 4.dp),
                    fontSize = 13.sp
                )
                Text(move[0], Modifier.weight(1f), textAlign = TextAlign.Center)
                if (move.size == 2) {
                    VerticalDivider(color = MaterialTheme.colorScheme.primary)
                    Text(move[1], Modifier.weight(1f), textAlign = TextAlign.Center)
                } else {
                    VerticalDivider()
                    Text("", Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun PawnPromotionDialog(color: PieceColor, onPromote: (PieceType) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(text = "Promote Pawn") },
        text = {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                for (pieceType in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                    Box(Modifier.clickable { onPromote(pieceType) }) {
                        ChessPiece(Piece(pieceType, color), 64.dp)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun CapturedPiecesRow(pieces: List<Piece>) {
    Box(
        Modifier
            .height(48.dp)
            .padding(4.dp), Alignment.Center
    ) {
        if (pieces.isNotEmpty()) {
            Card {
                Row(Modifier.padding(4.dp)) {
                    pieces.forEach { ChessPiece(it, 32.dp) }
                }
            }
        }
    }
}

@Composable
fun ChessBoard(
    uiState: ChessUiState,
    onSquareClick: (Position) -> Unit
) {
    val flipped = uiState.isBoardFlipped
    val lastMove = uiState.lastMove
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
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
                for (displayRow in 0..7) {
                    val logicalRow = if (flipped) 7 - displayRow else displayRow
                    Text("${8 - logicalRow}", fontSize = 10.sp, textAlign = TextAlign.Center)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
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
                            val isKingCheck =
                                uiState.kingInCheck && piece?.type == PieceType.KING && piece.color == uiState.turn
                            val base = if ((logicalRow + logicalCol) % 2 == 0) Color(0xFFEEEED2) else Color(0xFF769656)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(
                                        when {
                                            isLast -> Color(0xFFBACA2B)
                                            else -> base
                                        }
                                    )
                                    .clickable(enabled = !uiState.isAiThinking && !uiState.gameOver) {
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
                                piece?.let { ChessPiece(it) }
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
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(16.dp))
            Row(Modifier.weight(1f)) {
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

@Composable
fun ChessPiece(piece: Piece, size: Dp? = null) {
    Image(
        painterResource(id = piece.type.resID),
        "${piece.color} ${piece.type}",
        (if (size != null) Modifier.size(size) else Modifier.fillMaxSize())
            .padding(2.dp),
        colorFilter = ColorFilter.tint(
            if (piece.color == PieceColor.WHITE) Color(0xFFF0F0F0) else Color(0xFF1A1A1A)
        )
    )
}

