package com.huttsmedia.chess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChessStateParseTest {
    @Test
    fun parsesStartingBoardJson() {
        val json = """
            {
              "pieces": [
                [{"type":"ROOK","color":"BLACK"},null,null,null,null,null,null,null],
                [null,null,null,null,null,null,null,null],
                [null,null,null,null,null,null,null,null],
                [null,null,null,null,null,null,null,null],
                [null,null,null,null,null,null,null,null],
                [null,null,null,null,null,null,null,null],
                [null,null,null,null,null,null,null,null],
                [null,null,null,null,{"type":"KING","color":"WHITE"},null,null,null]
              ],
              "capturedByWhite": [],
              "capturedByBlack": [],
              "moves": ["e4"],
              "selected": [6, 4],
              "turn": "BLACK",
              "gameStatus": null,
              "promotionPending": false,
              "promotionColor": null,
              "isBoardFlipped": false,
              "kingInCheck": false
            }
        """.trimIndent()
        val state = parseChessState(json, GameMode.TwoPlayer, gameStarted = true, isAiThinking = false)
        assertEquals(PieceType.ROOK, state.pieces[0][0]?.type)
        assertEquals(PieceColor.WHITE, state.pieces[7][4]?.color)
        assertEquals(listOf("e4"), state.moves)
        assertEquals(Position(6, 4), state.selectedPiece)
        assertEquals(PieceColor.BLACK, state.turn)
        assertNull(state.gameStatus)
        assertEquals("none", state.lastEvent)
        assertEquals(0, state.material)
    }

    @Test
    fun parsesSaveModeAndEvents() {
        val json = """
            {
              "pieces": [[null,null,null,null,null,null,null,null],[null,null,null,null,null,null,null,null],[null,null,null,null,null,null,null,null],[null,null,null,null,null,null,null,null],[null,null,null,null,null,null,null,null],[null,null,null,null,null,null,null,null],[null,null,null,null,null,null,null,null],[null,null,null,null,null,null,null,null]],
              "moves": ["e4","e5"],
              "turn": "WHITE",
              "pgn": "1. e4 e5",
              "material": -1,
              "lastEvent": "capture"
            }
        """.trimIndent()
        val state = parseChessState(json, GameMode.TwoPlayer, gameStarted = true, isAiThinking = false)
        assertEquals("1. e4 e5", state.pgn)
        assertEquals(-1, state.material)
        assertEquals("capture", state.lastEvent)
        val mode = parseSavedGameMode("""{"vsAi":true,"playAsWhite":false,"difficulty":3}""")
        val ai = mode as GameMode.VsAI
        assertEquals(PieceColor.BLACK, ai.playerColor)
        assertEquals(AiDifficulty.GRANDMASTER, ai.difficulty)
    }
}
