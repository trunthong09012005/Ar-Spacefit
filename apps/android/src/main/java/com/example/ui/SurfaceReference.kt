package com.example.ui

/** A retiring SceneView must not clear the replacement installed during the same Compose update. */
internal class SurfaceReference<T:Any>{
    var current:T?=null
        private set
    fun created(surface:T){current=surface}
    fun released(surface:T){if(current===surface)current=null}
}
