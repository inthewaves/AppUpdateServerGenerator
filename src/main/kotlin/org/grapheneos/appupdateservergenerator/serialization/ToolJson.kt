package org.grapheneos.appupdateservergenerator.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.util.TreeSet

object ToolJson {
    val json = Json {
        serializersModule = SerializersModule {
            contextual(TreeSet::class) { typeArgs -> TreeSetSerializer(typeArgs.first()) }
        }
    }
}