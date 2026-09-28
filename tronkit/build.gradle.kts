import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("maven-publish")
}

val roomVersion = "2.8.4"

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // 21, not 17: hd-wallet-kit-kmp and secp256k1-kmp-jni-jvm publish Java 21 bytecode only.
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Not commonMain: the kit is JVM code shared by the two JVM-backed targets only.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Proto classes shipped inside the pre-KMP AAR, so consumers compiled against them.
                api(project(":tronkit-proto"))
                // Public API exposes okhttp3.EventListener.Factory (getInstance overloads).
                api("com.squareup.okhttp3:okhttp:4.11.0")
                implementation("com.github.piratecash.hd-wallet-kit-android:hd-wallet-kit-kmp:1.0.1")
                implementation("org.bouncycastle:bcpkix-jdk15to18:1.80")
                implementation("com.squareup.retrofit2:retrofit:2.9.0")
                implementation("com.squareup.retrofit2:adapter-rxjava2:2.9.0")
                implementation("com.squareup.retrofit2:converter-gson:2.9.0")
                implementation("com.squareup.retrofit2:converter-scalars:2.9.0")
                implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-rx2:1.6.4")
                implementation("com.google.code.gson:gson:2.9.0")
                implementation("androidx.room:room-runtime:$roomVersion")
            }
        }
        androidMain {
            // Sources come from AGP's own `main` source set; adding them here too would
            // list the same file in two fragments.
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("androidx.core:core-ktx:1.10.0")
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("com.google.android.material:material:1.8.0")
                implementation("androidx.room:room-ktx:$roomVersion")
                // Public API exposes its exceptions and DatabaseMigrationResult.
                api("com.github.piratecash.bitcoin-kit-android:sqlcipher-room:v0.1.0-pcash.36")
            }
        }
        val desktopMain by getting {
            kotlin.srcDir("src/main/java")
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
                api("com.github.piratecash.bitcoin-kit-android:sqlcipher-room:v0.1.0-pcash.36")
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("com.squareup.okhttp3:mockwebserver:4.11.0")
                implementation("io.mockk:mockk:1.13.8")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
                implementation("androidx.test:core:1.5.0")
                implementation("org.robolectric:robolectric:4.11.1") {
                    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
                }
                runtimeOnly("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
            }
        }
        // Runs on a device only; never part of the published AAR.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test.ext:junit:1.1.5")
                implementation("androidx.test.espresso:espresso-core:3.5.1")
                // Builds an interrupted-migration staging file directly.
                implementation("net.zetetic:sqlcipher-android:4.17.0")
            }
        }
        val desktopTest by getting {
            resources.srcDir("src/test/resources")
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("com.squareup.okhttp3:mockwebserver:4.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
                // Plaintext fixtures only; the kit itself opens databases through SQLCipher.
                implementation("androidx.sqlite:sqlite-bundled:2.6.2")
                // Reads and stages encrypted files directly; aligned with sqlcipher-room.
                implementation("com.github.piratecash.bitcoin-kit-android:sqlcipher-driver:v0.1.0-pcash.36")
            }
        }
    }
}

// The 6 Java sources sit in the pre-KMP layout, which only AGP's `main` source set knows about.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    sourceSets["desktopMain"].java.srcDir("src/main/java")
}

// Room rejects blocking DAO functions unless android.content.Context is visible to the processor.
// The DAOs are shared with Android, so KSP gets a bare marker type; it is never packaged.
val roomAndroidMarker = java.sourceSets.create("roomAndroidMarker") {
    java.setSrcDirs(listOf(rootProject.file("gradle/room-android-marker/java")))
}

dependencies {
    "desktopMainCompileOnly"(roomAndroidMarker.output)
    add("kspAndroid", "androidx.room:room-compiler:$roomVersion")
    add("kspDesktop", "androidx.room:room-compiler:$roomVersion")
    // KMP naming trap: kspAndroidTest is the JVM unit-test source set, kspAndroidAndroidTest the
    // instrumented one. The test-only @Database in MainDatabaseMigrationTest needs its own _Impl.
    add("kspAndroidTest", "androidx.room:room-compiler:$roomVersion")
}

android {
    namespace = "io.horizontalsystems.tronkit"
    compileSdk = 34

    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
        // The Room 2.6.1 fixture; read-only, shared with the host tests.
        getByName("androidTest").resources.srcDir("src/test/resources")
    }

    defaultConfig {
        minSdk = 27

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        targetSdk = 34
    }
    testOptions {
        targetSdk = 34
    }
}

// Parity with the pre-KMP artifact: the Kotlin Android plugin passed -parameters to javac.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// AGP's "test" lifecycle task only aggregates Android unit tests; wire in the desktop target too.
afterEvaluate {
    tasks.named("test") {
        dependsOn("desktopTest")
    }
}
