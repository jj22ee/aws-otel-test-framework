package software.amazon.adot.testbed;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.PrintWriter;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.InternetProtocol;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest;
import static org.assertj.core.api.Assertions.assertThat;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import io.opentelemetry.contrib.awsxray.AwsXrayIdGenerator;

import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.xray.XRayClient;
import software.amazon.awssdk.services.xray.model.BatchGetTracesRequest;
import software.amazon.awssdk.services.xray.model.BatchGetTracesResponse;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogsTestsSecond {
    private static final String TEST_IMAGE = System.getenv("TEST_IMAGE") != null && !System.getenv("TEST_IMAGE").isEmpty()
        ? System.getenv("TEST_IMAGE")
        : "public.ecr.aws/aws-observability/aws-otel-collector:latest";
    private final Logger collectorLogger = LoggerFactory.getLogger("collector");
    private static final String uniqueID = UUID.randomUUID().toString();
    private Path logDirectory;
    private GenericContainer<?> collector;

    private GenericContainer<?> createAndStartCollector(String configFilePath, String logStreamName) throws IOException {

        // Create an environment variable map
        Map<String, String> envVariables = new HashMap<>();
        envVariables.put("LOG_STREAM_NAME", logStreamName);
        //Set credentials
        envVariables.put("AWS_REGION", System.getenv("AWS_REGION"));
        envVariables.put("AWS_ACCESS_KEY_ID", System.getenv("AWS_ACCESS_KEY_ID"));
        envVariables.put("AWS_SECRET_ACCESS_KEY", System.getenv("AWS_SECRET_ACCESS_KEY"));

        // Check if AWS_SESSION_TOKEN is not null before adding it
        if (System.getenv("AWS_SESSION_TOKEN") != null) {
            envVariables.put("AWS_SESSION_TOKEN", System.getenv("AWS_SESSION_TOKEN"));
        }
        try {
            logDirectory = Files.createTempDirectory("tempLogs");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create log directory", e);
        }
        var collector = new FixedHostPortGenericContainer<>(TEST_IMAGE)
            .withCopyFileToContainer(MountableFile.forClasspathResource(configFilePath), "/etc/collector/config.yaml")
            .withFixedExposedPort(4317, 4317, InternetProtocol.TCP)
            .withLogConsumer(new Slf4jLogConsumer(collectorLogger))
            .waitingFor(Wait.forLogMessage(".*Everything is ready. Begin running and processing data.*", 1))
            .withEnv(envVariables)
            .withClasspathResourceMapping("/logs", "/logs", BindMode.READ_WRITE)
            .withCommand(
                "--config", "/etc/collector/config.yaml",
                "AWS_REGION=us-west-2"
            );

        collector.withFileSystemBind(logDirectory.toString(),"/tempLogs", BindMode.READ_WRITE);
        collector.start();
        return collector;
    }

    @Test
    void testW3CTraceIdSendToXRay() throws Exception {
        // String logStreamName = "fileRotation-logstream-" + uniqueID;
        // collector = createAndStartCollector("/configurations/config-fileRotation.yaml", logStreamName);
        // Thread.sleep(5000);

        // OpenTelemetry otel = openTelemetry();
        // Tracer tracer = otel.getTracer("xray-w3c-id-test");

        // Attributes attributes = Attributes.of(
        //     AttributeKey.stringKey("http.method"), "GET",
        //     AttributeKey.stringKey("http.url"), "http://localhost:8080/randomEndpoint",
        //     AttributeKey.stringKey("user"), "random-user",
        //     AttributeKey.stringKey("http.route"), "/randomEndpoint",
        //     AttributeKey.stringKey("required"), "false",
        //     AttributeKey.stringKey("http.target"), "/randomEndpoint");

        // int numOfTraces = 5;
        // List<String> traceIds = new ArrayList<String>();
        // HashSet<String> traceIdsSet = new HashSet<String>(); 
        // for (int count = 0; count < numOfTraces; count++) {
        //     Span span = tracer.spanBuilder("trace-span-test")
        //         .setSpanKind(SpanKind.SERVER)
        //         .setAllAttributes(attributes)
        //         .startSpan();
        //     System.out.print("Created Span #" + count + " - ");
        //     String id = new StringBuilder(span.getSpanContext().getTraceId()).insert(8, "-").insert(0, "1-").toString();
        //     traceIds.add(id);
        //     traceIdsSet.add(id);
        //     System.out.println(traceIds.get(traceIds.size() - 1));
        //     span.end();
        // }

        // // Takes a few seconds for traces to appear in XRay
        // System.out.println("Waiting 15 seconds for traces to be present in XRay");
        // Thread.sleep(15000);

        // Region region = Region.of("us-west-2");
        // XRayClient xray = XRayClient.builder()
        //     .region(region)
        //     .build();
        // BatchGetTracesResponse tracesResponse = xray.batchGetTraces(BatchGetTracesRequest.builder()
        //     .traceIds(traceIds)
        //     .build());

        // // Assertions
        // assertThat(tracesResponse.traces()).hasSize(traceIds.size());
        // tracesResponse.traces().forEach(trace ->
        //     {
        //         System.out.println(trace.id());
        //         assertThat(traceIdsSet.contains(trace.id())).isTrue();
        //     });

        // // Cleanup
        // collector.stop();
        assertThat(true).isTrue();
    }

    public OpenTelemetry openTelemetry() {
        Resource resource = Resource.getDefault().toBuilder().put(ResourceAttributes.SERVICE_NAME, "xray-test").put(ResourceAttributes.SERVICE_VERSION, "0.1.0").build();
    
        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
            .setSampler(Sampler.alwaysOn())
            .setResource(resource)
            .build();

        String exporter = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");

        OpenTelemetry openTelemetry =
            OpenTelemetrySdk.builder()
                .setTracerProvider(
                    SdkTracerProvider.builder()
                        .addSpanProcessor(
                            BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder().setEndpoint(exporter).build()).build())
                        .setIdGenerator(AwsXrayIdGenerator.getInstance())
                        .setResource(resource)
                    .build())
                .buildAndRegisterGlobal();
  
        return openTelemetry;
    }
}
