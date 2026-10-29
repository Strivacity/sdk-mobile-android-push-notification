// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.kotlin.serialization) apply false
}

spotless {
    kotlin {
        target("**/*.kt", "**/*.kts")
        targetExclude(
            "**/build/**/*.kt",
        )
        ktlint()
            .setEditorConfigPath("$rootDir/.editorconfig")
            .editorConfigOverride(
                mapOf(
                    "ktlint_standard_package-name" to "disabled",
                    "max_line_length" to "120",
                    "indent_size" to "4",
                    "indent_style" to "space",
                    "ktlint_code_style" to "ktlint_official",
                    "ij_kotlin_allow_trailing_comma" to "true",
                    "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
                    "ktlint_function_signature_rule_force_multiline_when_parameter_count_greater_or_equal_than" to
                        "2",
                    "ktlint_class_signature_rule_force_multiline_when_parameter_count_greater_or_equal_than" to
                        "1",
                    "ktlint_chain_method_rule_force_multiline_when_chain_operator_count_greater_or_equal_than" to
                        "4",
                    "ktlint_function_signature_body_expression_wrapping" to "multiline",
                    "ktlint_property_naming_constant_naming" to "screaming_snake_case",
                    "ktlint_enum_entry_name_casing" to "upper_or_camel_cases",
                ),
            )
    }
    java {
        target("**/*.java")
        toggleOffOn()
        googleJavaFormat().aosp()
        removeUnusedImports()
    }
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        config.setFrom("$rootDir/config/detekt/detekt.yml")
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "11"
        reports {
            html.required.set(true)
            xml.required.set(false)
            sarif.required.set(true)
            txt.required.set(false)
        }
    }

    pluginManager.apply("org.jetbrains.kotlinx.kover")

    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        reports {
            filters {
                excludes {
                    // Pure data holders / boilerplate exceptions — no branching or computed
                    // logic
                    classes(
                        "com.strivacity.android.pushnotification.model.Enrollment",
                        "com.strivacity.android.pushnotification.model.DeviceTokenType",
                        "com.strivacity.android.pushnotification.model.ChallengeContext",
                        "com.strivacity.android.pushnotification.model.ResolutionResult",
                        // A companion object's members compile into a separate `X$Companion`
                        // class — excluding "...ErrorCode" alone misses its actual constants.
                        "com.strivacity.android.pushnotification.model.ErrorCode",
                        "com.strivacity.android.pushnotification.model.ErrorCode\$*",
                        "com.strivacity.android.pushnotification.model.*Exception",
                        "com.strivacity.android.pushnotification.net.EnrollRequest",
                        "com.strivacity.android.pushnotification.net.ChallengeResponse",
                        "com.strivacity.android.pushnotification.net.Action",
                        "com.strivacity.android.pushnotification.net.ActionTypes",
                        "com.strivacity.android.pushnotification.net.Platforms",
                    )
                }
            }

            verify {
                rule {
                    // Minimum unit-test line coverage enforced in CI.
                    minBound(60)
                }
            }
        }
    }
}
