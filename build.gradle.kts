plugins {
    application
    java
}

repositories {
    mavenCentral()
}

group = "com.brook.tools"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

application {
    mainClass.set("com.brook.tools.mediacompress.Main")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.brook.tools.mediacompress.Main"
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.16.0")
    implementation("net.java.dev.jna:jna-platform:5.16.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("brook-media-compress")
    archiveClassifier.set("")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.brook.tools.mediacompress.Main"
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    dependsOn(tasks.named("classes"))
}
