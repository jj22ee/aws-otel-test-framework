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
// import io.opentelemetry.exporter.logging.LoggingMetricExporter;
// import io.opentelemetry.exporter.logging.LoggingSpanExporter;
// import io.opentelemetry.exporter.logging.SystemOutLogRecordExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
// import io.opentelemetry.semconv.ResourceAttributes;
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

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogsTests {
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
        var collector = new  FixedHostPortGenericContainer<>(TEST_IMAGE)
            .withCopyFileToContainer(MountableFile.forClasspathResource(configFilePath), "/etc/collector/config.yaml")
            // .withExposedPorts(4317)
            // .withExposedPorts(55680)
            // .withExposedPorts(8889)
            // // .withExposedPorts(8888)
            .withFixedExposedPort(4317, 4317, InternetProtocol.TCP)
            // .withNetworkMode("host")
            .withLogConsumer(new Slf4jLogConsumer(collectorLogger))
            .waitingFor(Wait.forLogMessage(".*Everything is ready. Begin running and processing data.*", 1))
            .withEnv(envVariables)
            .withClasspathResourceMapping("/logs", "/logs", BindMode.READ_WRITE)
            .withCommand(
                "--config", "/etc/collector/config.yaml",
                // "-p 4317:4317",
                "AWS_REGION=us-west-2"
            );
        // collector.addFixedExposedPort(4317, 4317, InternetProtocol.UDP);
            // docker run --rm -p 2000:2000 -p 55680:55680 -p 8889:8888 -p 4317:4317 -e AWS_REGION=us-west-2 -e AWS_PROFILE=default -v ~/.aws:/root/.aws -v "${PWD}/examples/docker/config-test.yaml":/otel-local-config.yaml --name awscollector public.ecr.aws/aws-observability/aws-otel-collector:latest --feature-gates=exporter.awsxray.skiptimestampvalidation --config otel-local-config.yaml;

       //Mount the Temp directory
        collector.withFileSystemBind(logDirectory.toString(),"/tempLogs", BindMode.READ_WRITE);
        System.out.println("HAHAHAHAHAHAHAA-Tues-Oct-17");
        collector.start();
        return collector;
    }


    @Test
    void testFileRotation() throws Exception {
        String logStreamName = "fileRotation-logstream-" + uniqueID;
        collector = createAndStartCollector("/configurations/config-fileRotation.yaml", logStreamName);


        Thread.sleep(5000);

        String address = collector.getHost();// + ":" + collector.getMappedPort(4317);
        System.out.println("123456 - " + address);

        OpenTelemetry otel = openTelemetry();
        Tracer tracer = otel.getTracer("standard-w3c-id-test");

        Attributes attributes = Attributes.of(
            AttributeKey.stringKey("http.method"), "GET",
            AttributeKey.stringKey("http.url"), "http://localhost:8080/importantEndpoint",
            AttributeKey.stringKey("user"), "random-user",
            AttributeKey.stringKey("http.route"), "/importantEndpoint",
            AttributeKey.stringKey("required"), "false",
            AttributeKey.stringKey("http.target"), "/importantEndpoint");

        String[] traceIds = new String[20];
        // Span span = tracer.spanBuilder("first-test").startSpan();
        for (int count = 0; count < 5; count++) {
            Span span = tracer.spanBuilder("second-test")
                .setSpanKind(SpanKind.SERVER)
                .setAllAttributes(attributes)
                .startSpan();
            System.out.println("Created Span #" + count);
            traceIds[count] = span.getSpanContext().getTraceId();
            System.out.println(traceIds[count] + " , " + Span.current().getSpanContext().getTraceId());
            System.out.println(traceIds[count] + " , " + Span.current().getSpanContext().getSpanId());
            span.end();
        }

        // Create and write data to File A
    //     File tempFile = new File(logDirectory.toString(), "testlogA.log");

    //     PrintWriter printWriter = new PrintWriter(tempFile);
    //     printWriter.println("Message in File A");
    //     printWriter.flush();
    //     printWriter.close();

    //     List<InputStream> inputStreams = new ArrayList<>();
    //     String expectedLogPath = logDirectory.toString();

    //     String logPath = expectedLogPath + "/testlogA.log";
    //     InputStream inputStream = new FileInputStream(logPath);
    //     inputStreams.add(inputStream);
    //     // validateLogs(logStreamName, inputStreams);
    //     inputStreams.remove(inputStream);

    //    //Rename testLogA
    //     File renameFile = new File(logDirectory.toString(), "testlogA-1234.log");
    //     tempFile.renameTo(renameFile);

    //     //Create testLogA again to imitate file rotation
    //     File tempFileB = new File(logDirectory.toString(), "testlogA.log");

    //     PrintWriter newprintWriter = new PrintWriter(tempFileB);
    //     newprintWriter.println("Message in renamed file - line 1");
    //     newprintWriter.println("Message in renamed file - line 2");
    //     newprintWriter.println("Message in renamed file - line 3");
    //     newprintWriter.flush();
    //     newprintWriter.close();


    //     String logPath1 = expectedLogPath + "/testlogA-1234.log";
    //     String logPath2 = expectedLogPath + "/testlogA.log";

    //     InputStream inputStream1 = new FileInputStream(logPath1);
    //     InputStream inputStream2 = new FileInputStream(logPath2);
    //     inputStreams.add(inputStream1);
    //     inputStreams.add(inputStream2);

    //     // validateLogs(logStreamName, inputStreams);


        Thread.sleep(15000);
        collector.stop();
    }

    void validateLogs(String testLogStreamName, List<InputStream> inputStreams) throws Exception {
        var lines = new HashSet<String>();

        for (InputStream inputStream : inputStreams) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                throw new RuntimeException("Error reading from the file: " + inputStream, e);
            }
        }

        var cwClient = CloudWatchLogsClient.builder()
            .build();

        var objectMapper = new ObjectMapper();

        RetryerBuilder.<Void>newBuilder()
            .retryIfException()
            .retryIfRuntimeException()
            .retryIfExceptionOfType(org.opentest4j.AssertionFailedError.class)
            .withWaitStrategy(WaitStrategies.fixedWait(10, TimeUnit.SECONDS))
            .withStopStrategy(StopStrategies.stopAfterAttempt(5))
            .build()
            .call(() -> {
                var now = Instant.now();
                var start = now.minus(Duration.ofMinutes(2));
                var end = now.plus(Duration.ofMinutes(2));
                var response = cwClient.getLogEvents(GetLogEventsRequest.builder().logGroupName("adot-testbed/logs-component-testing/logs")
                    .logStreamName(testLogStreamName)
                    .startTime(start.toEpochMilli())
                    .endTime(end.toEpochMilli())
                    .build());

                var events = response.events();
                var receivedMessages = events.stream().map(x -> x.message()).collect(Collectors.toSet());

                // Extract the "body" field from each received message that is received from cloudwatch in JSON Format
                var messageToValidate = receivedMessages.stream()
                    .map(message -> {
                        try {
                            JsonNode jsonNode = objectMapper.readTree(message);
                            return jsonNode.get("body").asText();
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

                //Validate body field in JSON-messageToValidate with actual log line from the log file.
                assertThat(messageToValidate.containsAll(lines)).isTrue();
                assertThat(messageToValidate).containsExactlyInAnyOrderElementsOf(lines);
                return null;
            });
    }


    public OpenTelemetry openTelemetry() {
        Resource resource = Resource.getDefault().toBuilder().put(ResourceAttributes.SERVICE_NAME, "xray-test").put(ResourceAttributes.SERVICE_VERSION, "0.1.0").build();
    
        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
            .setSampler(Sampler.alwaysOn())
            .setResource(resource)
            .build();
    


        String host = System.getenv("XRAY_ENDPOINT");
        if (host == null) {
          host = "http://0.0.0.0:2000";
        }

        String exporter = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");

        OpenTelemetry openTelemetry =
            OpenTelemetrySdk.builder()
                .setTracerProvider(
                    SdkTracerProvider.builder()
                        .addSpanProcessor(
                            BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder().setEndpoint(exporter).build()).build())
                        .setIdGenerator(AwsXrayIdGenerator.getInstance())
                        .setResource(resource)
                        //???   .setEndpoint("http://localhost:4317")
                        // .setSampler(
                        //     AwsXrayRemoteSampler.newBuilder(resource)
                        //         .setEndpoint(host)
                        //         .setPollingInterval(Duration.ofSeconds(10))
                        //         .build())
                    .build())
                .buildAndRegisterGlobal();
        // Tracer tracer = openTelemetry.getTracer("centralized-sampling-tests");
  
        return openTelemetry;
    }

}
