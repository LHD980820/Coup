pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    // 코어와 같은 Kotlin·코루틴·테스트 라이브러리 버전을 쓴다
    versionCatalogs {
        create("libs") {
            from(files("../core/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "coup-server"

// 서버 판정(ADR 0015)은 앱과 같은 엔진·판정 코드(:judge)를 그대로 쓴다
includeBuild("../core")
