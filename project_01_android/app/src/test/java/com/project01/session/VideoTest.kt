package com.project01.session

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.junit.Assert.*

@RunWith(MockitoJUnitRunner::class)
class VideoTest {

    @Mock
    lateinit var mockUri: Uri

    @Test
    fun `video can be instantiated`() {
        val video = Video(mockUri, "Video 1")
        assertEquals("Video 1", video.title)
        assertEquals(mockUri, video.uri)
    }
}
