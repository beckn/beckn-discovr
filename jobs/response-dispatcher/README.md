# Seeker Notifier Job

A Spring Boot Kafka job that reads messages from an input topic, processes them, and forwards them to an output topic. This job follows the same architecture patterns as the `catalog-publish` job.

## Overview

The Seeker Notifier job provides a simple message forwarding service with the following features:

- **Message Consumption**: Reads messages from `events.seeker.requests` topic
- **Message Processing**: Validates JSON format and extracts message keys
- **Message Production**: Forwards processed messages to `events.seeker.notifications` topic
- **Error Handling**: Routes failed messages to `events.seeker.dlt` (Dead Letter Topic)
- **Reliability**: Uses manual acknowledgment for message processing reliability

## Architecture

```
[Input Topic] → [EventListener] → [MessageProcessingService] → [EventProducer] → [Output Topic]
     ↓                                                                              
[DLT Topic] ← [Error Handler] ← [Processing Failures]
```

### Key Components

- **`SeekerNotifierApplication`**: Main Spring Boot application class
- **`EventListener`**: Kafka consumer that listens to input topic
- **`MessageProcessingService`**: Business logic for message processing
- **`EventProducer`**: Kafka producer for output and DLT topics
- **Configuration Classes**: Kafka consumer, producer, and topic configurations

## Prerequisites

- Java 17+
- Gradle 8.5+
- Docker (for containerized deployment)
- Kafka cluster (for runtime)

## Topics Configuration

| Topic | Purpose | Example |
|-------|---------|---------|
| `events.seeker.requests` | Input messages | `{"id":"req-123","data":"request data"}` |
| `events.seeker.notifications` | Processed output | `{"id":"req-123","data":"request data"}` |
| `events.seeker.dlt` | Failed messages | Messages with error headers |

## Building the Project

### Clean Build
```bash
./gradlew clean build
```

### Build without Tests
```bash
./gradlew build -x test
```

### Create JAR only
```bash
./gradlew bootJar
```

The build creates:
- **Main JAR**: `build/libs/seeker-notifier-1.0.0-SNAPSHOT.jar` (executable)
- **Plain JAR**: `build/libs/seeker-notifier-1.0.0-SNAPSHOT-plain.jar` (classes only)

## Running Tests

### Run All Tests
```bash
./gradlew test
```

### Run Specific Test Class
```bash
# Unit tests
./gradlew test --tests "org.beckn.seeker.service.MessageProcessingServiceTest"
./gradlew test --tests "org.beckn.seeker.messaging.EventListenerTest"

# Integration tests
./gradlew test --tests "org.beckn.seeker.integration.SeekerNotifierIntegrationTest"
```

### Run Tests with Detailed Output
```bash
./gradlew test --info
```

### Run Tests for Specific Package
```bash
./gradlew test --tests "org.beckn.seeker.service.*"
./gradlew test --tests "org.beckn.seeker.messaging.*"
```

### Test Reports
After running tests, view reports at:
- **HTML Report**: `build/reports/tests/test/index.html`
- **XML Results**: `build/test-results/test/`

## Running the Application

### Standalone (requires Kafka running)
```bash
java -jar build/libs/seeker-notifier-1.0.0-SNAPSHOT.jar
```

### With Custom Configuration
```bash
java -jar build/libs/seeker-notifier-1.0.0-SNAPSHOT.jar \
  --spring.kafka.bootstrap-servers=localhost:9092 \
  --topics.input=my.input.topic \
  --topics.output=my.output.topic
```

### Development Mode
```bash
./gradlew bootRun
```

## Docker Deployment

### Build Docker Image
```bash
docker build -t seeker-notifier:latest .
```

### Run with Docker Compose

#### Run only seeker-notifier service
```bash
# From project root directory
docker-compose --profile seeker-notifier up
```

#### Run full stack (all services)
```bash
# From project root directory
docker-compose --profile full up
```

#### Run in background
```bash
docker-compose --profile seeker-notifier up -d
```

#### Stop services
```bash
docker-compose --profile seeker-notifier down
```

## Configuration

### Application Properties

