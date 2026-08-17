package io.github.soulxyz.xyprt.data

import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DeltaPatchApplierTest {
    @Test fun rebuildsExactTargetFromCopyAndDataOperations() {
        val dir = createTempDir(prefix = "xydelta-")
        try {
            val old = File(dir, "old.apk").apply { writeBytes("abcdefghij0123456789".toByteArray()) }
            val expected = "abcXYZhij0123456789TAIL".toByteArray()
            val patch = File(dir, "p.xydelta")
            DataOutputStream(GZIPOutputStream(patch.outputStream())).use { out ->
                out.write(byteArrayOf('X'.code.toByte(),'Y'.code.toByte(),'D'.code.toByte(),'L'.code.toByte(),'T'.code.toByte(),'A'.code.toByte(),'1'.code.toByte(),0))
                out.writeInt(1)
                out.writeLong(old.length())
                out.write(sha(old.readBytes()))
                out.writeLong(expected.size.toLong())
                out.write(sha(expected))
                out.writeInt(4)
                out.writeByte(0); out.writeLong(0); out.writeInt(3)       // abc
                out.writeByte(1); out.writeInt(3); out.write("XYZ".toByteArray())
                out.writeByte(0); out.writeLong(7); out.writeInt(13)      // hij0123456789
                out.writeByte(1); out.writeInt(4); out.write("TAIL".toByteArray())
            }
            val rebuilt = File(dir, "new.apk")
            DeltaPatchApplier.apply(old, patch, rebuilt)
            assertArrayEquals(expected, rebuilt.readBytes())
        } finally { dir.deleteRecursively() }
    }

    private fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
