pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Coup"
include(":app", ":data")

// 순수 Kotlin 게임 코어(엔진·AI·런타임·화면 상태·로비·계정·원격). 독립 빌드이며 의존성 치환으로 :app이 가져다 쓴다(docs/adr/0001).
includeBuild("core")
