package jumpscan.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** What the record share card shows (SPEC 10.3); every string is already localised by the caller. */
internal data class RecordShare(val name: String, val value: String, val previous: String?, val date: String, val jumpCm: Double?)

/** The record share card: an Instrument-style image drawn on the phone and never uploaded. */
internal object RecordCard {
    const val WIDTH = 1080
    const val HEIGHT = 1350

    fun render(context: Context, record: RecordShare): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val res = context.resources
        val hero = context.getColor(R.color.hero); val heroTop = context.getColor(R.color.hero_top)
        val signal = context.getColor(R.color.signal); val ink = context.getColor(R.color.on_hero)
        val muted = 0x99DBDBD8.toInt()
        val grotesk = res.getFont(R.font.spacegrotesk_bold); val regular = res.getFont(R.font.spacegrotesk_regular)
        val mono = res.getFont(R.font.jetbrainsmono_regular)
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), heroTop, hero, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), pen)
        pen.shader = null
        val margin = 84f
        // Wordmark: the arc mark and TALOS, as on the dashboard masthead.
        pen.style = Paint.Style.STROKE; pen.strokeWidth = 9f; pen.strokeCap = Paint.Cap.ROUND; pen.strokeJoin = Paint.Join.ROUND; pen.color = signal
        canvas.drawPath(Path().apply { moveTo(margin, 128f); lineTo(margin + 30, 88f); lineTo(margin + 60, 128f) }, pen)
        pen.style = Paint.Style.FILL; pen.color = ink; pen.typeface = grotesk; pen.textSize = 46f; pen.letterSpacing = .16f
        canvas.drawText("TALOS", margin + 84, 128f, pen)
        pen.letterSpacing = .14f; pen.typeface = mono; pen.textSize = 30f; pen.color = signal
        canvas.drawText(context.getString(R.string.share_new_record), margin, 262f, pen)
        pen.letterSpacing = 0f
        val name = TextPaint(pen).apply { typeface = grotesk; textSize = 64f; color = ink; letterSpacing = -.02f }
        val title = StaticLayout.Builder.obtain(record.name, 0, record.name.length, name, (WIDTH - 2 * margin).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        canvas.save(); canvas.translate(margin, 290f); title.draw(canvas); canvas.restore()
        val valueTop = 290f + title.height + 190f
        pen.typeface = mono; pen.color = android.graphics.Color.WHITE; pen.textSize = 168f
        while (pen.measureText(record.value) > WIDTH - 2 * margin && pen.textSize > 60f) pen.textSize -= 6f
        canvas.drawText(record.value, margin, valueTop, pen)
        pen.textSize = 34f; pen.color = muted; pen.typeface = regular
        canvas.drawText(record.previous?.let { context.getString(R.string.share_previous, it) } ?: context.getString(R.string.share_first),
            margin, valueTop + 72f, pen)
        val ground = HEIGHT - 230f
        val top = valueTop + 150f
        pen.style = Paint.Style.STROKE; pen.strokeWidth = 3f; pen.color = 0x33FFFFFF
        canvas.drawLine(margin, ground, WIDTH - margin, ground, pen)
        if (record.jumpCm != null) {
            // The flight arc: a parabola whose apex sits at the jump height on a 0 to 60 cm ruler.
            val scale = (ground - top) / 60f
            val apex = ground - (record.jumpCm.coerceIn(0.0, 60.0) * scale).toFloat()
            pen.strokeWidth = 2f; pen.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            for (cm in listOf(20, 40, 60)) {
                val y = ground - cm * scale
                canvas.drawLine(margin, y, WIDTH - margin, y, pen)
            }
            pen.pathEffect = null; pen.strokeWidth = 8f; pen.color = signal
            val left = margin + 120f; val right = WIDTH - margin - 120f; val mid = (left + right) / 2
            canvas.drawPath(Path().apply { moveTo(left, ground); quadTo(mid, 2 * apex - ground, right, ground) }, pen)
            pen.style = Paint.Style.FILL
            canvas.drawCircle(mid, apex, 14f, pen)
            pen.textSize = 26f; pen.typeface = mono; pen.color = muted
            for (cm in listOf(20, 40, 60)) canvas.drawText("$cm", margin, ground - cm * scale - 10f, pen)
        } else {
            // A timing strip: ticks along the ground line, the last one lit.
            pen.strokeWidth = 4f
            for (index in 0..20) {
                val x = margin + index * (WIDTH - 2 * margin) / 20
                pen.color = if (index == 20) signal else 0x55FFFFFF
                canvas.drawLine(x, ground, x, ground - if (index % 5 == 0) 60f else 30f, pen)
            }
            pen.style = Paint.Style.FILL
        }
        pen.style = Paint.Style.FILL; pen.typeface = mono; pen.textSize = 32f; pen.color = ink
        canvas.drawText(record.date, margin, HEIGHT - 120f, pen)
        return bitmap
    }

    /** Render, save into the share cache and open the Android share sheet. */
    fun share(context: Context, record: RecordShare) {
        val folder = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(folder, "talos-record.png")
        val bitmap = render(context, record)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".diagnostic-files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = android.content.ClipData.newRawUri("", uri) }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)))
    }
}
