package app.pausecn.reports

import android.graphics.Bitmap

/** Stable sharing entry point; the renderer accepts only the public projection. */
internal object ReportShareRenderer {
    const val WIDTH = 1080
    fun render(model: ReportShareModel): Bitmap = ReportCardRenderer.render(model)
}
