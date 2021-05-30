import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.10"
    kotlin("plugin.serialization") version "1.5.0"
    id("com.squareup.sqldelight") version "1.5.0"
    application
}

sqldelight {
    database("Database") { // This will be the name of the generated database class.
        packageName = "org.grapheneos.appupdateservergenerator.db"
        deriveSchemaFromMigrations = true
        schemaOutputDirectory = file("src/main/sqldelight/schemas")
        verifyMigrations = true
        dialect = "sqlite:3.25"
    }
}

group = "org.grapheneos"
version = "0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(fileTree("include" to "*.jar", "dir" to "libs"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.5.10")
    implementation("com.github.ajalt.clikt:clikt:3.2.0")

    implementation("com.squareup.sqldelight:sqlite-driver:1.5.0")
    implementation("com.squareup.sqldelight:coroutines-extensions-jvm:1.5.0")

    testImplementation("org.bouncycastle:bcprov-jdk15to18:1.68")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
    kotlinOptions.freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
}

application {
    mainClass.set("org.grapheneos.appupdateservergenerator.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.grapheneos.appupdateservergenerator.MainKt"
    }
    archiveFileName.set(project.name + ".jar")

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.extension == "jar" }.map { zipTree(it) }
    })
}