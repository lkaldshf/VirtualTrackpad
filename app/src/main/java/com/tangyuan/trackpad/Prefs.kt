package com.tangyuan.trackpad

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "trackpad_prefs"

    const val KEY_SENSITIVITY = "sensitivity"
    const val KEY_CURSOR_SIZE = "cursor_size"
    const val KEY_PAD_W = "pad_w"
    const val KEY_PAD_X = "pad_x"
    const val KEY_PAD_Y = "pad_y"
    const val KEY_BUBBLE_X = "bubble_x"
    const val KEY_BUBBLE_Y = "bubble_y"
    const val KEY_HAPTIC = "haptic"
    const val KEY_COLLAPSED = "collapsed"
    const val KEY_FLICK_GUESS = "flick_guess"

    fun of(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // 默认值集中在这里，Activity 和 Service 共用同一套
    val DEFAULT_SENSITIVITY = 3.0f  // 跟手倍率：手指 1px → 光标 3px
    val DEFAULT_CURSOR_SIZE = 28f   // dp，光标圆点直径
    val DEFAULT_PAD_W = 140f        // dp，圆盘直径
    const val DEFAULT_HAPTIC = true
    const val DEFAULT_FLICK_GUESS = true  // 快刷滑屏：关掉后快刷只挪光标，不注入滑动
}
