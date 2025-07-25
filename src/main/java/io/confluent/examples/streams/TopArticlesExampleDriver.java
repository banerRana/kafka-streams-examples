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

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.WindowedSerdes;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * This is a sample driver for the {@link TopArticlesLambdaExample} and
 * To run this driver please first refer to the instructions in {@link TopArticlesLambdaExample}
 * You can then run this class directly in your IDE or via the command line.
 *
 * To run via the command line you might want to package as a fatjar first. Please refer to:
 * <a href='https://github.com/confluentinc/kafka-streams-examples#packaging-and-running'>Packaging</a>
 *
 * Once packaged you can then run:
 * <pre>
 * {@code
 * java -cp target/kafka-streams-examples-8.2.0-0-standalone.jar io.confluent.examples.streams.TopArticlesLambdaExample
 * }
 * </pre>
 *
 * You should terminate with {@code Ctrl-C}.
 */
public class TopArticlesExampleDriver {

  public static void main(final String[] args) throws IOException {
    final String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
    final String schemaRegistryUrl = args.length > 1 ? args[1] : "http://localhost:8081";
    produceInputs(bootstrapServers, schemaRegistryUrl);
    consumeOutput(bootstrapServers, schemaRegistryUrl);
  }

  private static void produceInputs(final String bootstrapServers, final String schemaRegistryUrl) throws IOException {
    final String[] users = {"erica", "bob", "joe", "damian", "tania", "phil", "sam",
        "lauren", "joseph"};
    final String[] industries = {"engineering", "telco", "finance", "health", "science"};
    final String[] pages = {"index.html", "news.html", "contact.html", "about.html", "stuff.html"};

    final Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
        StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
        io.confluent.kafka.serializers.KafkaAvroSerializer.class);
    props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
    final KafkaProducer<String, GenericRecord> producer = new KafkaProducer<>(props);

    final GenericRecordBuilder pageViewBuilder =
        new GenericRecordBuilder(loadSchema("pageview.avsc"));

    final Random random = new Random();
    for (final String user : users) {
      pageViewBuilder.set("industry", industries[random.nextInt(industries.length)]);
      pageViewBuilder.set("flags", "ARTICLE");
      // For each user generate some page views
      IntStream.range(0, random.nextInt(10))
          .mapToObj(value -> {
            pageViewBuilder.set("user", user);
            pageViewBuilder.set("page", pages[random.nextInt(pages.length)]);
            return pageViewBuilder.build();
          }).forEach(
          record -> producer.send(new ProducerRecord<>(TopArticlesLambdaExample.PAGE_VIEWS, null, record))
      );
    }
    producer.flush();
  }

  private static void consumeOutput(final String bootstrapServers, final String schemaRegistryUrl) {
    final Properties consumerProperties = new Properties();
    consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
              StringDeserializer.class);
    consumerProperties.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
    consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG,
        "top-articles-lambda-example-consumer");
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    final Deserializer<Windowed<String>> windowedDeserializer =
            WindowedSerdes.timeWindowedSerdeFrom(String.class, TopArticlesLambdaExample.windowSize.toMillis()).deserializer();
    final KafkaConsumer<Windowed<String>, String> consumer = new KafkaConsumer<>(consumerProperties,
                                                                                 windowedDeserializer,
                                                                                 Serdes.String().deserializer());

    consumer.subscribe(Collections.singleton(TopArticlesLambdaExample.TOP_NEWS_PER_INDUSTRY_TOPIC));
    while (true) {
      final ConsumerRecords<Windowed<String>, String> consumerRecords = consumer.poll(Duration.ofMillis(Long.MAX_VALUE));
      for (final ConsumerRecord<Windowed<String>, String> consumerRecord : consumerRecords) {
        System.out.println(consumerRecord.key().key() + "@" + consumerRecord.key().window().start() +  "=" + consumerRecord.value());
      }
    }
  }

  static Schema loadSchema(final String name) throws IOException {
    try (
        final InputStream input =
            TopArticlesLambdaExample
                .class
                .getClassLoader()
                .getResourceAsStream("avro/io/confluent/examples/streams/" + name)
    ) {
      return new Schema.Parser().parse(input);
    }
  }

}
