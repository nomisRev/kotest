package io.kotlintest.runner.junit5

import createSpecInterceptorChain
import io.kotlintest.AbstractSpec
import io.kotlintest.Project
import io.kotlintest.TestCase
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.discovery.ClasspathRootSelector
import org.junit.platform.engine.discovery.DirectorySelector
import org.junit.platform.engine.discovery.UriSelector
import org.reflections.util.ClasspathHelper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class KotlinTestEngine : TestEngine {

  companion object {
    const val EngineId = "io.kotlintest"
  }

  override fun getId(): String = EngineId

  private val executor = Executors.newFixedThreadPool(Project.parallelism())

  override fun execute(request: ExecutionRequest) {
    request.engineExecutionListener.executionStarted(request.rootTestDescriptor)
    try {
      Project.beforeAll()
      // each child is executed in the executor, which will have a single thread only
      // if parallelism is not enabled
      request.rootTestDescriptor.children.forEach {
        executor.submit {
          execute(it, request)
        }
      }
      executor.shutdown()
      executor.awaitTermination(1, TimeUnit.DAYS)
    } catch (t: Throwable) {
      t.printStackTrace()
      throw t
    } finally {
      Project.afterAll()
    }
    request.engineExecutionListener.executionFinished(request.rootTestDescriptor, TestExecutionResult.successful())
  }

  private fun execute(descriptor: TestDescriptor, request: ExecutionRequest) {
    println("descriptor " + descriptor.uniqueId)
    try {
      println("starting execution")
      println("listener = " + request.engineExecutionListener.javaClass)
      thread {
        request.engineExecutionListener.executionStarted(descriptor)
      }
      Thread.sleep(2000)
      println("returned")
      when (descriptor) {
        is TestContainerDescriptor -> {
          descriptor.discover(request.engineExecutionListener)
          // if this container is for the spec root and we're using a shared instance, then we can
          // invoke the spec interceptors here; otherwise the spec inteceptors will need to run each
          // time we create a fresh instance of the spec class
          if (descriptor.container.isSpecRoot && !descriptor.container.spec.isInstancePerTest()) {
            val initialInterceptor = { next: () -> Unit -> descriptor.container.spec.interceptSpec(next) }
            val extensions = descriptor.container.spec.specExtensions() + Project.specExtensions()
            val chain = createSpecInterceptorChain(descriptor.container.spec, extensions, initialInterceptor)
            chain {
              println("Spec interceptor has reached bottom " + descriptor.container.spec.name())
              descriptor.children.forEach {
                println("Proceeding with child " + it)
                execute(it, request)
              }
            }
          } else {
            descriptor.children.forEach { execute(it, request) }
          }
        }
        is TestCaseDescriptor -> {
          println("we have a test! " + descriptor.uniqueId)
          when (descriptor.testCase.spec.isInstancePerTest()) {
            true -> {
              println("ooo one instancec")

              // we use the prototype spec to create another instance of the spec for this test
              val freshSpec = descriptor.testCase.spec.javaClass.newInstance() as AbstractSpec

              // we get the root scope again for this spec, and find our test case
              val freshTestCase = freshSpec.root().discovery().find {
                when (it) {
                  is TestCase -> it.name() == descriptor.testCase.name()
                  else -> false
                }
              } as TestCase

              // we need to re-run the spec inteceptors for this fresh instance now
              val initialInterceptor = { next: () -> Unit -> freshSpec.interceptSpec(next) }
              val extensions = freshSpec.specExtensions() + Project.specExtensions()
              val chain = createSpecInterceptorChain(freshSpec, extensions, initialInterceptor)
              chain {
                println("Spec interceptor has reached bottom " + freshSpec.name())
                println("now going to run test")
                val freshDescriptor = TestCaseDescriptor(descriptor.id, freshTestCase)
                val runner = TestCaseRunner(request.engineExecutionListener)
                runner.runTest(freshDescriptor)
                println("Test has returned q")
              }
            }
            false -> {
              println("executing directly " + descriptor.uniqueId)
              val runner = TestCaseRunner(request.engineExecutionListener)
              runner.runTest(descriptor)
              println("test complete " + descriptor.uniqueId)
            }
          }
        }
        else -> throw IllegalStateException("$descriptor is not supported")
      }
      request.engineExecutionListener.executionFinished(descriptor, TestExecutionResult.successful())
    } catch (t: Throwable) {
      t.printStackTrace()
      request.engineExecutionListener.executionFinished(descriptor, TestExecutionResult.failed(t))
    }
  }

  override fun discover(request: EngineDiscoveryRequest,
                        uniqueId: UniqueId): TestDescriptor {
    // inside intellij when running a single test, we might be passed a class selector
    // which will be the classname of a spec implementation
    val classes = request.getSelectorsByType(ClassSelector::class.java).map { it.className }

    val uris = request.getSelectorsByType(ClasspathRootSelector::class.java).map { it.classpathRoot } +
        request.getSelectorsByType(DirectorySelector::class.java).map { it.path.toUri() } +
        request.getSelectorsByType(UriSelector::class.java).map { it.uri } +
        ClasspathHelper.forClassLoader().toList().map { it.toURI() }
    return TestDiscovery(TestDiscovery.DiscoveryRequest(uris, classes), uniqueId)
  }
}
