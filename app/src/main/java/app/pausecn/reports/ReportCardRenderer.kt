package app.pausecn.reports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Share projection only, including explicitly selected AI prose. Rings do not encode a score. */
internal object ReportCardRenderer {
    private const val WIDTH = ReportShareRenderer.WIDTH
    private const val MARGIN = 64f
    private const val BODY = WIDTH - MARGIN * 2
    private val ink = Color.rgb(23, 33, 27)
    private val sage = Color.rgb(106, 128, 110)
    private val soft = Color.rgb(226, 234, 224)
    private val paper = Color.rgb(247, 244, 236)
    private val muted = Color.rgb(95, 107, 99)
    private val line = Color.rgb(221, 226, 218)
    private val shades = intArrayOf(soft, Color.rgb(184, 197, 184), Color.rgb(148, 165, 150), sage, Color.rgb(65, 80, 69))

    private fun text(value: String, size: Float, color: Int, width: Int, bold: Boolean = false): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        }
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(5f, 1.08f).setIncludePad(false).build()
    }

    fun render(model: ReportShareModel): Bitmap {
        // Measure and draw from the same layout, including long captions and application names.
        val commands = mutableListOf<(Canvas) -> Unit>()
        fun rounded(x: Float, y: Float, w: Float, h: Float, fill: Int, radius: Float = 32f) {
            commands += { canvas -> canvas.drawRoundRect(x, y, x + w, y + h, radius, radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }) }
        }
        fun label(value: String, x: Float, y: Float, size: Float, color: Int = ink, width: Float = BODY, bold: Boolean = false): Float {
            val layout = text(value, size, color, width.toInt().coerceAtLeast(1), bold)
            commands += { canvas -> canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore() }
            return layout.height.toFloat()
        }
        fun rings(x: Float, y: Float, color: Int) {
            commands += { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 3f }
                listOf(50f, 72f, 92f).forEach { canvas.drawCircle(x, y, it, paint) }
            }
        }

        rounded(MARGIN, 60f, 64f, 64f, ink, 20f)
        rounded(MARGIN + 20, 78f, 8f, 28f, soft, 3f)
        rounded(MARGIN + 36, 78f, 8f, 28f, soft, 3f)
        label("停一下", MARGIN + 84, 67f, 36f, ink, 250f, true)
        label(when (model.template) {
            ReportTemplate.SIMPLE -> "注意力手记"
            ReportTemplate.REFLECTION -> "留一点空间"
            ReportTemplate.HEATMAP -> "我的使用节奏"
        }, 720f, 76f, 25f, muted, 300f)
        var y = 166f
        y += label(model.dates, MARGIN, y, 35f, ink, bold = true) + 12
        y += label(model.cutoff, MARGIN, y, 23f, muted) + 34

        val reflective = model.template == ReportTemplate.REFLECTION
        val heroInk = if (reflective) ink else paper
        val heroMuted = if (reflective) muted else soft
        val primary = model.metrics.firstOrNull()
        val heroValue = primary?.value.orEmpty()
        val valueHeight = text(heroValue, 112f, heroInk, (BODY - 96).toInt(), true).height
        val heroHeight = maxOf(336f, 210f + valueHeight)
        rounded(MARGIN, y, BODY, heroHeight, if (reflective) soft else ink, 40f)
        rings(855f, y + 108, if (reflective) Color.rgb(191, 205, 189) else Color.rgb(55, 72, 59))
        label(if (reflective) "停一停，再出发。" else "给注意力，一点空间。", MARGIN + 48, y + 38, 31f, heroMuted, BODY - 270)
        label(heroValue, MARGIN + 48, y + 105, 112f, heroInk, BODY - 96, true)
        label(primary?.label ?: "本地记录", MARGIN + 50, y + heroHeight - 61, 27f, heroMuted, BODY - 100)
        y += heroHeight + 24

        val pair = model.metrics.drop(1).take(2)
        val cardWidth = (BODY - 24) / 2
        val cardHeight = maxOf(174f, (pair.maxOfOrNull { text(it.value, 52f, ink, (cardWidth - 64).toInt(), true).height } ?: 60) + 96f)
        pair.forEachIndexed { index, metric ->
            val x = MARGIN + index * (cardWidth + 24)
            rounded(x, y, cardWidth, cardHeight, Color.WHITE)
            rounded(x + 30, y + 31, 6f, 28f, sage, 3f)
            label(metric.label, x + 50, y + 28, 27f, muted, cardWidth - 80)
            label(metric.value, x + 32, y + 80, 52f, ink, cardWidth - 64, true)
        }
        if (pair.isNotEmpty()) y += cardHeight + 24
        model.metrics.drop(3).forEach { metric ->
            val h = 85f + text(metric.value, 38f, ink, (BODY - 64).toInt(), true).height
            rounded(MARGIN, y, BODY, h, soft)
            label(metric.label, MARGIN + 32, y + 22, 25f, muted, BODY - 64)
            label(metric.value, MARGIN + 32, y + 62, 38f, ink, BODY - 64, true)
            y += h + 24
        }
        model.neutralSummary?.takeIf { model.aiObservations.isEmpty() }?.let { summary ->
            val h = text(summary, 32f, ink, (BODY - 112).toInt()).height
            rounded(MARGIN, y, BODY, h + 110f, Color.WHITE)
            label("“", MARGIN + 32, y + 8, 78f, sage, 80f, true)
            label(summary, MARGIN + 56, y + 65, 32f, ink, BODY - 112)
            y += h + 134f
        }

        if (model.cells.isNotEmpty()) {
            val top = y
            val days = model.cells.chunked(24)
            val h = 156f + days.size * 48
            rounded(MARGIN, top, BODY, h, Color.WHITE)
            label("一天的节奏", MARGIN + 32, top + 26, 30f, ink, 400f, true)
            val maximum = model.cells.mapNotNull { it.value }.maxOrNull()?.coerceAtLeast(1) ?: 1L
            label("0 → ${if (model.heatmapUnit == "分钟") shareDuration(maximum) else "$maximum 次"}", 640f, top + 31, 22f, muted, 340f)
            val left = MARGIN + 126
            val pitch = (BODY - 158) / 24
            listOf(0, 6, 12, 18).forEach { hour -> label("${hour}时", left + hour * pitch, top + 76, 21f, muted, 74f) }
            days.forEachIndexed { row, day ->
                val rowTop = top + 114 + row * 48
                label(day.first().date.takeLast(5), MARGIN + 24, rowTop + 4, 23f, muted, 100f)
                day.forEach { cell ->
                    val shade = if (cell.value == null) paper else shades[if (cell.value <= 0) 0 else kotlin.math.ceil(cell.value.toDouble() / maximum * 4).toInt().coerceIn(1, 4)]
                    val x = left + cell.hour * pitch
                    rounded(x, rowTop, pitch - 4, pitch - 4, shade, 5f)
                    if (cell.value == null) label("—", x + 2, rowTop, 21f, muted, pitch)
                    if (cell.partial && cell.value != null) commands += { canvas -> canvas.drawCircle(x + pitch - 10, rowTop + 7, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }) }
                }
            }
            y += h + 24
        }
        if (model.categories.isNotEmpty()) {
            val height = 104f + model.categories.sumOf { text("${it.label} · ${it.value}", 28f, ink, (BODY - 80).toInt()).height + 18 }
            rounded(MARGIN, y, BODY, height, Color.WHITE)
            label("应用分类 · 当前主分类，最多10类", MARGIN + 40, y + 26, 26f, sage, BODY - 80, true)
            var rowY = y + 78
            model.categories.forEach { row -> rowY += label("${row.label} · ${row.value}", MARGIN + 40, rowY, 28f, ink, BODY - 80) + 18 }
            y += height + 24
        }
        if (model.aiObservations.isNotEmpty()) {
            val paragraphs = model.aiObservations + listOfNotNull(model.aiSuggestion?.let { "留给自己的建议 · $it" })
            val bodyHeight = paragraphs.sumOf { text(it, 30f, ink, (BODY - 96).toInt()).height + 24 }
            val h = bodyHeight + 158f
            rounded(MARGIN, y, BODY, h, Color.WHITE)
            rounded(MARGIN + 32, y + 34, 7f, 32f, sage, 3f)
            label("AI 陪我复盘", MARGIN + 56, y + 30, 32f, sage, BODY - 112, true)
            var paragraphY = y + 94f
            paragraphs.forEach { paragraph -> paragraphY += label(paragraph, MARGIN + 48, paragraphY, 30f, ink, BODY - 96) + 24 }
            label("AI生成 · 个性化表达，不代表事实核验", MARGIN + 48, paragraphY + 4, 22f, muted, BODY - 96)
            y += h + 24
        }
        if (model.caption.isNotBlank()) {
            val height = text(model.caption, 34f, ink, (BODY - 80).toInt()).height + 102f
            rounded(MARGIN, y, BODY, height, Color.WHITE)
            label("此刻想说", MARGIN + 40, y + 26, 24f, sage, BODY - 80, true)
            label(model.caption, MARGIN + 40, y + 68, 34f, ink, BODY - 80)
            y += height + 26
        }
        if (model.appNames.isNotEmpty()) {
            y += label("所选应用 · ${model.appNames.joinToString("、")}${if (model.omittedAppNames > 0) "；另${model.omittedAppNames}个名称未列出" else ""}", MARGIN + 8, y, 24f, muted, BODY - 16) + 24
        }
        rounded(MARGIN, y + 8, BODY, 2f, line, 0f)
        y += 32
        model.notes.forEach { note -> y += label(note, MARGIN + 8, y, 22f, muted, BODY - 16) + 10 }
        y += 24
        label("少一点惯性，多一点自己。", MARGIN, y, 25f, sage, BODY, true)
        y += 72
        val height = y.toInt().coerceAtLeast(1400)
        require(height <= 8000) { "分享内容过长，请减少感想或隐藏应用名称" }
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(paper)
            commands.forEach { it(canvas) }
            return bitmap
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
}
