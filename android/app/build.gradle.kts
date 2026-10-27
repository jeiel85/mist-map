import java.util.Properties
import javax.inject.Inject

// 웹 렌더러는 저장소 루트의 index.html·src/·vendor/ 한 벌만 둔다. 빌드할 때 assets/web/으로 복사한다.
abstract class SyncWebAssets : DefaultTask() {
  @get:InputFile abstract val indexHtml: RegularFileProperty
  @get:InputDirectory abstract val webSrc: DirectoryProperty
  @get:InputDirectory abstract val webVendor: DirectoryProperty
  @get:OutputDirectory abstract val outputDir: DirectoryProperty
  @get:Inject abstract val fs: FileSystemOperations

  @TaskAction
  fun sync() {
    fs.sync {
      from(indexHtml)
      from(webSrc) { into("src") }
      from(webVendor) { into("vendor") }
      into(outputDir.dir("web"))
    }
  }
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.jeiel85.mistmap"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.jeiel85.mistmap"
    minSdk = 26
    targetSdk = 36
    versionCode = 6
    versionName = "0.8.0"
  }

  // 업로드 키는 저장소 밖(.keystore/, git 제외)에 둔다. 백업: G:\내 드라이브\모바일 앱 개발\Mist Map\릴리즈 키스토어
  val signingProps = rootProject.file(".keystore/release-signing.properties")
  signingConfigs {
    if (signingProps.isFile) {
      create("release") {
        val props = Properties().apply { signingProps.inputStream().use(::load) }
        storeFile = rootProject.file(".keystore/" + props.getProperty("keystorePath"))
        storePassword = props.getProperty("storePassword")
        keyAlias = props.getProperty("keyAlias")
        keyPassword = props.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.findByName("release")
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val syncWebAssets = tasks.register<SyncWebAssets>("syncWebAssets") {
  indexHtml.set(rootProject.layout.projectDirectory.file("../index.html"))
  webSrc.set(rootProject.layout.projectDirectory.dir("../src"))
  webVendor.set(rootProject.layout.projectDirectory.dir("../vendor"))
}

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(syncWebAssets, SyncWebAssets::outputDir)
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.webkit)
  // play-services가 끌어오는 옛 fragment(1.3 미만)에서는 registerForActivityResult가 깨진다
  implementation(libs.androidx.fragment)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  // 큰 기록 파일(JSON)을 요소 하나씩 읽는다
  implementation(libs.gson)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.play.services.location)
  testImplementation(libs.junit)
  // Room 쿼리를 JVM에서 실제 SQLite로 시험한다
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.kotlinx.coroutines.test)
  debugImplementation(libs.androidx.compose.ui.tooling)
  ksp(libs.androidx.room.compiler)
}
