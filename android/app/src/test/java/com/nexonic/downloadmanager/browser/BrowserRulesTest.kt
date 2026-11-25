package com.nexonic.downloadmanager.browser

import com.nexonic.downloadmanager.data.DownloadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRulesTest {
    @Test fun classifiesNavigationSchemes() {
        assertEquals(BrowserRules.LinkAction.ALLOW_WEB, BrowserRules.classifyNavigation("https://example.org/file"))
        assertEquals(BrowserRules.LinkAction.ALLOW_WEB, BrowserRules.classifyNavigation("http://example.org/"))
        assertEquals(BrowserRules.LinkAction.MAGNET, BrowserRules.classifyNavigation("magnet:?xt=urn:btih:1234"))
        assertEquals(BrowserRules.LinkAction.NEXONIC, BrowserRules.classifyNavigation("nexonic://add?url=https%3A%2F%2Fa.com%2Fx.zip"))
        assertEquals(BrowserRules.LinkAction.IGNORE, BrowserRules.classifyNavigation("javascript:alert(1)"))
        assertEquals(BrowserRules.LinkAction.IGNORE, BrowserRules.classifyNavigation("tel:+1234567"))
    }

    @Test fun detectsTorrentWebDownloads() {
        assertTrue(BrowserRules.isTorrentDownload("https://example.org/ubuntu.torrent?token=1", "application/octet-stream"))
        assertTrue(BrowserRules.isTorrentDownload("https://example.org/get?id=99", "application/x-bittorrent; charset=utf-8"))
        assertFalse(BrowserRules.isTorrentDownload("https://example.org/archive.zip", "application/zip"))
    }

    @Test fun rejectsStaleAndStreamingDetections() {
        assertTrue(BrowserRules.shouldCollectRequest("https://cdn.test/movie.mp4", isForMainFrame = false))
        assertFalse(BrowserRules.shouldCollectRequest("https://cdn.test/manifest.mpd", isForMainFrame = false))
        assertFalse(BrowserRules.shouldCollectRequest("https://cdn.test/seg-1.m4s", isForMainFrame = false))
        assertFalse(BrowserRules.shouldCollectRequest("https://cdn.test/seg-1.ts", isForMainFrame = false))
        assertFalse(BrowserRules.shouldCollectRequest("https://cdn.test/master.m3u8", isForMainFrame = false))
        assertTrue(BrowserRules.acceptScanItem("https://cdn.test/clip.mp4", "Video"))
        assertFalse(BrowserRules.acceptScanItem("https://cdn.test/manifest.mpd", "Video"))
        assertFalse(BrowserRules.acceptScanItem("https://cdn.test/seg.ts", "Link"))
    }

    @Test fun triggersRescanOnlyOnSpaRouteChange() {
        val initial = BrowserRules.pageKey("https://example.org/watch?v=1#t=10")
        assertFalse(BrowserRules.shouldRescanOnVisitedUpdate(initial, "https://example.org/watch?v=1#t=20", isReload = false))
        assertFalse(BrowserRules.shouldRescanOnVisitedUpdate(initial, "https://example.org/watch?v=2", isReload = true))
        assertFalse(BrowserRules.shouldRescanOnVisitedUpdate(initial, "about:blank", isReload = false))
        assertTrue(BrowserRules.shouldRescanOnVisitedUpdate(initial, "https://example.org/watch?v=2", isReload = false))
    }

    @Test fun preservesVideoAudioFlagAcrossJson() {
        val originalMkv = DownloadItem(id = 1L, url = "https://v.test/a", fileName = "clip.mkv", videoFormat = "bv*+ba/b", videoExt = "mkv", videoAudio = false)
        val audioM4a = DownloadItem(id = 2L, url = "https://v.test/b", fileName = "song.m4a", videoFormat = "ba[ext=m4a]/ba/b", videoExt = "m4a", videoAudio = true)
        assertFalse(DownloadItem.fromJson(originalMkv.toJson()).videoAudio)
        assertTrue(DownloadItem.fromJson(audioM4a.toJson()).videoAudio)
    }
}
