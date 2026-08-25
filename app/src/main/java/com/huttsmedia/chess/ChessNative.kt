package com.huttsmedia.chess

object ChessNative {
    init {
        System.loadLibrary("chessjni")
    }

    external fun newGame(vsAi: Boolean, playAsWhite: Boolean, difficulty: Int, analysis: Boolean): String
    external fun onSquareClick(row: Int, col: Int): String
    external fun promote(piece: String): String
    external fun undo(): String
    external fun redo(): String
    external fun hint(): String
    external fun aiMove(): String
    external fun getState(): String
    external fun deselect(): String
    external fun toggleFlip(): String
    external fun resign(): String
    external fun offerDraw(): String
    external fun acceptDraw(): String
    external fun declineDraw(): String
    external fun claimDraw(): String
    external fun flagLoss(whiteLost: Boolean): String
    external fun exportSave(): String
    external fun importSave(json: String): String
    external fun importText(text: String, vsAi: Boolean, playAsWhite: Boolean, difficulty: Int, analysis: Boolean): String
    external fun playUci(uci: String): String
    external fun gotoPly(ply: Int): String
    external fun analyze(depth: Int): String
}
