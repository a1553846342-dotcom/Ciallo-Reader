package com.example.download

import com.example.source.zlibrary.DiamWallInterceptor
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BackendDownloadPolicyTest {
    @Test fun rejectsWrongOffsetMissingAndOverflowedContentRanges() {
        assertEquals(100L, DownloadTransferPolicy.validateTail("bytes 50-99/100", 50, 50))
        for (header in listOf(null, "bytes 40-99/100", "bytes 50-89/100", "bytes 50-100/100", "bytes 999999999999999999999-99/100")) {
            assertTrue(runCatching { DownloadTransferPolicy.validateTail(header, 50, 50) }.isFailure)
        }
        assertTrue(runCatching { DownloadTransferPolicy.validateTail("bytes 50-99/100", 50, 49) }.isFailure)
    }

    @Test fun weakEtagsNeverAuthorizeAppending() {
        assertNull(DownloadTransferPolicy.validator("W/\"v1\"", null))
        assertEquals("\"v1\"", DownloadTransferPolicy.validator("\"v1\"", null))
        assertEquals("date", DownloadTransferPolicy.validator("W/\"v1\"", "date"))
    }

    @Test fun differentSourcesAndSanitizedNamesCannotCollide() {
        assertNotEquals(DownloadManager.taskId("源一", "42"), DownloadManager.taskId("源二", "42"))
        assertNotEquals(DownloadManager.taskId("a", "b::c"), DownloadManager.taskId("a::b", "c"))
        assertNotEquals(DownloadManager.sanitizeFileName("书/一"), DownloadManager.sanitizeFileName("书/二"))
        val key = DownloadManager.taskId("源:一", "book/42")
        assertEquals("book/42", DownloadManager.originalBookId(key, "源:一"))
        assertTrue(DownloadManager.sanitizeFileName("长标题".repeat(300)).length < 100)
    }

    @Test fun powIsCorrectAndRejectsUnboundedOrCancelledWork() {
        assertNull(DiamWallInterceptor.solveSha256("token", 100))
        assertNull(DiamWallInterceptor.solveSha256("token", 2) { true })
        val nonce = DiamWallInterceptor.solveSha256("test-token", 2)!!
        assertEquals(0.toByte(), MessageDigest.getInstance("SHA-256").digest("test-token:$nonce".toByteArray())[0])
        assertEquals(nonce, DiamWallInterceptor.solveSha256("test-token", 2))
    }

    @Test fun literalHtmlTextIsAcceptedButChallengesAreRejected() {
        val file = File.createTempFile("backend-html", ".txt")
        try {
            file.writeText("<html>本书介绍 HTML 标记写法</html>")
            assertTrue(DownloadFileValidator.validateFileIntegrity(file, "txt").valid)
            file.writeText("<html>Checking your browser - diamwall</html>")
            assertFalse(DownloadFileValidator.validateFileIntegrity(file, "txt").valid)
        } finally { file.delete() }
    }
}
