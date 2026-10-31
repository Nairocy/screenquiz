plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.nairocy.screenquiz"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nairocy.screenquiz"
        minSdk = 30
        targetSdk = 36
        versionCode = 9
        versionName = "0.7.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += "db"
    }

    testOptions {
        // LogBus 单测需要：android.util.Log 在 JVM 下返回默认值而不是抛异常
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
