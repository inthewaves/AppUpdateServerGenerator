package org.grapheneos.appupdateservergenerator.db

import com.squareup.sqldelight.ColumnAdapter
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.GroupId
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode

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
            override fun decode(databaseValue: String): Base64String = Base64String(databaseValue)
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
            groupIdAdapter = groupIdAdapter,
            iconSignatureAdapter = base64StringAdapter
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
    private fun createDatabaseInstance(driver: JdbcSqliteDriver) = Database(
        driver = driver,
        AppAdapter = appAdapter,
        AppReleaseAdapter = appReleaseAdapter,
        DeltaInfoAdapter = deltaInfoAdapter,
        AppGroupAdapter = appGroupAdapter
    )

    @Volatile
    private var driver: JdbcSqliteDriver? = null
    private fun getDriverInstance(fileManager: FileManager): JdbcSqliteDriver =
        driver ?: synchronized(DbWrapper::class) {
            createDriverInstance(fileManager, enforceForeignKeys = true)
                .also { driver = it }
        }

    private fun createDriverInstance(fileManager: FileManager, enforceForeignKeys: Boolean) =
        JdbcSqliteDriver(
            "jdbc:sqlite:${fileManager.databaseFile.absolutePath}",
            SQLiteConfig().apply {
                enforceForeignKeys(enforceForeignKeys)
                setJournalMode(SQLiteConfig.JournalMode.WAL)
            }.toProperties()
        )

    @Volatile
    private var database: Database? = null
    fun getDbInstance(fileManager: FileManager): Database =
        database ?: synchronized(DbWrapper::class) {
            val dbFile = fileManager.databaseFile
            val driverWithUnenforcedForeignKeys = createDriverInstance(fileManager, enforceForeignKeys = false)
            val newVersion = Database.Schema.version
            if (!dbFile.exists()) {
                Database.Schema.create(driverWithUnenforcedForeignKeys)
                // Database.Schema.create doesn't actually set user_version
                driverWithUnenforcedForeignKeys.execute(null, "PRAGMA user_version=$newVersion", 0)
            }

            val version = driverWithUnenforcedForeignKeys.executeQuery(null, "PRAGMA user_version", 0)
                .use { cursor ->
                    cursor.next()
                    cursor.getLong(0)!!.toInt()
                }

            println("user_version: $version, schema ver: $newVersion")
            if (version < newVersion) {
                val dbForMigration = createDatabaseInstance(driverWithUnenforcedForeignKeys)
                try {
                    dbForMigration.transaction {
                        Database.Schema.migrate(driverWithUnenforcedForeignKeys, version, newVersion)
                        driverWithUnenforcedForeignKeys.execute(null, "PRAGMA user_version=$newVersion", 0)
                    }
                } finally {
                    driverWithUnenforcedForeignKeys.use { driver ->
                        driver.getConnection().let { conn ->
                            conn.close()
                            driver.closeConnection(conn)
                        }
                    }
                }
            } else if (version > newVersion) {
                error("db is version $version and schema version is $newVersion")
            }

            val driver = getDriverInstance(fileManager)
                .apply {
                    execute(null, "ANALYZE", 0)
                    execute(null, "VACUUM", 0)
                }

            val database = createDatabaseInstance(driver)
            this.database = database
            return@synchronized database
        }
}