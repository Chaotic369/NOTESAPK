package app.ntnotes.android

import android.app.Application

class NtNotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
