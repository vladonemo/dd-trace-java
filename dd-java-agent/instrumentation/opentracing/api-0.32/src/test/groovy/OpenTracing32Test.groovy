import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDId
import datadog.trace.api.DDTags
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.context.TraceScope
import datadog.trace.core.DDSpan
import datadog.trace.core.propagation.ExtractedContext
import io.opentracing.References
import io.opentracing.Scope
import io.opentracing.Span
import io.opentracing.log.Fields
import io.opentracing.noop.NoopSpan
import io.opentracing.propagation.Format
import io.opentracing.propagation.TextMap
import io.opentracing.util.GlobalTracer
import spock.lang.Subject

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*

class OpenTracing32Test extends AgentTestRunner {

  @Subject
  def tracer = GlobalTracer.get()

  def "test #method"() {
    setup:
    def builder = tracer.buildSpan("some name")
    if (tagBuilder) {
      builder.withTag(DDTags.RESOURCE_NAME, "some resource")
        .withTag("string", "a")
        .withTag("number", 1)
        .withTag("boolean", true)
    }
    if (addReference) {
      def ctx = new ExtractedContext(DDId.ONE, DDId.from(2), SAMPLER_DROP, DEFAULT, null, 0, [:], [:], null)
      builder.addReference(addReference, tracer.tracer.converter.toSpanContext(ctx))
    }
    def result = builder.start()
    if (tagSpan) {
      result.setTag(DDTags.RESOURCE_NAME, "other resource")
        .setTag("string", "b")
        .setTag("number", 2)
        .setTag("boolean", false)
    }
    if (exception) {
      result.log([(Fields.ERROR_OBJECT): exception])
    }

    expect:
    result instanceof MutableSpan
    (result as MutableSpan).localRootSpan.delegate == result.delegate
    (result as MutableSpan).isError() == (exception != null)
    tracer.activeSpan() == null
    result.context().baggageItems().isEmpty()

    when:
    result.setBaggageItem("test", "baggage")

    then:
    result.getBaggageItem("test") == "baggage"
    result.context().baggageItems() == ["test": "baggage"].entrySet()

    when:
    result.finish()

    then:
    assertTraces(1) {
      trace(1) {
        span {
          if ([References.CHILD_OF, References.FOLLOWS_FROM].contains(addReference)) {
            parentDDId(DDId.from(2))
          } else {
            parent()
          }
          operationName "some name"
          if (tagSpan) {
            resourceName "other resource"
          } else if (tagBuilder) {
            resourceName "some resource"
          } else {
            resourceName "some name"
          }
          errored exception != null
          tags {
            if (tagSpan) {
              "string" "b"
              "number" 2
              "boolean" false
            } else if (tagBuilder) {
              "string" "a"
              "number" 1
              "boolean" true
            }
            if (exception) {
              errorTags(exception.class)
            }
            defaultTags(addReference != null)
          }
        }
      }
    }

    where:
    method        | addReference            | tagBuilder | tagSpan | exception
    "start"       | null                    | true       | false   | null
    "startManual" | References.CHILD_OF     | true       | true    | new Exception()
    "startManual" | "bogus"                 | false      | false   | new Exception()
    "start"       | References.FOLLOWS_FROM | false      | true    | null
  }

  def "test ignoreParent"() {
    setup:
    def otherSpan = runUnderTrace("parent") {
      tracer.buildSpan("other").ignoreActiveSpan().start()
    }

    expect:
    otherSpan.operationName == "other"
    (otherSpan.delegate as DDSpan).parentId == DDId.ZERO
  }

  def "test startActive"() {
    setup:
    def scope = tracer.buildSpan("some name").startActive(finishSpan)

    expect:
    scope instanceof TraceScope
    tracer.activeSpan().delegate == scope.span().delegate

    when:
    scope.close()

    then:
    (scope.span().delegate as DDSpan).isFinished() == finishSpan

    cleanup:
    if (finishSpan) {
      TEST_WRITER.waitForTraces(1)
    }

    where:
    finishSpan << [true, false]
  }

  def "test scopemanager"() {
    setup:
    def span = tracer.buildSpan("some name").start()
    def scope = tracer.scopeManager().activate(span, finishSpan)
    (scope as TraceScope).setAsyncPropagation(false)

    expect:
    span instanceof MutableSpan
    scope instanceof TraceScope
    !(scope as TraceScope).isAsyncPropagating()
    (scope as TraceScope).capture() == null
    (tracer.scopeManager().active().span().delegate == span.delegate)

    when:
    (scope as TraceScope).setAsyncPropagation(true)
    def continuation = (scope as TraceScope).capture()
    continuation.cancel()

    then:
    (scope as TraceScope).isAsyncPropagating()
    continuation instanceof TraceScope.Continuation

    when: "attempting to close the span this way doesn't work because we lost the 'finishSpan' reference"
    tracer.scopeManager().active().close()

    then:
    !(span.delegate as DDSpan).isFinished()

    when:
    scope.close()

    then:
    (span.delegate as DDSpan).isFinished() == finishSpan

    cleanup:
    if (finishSpan) {
      TEST_WRITER.waitForTraces(1)
    }

    where:
    finishSpan | _
    true       | _
    false      | _
  }

