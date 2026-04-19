package com.zephyr.watch.simulator;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.config.JobConfig;
import com.zephyr.watch.config.KafkaConfig;
import com.zephyr.watch.model.SensorReading;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Properties;

/**
 * 读取 NASA C-MAPSS 数据并发送到 Kafka
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
            System.out.println("Zephyr-Watch 模拟器启动，开始向 Kafka 发送工业数据...");

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
                    producer.send(new ProducerRecord<String, String>(KafkaConfig.INPUT_TOPIC, json));
                    System.out.println("已发送: " + json);

                    Thread.sleep(JobConfig.PRODUCER_SLEEP_MS);
                } catch (Exception lineEx) {
                    System.err.println("单行数据解析失败，已跳过。原始行: " + line);
                }
            }
        } finally {
            producer.close();
            System.out.println("数据发送结束。");
        }
    }
}