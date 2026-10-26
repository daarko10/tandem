package dev.daarkolabs.tandem.ui

import com.intellij.util.ui.JBUI
import dev.daarkolabs.tandem.session.SessionStatus
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import javax.swing.Icon

/**
 * A session tab's icon (desktop-parity spec 18.2): the sidebar's status dot, 8 px centred in a 12 px square, in the colour of the
 * theme's icon badges (running, waiting, idle) or the disabled label colour (stopped), with a 1 px ring at the edge in the warning
 * badge's colour while the session needs the user. The colours are read when painted, so a theme change shows at the next paint.
 * Equal by value, so an unchanged tab keeps its icon.
 */
internal data class SessionTabIcon(val status: SessionStatus, val attention: Boolean) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(SIZE)

    override fun getIconHeight(): Int = JBUI.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = JBUI.scale(SIZE).toDouble()
            val dot = JBUI.scale(DOT).toDouble()
            g2.color = dotColor(status)
            g2.fill(Ellipse2D.Double(x + (size - dot) / 2, y + (size - dot) / 2, dot, dot))
            if (attention) {
                val ring = JBUI.scale(1).toFloat()
                g2.color = JBUI.CurrentTheme.IconBadge.WARNING
                g2.stroke = BasicStroke(ring)
                g2.draw(Ellipse2D.Double(x + ring / 2.0, y + ring / 2.0, size - ring, size - ring))
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val SIZE: Int = 12
        const val DOT: Int = 8

        fun dotColor(status: SessionStatus): Color = when (status) {
            SessionStatus.RUNNING -> JBUI.CurrentTheme.IconBadge.INFORMATION
            SessionStatus.WAITING -> JBUI.CurrentTheme.IconBadge.WARNING
            SessionStatus.IDLE -> JBUI.CurrentTheme.IconBadge.SUCCESS
            SessionStatus.STOPPED -> JBUI.CurrentTheme.Label.disabledForeground()
        }
    }
}
