plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp") version "1.5.0"
}

ktlint {
    android.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

android {
    namespace = "com.mapconductor.marker.clustering"
    compileSdk = project.property("compileSdk").toString().toInt()

    defaultConfig {
        minSdk = project.property("minSdk").toString().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(project.property("javaVersion").toString())
        targetCompatibility = JavaVersion.toVersion(project.property("javaVersion").toString())
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion =
            project.property("kotlinCompilerExtensionVersion").toString()
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(
                project.property("jvmTarget").toString(),
            ),
        )
    }
}

dependencies {
    if (findProject(":android-sdk-core") != null) {
        implementation(project(":android-sdk-core"))
    } else {
        implementation("com.mapconductor:core:${project.findProperty("coreLibraryVersion") as String? ?: "1.0.0"}")
    }

    // Compose dependencies for DefaultIcon
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)

    // Coroutines for Semaphore and withPermit
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Publishing configuration
val libraryGroupId = project.findProperty("libraryGroupId") as String? ?: "com.mapconductor"
val libraryArtifactId = "marker-clustering"
val libraryVersion = project.findProperty("libraryVersion") as String? ?: "1.0.0"
val coreLibraryVersion = project.findProperty("coreLibraryVersion") as String? ?: "1.0.0"

// Set project version
version = libraryVersion
val libraryName = "MapConductor Marker Clustering"
val libraryDescription = "Marker clustering strategy for MapConductor"

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    // Since Android libraries don't have Javadoc task by default, create empty jar
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["release"])

            groupId = libraryGroupId
            artifactId = libraryArtifactId
            version = libraryVersion

            artifact(javadocJar.get())

            pom {
                name.set(libraryName)
                description.set(libraryDescription)
                url.set(
                    project.findProperty("libraryUrl") as String?
                        ?: "https://github.com/MapConductor/android-marker-clustering",
                )

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set(project.findProperty("developerId") as String? ?: "mapconductor")
                        name.set(project.findProperty("developerName") as String? ?: "MapConductor Team")
                        email.set(project.findProperty("developerEmail") as String? ?: "info@mkgeeklab.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/MapConductor/android-marker-clustering.git")
                    developerConnection
                        .set("scm:git:ssh://github.com:MapConductor/android-marker-clustering.git")
                    url.set(
                        project.findProperty("scmUrl") as String?
                            ?: "https://github.com/MapConductor/android-marker-clustering.git",
                    )
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            setUrl("https://maven.pkg.github.com/MapConductor/android-marker-clustering")
            credentials {
                username =
                    project.findProperty("gpr.user") as String? ?: System.getenv("GPR_USER")
                        ?: System.getenv("GITHUB_ACTOR")
                password =
                    project.findProperty("gpr.key") as String? ?: System.getenv("GPR_TOKEN")
                        ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

signing {
    val signingKey = findProperty("signingKey") as String?
    val signingPassword = findProperty("signingPassword") as String?
    if (!signingKey.isNullOrEmpty() && !signingPassword.isNullOrEmpty()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["release"])
    }
}

if (project == rootProject) {
    // standalone build only — in multi-project (android-sdk), parent configures nmcp
    nmcp {
        publishAllPublicationsToCentralPortal {
            username.set(findProperty("ossrh_username") as String? ?: System.getenv("OSSRH_USERNAME") ?: "")
            password.set(findProperty("ossrh_password") as String? ?: System.getenv("OSSRH_PASSWORD") ?: "")
        }
    }
}
