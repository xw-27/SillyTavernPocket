import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- 发布签名:读取 signing/signing.properties(keystore 与密码,已被 git 忽略)----
// 文件不存在时 release 构建为未签名包,debug 构建不受影响
val signingProps = rootProject.file("signing/signing.properties")

// ---- Node.js 运行时(Termux 预编译的 nodejs-lts,经 scripts/fetch-node-runtime.sh 获取并校验) ----
// 产物:build/node-runtime/jniLibs/arm64-v8a/ 下的 libnode-exec.so 及其依赖的 .so
val nodeRuntimeJniLibs = layout.buildDirectory.dir("node-runtime/jniLibs").get().asFile
val generatedAssets = layout.buildDirectory.dir("generated-assets").get().asFile

// ---- 第三方许可证:licenses/ 原样复制到 APK 的 assets/licenses/ ----
val stageLicenses by tasks.registering(Copy::class) {
    from(rootProject.file("licenses"))
    into(layout.buildDirectory.dir("generated-licenses/licenses"))
}

val fetchNodeRuntime by tasks.registering(Exec::class) {
    val script = rootProject.file("scripts/fetch-node-runtime.sh")
    inputs.file(script)
    outputs.dir(nodeRuntimeJniLibs)
    workingDir = rootProject.projectDir
    commandLine("bash", script.absolutePath, File(nodeRuntimeJniLibs, "arm64-v8a").absolutePath)
}

android {
    namespace = "com.xw.sillytavernpocket"
    compileSdk = 36
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.xw.sillytavernpocket"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.2"
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging {
        // Node 可执行文件必须是磁盘上的真实文件(才能 exec),不能留在 APK 压缩包里
        jniLibs.useLegacyPackaging = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(nodeRuntimeJniLibs)
            assets.srcDir(generatedAssets)
            assets.srcDir(layout.buildDirectory.dir("generated-licenses"))
        }
    }


    signingConfigs {
        if (signingProps.exists()) {
            create("release") {
                val p = Properties().apply { signingProps.inputStream().use { load(it) } }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (signingProps.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

// ---- 内核(SillyTavern)副本:kernel/ + patches/kernel/ -> build/generated-assets/kernel ----
val prepareKernel by tasks.registering(Exec::class) {
    val script = rootProject.file("scripts/prepare-kernel.sh")
    inputs.file(script)
    inputs.dir(rootProject.file("patches/kernel"))
    outputs.dir(generatedAssets)
    outputs.upToDateWhen { false } // npm ci 每次都执行,保证依赖与补丁一致
    workingDir = rootProject.projectDir
    // 先清空旧产物(例如之前的目录布局),避免残留文件被打进 APK
    doFirst { generatedAssets.deleteRecursively() }
    commandLine("bash", script.absolutePath, generatedAssets.absolutePath)
}

tasks.matching {
    it.name == "preBuild" ||
        (it.name.startsWith("merge") && (it.name.endsWith("JniLibFolders") || it.name.endsWith("Assets")))
}
    .configureEach { dependsOn(fetchNodeRuntime, prepareKernel, stageLicenses) }
