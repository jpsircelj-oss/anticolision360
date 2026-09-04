import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anticolision360.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anticolision360.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 600
        versionName = "6.0.0-native"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
}

val detectorModel = layout.projectDirectory.file("src/main/assets/efficientdet_lite0.tflite").asFile

tasks.register("downloadDetectorModel") {
    outputs.file(detectorModel)
    doLast {
        if (!detectorModel.exists() || detectorModel.length() < 1_000_000L) {
            detectorModel.parentFile.mkdirs()
            val url = URL("https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/rpi/lite-model_efficientdet_lite0_detection_metadata_1.tflite")
            url.openStream().use { input -> detectorModel.outputStream().use { output -> input.copyTo(output) } }
        }
    }
}

tasks.named("preBuild").configure { dependsOn("downloadDetectorModel") }
