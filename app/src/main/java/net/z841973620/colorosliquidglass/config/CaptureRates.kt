package net.z841973620.colorosliquidglass.config

/** 配置读取和设置滑条共用的采样档位；通知的 0 Hz 表示每次打开只采样一次。 */
object CaptureRates {
    const val DEFAULT_VOLUME = 30
    const val DEFAULT_MENU = 1
    const val DEFAULT_NOTIFICATION = 0

    @JvmField val VOLUME = listOf(10, 15, 30, 60, 90, 120)
    @JvmField val MENU = listOf(1, 2, 5, 10, 15, 30, 60, 90, 120)
    @JvmField val NOTIFICATION = listOf(0, 1, 2, 5, 10, 15, 30)

    // SharedPreferences 和导入配置属于外部输入；不支持的档位使用对应默认值。
    @JvmStatic fun volumeOrDefault(value: Int): Int =
        if (value in VOLUME) value else DEFAULT_VOLUME

    @JvmStatic fun menuOrDefault(value: Int): Int =
        if (value in MENU) value else DEFAULT_MENU

    @JvmStatic fun notificationOrDefault(value: Int): Int =
        if (value in NOTIFICATION) value else DEFAULT_NOTIFICATION
}
