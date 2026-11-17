plugins {
    java
}

group = "at.esoren"
version = "1.46.0-26.2"

val coreJar = file(providers.gradleProperty("vystormCoreJar").getOrElse("libs/Vystorm_Core.jar"))

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(coreJar))
    testImplementation(files(coreJar))
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

val pluginVersion = version.toString()
tasks.processResources {
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.test {
    useJUnitPlatform()
}

val paper1206Libraries = file(providers.gradleProperty("paper1206Libraries")
    .getOrElse((System.getProperty("user.home") + "/source/tools/smoke-1206/libraries")))
val compat1206 by tasks.registering(JavaCompile::class) {
    source(sourceSets.main.get().java)
    classpath = files(coreJar) + fileTree(paper1206Libraries) { include("**/*.jar") }
    destinationDirectory = layout.buildDirectory.dir("classes/compat1206")
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
    doFirst { require(paper1206Libraries.isDirectory) { "Missing Paper 1.20.6 libraries: $paper1206Libraries" } }
}
tasks.check { dependsOn(compat1206) }

val modern by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
sourceSets.test { runtimeClasspath += modern.output }

// Exercise the shipped classes with the old API too, catching linkage and static-initializer failures.
val test1206 by tasks.registering(Test::class) {
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + modern.output + files(coreJar) +
        fileTree(paper1206Libraries) { include("**/*.jar") } +
        configurations.testRuntimeClasspath.get().filter {
            it.name.startsWith("junit-") || it.name.startsWith("opentest4j-") ||
                it.name.startsWith("apiguardian-") || it.name.startsWith("mockito-") ||
                it.name.startsWith("byte-buddy") || it.name.startsWith("objenesis-")
        }
    filter {
        includeTestsMatching("*PaperCompatTest")
        includeTestsMatching("*HerbariumPanelTest")
        includeTestsMatching("*PlantingPanelTest")
        includeTestsMatching("*TreesPanelsTest")
    }
}
tasks.check { dependsOn(test1206) }

tasks.jar {
    from(modern.output)
    archiveFileName = "CustomTrees-${project.version}.jar"
    from(rootProject.file("LICENSE"))
}


tasks.compileJava {
    doFirst {
        require(coreJar.isFile) { "Vystorm Core jar missing: -PvystormCoreJar=/path/to/Vystorm_Core-0.22.0.jar" }
    }
}


val integration by sourceSets.creating {
    java.setSrcDirs(listOf("test-harness/src"))
    resources { setSrcDirs(listOf("test-harness")); include("plugin.yml") }
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("integrationJar") {
    dependsOn(tasks.named(integration.classesTaskName))
    from(integration.output)
    archiveFileName = "CustomTreesAcceptance.jar"
}
