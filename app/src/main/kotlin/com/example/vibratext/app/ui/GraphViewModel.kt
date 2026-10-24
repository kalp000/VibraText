package com.example.vibratext.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.vibratext.app.AppGraph
import com.example.vibratext.app.VibraTextApp

/** Base class: gives view models the shared [AppGraph] (storage, playback controller, ...). */
abstract class GraphViewModel(app: Application) : AndroidViewModel(app) {
    protected val graph: AppGraph = (app as VibraTextApp).graph
}
