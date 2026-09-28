plugins {
    `java-library`
    id("com.google.protobuf")
    `maven-publish`
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.19.4"
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:3.23.1")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "tronkit-proto"
            from(components["java"])
        }
    }
}
