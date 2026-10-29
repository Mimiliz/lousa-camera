package br.com.mimiexplica.sala

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import kotlin.math.min

/** Ícone desenhado a partir de um caminho SVG (grade 24x24). */
class IconDrawable(data: String, private val filled: Boolean = false) : Drawable() {
    private val path = PathParser.createPathFromPathData(data)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFECEEF2.toInt()
    }
    var color: Int
        get() = paint.color
        set(v) { paint.color = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.exactCenterX() - 12 * s, b.exactCenterY() - 12 * s)
        canvas.scale(s, s)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object Icons {
    const val CANETA = "M4 20l1-4L16 5l3 3L8 19z M14 7l3 3"
    const val MARCA = "M9 15l-3 3h-3l2-2 1-4 9-9 5 5-9 9z M3 21h18"
    const val RETA = "M5 19L19 5"
    const val MOVER = "M12 3v18M3 12h18M12 3l-3 3M12 3l3 3M12 21l-3-3M12 21l3-3M3 12l3-3M3 12l3 3M21 12l-3-3M21 12l-3 3"
    const val BORRACHA = "M8 20h12M4.5 15.5l9-9a2 2 0 0 1 2.8 0l2.2 2.2a2 2 0 0 1 0 2.8L11 19H8.5z M9 11l5 5"
    const val DESFAZER = "M9 7L4 12l5 5 M4 12h10a6 6 0 0 1 0 12"
    const val REFAZER = "M15 7l5 5-5 5 M20 12H10a6 6 0 0 0 0 12"
    const val LIXEIRA = "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"
    const val ANTERIOR = "M15 5l-7 7 7 7"
    const val PROXIMA = "M9 5l7 7-7 7"
    const val NOVA = "M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M12 9v6M9 12h6"
    const val EXCLUIR = "M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M9.5 9.5l5 5M14.5 9.5l-5 5"
    const val IMPORTAR = "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z M14 3v5h5M12 18v-6M9 15l3-3 3 3"
    const val AJUSTES = "M4 7h10M18 7h2M4 17h4M12 17h8 M16 5a2 2 0 1 1 0 4a2 2 0 1 1 0-4z M10 15a2 2 0 1 1 0 4a2 2 0 1 1 0-4z"
    const val CAMERA = "M5 5h9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M16 10l5-3v10l-5-3"
    const val PAUSA = "M7 5h3v14H7z M14 5h3v14h-3z"
    const val PLAY = "M7 4.5v15l13-7.5z"
    const val MIC = "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3"
    const val MIC_OFF = "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3 M3 3l18 18"
    const val CAM_OFF = "M5 5h9a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M16 10l5-3v10l-5-3 M2 2l20 20"
    const val TROCAR = "M4 7h11l-3-3 M20 17H9l3 3 M15 4l3 3-3 3 M9 20l-3-3 3-3"
    const val TELA = "M4 4h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M8 20h8 M12 16v4 M9 10l3-3 3 3 M12 7v6"
    const val GRAVAR = "M12 4a8 8 0 1 1 0 16a8 8 0 1 1 0-16z"
    const val PARAR = "M7 7h10v10H7z"
    const val CONVIDAR = "M9 11a4 4 0 1 0 0-8a4 4 0 0 0 0 8z M2 21v-1a6 6 0 0 1 12 0v1 M19 8v6M16 11h6"
    const val MIC_TROCAR = "M9 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M3 10a6 6 0 0 0 12 0 M9 16v4 M17 8l3 3-3 3 M20 11h-5"
    const val ALUNO_MUDO = "M9 11a4 4 0 1 0 0-8a4 4 0 0 0 0 8z M2 21v-1a6 6 0 0 1 12 0v1 M16 9l5 5M21 9l-5 5"
    const val SAIR = "M10 17l5-5-5-5 M15 12H3 M14 3h5a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-5"
}
