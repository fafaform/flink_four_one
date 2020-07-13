package example;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.serialization.AbstractDeserializationSchema;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.api.java.tuple.Tuple6;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.flink.streaming.util.serialization.JSONKeyValueDeserializationSchema;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

public class ReadFromKafka {

    public static String CARD_NUMBER = "CARD_NUMBER";
    public static String TXN_AMT = "TXN_AMT";
    public static String TIMESTAMP = "TIMESTAMP";
    //// VARIABLES
    public static String KAFKA_CONSUMER_TOPIC = "input";
    public static String KAFKA_PRODUCER_TOPIC = "credit_card_stats";
//    public static String KAFKA_CONSUMER_TOPIC = "flink-from-kafka";
//    public static String KAFKA_PRODUCER_TOPIC = "flink-to-kafka";
    //// TEST IN CLUSTER
//    public static String BOOTSTRAP_SERVER = "172.30.74.84:9092,172.30.74.85:9092,172.30.74.86:9092";
//    public static String BOOTSTRAP_SERVER = "poc01.kbtg:9092,poc02.kbtg:9092,poc03.kbtg:9092";
    //// TEST IN MY LOCAL
    public static String BOOTSTRAP_SERVER = "localhost:9092";

    public static Logger LOG = LoggerFactory.getLogger(ReadFromKafka.class);

    public static void main(String[] args) throws Exception{
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", BOOTSTRAP_SERVER);
//        properties.setProperty("group.id", "flink-gid");

        //// READ FROM EARLIEST HERE
//        properties.setProperty("auto.offset.reset", "earliest");

        //// END VARIABLES
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        ////////////////////////////////////////////////////////////////
        //// RECEIVE JSON
        FlinkKafkaConsumer<ObjectNode> JsonSource = new FlinkKafkaConsumer(KAFKA_CONSUMER_TOPIC, new JSONKeyValueDeserializationSchema(false), properties);
        DataStream<Tuple3<String,Double,Long>> messageStream = env.addSource(JsonSource).flatMap(new FlatMapFunction<ObjectNode, Tuple3<String,Double,Long>>() {
            @Override
            public void flatMap(ObjectNode s, Collector<Tuple3<String, Double, Long>> collector) throws Exception {
                collector.collect(new Tuple3<String, Double, Long>(
                        s.get("value").get(CARD_NUMBER).asText(),
                        s.get("value").get(TXN_AMT).asDouble(),
                        s.get("value").get(TIMESTAMP).asLong()));
            }
        });

        ////////////////////////////////////////////////////////////////
        //// RECEIVE RAW
//        FlinkKafkaConsumer<byte[]> kafkaSource = new FlinkKafkaConsumer(KAFKA_CONSUMER_TOPIC, new AbstractDeserializationSchema<byte[]>() {
//            @Override
//            public byte[] deserialize(byte[] bytes) throws IOException {
//                return bytes;
//            }
//        }, properties);
//
//        // CONVERTING PROCESS
//        DataStream<Tuple3<String, Double, Long>> messageStream = env.addSource(kafkaSource).process(new ProcessFunction<byte[], Tuple3<String, Double, Long>>() {
//            @Override
//            public void processElement(byte[] s, Context context, Collector<Tuple3<String, Double, Long>> collector) throws Exception {
//
//                TransactionObject transactionObject = new TransactionObject();
//                transactionObject.setTXN_ID(Arrays.copyOfRange(s, 0, 9));
//                transactionObject.setTIMESTAMP(Arrays.copyOfRange(s, 35, 45));
//                transactionObject.setCARD_TYPE(Arrays.copyOfRange(s, 25, 26));
//                transactionObject.setCARD_STATUS(Arrays.copyOfRange(s, 26, 27));
//                transactionObject.setTXN_AMT(Arrays.copyOfRange(s, 27, 35));
//                transactionObject.setCARD_NUMBER(Arrays.copyOfRange(s, 9, 25));
//
//                collector.collect(new Tuple3<String, Double, Long>(transactionObject.getCARD_NUMBER(), transactionObject.getTXN_AMT(), transactionObject.getTIMESTAMP()));
//            }
//        });
        ////////////////////////////////////////////////////////////////

        //// PRODUCT KAFKA
        FlinkKafkaProducer<String> myProducer = new FlinkKafkaProducer(KAFKA_PRODUCER_TOPIC, new ProducerStringSerializationSchema(KAFKA_PRODUCER_TOPIC), properties, FlinkKafkaProducer.Semantic.EXACTLY_ONCE);

//        DataStream<Tuple4<String, Double, Long, String>> accessKeys = messageStream
//                .keyBy(0).process(new KeyMappingFunction());

        DataStream<Tuple4<String, Double, Long, String>> accessCounts = messageStream
                .process(new ProcessFunction<Tuple3<String, Double, Long>, Tuple4<String, Double, Long, Long>>() {
                    @Override
                    public void processElement(Tuple3<String, Double, Long> input, Context context, Collector<Tuple4<String, Double, Long, Long>> collector) throws Exception {
                        collector.collect(new Tuple4<String, Double, Long, Long>(
                                input.f0, input.f1, input.f2, context.timestamp()));
                    }
                })
                .filter(new FilterFunction<Tuple4<String, Double, Long, Long>>() {
                    @Override
                    public boolean filter(Tuple4<String, Double, Long, Long> transactionData) throws Exception {
                        return (transactionData.f2 > (transactionData.f3-60000));
                    }
                })
                .keyBy(0).process(new CountWithTimeoutFunction());

        DataStreamSink<String> sendingToKafka = accessCounts.process(new ProcessFunction<Tuple4<String, Double, Long, String>, String>() {
            @Override
            public void processElement(Tuple4<String, Double, Long, String> stringLongLongTuple3, Context context, Collector<String> collector) throws Exception {
                collector.collect("{\"CARD_NUMBER\":\"" + stringLongLongTuple3.f0 + "\""
                        +",\"TOTAL_AMOUNT\":" + stringLongLongTuple3.f1
                        + ",\"COUNT\":" + stringLongLongTuple3.f2
                        + ",\"WINDOWED_TIME\":\"" + stringLongLongTuple3.f3 + "\""
                        +"}");
            }
        }).addSink(myProducer);

        env.execute("Transaction Count and Summary");
    }
}
