package org.grapheneos.appupdateservergenerator.db

import com.squareup.sqldelight.ColumnAdapter
import com.squareup.sqldelight.sqlite.driver.JdbcDriver
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.GroupId
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.sqlite.SQLiteConfig
import java.sql.DriverManager

object DbWrapper {
    private val appAdapter: App.Adapter
    private val appReleaseAdapter: AppRelease.Adapter
    private val deltaInfoAdapter: DeltaInfo.Adapter
    private val appGroupAdapter: AppGroup.Adapter
    init {
        val unixTimestampAdapter = object : ColumnAdapter<UnixTimestamp, Long> {
            override fun decode(databaseValue: Long): UnixTimestamp = UnixTimestamp(databaseValue)
            override fun encode(value: UnixTimestamp): Long = value.seconds
        }
        val versionCodeAdapter = object : ColumnAdapter<VersionCode, Long> {
            override fun decode(databaseValue: Long): VersionCode = VersionCode(databaseValue)
            override fun encode(value: VersionCode): Long = value.code
        }
        val base64StringAdapter = object : ColumnAdapter<Base64String, String> {
            override fun decode(databaseValue: String): Base64String = Base64String.fromBase64(databaseValue)
            override fun encode(value: Base64String): String = value.s
        }
        val packageNameAdapter = object : ColumnAdapter<PackageName, String> {
            override fun decode(databaseValue: String): PackageName = PackageName(databaseValue)
            override fun encode(value: PackageName): String = value.pkg
        }
        val groupIdAdapter = object : ColumnAdapter<GroupId, String> {
            override fun decode(databaseValue: String): GroupId = GroupId.of(databaseValue)
            override fun encode(value: GroupId): String = value.id
        }

        appAdapter = App.Adapter(
            lastUpdateTimestampAdapter = unixTimestampAdapter,
            packageNameAdapter = packageNameAdapter,
            groupIdAdapter = groupIdAdapter
        )
        appReleaseAdapter = AppRelease.Adapter(
            versionCodeAdapter = versionCodeAdapter,
            releaseTimestampAdapter = unixTimestampAdapter,
            apkSha256Adapter = base64StringAdapter,
            v4SigSha256Adapter = base64StringAdapter,
            packageNameAdapter = packageNameAdapter
        )
        deltaInfoAdapter = DeltaInfo.Adapter(
            baseVersionAdapter = versionCodeAdapter,
            targetVersionAdapter = versionCodeAdapter,
            sha256ChecksumAdapter = base64StringAdapter,
            packageNameAdapter = packageNameAdapter
        )
        appGroupAdapter = AppGroup.Adapter(groupIdAdapter)
    }
    private fun createDatabaseInstance(driver: JdbcDriver) = Database(
        driver = driver,
        AppAdapter = appAdapter,
        AppReleaseAdapter = appReleaseAdapter,
        DeltaInfoAdapter = deltaInfoAdapter,
        AppGroupAdapter = appGroupAdapter
    )

    @Volatile
    private var driver: JdbcDriver? = null
    private fun getDriverInstance(fileManager: FileManager): JdbcDriver =
        driver ?: synchronized(DbWrapper::class) {
            createDriverInstance(fileManager, enforceForeignKeys = true, singleConnection = false)
                .also { driver = it }
        }

    /**
     * Does a DB checkpoint. TRUNCATE mode means this will block until all readers and writers are done, do
     * checkpoints, then truncate the log file.
     *
     * See [https://www.sqlite.org/c3ref/wal_checkpoint_v2.html](https://www.sqlite.org/c3ref/wal_checkpoint_v2.html)
     */
    fun executeWalCheckpointTruncate(fileManager: FileManager) =
        getDriverInstance(fileManager).execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)

    private fun getDatabaseUrl(fileManager: FileManager) ="jdbc:sqlite:${fileManager.databaseFile.absolutePath}"

    private fun createDriverInstance(
        fileManager: FileManager,
        enforceForeignKeys: Boolean,
        singleConnection: Boolean
    ): JdbcDriver {
        val databaseUrl = getDatabaseUrl(fileManager)
        val config = SQLiteConfig().apply {
            enforceForeignKeys(enforceForeignKeys)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.FULL)
        }.toProperties()

        return if (singleConnection) {
            SingleConnectionJdbcSqliteDriver(databaseUrl, config)
        } else {
            JdbcSqliteDriver(databaseUrl, config)
        }
    }

    @Volatile
    private var database: Database? = null
    fun getDbInstance(fileManager: FileManager): Database =
        database ?: synchronized(DbWrapper::class) {
            createDriverInstance(
                fileManager,
                enforceForeignKeys = false,
                singleConnection = true
            ).use { driverNoEnforcedForeignKeys ->
                val dbForMigration = createDatabaseInstance(driverNoEnforcedForeignKeys)
                val newVersion = Database.Schema.version
                val version = driverNoEnforcedForeignKeys.executeQuery(null, "PRAGMA user_version", 0)
                    .use { cursor ->
                        cursor.next()
                        cursor.getLong(0)!!.toInt()
                    }
                println("database versions: user_version: $version, schema ver: $newVersion")
                if (version < newVersion) {
                    dbForMigration.transaction {
                        // If the database hasn't been created before, user_version will be 0.
                        if (version == 0) {
                            println("creating database with version $newVersion")
                            Database.Schema.create(driverNoEnforcedForeignKeys)
                        } else {
                            println("running database migrations to new version $newVersion")
                            Database.Schema.migrate(driverNoEnforcedForeignKeys, version, newVersion)
                        }
                        driverNoEnforcedForeignKeys.execute(null, "PRAGMA user_version=$newVersion", 0)
                    }
                } else if (version > newVersion) {
                    error("db ${fileManager.databaseFile} has version $version but current schema version is $newVersion")
                }

                driverNoEnforcedForeignKeys.apply {
                    execute(null, "ANALYZE", 0)
                    execute(null, "VACUUM", 0)
                    execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)
                }
            }
            System.gc()

            // this also initializes the driver property in this DbWrapper object
            val driver: JdbcDriver = getDriverInstance(fileManager)
            val database = createDatabaseInstance(driver)

            val dbUrl = getDatabaseUrl(fileManager)
            Runtime.getRuntime().addShutdownHook(Thread {
                DriverManager.getConnection(dbUrl).use { connection ->
                    connection.prepareStatement("PRAGMA wal_checkpoint(TRUNCATE)").execute()
                }
            })

            this.database = database
            return@synchronized database
        }
}