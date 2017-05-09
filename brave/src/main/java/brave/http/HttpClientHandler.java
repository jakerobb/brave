package brave.http;

import brave.Span;
import brave.Tracer;
import brave.internal.Nullable;
import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import zipkin.Endpoint;

/**
 * This standardizes a way to instrument http clients, particularly in a way that encourages use of
 * portable customizations via {@link HttpClientParser}.
 *
 * <p>This is an example of synchronous instrumentation:
 * <pre>{@code
 * Span span = handler.handleSend(injector, request);
 * Throwable error = null;
 * try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
 *   response = invoke(request); // any downstream code can see Tracer.currentSpan
 * } catch (RuntimeException | Error e) {
 *   error = e;
 *   throw e;
 * } finally {
 *   handler.handleReceive(response, error, span);
 * }
 * }</pre>
 *
 * @param <Req> the native http request type of the client.
 * @param <Resp> the native http response type of the client.
 */
public final class HttpClientHandler<Req, Resp> {

  public static <Req, Resp> HttpClientHandler create(HttpTracing httpTracing,
      HttpClientAdapter<Req, Resp> adapter) {
    return new HttpClientHandler<>(httpTracing, adapter);
  }

  final Tracer tracer;
  final CurrentTraceContext currentTraceContext;
  final HttpClientParser parser;
  final HttpClientAdapter<Req, Resp> adapter;
  final String serverName;
  final boolean serverNameSet;

  HttpClientHandler(HttpTracing httpTracing, HttpClientAdapter<Req, Resp> adapter) {
    this.tracer = httpTracing.tracing().tracer();
    this.currentTraceContext = httpTracing.tracing().currentTraceContext();
    this.parser = httpTracing.clientParser();
    this.serverName = httpTracing.serverName();
    this.serverNameSet = !serverName.equals("");
    this.adapter = adapter;
  }

  /**
   * Starts the client span after assigning it a name and tags. This {@link
   * TraceContext.Injector#inject(TraceContext, Object) injects} the trace context onto the request
   * before returning.
   *
   * <p>Call this before sending the request on the wire.
   */
  public Span handleSend(TraceContext.Injector<Req> injector, Req request) {
    return handleSend(injector, request, request);
  }

  /**
   * Like {@link #handleSend(TraceContext.Injector, Object)}, except for when the carrier of
   * trace data is not the same as the request.
   */
  public <C> Span handleSend(TraceContext.Injector<C> injector, C carrier, Req request) {
    Span span = tracer.nextSpan();
    injector.inject(span.context(), carrier);
    if (span.isNoop()) return span;

    // all of the parsing here occur before a timestamp is recorded on the span
    span.kind(Span.Kind.CLIENT).name(parser.spanName(adapter, request));
    parser.requestTags(adapter, request, span);
    Endpoint.Builder remoteEndpoint = Endpoint.builder();
    if (adapter.parseServerAddress(request, remoteEndpoint) || serverNameSet) {
      span.remoteEndpoint(remoteEndpoint.serviceName(serverName).build());
    }
    return span.start();
  }

  /**
   * Finishes the client span after assigning it tags according to the response or error.
   *
   * <p>This is typically called once the response headers are received, and after the span is
   * {@link brave.Tracer.SpanInScope#close() no longer in scope}.
   */
  public void handleReceive(@Nullable Resp response, @Nullable Throwable error, Span span) {
    if (span.isNoop()) return;

    try {
      if (response != null || error != null) {
        String message = adapter.parseError(response, error);
        if (message != null) span.tag(zipkin.Constants.ERROR, message);
      }
      if (response != null) parser.responseTags(adapter, response, span);
    } finally {
      span.finish();
    }
  }
}
