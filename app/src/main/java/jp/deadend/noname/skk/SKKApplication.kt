package jp.deadend.noname.skk

import android.app.Application

val skkPrefs: SKKPrefs by lazy { SKKApplication.prefs!! }

class SKKApplication : Application() {
    companion object {
        var prefs: SKKPrefs? = null
    }

    override fun onCreate() {
        prefs = SKKPrefs(applicationContext)
        super.onCreate()
    }
}