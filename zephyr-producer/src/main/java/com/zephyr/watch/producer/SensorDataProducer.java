package com.zephyr.watch.producer;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.constants.KafkaConfig;
import com.zephyr.watch.common.entity.SensorReading;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Properties;

/**
 * 鐠囪褰?NASA C-MAPSS 閺佺増宓侀獮璺哄絺闁礁鍩?Kafka
 */
public class SensorDataProducer {

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaConfig.BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        KafkaProducer<String, String> producer = new KafkaProducer<String, String>(props);

        try (BufferedReader br = new BufferedReader(new FileReader(JobConfig.DATA_FILE_PATH))) {
            String line;
            int lineNo = 0;
            System.out.println("Zephyr-Watch 濡剝瀚欓崳銊ユ儙閸旑煉绱濆鈧慨瀣倻 Kafka 閸欐垿鈧礁浼愭稉姘殶閹?..");

            while ((line = br.readLine()) != null) {
                lineNo++;
                try {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 9) {
                        continue;
                    }

                    long now = System.currentTimeMillis();
                    long eventTime = now;
                    if (lineNo % 15 == 0) {
                        eventTime = now - 2000L;
                    }

                    SensorReading reading = new SensorReading();
                    reading.setMachineId(Integer.parseInt(parts[0]));
                    reading.setCycle(Integer.parseInt(parts[1]));
                    reading.setPressure(Double.parseDouble(parts[6]));
                    reading.setTemperature(Double.parseDouble(parts[7]));
                    reading.setSpeed(Double.parseDouble(parts[8]));
                    reading.setEventTime(eventTime);

                    String json = JSON.toJSONString(reading);
                    producer.send(new ProducerRecord<String, String>(KafkaConfig.INPUT_TOPIC, json), (RecordMetadata metadata, Exception exception) -> {
                        if (exception != null) {
                            System.err.println("Kafka send failed: " + exception.getMessage());
                        }
                    });
                    System.out.println("瀹告彃褰傞柅? " + json);

                    Thread.sleep(JobConfig.PRODUCER_SLEEP_MS);
                } catch (Exception lineEx) {
                    System.err.println("閸楁洝顢戦弫鐗堝祦鐟欙絾鐎芥径杈Е閿涘苯鍑＄捄瀹犵箖閵嗗倸甯慨瀣攽: " + line);
                }
            }
        } finally {
            producer.close();
            System.out.println("Sensor data producer finished.");
        }
    }
}
