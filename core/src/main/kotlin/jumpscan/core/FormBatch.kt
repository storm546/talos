package jumpscan.core

import java.io.File
import java.util.zip.GZIPInputStream

/** JSON lines keep per-repetition metrics and refusal reasons together, one record per input CSV. */
fun runFormBatch(inDir: File, outFile: File, exercise: String = "squat", side: String? = null,
    view: JumpAnalyzer.View = JumpAnalyzer.View.AUTO): String {
    require(inDir.isDirectory) { "input directory not found: $inDir" }
    FormExercises[exercise].validateSide(side)
    fun quote(s: String) = "\"" + s.map { c -> when (c) {
        '\\' -> "\\\\"
        '"' -> "\\\""
        else -> if (c < ' ') "\\u%04x".format(c.code) else c.toString()
    } }.joinToString("") + "\""
    val files = inDir.walkTopDown().filter { it.isFile && it.canonicalFile != outFile.canonicalFile &&
        (it.name.endsWith(".csv") || it.name.endsWith(".csv.gz")) }.sortedBy { it.path }.toList()
    var errors = 0
    outFile.absoluteFile.parentFile?.mkdirs()
    outFile.bufferedWriter().use { writer ->
        for (file in files) {
            val clip = quote(file.relativeTo(inDir).path)
            val record = try {
                val (aspect, frames) = file.inputStream().use { raw ->
                    (if (file.name.endsWith(".gz")) GZIPInputStream(raw) else raw).bufferedReader().use(::readLandmarkCsv)
                }
                val metrics = FormAnalyzer.analyze(frames, exercise, side, aspect, view)
                "{\"clip\":$clip,\"status\":\"ok\",\"error\":null,\"metrics\":${metrics.toJson()}}"
            } catch (e: Exception) {
                errors++
                "{\"clip\":$clip,\"status\":\"error\",\"error\":${quote(e.message ?: e.javaClass.simpleName)},\"metrics\":null}"
            }
            writer.write(record + "\n")
        }
    }
    return "clips=${files.size} errors=$errors (refusals are errors, never zero-repetition successes)"
}

object FormBatch {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) { "usage: formBatch <input dir> <output jsonl> <exercise> <L|R|none> <AUTO|FRONT|SIDE>" }
        println(runFormBatch(File(args[0]), File(args[1]), args[2], args[3].takeUnless { it == "none" },
            JumpAnalyzer.View.valueOf(args[4])))
    }
}
