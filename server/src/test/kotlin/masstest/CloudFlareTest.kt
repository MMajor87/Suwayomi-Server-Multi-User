package masstest

import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.Source
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.manga.impl.extension.ExtensionsList
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource
import suwayomi.tachidesk.server.applicationSetup
import suwayomi.tachidesk.test.BASE_PATH
import suwayomi.tachidesk.test.setLoggingEnabled
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudFlareTest {
    private var nhentai: HttpSource? = null

    @BeforeAll
    fun setup() {
        val dataRoot = File(BASE_PATH).absolutePath
        System.setProperty("$CONFIG_PREFIX.server.rootDir", dataRoot)
        applicationSetup()
        setLoggingEnabled(false)

        runBlocking {
            val extensions = ExtensionsList.getExtensionList()
            val nhentaiExtension =
                extensions.firstOrNull {
                    it.name.equals("NHentai", ignoreCase = true) || it.pkgName.contains("nhentai", ignoreCase = true)
                }

            if (nhentaiExtension == null) {
                logger.warn { "Skipping CloudFlareTest setup: NHentai extension is unavailable" }
                return@runBlocking
            }

            with(nhentaiExtension) {
                if (!installed) {
                    Extension.installExtension(pkgName)
                } else if (hasUpdate) {
                    Extension.updateExtension(pkgName)
                }
                Unit
            }

            nhentai =
                Source
                    .getSourceList()
                    .firstOrNull { it.id.toLong() == 3122156392225024195L }
                    ?.id
                    ?.toLong()
                    ?.let(GetCatalogueSource::getCatalogueSourceOrNull) as? HttpSource
        }
        setLoggingEnabled(true)
    }

    private val logger = KotlinLogging.logger {}

    @Test
    fun `test nhentai browse`() =
        runTest {
            val source = nhentai
            assumeTrue(source != null, "NHentai source unavailable in this environment")
            val availableSource = source!!

            assert(availableSource.getPopularManga(1).mangas.isNotEmpty()) {
                "NHentai results were empty"
            }
        }
}
