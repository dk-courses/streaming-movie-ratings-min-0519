package io.confluent.demo;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.test.ConsumerRecordFactory;
import org.apache.kafka.streams.test.OutputVerifier;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import lombok.extern.slf4j.Slf4j;

import static io.confluent.demo.StreamsDemo.getMoviesTable;
import static io.confluent.demo.StreamsDemo.getRatedMoviesTable;
import static io.confluent.demo.StreamsDemo.getRatingAverageTable;
import static io.confluent.demo.StreamsDemo.getRawRatingsStream;
import static io.confluent.demo.fixture.MoviesAndRatingsData.DUMMY_KAFKA_CONFLUENT_CLOUD_9092;
import static io.confluent.demo.fixture.MoviesAndRatingsData.DUMMY_SR_CONFLUENT_CLOUD_8080;
import static io.confluent.demo.fixture.MoviesAndRatingsData.LETHAL_WEAPON_MOVIE;
import static io.confluent.demo.fixture.MoviesAndRatingsData.LETHAL_WEAPON_RATING_9;

@Slf4j
@Ignore
public class StreamsDemoE2ETest {

  private static final String RAW_RATINGS_TOPIC_NAME = "raw-ratings";
  private static final String RAW_MOVIES_TOPIC_NAME = "raw-movies";
  private static final String RATED_MOVIES_TOPIC_NAME = "rated-movies";
  private TopologyTestDriver td;
  private SpecificAvroSerde<RatedMovie> ratedMovieSerde;


  @Before
  public void setUp() {

    final Properties properties = new Properties();
    properties.put("application.id", "kafka-movies-test");
    properties.put("bootstrap.servers", DUMMY_KAFKA_CONFLUENT_CLOUD_9092);
    properties.put("schema.registry.url", DUMMY_SR_CONFLUENT_CLOUD_8080);
    properties.put("default.topic.replication.factor", "1");
    properties.put("offset.reset.policy", "latest");

    final StreamsDemo streamsApp = new StreamsDemo();
    final Properties streamsConfig = streamsApp.buildStreamsProperties(properties);

    // workaround https://stackoverflow.com/a/50933452/27563
    streamsConfig.setProperty(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams/" + LocalDateTime.now().toString());

    final Map<String, String> mockSerdeConfig = Serdes.getSerdeConfig(streamsConfig);

    //building topology
    StreamsBuilder builder = new StreamsBuilder();

    SpecificAvroSerde<Movie> movieSerde = new SpecificAvroSerde<>(new MockSchemaRegistryClient());
    movieSerde.configure(mockSerdeConfig, false);

    ratedMovieSerde = new SpecificAvroSerde<>(new MockSchemaRegistryClient());
    ratedMovieSerde.configure(mockSerdeConfig, false);

    final KStream<Long, String> rawRatingsStream = getRawRatingsStream(builder, "raw-ratings");
    final KTable<Long, Double> ratingAverageTable = getRatingAverageTable(rawRatingsStream, "average-ratings");

    final KTable<Long, Movie> moviesTable = getMoviesTable(builder,
                                                           "raw-movies",
                                                           "movies",
                                                           movieSerde);
    
    getRatedMoviesTable(moviesTable, ratingAverageTable, "rated-movies", ratedMovieSerde);

    final Topology topology = builder.build();
    log.info("Topology = \n{}", topology.describe());
    td = new TopologyTestDriver(topology, streamsConfig);
  }

  @Test
  public void validateRatingForLethalWeapon() {

    ConsumerRecordFactory<Long, String> rawRatingsRecordFactory =
        new ConsumerRecordFactory<>(RAW_RATINGS_TOPIC_NAME, new LongSerializer(), new StringSerializer());

    ConsumerRecordFactory<Long, String> rawMoviesRecordFactory =
        new ConsumerRecordFactory<>(RAW_MOVIES_TOPIC_NAME, new LongSerializer(), new StringSerializer());

    td.pipeInput(rawMoviesRecordFactory.create(LETHAL_WEAPON_MOVIE));

    List<ConsumerRecord<byte[], byte[]>> list = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      list.add(rawRatingsRecordFactory.create(LETHAL_WEAPON_RATING_9));
    }
    td.pipeInput(list);

    List<ProducerRecord<Long, RatedMovie>> result = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      result.add(td.readOutput(RATED_MOVIES_TOPIC_NAME, new LongDeserializer(), ratedMovieSerde.deserializer()));
    }
    result.forEach(record -> {
      ProducerRecord<Long, RatedMovie>
          lethalWeaponRecord =
          new ProducerRecord<>(RATED_MOVIES_TOPIC_NAME, 362L, new RatedMovie(362L, "Lethal Weapon", 1987, 9.0));
      OutputVerifier.compareValue(record, lethalWeaponRecord);
    });


  }

}