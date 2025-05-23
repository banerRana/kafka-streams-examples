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

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * End-to-end integration test that demonstrates how to mix and match the DSL and the Processor
 * API of Kafka Streams.
 * <p>
 * More concretely, we show how to use the {@link KStream#process(ProcessorSupplier, String...)}
 * method to include a custom {@link org.apache.kafka.streams.processor.api.Processor} (from the
 * Processor API) in a topology defined via the DSL.  The fictitious use case is to anonymize
 * IPv4 addresses contained in the input data.
 * <p>
 * Note: This example uses lambda expressions and thus works with Java 8+ only.
 */
public class MixAndMatchLambdaIntegrationTest {

  /**
   * Performs rudimentary anonymization of IPv4 address in the input data.
   * You should use this for demonstration purposes only.
   */
  private static class AnonymizeIpAddressTransformer implements Processor<byte[], String, byte[], String> {

    private static final Pattern ipv4AddressPattern =
        Pattern.compile("(?<keep>[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.)(?<anonymize>[0-9]{1,3})");

    private ProcessorContext<byte[], String> context;

    @Override
    public void init(final ProcessorContext<byte[], String> context) {
      this.context = context;
    }

    @Override
    public void process(final Record<byte[], String> record) {
      // The record value contains the IP address in string representation.
      // The original record key is ignored because we don't need it for this logic.
      final String anonymizedIpAddress = anonymizeIpAddress(record.value());
      context.forward(record.withValue(anonymizedIpAddress));
    }

    /**
     * Anonymizes the provided IPv4 address (in string representation) by replacing the fourth byte
     * with "XXX".  For example, "192.168.1.234" is anonymized to "192.168.1.XXX".
     * <p>
     * Note: This method is for illustration purposes only.  The anonymization is both lackluster
     * (in terms of the achieved anonymization) and slow/inefficient (in terms of implementation).
     *
     * @param ipAddress The IPv4 address
     * @return Anonymized IPv4 address.
     */
    private String anonymizeIpAddress(final String ipAddress) {
      return ipv4AddressPattern.matcher(ipAddress).replaceAll("${keep}XXX");
    }
  }


  @Test
  public void shouldAnonymizeTheInput() {
    final List<String> inputValues = Arrays.asList("Hello, 1.2.3.4!", "foo 192.168.1.55 bar");
    final List<String> expectedValues = Arrays.asList("HELLO, 1.2.3.XXX!", "FOO 192.168.1.XXX BAR");

    //
    // Step 1: Configure and start the processor topology.
    //
    final StreamsBuilder builder = new StreamsBuilder();

    final Properties streamsConfiguration = new Properties();
    streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "mix-and-match-lambda-integration-test");
    streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy config");
    streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.ByteArraySerde.class);
    streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);

    final String inputTopic = "inputTopic";
    final String outputTopic = "outputTopic";

    final KStream<byte[], String> stream = builder.stream(inputTopic);
    final KStream<byte[], String> uppercasedAndAnonymized = stream
      .mapValues(s -> s.toUpperCase())
      .process(AnonymizeIpAddressTransformer::new);
    uppercasedAndAnonymized.to(outputTopic);

    try (final TopologyTestDriver topologyTestDriver = new TopologyTestDriver(builder.build(), streamsConfiguration)) {
      //
      // Step 2: Setup input and output topics.
      //
      final TestInputTopic<Void, String> input = topologyTestDriver
        .createInputTopic(inputTopic,
                          new IntegrationTestUtils.NothingSerde<>(),
                          new StringSerializer());
      final TestOutputTopic<Void, String> output = topologyTestDriver
        .createOutputTopic(outputTopic,
                           new IntegrationTestUtils.NothingSerde<>(),
                           new StringDeserializer());

      //
      // Step 3: Produce some input data to the input topic.
      //
      input.pipeValueList(inputValues);

      //
      // Step 4: Verify the application's output data.
      //
      assertThat(output.readValuesToList(), equalTo(expectedValues));
    }
  }
}