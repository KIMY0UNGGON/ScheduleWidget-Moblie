package com.schedulewidget.mobile.pet

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PetImportZipSafetyTest {
    @Test
    fun zipEntryCountIsCappedBeforeImportKeepsEntryObjects() {
        val file = File.createTempFile("pet-entry-cap", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                repeat(1025) { index ->
                    zip.putNextEntry(ZipEntry("entry-$index"))
                    zip.closeEntry()
                }
            }
            ZipFile(file).use { assertNull(PetImport.zipEntriesWithinLimit(it)) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun normalSmallZipStillEnumerates() {
        val file = File.createTempFile("pet-entry-cap", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("pet.json"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("image.png"))
                zip.closeEntry()
            }
            ZipFile(file).use { assertNotNull(PetImport.zipEntriesWithinLimit(it)) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun zipEntryNameLengthIsCapped() {
        val file = File.createTempFile("pet-entry-name", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("x".repeat(2049)))
                zip.closeEntry()
            }
            ZipFile(file).use { assertNull(PetImport.zipEntriesWithinLimit(it)) }
        } finally {
            file.delete()
        }
    }
}
