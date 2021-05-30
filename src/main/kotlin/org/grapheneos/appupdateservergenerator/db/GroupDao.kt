package org.grapheneos.appupdateservergenerator.db

import org.grapheneos.appupdateservergenerator.model.GroupId
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.util.executeAsSequence

/**
 * A data access object for [AppGroup]
 */
class GroupDao(private val database: Database) {
    fun getGroupToAppMap(): Map<GroupId, Set<PackageLabelsByGroup>> {
        return database.transactionWithResult {
            val map = sortedMapOf<GroupId, Set<PackageLabelsByGroup>>()
            database.appGroupQueries.selectAll().executeAsSequence { groupSequence ->
                groupSequence.forEach { groupId ->
                    map[groupId] = database.appQueries.packageLabelsByGroup(groupId)
                        .executeAsSequence { appSequence -> appSequence.toSet() }
                }
            }
            return@transactionWithResult map
        }
    }

    fun doesGroupExist(groupId: GroupId) = database.appGroupQueries.select(groupId).executeAsOneOrNull() != null

    fun deleteGroup(groupId: GroupId) = database.appGroupQueries.delete(groupId)

    fun createGroupWithPackages(groupId: GroupId, packages: Iterable<PackageName>, updateTimestamp: UnixTimestamp) {
        database.transaction {
            database.appGroupQueries.apply {
                insert(AppGroup(groupId))
                packages.forEach {
                    database.appQueries.setGroup(groupId, updateTimestamp, it)
                }
            }
        }
    }
}