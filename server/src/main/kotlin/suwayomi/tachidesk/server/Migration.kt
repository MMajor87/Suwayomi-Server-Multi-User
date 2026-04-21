package suwayomi.tachidesk.server

import android.app.Application
import android.content.Context
import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.graphql.types.WebUIChannel
import suwayomi.tachidesk.graphql.types.WebUIFlavor
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerPreferences
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.server.user.findDefaultActiveUser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.prefs.Preferences
import kotlin.collections.isNotEmpty
import kotlin.collections.orEmpty

private val migrationLogger = KotlinLogging.logger("Migration")
private const val MULTI_USER_BOOTSTRAP_PREFS = "multi_user_bootstrap"
private const val MULTI_USER_BOOTSTRAP_VERSION_KEY = "version"
private const val MULTI_USER_BOOTSTRAP_VERSION = 1
private const val TRACKER_SECRET_MIGRATION_PREFS = "tracker_secret_storage"
private const val TRACKER_SECRET_MIGRATION_VERSION_KEY = "version"
private const val TRACKER_SECRET_MIGRATION_VERSION = 1

private fun migratePreferences(
    parent: String?,
    rootNode: Preferences,
) {
    val subNodes = rootNode.childrenNames()

    for (subNodeName in subNodes) {
        val subNode = rootNode.node(subNodeName)
        val key =
            if (parent != null) {
                "$parent/$subNodeName"
            } else {
                subNodeName
            }
        val preferences = Injekt.get<Application>().getSharedPreferences(key, Context.MODE_PRIVATE)

        val items: Map<String, String?> =
            subNode.keys().associateWith {
                subNode[it, null]?.ifBlank { null }
            }

        preferences
            .edit()
            .apply {
                items.forEach { (key, value) ->
                    if (value != null) {
                        putString(key, value)
                    }
                }
            }.apply()

        migratePreferences(key, subNode) // Recursively migrate sub-level nodes
    }
}

private fun migratePreferencesToNewXmlFileBasedStorage() {
    // Migrate from old preferences api
    val prefRootNode = "suwayomi/tachidesk"
    val isMigrationRequired = Preferences.userRoot().nodeExists(prefRootNode)
    if (isMigrationRequired) {
        val preferences = Preferences.userRoot().node(prefRootNode)
        migratePreferences(null, preferences)
        preferences.removeNode()
    }
}

private fun migrateMangaDownloadDir(applicationDirs: ApplicationDirs) {
    val oldMangaDownloadDir = File(applicationDirs.downloadsRoot)
    val newMangaDownloadDir = File(applicationDirs.mangaDownloadsRoot)
    val downloadDirs = oldMangaDownloadDir.listFiles().orEmpty()

    val moveDownloadsToNewFolder = !newMangaDownloadDir.exists() && downloadDirs.isNotEmpty()
    if (moveDownloadsToNewFolder) {
        newMangaDownloadDir.mkdirs()

        for (downloadDir in downloadDirs) {
            if (downloadDir == File(applicationDirs.thumbnailDownloadsRoot)) {
                continue
            }

            downloadDir.renameTo(File(newMangaDownloadDir, downloadDir.name))
        }
    }
}

private fun resolveSecureAuthModeTarget(): AuthMode {
    val configuredTarget = serverConfig.authModeNoneMigrationTarget.value

    return when (configuredTarget) {
        AuthMode.UI_LOGIN, AuthMode.SIMPLE_LOGIN -> configuredTarget
        AuthMode.NONE, AuthMode.BASIC_AUTH -> {
            migrationLogger.warn {
                "Invalid authModeNoneMigrationTarget=$configuredTarget. Falling back to ${AuthMode.UI_LOGIN}."
            }
            AuthMode.UI_LOGIN
        }
    }
}

private fun migrateAuthModeNoneToSecureModeAtStartup() {
    if (!serverConfig.multiUserBootstrapEnabled.value) {
        migrationLogger.info { "Skipping multi-user bootstrap gate because it is disabled." }
        return
    }

    if (serverConfig.authMode.value == AuthMode.NONE) {
        val secureTarget = resolveSecureAuthModeTarget()
        migrationLogger.warn {
            "Detected authMode=${AuthMode.NONE} at startup. Migrating to secure auth mode $secureTarget."
        }
        serverConfig.authMode.value = secureTarget
    }
}

