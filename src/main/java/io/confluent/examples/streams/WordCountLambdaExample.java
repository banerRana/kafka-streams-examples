/*
 * Copyright Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.examples.streams;

import io.confluent.common.utils.TestUtils;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Produced;

import java.util.Arrays;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Demonstrates, using the high-level KStream DSL, how to implement the WordCount program that
 * computes a simple word occurrence histogram from an input text. This example uses lambda
 * expressions and thus works with Java 8+ only.
 * <p>
 * In this example, the input stream reads from a topic named "streams-plaintext-input", where the values of
 * messages represent lines of text; and the histogram output is written to topic
 * "streams-wordcount-output", where each record is an updated count of a single word, i.e. {@code word (String) -> currentCount (Long)}.
 * <p>
 * Note: Before running this example you must 1) create the source topic (e.g. via {@code kafka-topics --create ...}),
 * then 2) start this example and 3) write some data to the source topic (e.g. via {@code kafka-console-producer}).
 * Otherwise you won't see any data arriving in the output topic.
 * <p>
 * <br>
 * HOW TO RUN THIS EXAMPLE
 * <p>
 * 1) Start Zookeeper and Kafka. Please refer to <a href="http://docs.confluent.io/current/quickstart.html#quickstart">QuickStart</a>.
 * <p>
 * 2) Create the input and output topics used by this example.
 * <pre>
 * {@code
 * $ bin/kafka-topics --bootstrap-server localhost:9092 --create --topic streams-plaintext-input \
 *                   --partitions 1 --replication-factor 1
 * $ bin/kafka-topics --bootstrap-server localhost:9092 --create --topic streams-wordcount-output \
 *                   --partitions 1 --replication-factor 1
 * }</pre>
 * Note: The above commands are for the Confluent Platform. For Apache Kafka it should be {@code bin/kafka-topics.sh ...}.
 * <p>
 * 3) Start this example application either in your IDE or on the command line.
 * <p>
 * If via the command line please refer to <a href="https://github.com/confluentinc/kafka-streams-examples#packaging-and-running">Packaging</a>.
 * Once packaged you can then run:
 * <pre>
 * {@code
 * $ java -cp target/kafka-streams-examples-8.2.0-0-standalone.jar io.confluent.examples.streams.WordCountLambdaExample
 * }
 * </pre>
 * 4) Write some input data to the source topic "streams-plaintext-input" (e.g. via {@code kafka-console-producer}).
 * The already running example application (step 3) will automatically process this input data and write the
 * results to the output topic "streams-wordcount-output".
 * <pre>
 * {@code
 * # Start the console producer. You can then enter input data by writing some line of text, followed by ENTER:
 * #
 * #   hello kafka streams<ENTER>
 * #   all streams lead to kafka<ENTER>
 * #   join kafka summit<ENTER>
 * #
 * # Every line you enter will become the value of a single Kafka message.
 * $ bin/kafka-console-producer --broker-list localhost:9092 --topic streams-plaintext-input
 * }</pre>
 * 5) Inspect the resulting data in the output topic, e.g. via {@code kafka-console-consumer}.
 * <pre>
 * {@code
 * $ bin/kafka-console-consumer --topic streams-wordcount-output --from-beginning \
 *                              --bootstrap-server localhost:9092 \
 *                              --property print.key=true \
 *                              --property value.deserializer=org.apache.kafka.common.serialization.LongDeserializer
 * }</pre>
 * You should see output data similar to below. Please note that the exact output
 * sequence will depend on how fast you type the above sentences. If you type them
 * slowly, you are likely to get each count update, e.g., kafka 1, kafka 2, kafka 3.
 * If you type them quickly, you are likely to get fewer count updates, e.g., just kafka 3.
 * This is because the commit interval is set to 10 seconds. Anything typed within
 * that interval will be compacted in memory.
 * <pre>
 * {@code
 * hello    1
 * kafka    1
 * streams  1
 * all      1
 * streams  2
 * lead     1
 * to       1
 * join     1
 * kafka    3
 * summit   1
 * }</pre>
 * 6) Once you're done with your experiments, you can stop this example via {@code Ctrl-C}. If needed,
 * also stop the Kafka broker ({@code Ctrl-C}), and only then stop the ZooKeeper instance (`{@code Ctrl-C}).
 */
