import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Zip
import java.io.File
plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.powerlaunch"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

javafx {
    version = "24"
    modules = listOf("javafx.controls", "javafx.fxml")
}

application {
    mainClass.set("com.powerlaunch.Main")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.powerlaunch.Main"
    }
    // Fat JAR: include all runtime dependencies (Gson, etc.)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        // Exclude signature files to avoid security errors
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

// === Shared jpackage config ===
val javafxJmods = "${projectDir.absolutePath}\\javafx-jmods\\javafx-jmods-24"
val customJre = "${layout.buildDirectory.get().asFile.absolutePath}\\custom-jre"
val wixPath = "${projectDir.absolutePath}\\wix-toolset"

// === Task: Build installer JAR (self-contained, all classes + deps) ===
tasks.register<Jar>("installerJar") {
    archiveBaseName.set("PowerLaunchInstaller")
    archiveVersion.set(project.version.toString())
    manifest {
        attributes["Main-Class"] = "com.powerlaunch.installer.InstallerMain"
    }
    // Include ALL source classes (installer + launcher + everything)
    from(sourceSets.main.get().output)
    // Fat JAR: include runtime deps (Gson, etc.)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    // Include resources (CSS, etc.)
    from(sourceSets.main.get().resources)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// === Task: Build custom JRE with JavaFX via jlink ===
tasks.register<Exec>("jlinkJre") {
    outputs.dir(customJre)

    // Only run jlink if custom JRE doesn't have java.exe yet
    onlyIf { !file("$customJre/bin/java.exe").exists() }

    doFirst {
        if (!file(javafxJmods).exists()) {
            throw GradleException("JavaFX jmods not found at $javafxJmods")
        }
        // Clean up any old/broken JRE directory before rebuilding
        delete(customJre)
        logger.lifecycle("Building custom JRE with JavaFX via jlink...")
    }

    commandLine(
        "jlink",
        "--module-path", javafxJmods,
        "--add-modules", "javafx.controls,javafx.fxml,java.management,java.sql,java.naming,java.scripting,java.rmi,java.compiler,java.instrument,java.security.jgss,jdk.unsupported,jdk.charsets,jdk.zipfs,java.net.http,jdk.crypto.ec,java.desktop,java.logging,java.xml,java.transaction.xa,java.security.sasl,java.naming",
        "--output", customJre,
        "--no-header-files",
        "--no-man-pages"
    )
}

// === Task: Build portable launcher app-image (the Minecraft launcher) ===
tasks.register<Exec>("jpackageAppImage") {
    dependsOn("jar")
    dependsOn("jlinkJre")

    workingDir = file(layout.buildDirectory.get().asFile.absolutePath)

    doFirst {
        delete("${layout.buildDirectory.get().asFile.absolutePath}\\installer\\PowerLaunch")
    }

    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "PowerLaunch",
        "--app-version", project.version.toString(),
        "--vendor", "PowerLaunch",
        "--description", "PowerLaunch Minecraft Launcher",
        "--input", "${layout.buildDirectory.get().asFile.absolutePath}\\libs",
        "--main-jar", "PowerLaunch-${project.version}.jar",
        "--main-class", "com.powerlaunch.Main",
        "--runtime-image", customJre,
        "--dest", "${layout.buildDirectory.get().asFile.absolutePath}\\installer",
        "--java-options", "-Dfile.encoding=UTF-8"
    )
}

// Make 'gradlew build' only build the launcher JAR + app-image
tasks.named("build") {
    dependsOn("jpackageAppImage")
}

// === Task: Prepare clean input directory for installer (only installer JAR) ===
val installerInputDir = "${layout.buildDirectory.get().asFile.absolutePath}\\installer-input"

tasks.register<Sync>("prepareInstallerInput") {
    dependsOn("installerJar")
    from("${layout.buildDirectory.get().asFile.absolutePath}\\libs") {
        include("PowerLaunchInstaller-${project.version}.jar")
    }
    into(installerInputDir)
}

// === Task: Build installer app-image (shows Launch/Install choice) ===
tasks.register<Exec>("jpackageInstallerImage") {
    dependsOn("prepareInstallerInput")
    dependsOn("jlinkJre")

    workingDir = file(layout.buildDirectory.get().asFile.absolutePath)

    doFirst {
        delete("${layout.buildDirectory.get().asFile.absolutePath}\\installer\\PowerLaunch Setup")
    }

    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "PowerLaunch Setup",
        "--app-version", project.version.toString(),
        "--vendor", "PowerLaunch",
        "--description", "PowerLaunch Minecraft Launcher - Installer",
        "--input", installerInputDir,
        "--main-jar", "PowerLaunchInstaller-${project.version}.jar",
        "--main-class", "com.powerlaunch.installer.InstallerMain",
        "--runtime-image", customJre,
        "--dest", "${layout.buildDirectory.get().asFile.absolutePath}\\installer",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dpowerlaunch.home=${layout.buildDirectory.get().asFile.absolutePath}\\.."
    )
}

