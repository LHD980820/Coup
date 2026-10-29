package io.github.lhd980820.coup.appcheck

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.appcheck.AppCheckProvider
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.github.lhd980820.coup.CoupApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** App Check 설치: 한 번 설치하고, 실패해도 앱이 멈추지 않으며, 디버그 증명 방식이 릴리스에 들어가지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppCheckSetupTest {
    /** 앞선 테스트가 만든 기본 FirebaseApp이 남아 결과가 순서에 기대지 않도록 매번 비운다. */
    @Before
    fun resetFirebase() {
        FirebaseApp.clearInstancesForTest()
    }

    private val factory = object : AppCheckProviderFactory {
        override fun create(firebaseApp: FirebaseApp): AppCheckProvider = error("not used")
    }

    @Test
    fun `주어진 증명 방식을 대상에 정확히 한 번 설치한다`() {
        val installed = mutableListOf<AppCheckProviderFactory>()
        val result = AppCheckSetup.install(factory, target = { installed += it })
        assertEquals(AppCheckSetup.Result.Installed, result)
        assertEquals(1, installed.size)
        assertSame(factory, installed.single())
    }

    @Test
    fun `설치가 실패해도 예외를 던지지 않고 알린다 - 앱은 계속 돈다`() {
        val boom = IllegalStateException("no default app")
        val logs = mutableListOf<Pair<String, Throwable?>>()
        val result = AppCheckSetup.install(factory, target = { throw boom }, log = { m, e -> logs += m to e })
        assertEquals(AppCheckSetup.Result.Failed(boom), result)
        assertEquals(1, logs.size)
        assertSame(boom, logs.single().second)
    }

    @Test
    fun `취소처럼 Exception이 아닌 것은 삼키지 않는다`() {
        var thrown: Throwable? = null
        try {
            AppCheckSetup.install(factory, target = { throw OutOfMemoryError() })
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(thrown is OutOfMemoryError)
    }

    @Test
    fun `디버그 빌드는 디버그 제공자를 쓴다`() {
        assertTrue(BuildAppCheck.factory() is DebugAppCheckProviderFactory)
    }

    @Test
    fun `앱 클래스가 매니페스트에 연결돼 있다`() {
        assertTrue(ApplicationProvider.getApplicationContext<Application>() is CoupApplication)
    }

    @Test
    fun `Firebase가 준비되지 않은 프로세스에서도 설치 실패가 앱을 멈추지 않는다 - 진짜 설치 경로`() {
        // 단위 테스트에는 기본 FirebaseApp이 없다. CoupApplication.onCreate가 이미 같은 호출을 했고 죽지 않았다.
        val result = AppCheckSetup.install(BuildAppCheck.factory(), log = { _, _ -> })
        assertTrue(result is AppCheckSetup.Result.Failed)
    }

    @Test
    fun `Firebase가 준비되어 있으면 진짜 App Check에 설치된다`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder().setApplicationId("1:1:android:1").setApiKey("fake-key").setProjectId("demo-coup").build(),
        )
        assertEquals(AppCheckSetup.Result.Installed, AppCheckSetup.install(BuildAppCheck.factory()))
    }

    @Test
    fun `진짜 설치 경로는 받은 증명 방식을 App Check에 실제로 등록한다`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder().setApplicationId("1:1:android:1").setApiKey("fake-key").setProjectId("demo-coup").build(),
        )
        var created = 0
        val counting = object : AppCheckProviderFactory {
            override fun create(firebaseApp: FirebaseApp): AppCheckProvider {
                created++
                return AppCheckProvider { com.google.android.gms.tasks.Tasks.forException(IllegalStateException("not used")) }
            }
        }
        assertEquals(AppCheckSetup.Result.Installed, AppCheckSetup.install(counting))
        assertEquals("App Check가 증명 방식으로 제공자를 만들었다", 1, created)
    }

    // ---- 디버그 제공자가 릴리스에 섞이지 않게 하는 구성 검사 ----

    private fun moduleFile(path: String) = File(path).takeIf { it.exists() } ?: error("$path not found from ${File(".").absolutePath}")

    @Test
    fun `디버그 제공자는 debug 빌드에만, Play Integrity는 release 빌드에만 의존한다`() {
        val lines = moduleFile("build.gradle.kts").readLines().map { it.trim() }
        val debugLines = lines.filter { "appcheck.debug" in it }
        val playLines = lines.filter { "appcheck.playintegrity" in it }
        assertEquals(1, debugLines.size)
        assertEquals(1, playLines.size)
        assertTrue(debugLines.single().startsWith("debugImplementation("))
        assertTrue(playLines.single().startsWith("releaseImplementation("))
        // 공통 코드에는 어느 쪽도 implementation으로 새어 들어오지 않는다
        assertFalse(lines.any { it.startsWith("implementation(") && ("appcheck.debug" in it || "appcheck.playintegrity" in it) })
    }

    @Test
    fun `release와 main 소스는 디버그 제공자를 참조하지 않는다`() {
        val sources = listOf("src/release", "src/main").flatMap { dir -> File(dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(sources.isNotEmpty())
        val offenders = sources.filter { "DebugAppCheck" in it.readText().replace(Regex("""//[^\n]*"""), "") }.map { it.path }
        assertEquals(emptyList<String>(), offenders)
    }
}
