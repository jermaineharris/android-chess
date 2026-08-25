package com.huttsmedia.chess

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class EngineInfo(
    val depth: Int,
    val cp: Int?,
    val mate: Int?,
    val pvUci: List<String>,
    val engine: String
)

/**
 * Stockfish 17.1 + embedded NNUE, run as a separate GPL process (not linked into chessjni).
 */
class StockfishEngine(private val context: Context) {
    private val lock = Any()
    private var process: Process? = null
    private val lines = LinkedBlockingQueue<String>()
    private var readerThread: Thread? = null
    @Volatile var ready = false
        private set
    private val stopFlag = AtomicBoolean(false)

    fun start(): Boolean {
        synchronized(lock) {
            if (ready && process?.isAlive == true) return true
            val bin = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
            if (!bin.canExecute()) {
                Log.w(TAG, "Stockfish missing at ${bin.absolutePath}")
                return false
            }
            return try {
                lines.clear()
                val proc = ProcessBuilder(bin.absolutePath)
                    .redirectErrorStream(true)
                    .directory(context.filesDir)
                    .start()
                process = proc
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                readerThread = Thread {
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            lines.put(line)
                        }
                    } catch (_: Exception) {
                    }
                }.also {
                    it.isDaemon = true
                    it.name = "stockfish-stdout"
                    it.start()
                }
                write("uci")
                if (!waitFor("uciok", 5000)) {
                    quitLocked()
                    return false
                }
                write("setoption name Threads value 1")
                write("setoption name Hash value 32")
                write("isready")
                if (!waitFor("readyok", 5000)) {
                    quitLocked()
                    return false
                }
                ready = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "Stockfish start failed", e)
                quitLocked()
                false
            }
        }
    }

    fun stopSearch() {
        stopFlag.set(true)
        synchronized(lock) {
            if (process?.isAlive == true) write("stop")
        }
    }

    fun quit() {
        synchronized(lock) { quitLocked() }
    }

    fun bestMove(fen: String, movetimeMs: Int, elo: Int?): String? {
        if (!start()) return null
        synchronized(lock) {
            stopFlag.set(false)
            configureStrength(elo)
            write("ucinewgame")
            write("isready")
            if (!waitFor("readyok", 4000)) return null
            write("position fen $fen")
            write("go movetime $movetimeMs")
            val deadline = System.currentTimeMillis() + movetimeMs + 5000
            while (System.currentTimeMillis() < deadline) {
                val line = poll(250) ?: continue
                if (line.startsWith("bestmove")) {
                    val mv = line.split(Regex("\\s+")).getOrNull(1) ?: return null
                    return if (mv == "(none)") null else mv
                }
            }
            write("stop")
            return null
        }
    }

    fun searchOnce(fen: String, movetimeMs: Int): EngineInfo? {
        if (!start()) return null
        synchronized(lock) {
            stopFlag.set(false)
            configureStrength(null)
            write("ucinewgame")
            write("isready")
            if (!waitFor("readyok", 4000)) return null
            write("position fen $fen")
            write("go movetime $movetimeMs")
            var last: EngineInfo? = null
            val deadline = System.currentTimeMillis() + movetimeMs + 5000
            while (System.currentTimeMillis() < deadline) {
                val line = poll(200) ?: continue
                parseInfo(line)?.let { last = it }
                if (line.startsWith("bestmove")) break
            }
            return last
        }
    }

    fun searchInfinite(fen: String, onInfo: (EngineInfo) -> Unit) {
        if (!start()) return
        synchronized(lock) {
            stopFlag.set(false)
            configureStrength(null)
            write("stop")
            write("ucinewgame")
            write("isready")
            if (!waitFor("readyok", 4000)) return
            write("position fen $fen")
            write("go infinite")
            while (!stopFlag.get() && process?.isAlive == true) {
                val line = poll(200) ?: continue
                parseInfo(line)?.let { onInfo(it) }
                if (line.startsWith("bestmove")) break
            }
        }
    }

    private fun configureStrength(elo: Int?) {
        if (elo != null) {
            write("setoption name UCI_LimitStrength value true")
            write("setoption name UCI_Elo value ${elo.coerceIn(1320, 3190)}")
        } else {
            write("setoption name UCI_LimitStrength value false")
            write("setoption name Skill Level value 20")
        }
    }

    private fun parseInfo(line: String): EngineInfo? {
        if (!line.startsWith("info") || !line.contains(" pv ")) return null
        val parts = line.split(Regex("\\s+"))
        var depth = 0
        var cp: Int? = null
        var mate: Int? = null
        val pv = mutableListOf<String>()
        var i = 0
        while (i < parts.size) {
            when (parts[i]) {
                "depth" -> depth = parts.getOrNull(i + 1)?.toIntOrNull() ?: depth
                "score" -> {
                    when (parts.getOrNull(i + 1)) {
                        "cp" -> cp = parts.getOrNull(i + 2)?.toIntOrNull()
                        "mate" -> mate = parts.getOrNull(i + 2)?.toIntOrNull()
                    }
                }
                "pv" -> {
                    pv += parts.drop(i + 1).takeWhile { tok ->
                        tok.length >= 4 && tok[0].isLetter() && tok[1].isDigit()
                    }
                    break
                }
            }
            i++
        }
        if (pv.isEmpty()) return null
        return EngineInfo(depth, cp, mate, pv, "Stockfish 17.1 NNUE")
    }

    private fun write(cmd: String) {
        val os = process?.outputStream ?: return
        os.write((cmd + "\n").toByteArray())
        os.flush()
    }

    private fun poll(timeoutMs: Long): String? =
        lines.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun waitFor(token: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = poll(200) ?: continue
            if (line.contains(token)) return true
        }
        return false
    }

    private fun quitLocked() {
        ready = false
        stopFlag.set(true)
        try {
            write("quit")
        } catch (_: Exception) {
        }
        process?.destroy()
        process = null
        readerThread = null
        lines.clear()
    }

    companion object {
        private const val TAG = "Stockfish"
    }
}
