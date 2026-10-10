package tribixbite.cleverkeys.openjev.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Debug distribution helper: explicitly run once after staging cache/openjev-bundle.zip via adb run-as. */
@RunWith(AndroidJUnit4::class)
class OpenJevModelInstallationInstrumentedTest {
    @Test fun importStagedBundleThroughVerifiedStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundle = File(context.cacheDir, "openjev-bundle.zip")
        assertTrue("Stage a verified local model bundle in the debug app cache first", bundle.isFile)
        assertTrue(bundle.length() in 1..920_000_000L)
        bundle.inputStream().use { OpenJevModelStore(context).importBundle(it) }
        assertEquals("INSTALLED", OpenJevModelStore(context).status())
        assertTrue("Remove the now imported duplicate ZIP", bundle.delete())
    }
}
