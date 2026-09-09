plugins {
    application
    java
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

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("brook-media-compress")
    archiveClassifier.set("")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.brook.tools.mediacompress.Main"
    }
    from(sourceSets.main.get().output)
    dependsOn(tasks.named("classes"))
}