public class WordCountLambdaExample {

  static final String inputTopic = "streams-plaintext-input";
  static final String outputTopic = "streams-wordcount-output";

  /**
   * The Streams application as a whole can be launched like any normal Java application that has a `main()` method.
   */
  public static void main(final String[] args) {
    final String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";

    // Configure the Streams application.
    final Properties streamsConfiguration = getStreamsConfiguration(bootstrapServers);

    // Define the processing topology of the Streams application.
    final StreamsBuilder builder = new StreamsBuilder();
    createWordCountStream(builder);
    final KafkaStreams streams = new KafkaStreams(builder.build(), streamsConfiguration);

    // Always (and unconditionally) clean local state prior to starting the processing topology.
    // We opt for this unconditional call here because this will make it easier for you to play around with the example
    // when resetting the application for doing a re-run (via the Application Reset Tool,
    // https://docs.confluent.io/platform/current/streams/developer-guide/app-reset-tool.html).
    //
    // The drawback of cleaning up local state prior is that your app must rebuild its local state from scratch, which
    // will take time and will require reading all the state-relevant data from the Kafka cluster over the network.
    // Thus in a production scenario you typically do not want to clean up always as we do here but rather only when it
    // is truly needed, i.e., only under certain conditions (e.g., the presence of a command line flag for your app).
    // See `ApplicationResetExample.java` for a production-like example.
    streams.cleanUp();

    // Now run the processing topology via `start()` to begin processing its input data.
    streams.start();

    // Add shutdown hook to respond to SIGTERM and gracefully close the Streams application.
    Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
  }

  /**
   * Configure the Streams application.
   *
   * <p>Various Kafka Streams related settings are defined here such as the location of the target Kafka cluster to use.
   * Additionally, you could also define Kafka Producer and Kafka Consumer settings when needed.
   *
   * @param bootstrapServers Kafka cluster address
   * @return Properties getStreamsConfiguration
   */
  static Properties getStreamsConfiguration(final String bootstrapServers) {
    final Properties streamsConfiguration = new Properties();
    // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
    // against which the application is run.
    streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-lambda-example");
    streamsConfiguration.put(StreamsConfig.CLIENT_ID_CONFIG, "wordcount-lambda-example-client");
    // Where to find Kafka broker(s).
    streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    // Specify default (de)serializers for record keys and for record values.
    streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
    streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
    // Records should be flushed every 10 seconds. This is less than the default
    // in order to keep this example interactive.
    streamsConfiguration.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 10 * 1000);
    // For illustrative purposes we disable record caches.
    streamsConfiguration.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
    // Use a temporary directory for storing state, which will be automatically removed after the test.
    streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory().getAbsolutePath());
    return streamsConfiguration;
  }

  /**
   * Define the processing topology for Word Count.
   *
   * @param builder StreamsBuilder to use
   */
  static void createWordCountStream(final StreamsBuilder builder) {
    // Construct a `KStream` from the input topic "streams-plaintext-input", where message values
    // represent lines of text (for the sake of this example, we ignore whatever may be stored
    // in the message keys).  The default key and value serdes will be used.
    final KStream<String, String> textLines = builder.stream(inputTopic);

    final Pattern pattern = Pattern.compile("\\W+", Pattern.UNICODE_CHARACTER_CLASS);

    final KTable<String, Long> wordCounts = textLines
      // Split each text line, by whitespace, into words.  The text lines are the record
      // values, i.e. we can ignore whatever data is in the record keys and thus invoke
      // `flatMapValues()` instead of the more generic `flatMap()`.
      .flatMapValues(value -> Arrays.asList(pattern.split(value.toLowerCase())))
      // Group the split data by word so that we can subsequently count the occurrences per word.
      // This step re-keys (re-partitions) the input data, with the new record key being the words.
      // Note: No need to specify explicit serdes because the resulting key and value types
      // (String and String) match the application's default serdes.
      .groupBy((keyIgnored, word) -> word)
      // Count the occurrences of each word (record key).
      .count();

    // Write the `KTable<String, Long>` to the output topic.
    wordCounts.toStream().to(outputTopic, Produced.with(Serdes.String(), Serdes.Long()));
  }

}
