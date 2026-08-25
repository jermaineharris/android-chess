package com.huttsmedia.chess

import org.json.JSONArray
import org.json.JSONObject

enum class PieceType {
    KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN;

    val resID: Int
        get() = when (this) {
            KING -> R.drawable.chess_king_2_fill1_24px
            QUEEN -> R.drawable.chess_queen_fill1_24px
            ROOK -> R.drawable.chess_rook_fill1_24px
            BISHOP -> R.drawable.chess_bishop_fill1_24px
            KNIGHT -> R.drawable.chess_knight_fill1_24px
            PAWN -> R.drawable.chess_pawn_fill1_24px
        }
}

enum class PieceColor { WHITE, BLACK }

enum class AiDifficulty(val nativeValue: Int) {
    BEGINNER(0),
    INTERMEDIATE(1),
    ADVANCED(2),
    GRANDMASTER(3)
}

data class Piece(val type: PieceType, val color: PieceColor)

data class Position(val row: Int, val col: Int)

sealed class GameMode {
    data object TwoPlayer : GameMode()
    data class VsAI(val playerColor: PieceColor, val difficulty: AiDifficulty) : GameMode()
}

data class ChessUiState(
    val pieces: List<List<Piece?>> = emptyBoard(),
    val capturedByWhite: List<Piece> = emptyList(),
    val capturedByBlack: List<Piece> = emptyList(),
    val moves: List<String> = emptyList(),
    val selectedPiece: Position? = null,
    val gameMode: GameMode = GameMode.TwoPlayer,
    val turn: PieceColor = PieceColor.WHITE,
    val gameStatus: String? = null,
    val isBoardFlipped: Boolean = false,
    val promotionPending: Boolean = false,
    val promotionColor: PieceColor? = null,
    val kingInCheck: Boolean = false
)

fun emptyBoard(): List<List<Piece?>> = List(8) { List(8) { null } }

fun parseChessState(json: String, gameMode: GameMode): ChessUiState {
    val obj = JSONObject(json)
    if (obj.has("error")) {
        return ChessUiState(gameMode = gameMode, gameStatus = obj.optString("error"))
    }
    return ChessUiState(
        pieces = parseBoard(obj.getJSONArray("pieces")),
        capturedByWhite = parsePieceList(obj.optJSONArray("capturedByWhite")),
        capturedByBlack = parsePieceList(obj.optJSONArray("capturedByBlack")),
        moves = parseStringList(obj.optJSONArray("moves")),
        selectedPiece = obj.optJSONArray("selected")?.let {
            Position(it.getInt(0), it.getInt(1))
        },
        gameMode = gameMode,
        turn = PieceColor.valueOf(obj.getString("turn")),
        gameStatus = obj.optNullableString("gameStatus"),
        isBoardFlipped = obj.optBoolean("isBoardFlipped"),
        promotionPending = obj.optBoolean("promotionPending"),
        promotionColor = obj.optNullableString("promotionColor")?.let { PieceColor.valueOf(it) },
        kingInCheck = obj.optBoolean("kingInCheck")
    )
}

private fun parseBoard(rows: JSONArray): List<List<Piece?>> {
    return (0 until rows.length()).map { r ->
        val cols = rows.getJSONArray(r)
        (0 until cols.length()).map { c ->
            if (cols.isNull(c)) null else parsePiece(cols.getJSONObject(c))
        }
    }
}

private fun parsePieceList(arr: JSONArray?): List<Piece> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { parsePiece(arr.getJSONObject(it)) }
}

private fun parseStringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).map { arr.getString(it) }
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return getString(key).takeIf { it.isNotEmpty() }
}

private fun parsePiece(obj: JSONObject): Piece {
    return Piece(
        type = PieceType.valueOf(obj.getString("type")),
        color = PieceColor.valueOf(obj.getString("color"))
    )
}
