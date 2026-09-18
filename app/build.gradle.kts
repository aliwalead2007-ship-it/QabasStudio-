plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
  alias(libs.plugins.kotlin.serialization)
}

android {
  // تم تعديل الـ namespace ليتطابق مع الـ applicationId لسلامة ملفات الـ R
  namespace = "com.qabas.app"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.qabas.app"
    minSdk = 24
    targetSdk = 36
    // versionCode تلقائي من رقم تشغيل CI (يتصاعد دائماً بلا حلقة commit).
    // محلياً: القيمة الاحتياطية 3.
    versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 3
    versionName = "1.2.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      if (keystoreFile.exists()) {
        storeFile = keystoreFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
    create("debugConfig") {
      val ciKeystore = System.getenv("KEYSTORE_PATH")?.let { file(it) }
      val repoKeystore = file("${rootDir}/signing/qabas-ci.p12")
      val projectDebug = file("${rootDir}/debug.keystore")
      val sdkDebug = file("${System.getProperty("user.home")}/.android/debug.keystore")
      when {
        repoKeystore.exists() -> {
          storeFile = repoKeystore
          storePassword = "android"
          keyAlias = "androiddebugkey"
          keyPassword = "android"
        }
        ciKeystore != null && ciKeystore.exists() -> {
          storeFile = ciKeystore
          storePassword = System.getenv("STORE_PASSWORD") ?: "android"
          keyAlias = "androiddebugkey"
          keyPassword = "android"
        }
        projectDebug.exists() -> {
          storeFile = projectDebug
          storePassword = "android"
          keyAlias = "androiddebugkey"
          keyPassword = "android"
        }
        sdkDebug.exists() -> {
          storeFile = sdkDebug
          storePassword = "android"
          keyAlias = "androiddebugkey"
          keyPassword = "android"
        }
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val releaseCfg = signingConfigs.getByName("release")
      if (releaseCfg.storeFile != null && releaseCfg.storeFile!!.exists()) {
        signingConfig = releaseCfg
      }
    }
    debug {
      val debugCfg = signingConfigs.getByName("debugConfig")
      if (debugCfg.storeFile != null && debugCfg.storeFile!!.exists()) {
        signingConfig = debugCfg
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }

  packaging {
    jniLibs {
      pickFirsts += listOf(
        "lib/arm64-v8a/libc++_shared.so",
        "lib/armeabi-v7a/libc++_shared.so",
        "lib/x86/libc++_shared.so",
        "lib/x86_64/libc++_shared.so"
      )
    }
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

// تم حذف سطر الـ missingGoogleServicesStrategy لفرض وجود الملف وتجنب فشل التشغيل الصامت

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.foundation.layout)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.ui.text.google.fonts)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.lottie.compose)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.crashlytics)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  implementation(libs.ffmpeg.kit.full)
  implementation(libs.billing.ktx)
  implementation(libs.opencv)

  // ONNX Runtime for Kokoro TTS
  implementation(libs.onnx.runtime.android)

  // llama.cpp for local LLM
  implementation(libs.llama.cpp.android)

  implementation(platform(libs.supabase.bom))
  implementation(libs.supabase.postgrest)
  implementation(libs.supabase.auth)
  implementation(libs.supabase.storage)
  implementation(libs.supabase.functions)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