// === Task: Build installer .exe (WiX required) ===
tasks.register<Exec>("jpackageInstallerExe") {
    dependsOn("prepareInstallerInput")
    dependsOn("jlinkJre")

    workingDir = file(layout.buildDirectory.get().asFile.absolutePath)

    doFirst {
        if (!file(javafxJmods).exists()) throw GradleException("JavaFX jmods not found at $javafxJmods")
        if (!file(wixPath).exists()) throw GradleException("WiX not found at $wixPath")
    }

    environment["PATH"] = "${wixPath};${System.getenv("PATH")}"

    commandLine(
        "jpackage",
        "--type", "exe",
        "--name", "PowerLaunch Setup",
        "--app-version", project.version.toString(),
        "--vendor", "PowerLaunch",
        "--description", "PowerLaunch Minecraft Launcher - Installer",
        "--input", installerInputDir,
        "--main-jar", "PowerLaunchInstaller-${project.version}.jar",
        "--main-class", "com.powerlaunch.installer.InstallerMain",
        "--runtime-image", customJre,
        "--dest", "${layout.buildDirectory.get().asFile.absolutePath}\\installer",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dpowerlaunch.home=${layout.buildDirectory.get().asFile.absolutePath}\\..",
        "--win-menu",
        "--win-shortcut",
        "--win-dir-chooser",
        "--win-menu-group", "PowerLaunch"
    )
}


// === Portable mode: gradlew run uses project dir as launcher home (won't pollute reference distribution) ===
// === Task: Build CLI-only JAR (no JavaFX, just Gson + SQLite) ===
tasks.register<Jar>("cliJar") {
    archiveBaseName.set("PowerLaunch")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("cli")
    manifest {
        attributes["Main-Class"] = "com.powerlaunch.CliEntryPoint"
    }
    // Include only CLI classes (no GUI/FXML/JavaFX)
    from(sourceSets.main.get().output) {
        exclude("**/gui/**")
        exclude("**/installer/**")
        exclude("**/news/**")
    }
    // Fat JAR: include Gson + SQLite (no JavaFX)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        // Exclude JavaFX jars
        exclude("**/javafx/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<JavaExec>("run") {
    systemProperty("powerlaunch.home", layout.projectDirectory.asFile.absolutePath)
    systemProperty("powerlaunch.devhome", layout.buildDirectory.dir("dev-launcher-home").get().asFile.absolutePath)
    // Принудительно IPv4 (Java 17+ по умолчанию пробует IPv6 first → таймаут на серверах без IPv6)
    systemProperty("java.net.preferIPv4Stack", "true")
    // НЕ включаем java.net.useSystemProxies — на Windows без прокси ломает HTTP
    standardInput = System.`in`
}

// === Task: build portable launcher folder (the user-facing portable deliverable) ===
val portableDistDir = layout.buildDirectory.dir("portable/PowerLaunch")

tasks.register("portableDistribution") {
    group = "distribution"
    description = "Assembles the portable PowerLaunch folder (JRE + fat JAR + launch scripts)."
    dependsOn("jar", "jlinkJre")

    val launcherJar = layout.buildDirectory.file("libs/PowerLaunch-${project.version}.jar")
    val jreSrcDir = file(customJre)
    val dest = portableDistDir

        doLast {
        val rootDir = dest.get().asFile
        delete(rootDir)
        copy {
            from(launcherJar)
            into(rootDir)
            rename { "PowerLaunch.jar" }
        }
        copy {
            from(jreSrcDir)
            into(File(rootDir, "runtime"))
        }
        copy {
            from(layout.projectDirectory.dir("src/main/resources/scripts").asFile)
            into(rootDir)
        }
        if (!System.getProperty("os.name").lowercase().contains("windows")) {
            File(rootDir, "launch.sh").setExecutable(true)
        }
        logger.lifecycle("Portable launcher assembled at: ${rootDir.absolutePath}")
    }
}

tasks.register<Zip>("portableZip") {
    group = "distribution"
    description = "Bundles the portable launcher folder into a zip."
    dependsOn("portableDistribution")
    archiveFileName.set("PowerLaunch-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("portable"))
    from(portableDistDir)
}
