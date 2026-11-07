package com.example.ui

import org.junit.Assert.*
import org.junit.Test

class SurfaceReferenceTest{
    @Test fun retiringViewCannotClearItsReplacement(){
        val old=Any();val replacement=Any();val reference=SurfaceReference<Any>()
        reference.created(old);reference.created(replacement);reference.released(old)
        assertSame(replacement,reference.current)
        reference.released(replacement);assertNull(reference.current)
    }
    @Test fun releaseUsesIdentityEvenWhenTwoViewsCompareEqual(){
        data class Fixture(val id:Int)
        val old=Fixture(1);val replacement=Fixture(1);val reference=SurfaceReference<Fixture>()
        reference.created(replacement);reference.released(old)
        assertSame(replacement,reference.current)
        reference.released(replacement);reference.released(replacement);assertNull(reference.current)
    }
}
