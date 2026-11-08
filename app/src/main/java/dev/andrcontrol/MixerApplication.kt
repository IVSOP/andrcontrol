package dev.andrcontrol

import android.app.Application

class MixerApplication : Application() {
    val mixer by lazy { MixerController(this) }
}

