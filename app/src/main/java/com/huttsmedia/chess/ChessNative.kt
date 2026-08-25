package com.huttsmedia.chess

object ChessNative {
    init {
        System.loadLibrary("chessjni")
    }

    external fun newGame(vsAi: Boolean, playAsWhite: Boolean, difficulty: Int): String
    external fun onSquareClick(row: Int, col: Int): String
    external fun promote(piece: String): String
    external fun aiMove(): String
    external fun getState(): String
}
