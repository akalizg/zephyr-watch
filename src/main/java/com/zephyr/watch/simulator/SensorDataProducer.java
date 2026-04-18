package com.zephyr.watch.simulator;

import com.zephyr.watch.config.JobConfig;
import com.zephyr.watch.config.KafkaConfig;
import com.zephyr.watch.model.SensorReading;
import com.zephyr.watch.util.JsonUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Properties;

/**
 * 读取 NASA 数据集并发送到 Kafka
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
            System.out.println("Zephyr-Watch 模拟器启动，开始向 Kafka 发送工业数据...");

            while ((line = br.readLine()) != null) {
                try {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length < 9) {
                        continue;
                    }

                    SensorReading reading = new SensorReading();
                    reading.setMachineId(Integer.parseInt(parts[0]));
                    reading.setCycle(Integer.parseInt(parts[1]));
                    reading.setPressure(Double.parseDouble(parts[6]));
                    reading.setTemperature(Double.parseDouble(parts[7]));
                    reading.setSpeed(Double.parseDouble(parts[8]));
                    reading.setEventTime(System.currentTimeMillis());

                    String jsonString = JsonUtils.toJsonString(reading);
                    ProducerRecord<String, String> record =
                            new ProducerRecord<String, String>(KafkaConfig.INPUT_TOPIC, jsonString);

                    producer.send(record);
                    System.out.println("已发送: " + jsonString);

                    Thread.sleep(JobConfig.PRODUCER_SLEEP_MS);
                } catch (Exception lineEx) {
                    System.err.println("单行数据解析失败，已跳过。原始行: " + line);
                }
            }
        } catch (Exception e) {
            System.err.println("发送失败，请检查文件路径或 Kafka 连接。报错信息：" + e.getMessage());
        } finally {
            producer.close();
            System.out.println("数据发送结束。");
        }
    }
}