#### Default Configuration (`application.yml`)
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: seeker-notifier-group
    listener:
      concurrency: 1

topics:
  input: events.seeker.requests
  output: events.seeker.notifications
  dlt: events.seeker.dlt
```

#### Docker Configuration (`application-docker.yml`)
```yaml
spring:
  kafka:
    bootstrap-servers: kafka:9092
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `localhost:9092` |
| `TOPICS_INPUT` | Input topic name | `events.seeker.requests` |
| `TOPICS_OUTPUT` | Output topic name | `events.seeker.notifications` |
| `TOPICS_DLT` | Dead letter topic | `events.seeker.dlt` |
| `SPRING_KAFKA_LISTENER_CONCURRENCY` | Consumer concurrency | `1` |

## Monitoring and Health Checks

### Health Check Endpoint
```bash
curl http://localhost:8081/actuator/health
```

### Metrics Endpoint
```bash
curl http://localhost:8081/actuator/metrics
```

### Application Info
```bash
curl http://localhost:8081/actuator/info
```

## Testing Message Flow

### Send Test Message
```bash
# Using Kafka console producer
docker exec -it beckn-kafka kafka-console-producer \
  --topic events.seeker.requests \
  --bootstrap-server localhost:9092

# Enter JSON message:
{"id":"test-123","message":"Hello Seeker","timestamp":"2023-01-01T10:00:00Z"}
```

### Consume Output Messages
```bash
# Monitor output topic
docker exec -it beckn-kafka kafka-console-consumer \
  --topic events.seeker.notifications \
  --bootstrap-server localhost:9092 \
  --from-beginning
```

### Monitor DLT Messages
```bash
# Monitor dead letter topic
docker exec -it beckn-kafka kafka-console-consumer \
  --topic events.seeker.dlt \
  --bootstrap-server localhost:9092 \
  --from-beginning \
  --property print.headers=true
```

## Development

### Project Structure
```
src/
├── main/java/org/beckn/seeker/
│   ├── SeekerNotifierApplication.java
│   ├── config/
│   │   ├── KafkaConsumerConfig.java
│   │   ├── KafkaProducerConfig.java
│   │   └── KafkaTopicsConfig.java
│   ├── messaging/
│   │   ├── consumer/EventListener.java
│   │   └── producer/EventProducer.java
│   └── service/MessageProcessingService.java
├── main/resources/
│   ├── application.yml
│   └── application-docker.yml
└── test/java/org/beckn/seeker/
    ├── integration/SeekerNotifierIntegrationTest.java
    ├── messaging/EventListenerTest.java
    └── service/MessageProcessingServiceTest.java
```

### Adding Business Logic

To add custom business logic, modify the `MessageProcessingService.processMessage()` method:

```java
@Service
public class MessageProcessingService {
    
    public String processMessage(String message) {
        // Add your business logic here
        // Example: transform, validate, enrich the message
        
        return processedMessage;
    }
}
```

## Troubleshooting

### Common Issues

1. **Kafka Connection Failed**
   ```
   Error: Connection to node -1 could not be established
   ```
   - Ensure Kafka is running and accessible
   - Check `bootstrap-servers` configuration

2. **Topic Not Found**
   ```
   Error: Topic 'events.seeker.requests' does not exist
   ```
   - Topics are auto-created if `auto.create.topics.enable=true`
   - Or create topics manually using Kafka CLI

3. **Consumer Group Lag**
   ```bash
   # Check consumer group status
   docker exec -it beckn-kafka kafka-consumer-groups \
     --bootstrap-server localhost:9092 \
     --group seeker-notifier-group \
     --describe
   ```

### Logs

View application logs:
```bash
# Docker logs
docker logs beckn-seeker-notifier -f

# File logs (if configured)
tail -f logs/seeker-notifier.log
```

## Contributing

1. Follow the existing code patterns from `catalog-publish`
2. Add comprehensive tests for new features
3. Update this README for any configuration changes
4. Ensure Docker Compose profiles work correctly

## Related Services

- **catalog-publish**: Similar job for catalog data processing
- **catalog-es-sync**: Elasticsearch synchronization job
- **discovery-service**: Main API service
