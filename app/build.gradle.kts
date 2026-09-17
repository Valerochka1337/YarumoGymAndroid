import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
  alias(libs.plugins.room)
}

val localKeystoreProperties = Properties()
val localKeystorePropertiesFile = rootProject.file("keystore.properties")

if (localKeystorePropertiesFile.isFile) {
  localKeystorePropertiesFile.inputStream().use { localKeystoreProperties.load(it) }
}

fun releaseSigningValue(environmentVariable: String, propertyName: String): String? =
    providers.environmentVariable(environmentVariable).orNull?.takeIf { it.isNotBlank() }
        ?: localKeystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = releaseSigningValue("RELEASE_KEYSTORE_FILE", "storeFile")
val releaseKeystorePassword = releaseSigningValue("RELEASE_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = releaseSigningValue("RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = releaseSigningValue("RELEASE_KEY_PASSWORD", "keyPassword")

val releaseSigningInputs =
    linkedMapOf(
        "RELEASE_KEYSTORE_FILE / storeFile" to releaseKeystoreFile,
        "RELEASE_KEYSTORE_PASSWORD / storePassword" to releaseKeystorePassword,
        "RELEASE_KEY_ALIAS / keyAlias" to releaseKeyAlias,
        "RELEASE_KEY_PASSWORD / keyPassword" to releaseKeyPassword,
    )

// Только для ручной Actions-сборки старой тестовой версии updater-а. Значения не меняют
// исходники и не используются при обычном push в main.
val testVersionName = providers.gradleProperty("testVersionName").orNull?.takeIf { it.isNotBlank() }
val testVersionCodeRaw =
    providers.gradleProperty("testVersionCode").orNull?.takeIf { it.isNotBlank() }

if ((testVersionName == null) != (testVersionCodeRaw == null)) {
  throw GradleException("testVersionName and testVersionCode must be provided together")
}

if (testVersionName != null && !testVersionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
  throw GradleException("testVersionName must be a stable three-part SemVer")
}

val testVersionCode = testVersionCodeRaw?.toIntOrNull()?.takeIf { it > 0 }

if (testVersionCodeRaw != null && testVersionCode == null) {
  throw GradleException("testVersionCode must be a positive integer")
}

android {
  namespace = "com.valerochka1337.valerochkagym"
  compileSdk { version = release(37) }

  defaultConfig {
    applicationId = "com.valerochka1337.valerochkagym"
    minSdk = 36
    targetSdk = 37
    versionCode = testVersionCode ?: 77
    versionName = testVersionName ?: "1.3.69"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      storeFile = releaseKeystoreFile?.let(rootProject::file)
      storePassword = releaseKeystorePassword
      keyAlias = releaseKeyAlias
      keyPassword = releaseKeyPassword
    }
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.getByName("release")

      // R8: сжатие кода и ресурсов. Keep-правила — в src/main/keepRules/rules.keep
      // (kotlinx-serialization DTO Google API; Hilt/Room/WorkManager несут consumer-rules).
      optimization { enable = true }
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }

  sourceSets {
    // MigrationTestHelper reads the Robolectric debug target's AssetManager.
    getByName("debug").assets.directories.add("$projectDir/schemas")
  }
}

tasks
    .matching { it.name == "validateSigningRelease" }
    .configureEach {
      doFirst {
        val missingInputs = releaseSigningInputs.filterValues { it.isNullOrBlank() }.keys
        if (missingInputs.isNotEmpty()) {
          throw GradleException(
              "Release-подпись не настроена. Задайте: ${missingInputs.joinToString()}. " +
                  "Локально можно использовать keystore.properties в корне проекта.",
          )
        }
      }
    }

kotlin { jvmToolchain(17) }

// Robolectric 4.16 loads Java-21-compiled android-all jars for recent SDK levels, so the unit-test
// JVM must be Java 21 even though the app itself is compiled with the JDK 17 toolchain.
val javaToolchains = extensions.getByType<JavaToolchainService>()

tasks.withType<Test>().configureEach {
  javaLauncher.set(
      javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
  )
  testLogging {
    events(TestLogEvent.FAILED)
    exceptionFormat = TestExceptionFormat.FULL
    showExceptions = true
    showCauses = true
    showStackTraces = true
  }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
  implementation(libs.androidx.exifinterface)
  // Official Xiaomi Wear interconnect SDK from the Vela third-party-app demo.
  implementation(files("libs/xms-wearable-lib_1.4_release.aar"))

  // Compose
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material3.adaptive.navigation.suite)
  implementation(libs.androidx.material.icons.core)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.reorderable)

  // Hilt
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.androidx.hilt.work)
  ksp(libs.androidx.hilt.compiler)

  // Room
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // WorkManager
  implementation(libs.androidx.work.runtime)

  // DataStore
  implementation(libs.androidx.datastore.preferences)

  // Credentials / Sign-in
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services.auth)
  implementation(libs.googleid)
  implementation(libs.play.services.auth)

  // Networking / Serialization
  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.kotlinx.serialization)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)

  // Test
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.androidx.test.core)
  // Рендер Compose в юнит-тестах (Robolectric, нативная графика): проверяет, что графики
  // и карта тела действительно отрисовываются, а не только компилируются.
  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.test.manifest)
}
