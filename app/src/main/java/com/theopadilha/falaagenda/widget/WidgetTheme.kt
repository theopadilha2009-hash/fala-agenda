package com.theopadilha.falaagenda.widget

import android.content.Context
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.theopadilha.falaagenda.R
import com.theopadilha.falaagenda.data.prefs.ThemeMode

/**
 * Cores do widget para um tema explícito. Os ids apontam para @color que existe só em
 * values/ (nunca em values-night): é isso que faz "Claro" valer mesmo com o celular no
 * modo escuro, onde o launcher resolveria @color/widget_bg para a versão da noite.
 */
internal data class WidgetPalette(
    @param:ColorRes val background: Int,
    @param:ColorRes val text: Int,
    @param:ColorRes val muted: Int,
    @param:ColorRes val accent: Int,
    @param:ColorRes val onAccent: Int,
)

/** As mesmas cores já resolvidas, prontas para o RemoteViews. */
internal data class WidgetColors(
    val background: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
)

/** null = "seguir o celular": o widget continua com as cores do values-night. */
internal fun paletteFor(mode: ThemeMode): WidgetPalette? = when (mode) {
    ThemeMode.SYSTEM -> null
    ThemeMode.LIGHT -> WidgetPalette(
        background = R.color.widget_light_bg,
        text = R.color.widget_light_text,
        muted = R.color.widget_light_muted,
        accent = R.color.widget_light_accent,
        onAccent = R.color.widget_light_on_accent,
    )
    ThemeMode.DARK -> WidgetPalette(
        background = R.color.widget_dark_bg,
        text = R.color.widget_dark_text,
        muted = R.color.widget_dark_muted,
        accent = R.color.widget_dark_accent,
        onAccent = R.color.widget_dark_on_accent,
    )
}

internal fun widgetColors(context: Context, mode: ThemeMode): WidgetColors? =
    paletteFor(mode)?.let { palette ->
        WidgetColors(
            background = ContextCompat.getColor(context, palette.background),
            text = ContextCompat.getColor(context, palette.text),
            muted = ContextCompat.getColor(context, palette.muted),
            accent = ContextCompat.getColor(context, palette.accent),
            onAccent = ContextCompat.getColor(context, palette.onAccent),
        )
    }
