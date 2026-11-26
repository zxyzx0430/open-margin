package com.bookkeeping.app.backup.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavClientTest {

    @Test
    fun parsePropfindXmlExtractsZipBackupsSortedDesc() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/清衡备份/</D:href>
                <D:propstat><D:prop><D:getcontentlength>0</D:getcontentlength></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/清衡备份/open-margin-backup-20261124-100000.zip</D:href>
                <D:propstat>
                  <D:prop>
                    <D:getcontentlength>102400</D:getcontentlength>
                    <D:getlastmodified>Tue, 24 Nov 2026 10:00:00 GMT</D:getlastmodified>
                  </D:prop>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/清衡备份/open-margin-backup-20261125-210000.zip.tmp</D:href>
                <D:propstat><D:prop><D:getcontentlength>500</D:getcontentlength></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val files = WebDavClient.parsePropfindXml("https://dav.jianguoyun.com/dav/清衡备份/", xml)
        val zips = files.filter { it.name.endsWith(".zip") }
        assertEquals(1, zips.size)
        assertEquals("open-margin-backup-20261124-100000.zip", zips.first().name)
        assertEquals(102400L, zips.first().sizeBytes)
        assertTrue(zips.first().modifiedMs > 0L)
    }
}
