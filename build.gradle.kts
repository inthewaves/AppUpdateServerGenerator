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
        verifyMigrations = true
        dialect = "sqlite:3.25"
    }
}

group = "org.grapheneos"
version = "0.1"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(fileTree("include" to "*.jar", "dir" to "libs"))
    implementation(project(":apksig")) {
        // the library doesn't even use protos
        exclude("com.google.protobuf")
    }

    implementation("org.jetbrains:markdown:0.2.4")
    implementation("com.googlecode.htmlcompressor:htmlcompressor:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
    implementation("com.github.ajalt.clikt:clikt:3.2.0")

    val apkParserVersion = "30.1.0-alpha01"
    /*
    Uncomment this if we want to parse binary XML from APK files

    implementation("com.android.tools.apkparser:apkanalyzer:$apkParserVersion") {
        exclude("com.android.tools.lint")
        exclude("com.google.protobuf")
        exclude("com.android.tools.build", "aapt2-proto")
        exclude("com.android.tools", "repository")
        exclude("org.apache.httpcomponents")
        exclude("org.apache.commons")
        exclude("org.glassfish.jaxb")
        exclude("org.jetbrains.kotlin", "kotlin-reflect")
        exclude("com.android.tools.analytics-library")
        exclude("org.bouncycastle")
        exclude("xerces")
    }
     */
    implementation("com.android.tools.apkparser:binary-resources:$apkParserVersion")
    implementation("com.squareup.sqldelight:sqlite-driver:1.5.0")
    // transitive dependency of com.squareup.sqldelight:sqlite-driver anyway, included for config options
    implementation("org.xerial:sqlite-jdbc:3.34.0")
    implementation("org.bouncycastle:bcprov-jdk15on:1.68")

    val jacksonVersion = "2.12.3"
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:1.5.10")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        languageVersion = "1.5"
        jvmTarget = "11"
        freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn", "-Xopt-in=kotlin.ExperimentalUnsignedTypes")
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

tasks.withType<AbstractArchiveTask> {
    isPreserveFileTimestamps = true
    isReproducibleFileOrder = true
}

application {
    mainClass.set("org.grapheneos.appupdateservergenerator.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.grapheneos.appupdateservergenerator.MainKt"
    }
}

tasks.create("copyDeps", type = Copy::class) {
    val destDir = "build/libs"
    doFirst { delete(destDir) }
    into(destDir)
    from(configurations.runtimeClasspath)
}

tasks.assemble {
    dependsOn(tasks["copyDeps"])
}