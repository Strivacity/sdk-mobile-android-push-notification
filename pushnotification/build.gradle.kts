import org.gradle.kotlin.dsl.support.kotlinCompilerOptions

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
    id("signing")
}

group = "com.strivacity.android"

// A release passes its version with `-Pversion=<version>`; local builds keep the local one.
if (version == Project.DEFAULT_VERSION) {
    version = "0.1.0-local"
}

kotlin {
    explicitApi() // or explicitApiWarning() for warnings instead of build failures
}

android {
    namespace = "com.strivacity.android.pushnotification"
    compileSdk = 36

    defaultConfig {
        minSdk = 29

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        managedDevices {
            localDevices {
                create("ciDevice") {
                    device = "Pixel 6"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        htmlReport = true
        xmlReport = false
        sarifReport = true
        informational += "AndroidGradlePluginVersion"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Strivacity/sdk-mobile-android-push-notification")
                credentials {
                    username = System.getenv("GITHUB_USER")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }

        publications {
            register<MavenPublication>("release") {
                artifactId = "pushnotification"
                groupId = "com.strivacity.android"

                from(components["release"])

                pom {
                    name = "Strivacity Push Notification SDK for Android"
                    description =
                        "Push-based multi-factor authentication for Android apps: enroll a device, " +
                        "receive sign-in challenges, and approve, deny or answer them."
                    url = "https://github.com/Strivacity/sdk-mobile-android-push-notification"

                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }

                    developers {
                        developer {
                            id = "Strivacity"
                            name = "Strivacity"
                            email = "opensource@strivacity.com"
                        }
                    }

                    scm {
                        connection = "scm:git:git://github.com/Strivacity/sdk-mobile-android-push-notification.git"
                        developerConnection =
                            "scm:git:ssh://github.com/Strivacity/sdk-mobile-android-push-notification.git"
                        url = "https://github.com/Strivacity/sdk-mobile-android-push-notification"
                    }
                }
            }
        }
    }

    signing {
        sign(publishing.publications["release"])
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.ktor.client.mock)
}