  def "test scopemanager with non OTSpan"() {
    setup:
    def span = NoopSpan.INSTANCE
    def scope = tracer.scopeManager().activate(span, true)

    expect:
    !(span instanceof MutableSpan)
    scope instanceof TraceScope

    and: "non OTSpans aren't supported and get converted to NoopAgentSpan"
    tracer.scopeManager().active().span() != span

    when:
    scope.close()
    scope.span().finish()

    then:
    assertTraces(0) {}
  }

  def "test continuation"() {
    setup:
    def span = tracer.buildSpan("some name").start()
    TraceScope scope = tracer.scopeManager().activate(span, false)
    scope.setAsyncPropagation(true)

    expect:
    tracer.activeSpan().delegate == span.delegate

    when:
    def continuation = scope.capture()

    then:
    continuation instanceof TraceScope.Continuation

    when:
    scope.close()

    then:
    tracer.activeSpan() == null

    when:
    scope = continuation.activate()

    then:
    tracer.activeSpan().delegate == span.delegate

    cleanup:
    scope.close()
  }

  def "closing scope when not on top"() {
    when:
    Span firstSpan = tracer.buildSpan("someOperation").start()
    Scope firstScope = tracer.scopeManager().activate(firstSpan)

    Span secondSpan = tracer.buildSpan("someOperation").start()
    Scope secondScope = tracer.scopeManager().activate(secondSpan)

    firstSpan.finish()
    firstScope.close()

    then:
    tracer.scopeManager().active().delegate == secondScope.delegate
    1 * STATS_D_CLIENT.incrementCounter("scope.close.error")
    1 * STATS_D_CLIENT.incrementCounter("scope.user.close.error")
    _ * TEST_CHECKPOINTER._
    0 * _

    when:
    secondSpan.finish()
    secondScope.close()

    then:
    assert tracer.scopeManager().active() == null
  }

  def "test inject extract"() {
    setup:
    def context = tracer.buildSpan("some name").start().context()
    def textMap = [:]
    def adapter = new TextMapAdapter(textMap)
    def serviceNameBase64 = "d29ya2VyLm9yZy5ncmFkbGUucHJvY2Vzcy5pbnRlcm5hbC53b3JrZXIuR3JhZGxlV29ya2VyTWFpbg"

    when:
    context.delegate.setSamplingPriority(contextPriority, samplingMechanism)
    tracer.inject(context, Format.Builtin.TEXT_MAP, adapter)

    then:
    textMap == [
      "x-datadog-trace-id"         : "$context.delegate.traceId",
      "x-datadog-parent-id"        : "$context.delegate.spanId",
      "x-datadog-sampling-priority": propagatedPriority.toString(),
      "x-datadog-tags"             : "_dd.p.upstream_services=$serviceNameBase64|$propagatedPriority|$propagatedMechanism" + (samplingRate != null ? "|" + samplingRate : ""),
    ]

    when:
    def extract = tracer.extract(Format.Builtin.TEXT_MAP, adapter)

    then:
    extract.delegate.traceId == context.delegate.traceId
    extract.delegate.spanId == context.delegate.spanId
    extract.delegate.samplingPriority == propagatedPriority

    where:
    contextPriority | samplingMechanism | propagatedPriority | propagatedMechanism | samplingRate
    SAMPLER_DROP    | DEFAULT           | SAMPLER_DROP       | DEFAULT             | null
    SAMPLER_KEEP    | DEFAULT           | SAMPLER_KEEP       | DEFAULT             | null
    UNSET           | DEFAULT           | SAMPLER_KEEP       | AGENT_RATE          | 1
    USER_KEEP       | MANUAL            | USER_KEEP          | MANUAL              | null
    USER_DROP       | MANUAL            | USER_DROP          | MANUAL              | null
  }

  def "tolerate null span activation"() {
    when:
    try {
      tracer.scopeManager().activate(null)?.close()
    } catch (Exception ignored) {}

    try {
      tracer.activateSpan(null)?.close()
    } catch (Exception ignored) {}

    // make sure scope stack has been left in a valid state
    Span testSpan = tracer.buildSpan("someOperation").start()
    Scope testScope = tracer.scopeManager().activate(testSpan)
    testSpan.finish()
    testScope.close()

    then:
    assert tracer.scopeManager().active() == null
  }

  static class TextMapAdapter implements TextMap {
    private final Map<String, String> map

    TextMapAdapter(Map<String, String> map) {
      this.map = map
    }

    @Override
    Iterator<Map.Entry<String, String>> iterator() {
      return map.entrySet().iterator()
    }

    @Override
    void put(String key, String value) {
      map.put(key, value)
    }
  }
}
