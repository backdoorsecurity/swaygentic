plugins {
    id("com.android.library")
}

android {
    namespace = "com.shinyhut.vernacular"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Pure Java RFB client (MIT vernacular-vnc) ported to android.graphics.Bitmap.
}
