package org.grapheneos.appupdateservergenerator.db

import com.squareup.sqldelight.sqlite.driver.ConnectionManager
import com.squareup.sqldelight.sqlite.driver.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * A [JdbcDriver] that only maintains one connection instead of having multiple connections by ThreadLocal.
 * This is unlike the default SQLite [JdbcDriver]  / [ConnectionManager] implementation offered by the library for
 * persistent databases on the filesystem, [com.squareup.sqldelight.sqlite.driver.ThreadedConnectionManager]. This was
 * created to allow for closing the database in a timely manner without needing to wait for garbage collection on the
 * ThreadLocal'd [Connection]s
 *
 * This combines functions from [com.squareup.sqldelight.sqlite.driver.InMemoryConnectionManager] and
 * [com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriverConnectionManager].
 *
 * @see com.squareup.sqldelight.sqlite.driver.InMemoryConnectionManager
 * @see com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriverConnectionManager
 */
class SingleConnectionJdbcSqliteDriver constructor(
    /**
     * Database connection URL in the form of `jdbc:sqlite:path` where `path` is either blank
     * (creating an in-memory database) or a path to a file.
     */
    url: String,
    properties: Properties = Properties()
) : JdbcDriver(), ConnectionManager {
    /** Reference: [com.squareup.sqldelight.sqlite.driver.InMemoryConnectionManager.transaction] */
    override var transaction: ConnectionManager.Transaction? = null

    /**
     * Reference: [com.squareup.sqldelight.sqlite.driver.InMemoryConnectionManager.connection]
     * This has been changed to allow referencing arbitrary database URLs.
     */
    private val connection: Connection = DriverManager.getConnection(url, properties)

    /** Reference: [com.squareup.sqldelight.sqlite.driver.InMemoryConnectionManager.getConnection] */
    override fun getConnection(): Connection = connection

    /** Reference: [com.squareup.sqldelight.sqlite.driver.InMemoryConnectionManager.closeConnection] */
    override fun closeConnection(connection: Connection) = Unit

    /** Reference: [com.squareup.sqldelight.sqlite.driver.InMemoryConnectionManager.close] */
    override fun close() = connection.close()

    /**
     * Reference: [com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriverConnectionManager.beginTransaction]
     * This is here because we can't inherit from JdbcSqliteDriverConnectionManager.
     */
    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN TRANSACTION").execute()
    }

    /**
     * Reference: [com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriverConnectionManager.endTransaction]
     * This is here because we can't inherit from JdbcSqliteDriverConnectionManager.
     */
    override fun Connection.endTransaction() {
        prepareStatement("END TRANSACTION").execute()
    }

    /**
     * Reference: [com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriverConnectionManager.rollbackTransaction]
     * This is here because we can't inherit from JdbcSqliteDriverConnectionManager.
     */
    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK TRANSACTION").execute()
    }
}