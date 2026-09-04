plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.swaygentrc"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.swaygentrc"
        minSdk = 29
        targetSdk = 37
        versionCode = 22
        versionName = "0.9.4"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val ver = android.defaultConfig.versionName
        variant.outputs.forEach { output ->
            (output as com.android.build.api.variant.impl.VariantOutputImpl)
                .outputFileName.set("swaygentrc_v$ver.apk")
        }
    }
}

tasks.configureEach {
    if (name == "assembleDebug" || name == "assembleRelease") {
        doLast {
            val ver = android.defaultConfig.versionName
            val flavor = if (name.endsWith("Release")) "release" else "debug"
            val apk = layout.buildDirectory.file("outputs/apk/$flavor/swaygentrc_v$ver.apk").get().asFile
            if (apk.exists()) {
                val destDir = rootProject.layout.projectDirectory.dir("apks").asFile
                destDir.mkdirs()
                apk.copyTo(destDir.resolve(apk.name), overwrite = true)
            }
        }
    }
}

dependencies {
    implementation(project(":vernacular"))
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
