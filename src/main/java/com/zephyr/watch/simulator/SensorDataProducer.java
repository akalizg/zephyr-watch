package com.zephyr.watch.simulator;

import com.alibaba.fastjson2.JSON;
import com.zephyr.watch.bean.SensorReading;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Properties;

/**
 * Zephyr-Watch 数据流模拟器
 * 读取本地数据集并发送到 Kafka
 */
public class SensorDataProducer {
    public static void main(String[] args) throws Exception {
        // 1. Kafka 生产者配置
        Properties props = new Properties();
        // TODO: 如果你没配 hosts，请把 node1 换成虚拟机的实际 IP，例如 "192.168.1.100:9092"
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "192.168.88.161:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        KafkaProducer<String, String> producer = new KafkaProducer<>(props);

        // 2. 数据集文件路径 (指向你刚才建好的 data 文件夹)
        String filePath = "data/train_FD001.txt";

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            System.out.println("🚀 Zephyr-Watch 模拟器启动，开始向 Kafka 发送工业数据...");

            while ((line = br.readLine()) != null) {
                // NASA数据是以空格分隔的，使用正则切分
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 10) continue;

                // 3. 提取数据并封装为 Bean
                SensorReading reading = new SensorReading();
                reading.setMachineId(Integer.parseInt(parts[0]));
                reading.setCycle(Integer.parseInt(parts[1]));
                // 注意数组索引：第7列数据的索引是6
                reading.setS2(Double.parseDouble(parts[6]));
                reading.setS3(Double.parseDouble(parts[7]));
                reading.setS4(Double.parseDouble(parts[8]));
                reading.setTs(System.currentTimeMillis());

                // 4. 使用 fastjson2 转为 JSON 字符串
                String jsonString = JSON.toJSONString(reading);

                // 5. 发送到 Kafka 的 iot_sensor_data 主题
                ProducerRecord<String, String> record = new ProducerRecord<>("iot_sensor_data", jsonString);
                producer.send(record);

                System.out.println("📡 已发送: " + jsonString);

                // 6. 模拟工业高频采样：每 500 毫秒发送一条
                Thread.sleep(500);
            }
        } catch (Exception e) {
            System.err.println("❌ 发送失败，请检查文件路径或 Kafka 连接！报错信息：" + e.getMessage());
        } finally {
            producer.close();
            System.out.println("🛑 数据发送结束。");
        }
    }
}