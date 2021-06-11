package org.grapheneos.appupdateservergenerator.db

import com.squareup.sqldelight.ColumnAdapter
import com.squareup.sqldelight.TransactionWithReturn
import com.squareup.sqldelight.TransactionWithoutReturn
import com.squareup.sqldelight.sqlite.driver.JdbcDriver
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.util.withReentrantLock
import org.sqlite.SQLiteConfig
import java.sql.DriverManager
import java.util.concurrent.Executors

/**
 * A wrapper around the database to enforce that only one thread is able to access the database connection.
 * This thread is represented by the [transactionExecutor]
 *
 * Other threads / coroutines can use the [transactionExecutor] by using one of the database-access functions:
 *
 * - [useDatabase],
 * - [transaction], or
 * - [transactionWithResult].
 *
 * If there is another coroutine / thread using the [transactionExecutor], the
 * caller will suspend until the [transactionExecutor] is freed.
 *
 * The bodies of the methods to access the database *should not call any methods in the [DbWrapper]* in order to prevent
 * deadlocking from the [transactionMutex]. Since the bodies for the database-access functions in [DbWrapper] are set as
 * non-suspending blocks and all of the methods in [DbWrapper] are suspend functions, this discourages using the
 * [DbWrapper] in the bodies.
 *
 * The DAO (data access object) classes such as [AppDao] and [DeltaInfoDao] are all methods to help deal with the
 * database queries. All of those classes have methods that accept an instance of [Database]. However, the [Database] is
 * only provided through the database-access functions in this class.
 */
class DbWrapper private constructor(fileManager: FileManager) {
    private val database: Database
    private val driver: JdbcDriver
    init {
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

        driver = createDriverInstance(fileManager, enforceForeignKeys = true, singleConnection = true)
        database = createDatabaseInstance(driver)

        val dbUrl = getDatabaseUrl(fileManager)
        Runtime.getRuntime().addShutdownHook(Thread {
            driver.close()
            DriverManager.getConnection(dbUrl).use { connection ->
                connection.prepareStatement("PRAGMA wal_checkpoint(TRUNCATE)").execute()
            }
        })
    }

    private val transactionMutex = Mutex()
    private val transactionExecutor = Executors.newSingleThreadExecutor()
    private val transactionDispatcher = transactionExecutor.asCoroutineDispatcher()

    /**
     * Does a DB checkpoint. TRUNCATE mode means this will block until all readers and writers are done, do
     * checkpoints, then truncate the log file.
     *
     * See [https://www.sqlite.org/c3ref/wal_checkpoint_v2.html](https://www.sqlite.org/c3ref/wal_checkpoint_v2.html)
     */
    suspend fun executeWalCheckpointTruncate() {
        transactionMutex.withReentrantLock {
            withContext(transactionDispatcher) {
                driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)
            }
        }
    }

    /**
     * Attempts to access the [transactionExecutor] and then calls [Database.transaction] using the given [body],
     * providing the [body] with the [Database] for access to queries via [Database.appQueries] and etc. or
     * access to one of the DAO (data access object) functions such as the functions in [AppDao].
     *
     * If the [transactionExecutor] is not available because another coroutine is using it for execution, the
     * caller will suspend until the [transactionExecutor] is available.
     *
     * The [body] *should not call any methods in the [DbWrapper]* in order to prevent deadlocking from the mutex. Since
     * the [body] is set as a non-suspending block and all of the methods in [DbWrapper] are suspend functions, this
     * discourages using the [DbWrapper] in the [body].
     */
    suspend fun transaction(body: TransactionWithoutReturn.(db: Database) -> Unit) {
        transactionMutex.withReentrantLock {
            withContext(transactionDispatcher) {
                database.transaction { body(database) }
            }
        }
    }

    /**
     * Attempts to access the [transactionExecutor] and then calls [Database.transactionWithResult] using the given
     * [body], providing the [body] with the [Database] for access to queries via [Database.appQueries] and etc. or
     * access to one of the DAO (data access object) functions such as the functions in [AppDao].
     *
     * If the [transactionExecutor] is not available because another coroutine is using it for execution, the
     * caller will suspend until the [transactionExecutor] is available.
     *
     * The [body] *should not call any methods in the [DbWrapper]* in order to prevent deadlocking from the mutex. Since
     * the [body] is set as a non-suspending block and all of the methods in [DbWrapper] are suspend functions, this
     * discourages using the [DbWrapper] in the [body].
     */
    suspend fun <T> transactionWithResult(body: TransactionWithReturn<T>.(db: Database) -> T): T {
        return transactionMutex.withReentrantLock {
            withContext(transactionDispatcher) {
                database.transactionWithResult { body(database) }
            }
        }
    }

    /**
     * Attempts to access the [transactionExecutor] and then executes the [body], providing the [body] with the
     * [Database] for access to queries via [Database.appQueries] and etc. or access to one of the DAO (data access
     * object) functions such as the functions in [AppDao].
     *
     * The [body] *should not call any methods in the [DbWrapper]* in order to prevent deadlocking from the mutex. Since
     * the [body] is set as a non-suspending block and all of the methods in [DbWrapper] are suspend functions, this
     * discourages using the [DbWrapper] in the [body].
     */
    suspend fun <T> useDatabase(body: (db: Database) -> T): T {
        return transactionMutex.withReentrantLock {
            withContext(transactionDispatcher) {
                body(database)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: DbWrapper? = null
        fun getInstance(fileManager: FileManager): DbWrapper =
            instance ?: synchronized(DbWrapper::class) {
                instance ?: DbWrapper(fileManager).also { instance = it }
            }

        private val appAdapter: App.Adapter
        private val appReleaseAdapter: AppRelease.Adapter

        private fun createDatabaseInstance(driver: JdbcDriver) = Database(
            driver = driver,
            AppAdapter = appAdapter,
            AppReleaseAdapter = appReleaseAdapter,
        )

        private fun getDatabaseUrl(fileManager: FileManager) = "jdbc:sqlite:${fileManager.databaseFile.absolutePath}"

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

            appAdapter = App.Adapter(
                lastUpdateTimestampAdapter = unixTimestampAdapter,
                packageNameAdapter = packageNameAdapter,
            )
            appReleaseAdapter = AppRelease.Adapter(
                versionCodeAdapter = versionCodeAdapter,
                releaseTimestampAdapter = unixTimestampAdapter,
                apkSha256Adapter = base64StringAdapter,
                v4SigSha256Adapter = base64StringAdapter,
                packageNameAdapter = packageNameAdapter,
                staticLibraryVersionAdapter = versionCodeAdapter
            )
        }
    }
}
