package ua.com.radiokot.photoprism

import org.junit.Assert
import org.junit.Test
import ua.com.radiokot.photoprism.features.importt.queue.logic.ImportQueueTakenAt
import java.util.TimeZone

class ImportQueueTakenAtTest {
    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    @Test
    fun summerTime() {
        // 2020-10-06 22:03:26 in Berlin (CEST, UTC+2).
        val update = ImportQueueTakenAt.toPhotoUpdate(1602014606000L, berlin)
        Assert.assertEquals("2020-10-06T20:03:26Z", update.takenAt)
        Assert.assertEquals("2020-10-06T22:03:26Z", update.takenAtLocal)
        Assert.assertEquals("Europe/Berlin", update.timeZone)
        Assert.assertEquals("manual", update.takenSrc)
        Assert.assertEquals(2020, update.year)
        Assert.assertEquals(10, update.month)
        Assert.assertEquals(6, update.day)
    }

    @Test
    fun localDayDiffersFromUtcDay() {
        // 2020-11-16 00:30:00 in Berlin (CET, UTC+1) is still the 15th in UTC.
        val update = ImportQueueTakenAt.toPhotoUpdate(1605483000000L, berlin)
        Assert.assertEquals("2020-11-15T23:30:00Z", update.takenAt)
        Assert.assertEquals("2020-11-16T00:30:00Z", update.takenAtLocal)
        Assert.assertEquals(16, update.day)
    }
}
