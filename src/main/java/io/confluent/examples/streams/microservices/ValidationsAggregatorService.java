package io.confluent.examples.streams.microservices;

import static io.confluent.examples.streams.avro.microservices.Order.newBuilder;
import static io.confluent.examples.streams.avro.microservices.OrderState.VALIDATED;
import static io.confluent.examples.streams.avro.microservices.OrderValidationResult.FAIL;
import static io.confluent.examples.streams.avro.microservices.OrderValidationResult.PASS;
import static io.confluent.examples.streams.microservices.domain.Schemas.Topics.ORDERS;
import static io.confluent.examples.streams.microservices.domain.Schemas.Topics.ORDER_VALIDATIONS;
import static io.confluent.examples.streams.microservices.util.MicroserviceUtils.addShutdownHookAndBlock;
import static io.confluent.examples.streams.microservices.util.MicroserviceUtils.baseStreamsConfig;
import static io.confluent.examples.streams.microservices.util.MicroserviceUtils.parseArgsAndConfigure;

import io.confluent.examples.streams.avro.microservices.Order;
import io.confluent.examples.streams.avro.microservices.OrderState;
import io.confluent.examples.streams.avro.microservices.OrderValidation;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.SessionWindows;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/**
 * A simple service which listens to to validation results from each of the Validation
 * services and aggregates them by order Id, triggering a pass or fail based on whether
 * all rules pass or not.
 */
public class ValidationsAggregatorService implements Service {

  private static final Logger log = LoggerFactory.getLogger(ValidationsAggregatorService.class);
  private final String SERVICE_APP_ID = getClass().getSimpleName();
  private final Consumed<String, OrderValidation> serdes1 = Consumed
      .with(ORDER_VALIDATIONS.keySerde(), ORDER_VALIDATIONS.valueSerde());
  private final Consumed<String, Order> serdes2 = Consumed.with(ORDERS.keySerde(),
      ORDERS.valueSerde());
  private final Grouped<String, OrderValidation> serdes3 = Grouped
      .with(ORDER_VALIDATIONS.keySerde(), ORDER_VALIDATIONS.valueSerde());
  private final StreamJoined<String, Long, Order> serdes4 = StreamJoined
      .with(ORDERS.keySerde(), Serdes.Long(), ORDERS.valueSerde());
  private final Produced<String, Order> serdes5 = Produced
      .with(ORDERS.keySerde(), ORDERS.valueSerde());
  private final Grouped<String, Order> serdes6 = Grouped
      .with(ORDERS.keySerde(), ORDERS.valueSerde());
  private final StreamJoined<String, OrderValidation, Order> serdes7 = StreamJoined
      .with(ORDERS.keySerde(), ORDER_VALIDATIONS.valueSerde(), ORDERS.valueSerde());

  private KafkaStreams streams;

  @Override
  public void start(final String bootstrapServers, final String stateDir) {
    final CountDownLatch startLatch = new CountDownLatch(1);
    streams = aggregateOrderValidations(bootstrapServers, stateDir);
    streams.cleanUp(); //don't do this in prod as it clears your state stores

    streams.setStateListener((newState, oldState) -> {
      if (newState == KafkaStreams.State.RUNNING && oldState != KafkaStreams.State.RUNNING) {
        startLatch.countDown();
      }

    });
    streams.start();

    try {
      if (!startLatch.await(60, TimeUnit.SECONDS)) {
        throw new RuntimeException("Streams never finished rebalancing on startup");
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    log.info("Started Service " + getClass().getSimpleName());
  }

  private KafkaStreams aggregateOrderValidations(
      final String bootstrapServers,
      final String stateDir) {
    final int numberOfRules = 3; //TODO put into a KTable to make dynamically configurable

    final StreamsBuilder builder = new StreamsBuilder();
    final KStream<String, OrderValidation> validations = builder
        .stream(ORDER_VALIDATIONS.name(), serdes1);
    final KStream<String, Order> orders = builder
        .stream(ORDERS.name(), serdes2)
        .filter((id, order) -> OrderState.CREATED.equals(order.getState()));

    //If all rules pass then validate the order
    validations
        .groupByKey(serdes3)
        .windowedBy(SessionWindows.with(Duration.ofMinutes(5)))
        .aggregate(
            () -> 0L,
            (id, result, total) -> PASS.equals(result.getValidationResult()) ? total + 1 : total,
            (k, a, b) -> b == null ? a : b, //include a merger as we're using session windows.
            Materialized.with(null, Serdes.Long())
        )
        //get rid of window
        .toStream((windowedKey, total) -> windowedKey.key())
        //When elements are evicted from a session window they create delete events. Filter these.
        .filter((k1, v) -> v != null)
        //only include results were all rules passed validation
        .filter((k, total) -> total >= numberOfRules)
        //Join back to orders
        .join(orders, (id, order) ->
                //Set the order to Validated
                newBuilder(order).setState(VALIDATED).build()
            , JoinWindows.of(Duration.ofMinutes(5)), serdes4)
        //Push the validated order into the orders topic
        .to(ORDERS.name(), serdes5);

    //If any rule fails then fail the order
    validations.filter((id, rule) -> FAIL.equals(rule.getValidationResult()))
        .join(orders, (id, order) ->
                //Set the order to Failed and bump the version on it's ID
                newBuilder(order).setState(OrderState.FAILED).build(),
            JoinWindows.of(Duration.ofMinutes(5)), serdes7)
        //there could be multiple failed rules for each order so collapse to a single order
        .groupByKey(serdes6)
        .reduce((order, v1) -> order)
        //Push the validated order into the orders topic
        .toStream().to(ORDERS.name(), Produced.with(ORDERS.keySerde(), ORDERS.valueSerde()));

    return new KafkaStreams(builder.build(),
        baseStreamsConfig(bootstrapServers, stateDir, SERVICE_APP_ID));
  }

  @Override
  public void stop() {
    if (streams != null) {
      streams.close();
    }
  }

  public static void main(final String[] args) throws Exception {
    final String bootstrapServers = parseArgsAndConfigure(args);
    final ValidationsAggregatorService service = new ValidationsAggregatorService();
    service.start(bootstrapServers, "/tmp/kafka-streams");
    addShutdownHookAndBlock(service);
  }
}