private fun runMultiUserBootstrapGate() {
    if (!serverConfig.multiUserBootstrapEnabled.value) {
        return
    }

    val bootstrapPreferences =
        Injekt
            .get<Application>()
            .getSharedPreferences(
                MULTI_USER_BOOTSTRAP_PREFS,
                Context.MODE_PRIVATE,
            )
    val bootstrapVersion = bootstrapPreferences.getInt(MULTI_USER_BOOTSTRAP_VERSION_KEY, 0)

    if (bootstrapVersion >= MULTI_USER_BOOTSTRAP_VERSION) {
        return
    }

    migrationLogger.info {
        "Initializing multi-user bootstrap gate, version $bootstrapVersion -> $MULTI_USER_BOOTSTRAP_VERSION."
    }

    bootstrapPreferences.edit().putInt(MULTI_USER_BOOTSTRAP_VERSION_KEY, MULTI_USER_BOOTSTRAP_VERSION).apply()
}

private fun migrateTrackerSecretsToUserScopedStorage() {
    val preferences =
        Injekt
            .get<Application>()
            .getSharedPreferences(
                TRACKER_SECRET_MIGRATION_PREFS,
                Context.MODE_PRIVATE,
            )
    val migrationVersion = preferences.getInt(TRACKER_SECRET_MIGRATION_VERSION_KEY, 0)
    if (migrationVersion >= TRACKER_SECRET_MIGRATION_VERSION) {
        return
    }

    val owner = findDefaultActiveUser()
    if (owner == null) {
        migrationLogger.info { "Skipping tracker secret migration because no active user exists yet." }
        return
    }

    val migratedKeys = TrackerPreferences.migrateLegacySecretsToUser(owner.id)
    migrationLogger.info {
        "Migrated $migratedKeys tracker secret key(s) to user-scoped storage for userId=${owner.id}."
    }

    preferences.edit().putInt(TRACKER_SECRET_MIGRATION_VERSION_KEY, TRACKER_SECRET_MIGRATION_VERSION).apply()
}

private fun migrateWebUIFlavorDefaultToBundled() {
    val isLegacyWebUIDefaultProfile =
        serverConfig.webUIFlavor.value == WebUIFlavor.WEBUI &&
            serverConfig.webUIChannel.value == WebUIChannel.STABLE
    if (!isLegacyWebUIDefaultProfile) {
        return
    }

    migrationLogger.info {
        "Migrating webUIFlavor default from ${WebUIFlavor.WEBUI} to ${WebUIFlavor.BUNDLED}."
    }
    serverConfig.webUIFlavor.value = WebUIFlavor.BUNDLED
}

private val MIGRATIONS =
    listOf<Pair<String, (ApplicationDirs) -> Unit>>(
        "InitialMigration" to { applicationDirs ->
            migrateMangaDownloadDir(applicationDirs)
            migratePreferencesToNewXmlFileBasedStorage()
        },
        "FixGlobalUpdateScheduling" to {
            Injekt.get<IUpdater>().deleteLastAutomatedUpdateTimestamp()
        },
        "MigrateWebUIFlavorDefaultToBundled" to {
            migrateWebUIFlavorDefaultToBundled()
        },
    )

fun runMigrations(applicationDirs: ApplicationDirs) {
    migrateAuthModeNoneToSecureModeAtStartup()
    runMultiUserBootstrapGate()
    migrateTrackerSecretsToUserScopedStorage()

    val migrationPreferences =
        Injekt
            .get<Application>()
            .getSharedPreferences(
                "migrations",
                Context.MODE_PRIVATE,
            )
    val version = migrationPreferences.getInt("version", 0)

    migrationLogger.info { "Running migrations, previous version $version, target version ${MIGRATIONS.size}" }

    MIGRATIONS.forEachIndexed { index, (migrationName, migrationFunction) ->
        val migrationVersion = index + 1

        val isMigrationRequired = version < migrationVersion
        if (!isMigrationRequired) {
            migrationLogger.info { "Skipping migration version $migrationVersion: $migrationName" }
            return@forEachIndexed
        }

        migrationLogger.info { "Running migration version $migrationVersion: $migrationName" }

        migrationFunction(applicationDirs)

        migrationPreferences.edit().putInt("version", migrationVersion).apply()
    }
}
