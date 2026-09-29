package com.ev3.spacesound

import android.content.Context
import com.ev3.spacesound.audio.Packs

/** Remembers the chosen sound pack between runs. */
object PackPrefs {
    private const val FILE = "ev3spacesound"
    private const val KEY = "pack"

    fun load(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY, 1).coerceIn(0, Packs.all.size - 1)

    fun loadBass(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("bass", 0).coerceIn(0, 15)

    fun saveBass(context: Context, db: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("bass", db).apply()
    }

    fun save(context: Context, index: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY, index).apply()
    }
}
